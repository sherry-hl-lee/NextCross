package com.fit5120tp10.nextcross.client.tripreport

import com.fit5120tp10.nextcross.client.detection.DrivingEventType
import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.HaversineDistanceCalculator
import com.fit5120tp10.nextcross.client.permittedarea.storage.PermittedTripConfigurationRepository
import com.fit5120tp10.nextcross.client.permittedarea.storage.TripRecordingBindingRepository
import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import com.fit5120tp10.nextcross.client.recording.storage.RecordingDao
import com.fit5120tp10.nextcross.client.recording.storage.TripReportEntity
import com.fit5120tp10.nextcross.client.recording.storage.TripAiReflectionEntity
import kotlin.math.roundToInt

/**
 * Local Trip Report persistence and 10-day purge.
 * Deletes only app-local Room (+ optional planned-trip config / binding) data.
 */
class TripReportRepository(
    private val recordingDao: RecordingDao,
    private val bindingRepository: TripRecordingBindingRepository? = null,
    private val configurationRepository: PermittedTripConfigurationRepository? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun saveCompletedTripReport(request: SaveCompletedTripReportRequest): TripReport {
        require(request.recordingTripId.isNotBlank()) { "Recording trip id must not be blank." }
        require(request.endedAtMillis >= request.startedAtMillis) {
            "Trip end time must be on or after start time."
        }

        val now = clock()
        val existing = recordingDao.tripReportById(request.recordingTripId)
        val gpsPoints = recordingDao.gpsPointsForTrip(request.recordingTripId)
        val hardAccel = recordingDao.countDrivingEvents(
            request.recordingTripId,
            DrivingEventType.HardAcceleration.name
        )
        val hardBrake = recordingDao.countDrivingEvents(
            request.recordingTripId,
            DrivingEventType.HardBraking.name
        )
        val durationSeconds = ((request.endedAtMillis - request.startedAtMillis) / 1000L)
            .coerceAtLeast(0L)
        val entity = TripReportEntity(
            tripId = request.recordingTripId,
            plannedTripId = request.plannedTripId?.takeIf { it.isNotBlank() },
            startedAtMillis = request.startedAtMillis,
            endedAtMillis = request.endedAtMillis,
            startingPointLabel = request.startingPointLabel.trim().ifBlank { "Starting point" },
            destinationLabel = request.destinationLabel.trim().ifBlank { "Destination" },
            durationSeconds = durationSeconds,
            distanceMeters = estimateDistanceMeters(gpsPoints),
            hardAccelerationCount = hardAccel,
            hardBrakingCount = hardBrake,
            includeDetailedRouteByDefault = request.includeDetailedRouteByDefault,
            includeReflectionByDefault = request.includeReflectionByDefault,
            finalAiText = request.finalAiText.storedOptional() ?: existing?.finalAiText,
            parentEmail = request.parentEmail.storedOptional() ?: existing?.parentEmail,
            createdAtMillis = existing?.createdAtMillis ?: now,
            expiresAtMillis = existing?.expiresAtMillis
                ?: TripReportRetention.expiresAtMillis(request.endedAtMillis)
        )
        recordingDao.upsertTripReport(entity)
        return entity.toDomain()
    }

    fun saveAiReflection(tripId: String, eventId: String, text: String) {
        require(tripId.isNotBlank())
        require(eventId.isNotBlank())
        val storedText = text.storedOptional() ?: return
        recordingDao.upsertTripAiReflection(
            TripAiReflectionEntity(tripId, eventId, storedText, clock())
        )
    }

    fun aiReflectionsForTrip(tripId: String): List<TripAiReflection> =
        recordingDao.tripAiReflectionsForTrip(tripId).map {
            TripAiReflection(eventId = it.eventId, text = it.text)
        }

    fun getReport(tripId: String, nowMillis: Long = clock()): TripReport? {
        purgeExpiredReports(nowMillis)
        val report = recordingDao.tripReportById(tripId) ?: return null
        if (TripReportRetention.isExpired(report.expiresAtMillis, nowMillis)) {
            deleteReportAndAssociatedTripData(report.tripId, report.plannedTripId)
            return null
        }
        return report.toDomain()
    }

    fun listUnexpiredReports(nowMillis: Long = clock()): List<TripReport> {
        purgeExpiredReports(nowMillis)
        return recordingDao.unexpiredTripReports(nowMillis).map { it.toDomain() }
    }

    /** List projection: date/start/endpoints for distinguishing trips. */
    fun listHistoryItems(nowMillis: Long = clock()): List<TripReportHistoryItem> {
        purgeExpiredReports(nowMillis)
        return recordingDao.unexpiredTripReports(nowMillis).map { entity ->
            TripReportHistoryItem(
                tripId = entity.tripId,
                startedAtMillis = entity.startedAtMillis,
                endedAtMillis = entity.endedAtMillis,
                startingPointLabel = entity.startingPointLabel,
                destinationLabel = entity.destinationLabel,
                parentEmail = entity.parentEmail
            )
        }
    }

    /**
     * Build share payload for [tripId] only.
     * Parent-facing events come from [events] (post-trip analysis), not Room detector counts.
     * Optional route/reflection follow [selection].
     *
     * [attachments] must be produced by the report UI (map snapshot / report PDF) for this
     * same trip — Client never renders maps or invents coordinates in the share text.
     */
    fun buildSharePayload(
        tripId: String,
        selection: TripReportShareSelection,
        /** Acceleration/braking events selected for the parent-facing report. */
        events: List<ShareEventLine>,
        attachments: List<TripReportShareAttachment> = emptyList(),
        nowMillis: Long = clock()
    ): TripReportSharePayload? {
        val report = getReport(tripId, nowMillis) ?: return null
        val reflectionText = aiReflectionsForTrip(tripId)
            .filter { isReportableReflectionEventId(it.eventId) }
            .joinToString("\n\n") { it.text }
            .ifBlank { report.finalAiText.orEmpty() }
        return TripReportShareTextBuilder.build(
            report = report.copy(finalAiText = reflectionText.takeIf { it.isNotBlank() }),
            selection = selection,
            events = events,
            attachments = attachments
        )
    }

    /** Remove expired reports and associated local trip/GPS/event rows. */
    fun purgeExpiredReports(nowMillis: Long = clock()): Int {
        val expiredIds = recordingDao.expiredTripReportIds(nowMillis)
        expiredIds.forEach { tripId ->
            val plannedTripId = recordingDao.tripReportById(tripId)?.plannedTripId
            deleteReportAndAssociatedTripData(tripId, plannedTripId)
        }
        return expiredIds.size
    }

    private fun deleteReportAndAssociatedTripData(tripId: String, plannedTripId: String?) {
        recordingDao.deleteTripReport(tripId)
        recordingDao.deleteTripAiReflectionsForTrip(tripId)
        recordingDao.deleteGpsPointsForTrip(tripId)
        recordingDao.deleteDrivingEventsForTrip(tripId)
        recordingDao.deleteTrip(tripId)
        val plannedId = plannedTripId?.takeIf { it.isNotBlank() }
        if (plannedId != null) {
            runCatching { bindingRepository?.deleteByPlannedTripId(plannedId) }
            runCatching { configurationRepository?.delete(plannedId) }
        }
    }

    private fun estimateDistanceMeters(points: List<GpsPointEntity>): Int? {
        if (points.size < 2) return null
        var totalKm = 0.0
        for (index in 1 until points.size) {
            val from = points[index - 1]
            val to = points[index]
            if (!from.latitude.isFinite() || !from.longitude.isFinite() ||
                !to.latitude.isFinite() || !to.longitude.isFinite()
            ) {
                continue
            }
            totalKm += HaversineDistanceCalculator.distanceKilometres(
                GeographicPoint(from.latitude, from.longitude),
                GeographicPoint(to.latitude, to.longitude)
            )
        }
        return (totalKm * 1000.0).roundToInt().takeIf { it > 0 }
    }

    private fun String?.storedOptional(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun TripReportEntity.toDomain(): TripReport = TripReport(
        tripId = tripId,
        plannedTripId = plannedTripId,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        startingPointLabel = startingPointLabel,
        destinationLabel = destinationLabel,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        hardAccelerationCount = hardAccelerationCount,
        hardBrakingCount = hardBrakingCount,
        includeDetailedRouteByDefault = includeDetailedRouteByDefault,
        includeReflectionByDefault = includeReflectionByDefault,
        finalAiText = finalAiText,
        parentEmail = parentEmail,
        createdAtMillis = createdAtMillis,
        expiresAtMillis = expiresAtMillis
    )
}
