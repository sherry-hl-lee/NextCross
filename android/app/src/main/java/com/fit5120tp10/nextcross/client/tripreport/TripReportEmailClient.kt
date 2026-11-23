package com.fit5120tp10.nextcross.client.tripreport

import android.content.ContentResolver
import android.net.Uri
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Path B entry point for UI: load one local report, build text, POST multipart to I2.
 * Does not open the system share sheet and does not delete the local Room report.
 */
class TripReportEmailClient(
    private val repository: TripReportRepository,
    private val http: TripReportEmailHttpClient,
    private val contentResolver: ContentResolver,
    private val sendStore: TripReportEmailSendStore,
    private val enabled: Boolean,
    private val isInternetAvailable: () -> Boolean
) {
    fun isEnabled(): Boolean = enabled

    fun sendStatus(tripId: String): TripReportEmailSendStore.Status = sendStore.status(tripId)

    /**
     * One report email per trip. The request ID is stable for the trip, and a local claim
     * prevents another send even if the backend's temporary store bypass is enabled.
     */
    fun sendToParent(
        tripId: String,
        parentEmail: String,
        selection: TripReportShareSelection,
        /** Acceleration/braking events selected for the parent-facing report. */
        events: List<ShareEventLine>,
        attachments: List<TripReportShareAttachment> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): TripReportEmailResult {
        if (!enabled) {
            return TripReportEmailResult.Invalid("Trip report email is disabled in this build.")
        }
        val email = parentEmail.trim()
        if (!RecipientEmailValidator.isValid(email)) {
            return TripReportEmailResult.Invalid("Enter a valid parent email address.")
        }
        when (sendStore.status(tripId)) {
            TripReportEmailSendStore.Status.Accepted ->
                return TripReportEmailResult.Invalid("This trip report has already been emailed.")
            TripReportEmailSendStore.Status.Pending ->
                return TripReportEmailResult.Invalid("The previous email's outcome is uncertain. Do not resend this trip report.")
            TripReportEmailSendStore.Status.NotSent -> Unit
        }
        val report = repository.getReport(tripId, nowMillis)
            ?: return TripReportEmailResult.Invalid("This trip report is missing or expired.")

        val payload = repository.buildSharePayload(
            tripId = tripId,
            selection = selection,
            events = events,
            attachments = attachments,
            nowMillis = nowMillis
        ) ?: return TripReportEmailResult.Invalid("This trip report is missing or expired.")

        val prepared = prepareAttachments(selection, attachments)
        if (prepared is TripReportEmailResult) return prepared
        @Suppress("UNCHECKED_CAST")
        val parts = prepared as List<TripReportEmailAttachmentBytes>

        // A known-offline attempt must not claim this trip. Once dispatch begins, failures stay
        // pending because the provider may have accepted the email before the connection failed.
        if (!isInternetAvailable()) {
            return TripReportEmailResult.Invalid("No internet connection. Reconnect and try again.")
        }

        val clientRequestId = requestIdForTrip(tripId)

        val meta = TripReportEmailMetaJson.build(
            report = report,
            parentEmail = email,
            selection = selection,
            events = events,
            subject = payload.subject,
            plainText = payload.plainText,
            clientRequestId = clientRequestId
        )
        if (!sendStore.claim(tripId)) {
            return TripReportEmailResult.Invalid("This trip report is already sent or its email outcome is uncertain.")
        }
        val result = http.send(metaJson = meta, attachments = parts)
        when (result) {
            is TripReportEmailResult.Accepted -> sendStore.markAccepted(tripId)
            is TripReportEmailResult.Failed -> {
                if (result.code in PRE_DISPATCH_FAILURE_CODES || result.httpStatus == 422) {
                    sendStore.releaseKnownFailure(tripId)
                }
            }
            is TripReportEmailResult.Invalid -> Unit
        }
        return result
    }

    private fun requestIdForTrip(tripId: String): String = UUID.nameUUIDFromBytes(
        "nextcross-trip-report-email:$tripId".toByteArray(StandardCharsets.UTF_8)
    ).toString()

    private fun prepareAttachments(
        selection: TripReportShareSelection,
        attachments: List<TripReportShareAttachment>
    ): Any {
        var pdf: TripReportEmailAttachmentBytes? = null
        var map: TripReportEmailAttachmentBytes? = null

        for (attachment in attachments) {
            val mime = attachment.mimeType.trim().lowercase()
            val bytes = readBytes(attachment.uriString)
                ?: return TripReportEmailResult.Invalid("Could not read attachment: ${attachment.displayName ?: attachment.uriString}")
            when {
                mime == "application/pdf" -> {
                    if (pdf != null) {
                        return TripReportEmailResult.Invalid("Only one PDF attachment is allowed.")
                    }
                    if (bytes.isEmpty() || bytes.size > MAX_PDF_BYTES) {
                        return TripReportEmailResult.Invalid("PDF must be non-empty and at most 5 MiB.")
                    }
                    if (!bytes.startsWithPdf()) {
                        return TripReportEmailResult.Invalid("PDF attachment is not a valid PDF.")
                    }
                    pdf = TripReportEmailAttachmentBytes(
                        partName = TripReportEmailAttachmentBytes.PART_PDF,
                        fileName = "trip-report.pdf",
                        mimeType = "application/pdf",
                        bytes = bytes
                    )
                }
                mime == "image/png" || mime == "image/jpeg" -> {
                    if (map != null) {
                        return TripReportEmailResult.Invalid("Only one route map attachment is allowed.")
                    }
                    if (bytes.isEmpty() || bytes.size > MAX_MAP_BYTES) {
                        return TripReportEmailResult.Invalid("Map must be non-empty and at most 2 MiB.")
                    }
                    val ok = if (mime == "image/png") bytes.startsWithPng() else bytes.startsWithJpeg()
                    if (!ok) {
                        return TripReportEmailResult.Invalid("Route map attachment signature is invalid.")
                    }
                    if (!selection.includeDetailedRoute) {
                        return TripReportEmailResult.Invalid("Route map was provided but detailed route is not selected.")
                    }
                    map = TripReportEmailAttachmentBytes(
                        partName = TripReportEmailAttachmentBytes.PART_MAP,
                        fileName = if (mime == "image/png") "trip-route.png" else "trip-route.jpg",
                        mimeType = mime,
                        bytes = bytes
                    )
                }
                else -> {
                    return TripReportEmailResult.Invalid("Unsupported attachment type: $mime")
                }
            }
        }

        if (selection.includeDetailedRoute && map == null) {
            return TripReportEmailResult.Invalid(
                "Detailed route is selected — attach a route map snapshot before sending."
            )
        }

        return listOfNotNull(pdf, map)
    }

    private fun readBytes(uriString: String): ByteArray? {
        return runCatching {
            val uri = Uri.parse(uriString)
            contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            }
        }.getOrNull()
    }

    private fun ByteArray.startsWithPdf(): Boolean =
        size >= 5 && this[0] == '%'.code.toByte() && this[1] == 'P'.code.toByte() &&
            this[2] == 'D'.code.toByte() && this[3] == 'F'.code.toByte() && this[4] == '-'.code.toByte()

    private fun ByteArray.startsWithPng(): Boolean =
        size >= 8 &&
            this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'N'.code.toByte() &&
            this[3] == 'G'.code.toByte() && this[4] == 0x0d.toByte() && this[5] == 0x0a.toByte() &&
            this[6] == 0x1a.toByte() && this[7] == 0x0a.toByte()

    private fun ByteArray.startsWithJpeg(): Boolean =
        size >= 3 && this[0] == 0xff.toByte() && this[1] == 0xd8.toByte() && this[2] == 0xff.toByte()

    companion object {
        private const val MAX_PDF_BYTES = 5 * 1024 * 1024
        private const val MAX_MAP_BYTES = 2 * 1024 * 1024
        /** Backend codes returned before any provider call. Other failures may be ambiguous. */
        private val PRE_DISPATCH_FAILURE_CODES = setOf(
            "INVALID_REQUEST", "PAYLOAD_TOO_LARGE", "RECIPIENT_NOT_ALLOWED",
            "DISPATCH_STORE_UNAVAILABLE"
        )
    }
}
