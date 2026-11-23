package com.fit5120tp10.nextcross.debug

import android.content.Context
import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.runtime.ClientBTripRuntimeLocator
import com.fit5120tp10.nextcross.client.recording.RecordingLocator
import com.fit5120tp10.nextcross.client.recording.TripLocationSample
import com.fit5120tp10.nextcross.client.recording.TripSampleProcessor
import kotlin.math.PI

/** Debug-only driving situations that feed the existing detector and Room path. */
object DebugScenarioInjector {
    private val processorsByTripId = mutableMapOf<String, TripSampleProcessor>()

    fun run(context: Context, requestedScenario: String?): String {
        val runtime = ClientBTripRuntimeLocator.get(context)
        val binding = runtime.currentBinding
            ?: return "Start a trip in NextCross before choosing a driving situation."
        if (!RecordingLocator.sessionController.isRecording) {
            return "NextCross is not recording. Return to the app and start the trip first."
        }
        val configuration = runtime.getReviewedTrip(binding.plannedTripId)
            ?: return "This trip has no saved permitted area. Complete Trip Setup first."
        val processor = processorsByTripId.getOrPut(binding.recordingTripId) {
            TripSampleProcessor(RecordingLocator.database(context).recordingDao())
        }
        if (requestedScenario != SCENARIO_STATUS) {
            processor.reset()
        }
        val now = System.currentTimeMillis()
        val centre = configuration.permittedArea.centre

        return when (requestedScenario) {
            SCENARIO_STEADY -> {
                processor.record(sample(binding.recordingTripId, now, centre, 40f))
                "Driving steadily at 40 km/h was recorded."
            }
            SCENARIO_ACCELERATE -> {
                processor.record(sample(binding.recordingTripId, now, centre, 20f))
                processor.record(sample(binding.recordingTripId, now + ONE_SECOND_MILLIS, centre, 60f))
                "Acceleration from 20 km/h to 60 km/h was recorded. Check Summary after ending the trip."
            }
            SCENARIO_BRAKE -> {
                processor.record(sample(binding.recordingTripId, now, centre, 60f))
                processor.record(sample(binding.recordingTripId, now + ONE_SECOND_MILLIS, centre, 10f))
                "Sudden braking from 60 km/h to 10 km/h was recorded. Check Summary after ending the trip."
            }
            SCENARIO_OUTSIDE_AREA -> {
                processor.record(sample(binding.recordingTripId, now, centre, 0f))
                val outsidePoint = oneKilometreBeyond(configuration.permittedArea.radiusKilometres, centre)
                processor.record(sample(binding.recordingTripId, now + ONE_SECOND_MILLIS, outsidePoint, 0f))
                "The vehicle has left the permitted area. Wait a moment for NextCross to process the breach."
            }
            SCENARIO_STATUS -> {
                val dao = RecordingLocator.database(context).recordingDao()
                val braking = dao.countDrivingEvents(binding.recordingTripId, "HardBraking")
                val acceleration = dao.countDrivingEvents(binding.recordingTripId, "HardAcceleration")
                "Recorded so far: $braking hard braking, $acceleration hard acceleration."
            }
            else -> "Choose a driving situation from the scenario console."
        }
    }

    private fun sample(
        tripId: String,
        timestamp: Long,
        point: GeographicPoint,
        speedKilometresPerHour: Float
    ): TripLocationSample {
        return TripLocationSample(
            tripId = tripId,
            recordedAtMillis = timestamp,
            latitude = point.latitude,
            longitude = point.longitude,
            speedMetersPerSecond = speedKilometresPerHour / KILOMETRES_PER_HOUR_PER_METRE_PER_SECOND,
            accuracyMeters = 5f
        )
    }

    private fun oneKilometreBeyond(radiusKilometres: Double, centre: GeographicPoint): GeographicPoint {
        val northwardDegrees = ((radiusKilometres + 1.0) / EARTH_RADIUS_KILOMETRES) * (180.0 / PI)
        return GeographicPoint(
            latitude = centre.latitude + northwardDegrees,
            longitude = centre.longitude
        )
    }

    private const val KILOMETRES_PER_HOUR_PER_METRE_PER_SECOND = 3.6f
    private const val EARTH_RADIUS_KILOMETRES = 6_371.0
    private const val ONE_SECOND_MILLIS = 1_000L

    const val SCENARIO_STEADY = "steady"
    const val SCENARIO_ACCELERATE = "accelerate"
    const val SCENARIO_BRAKE = "brake"
    const val SCENARIO_OUTSIDE_AREA = "outside_area"
    const val SCENARIO_STATUS = "status"
}
