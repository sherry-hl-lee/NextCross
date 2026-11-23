package com.fit5120tp10.nextcross.client.tripreport

/**
 * Path B — backend SMTP/Resend send (not the device share sheet).
 * Success means the provider accepted submission, not that the parent opened the mail.
 */
sealed class TripReportEmailResult {
    data class Accepted(
        val tripId: String,
        val clientRequestId: String,
        val duplicate: Boolean
    ) : TripReportEmailResult()

    /**
     * Known failure or uncertain outcome.
     * [code] mirrors backend `code` when present (e.g. `SMTP_UNAVAILABLE`, `RATE_LIMITED`).
     */
    data class Failed(
        val httpStatus: Int? = null,
        val code: String? = null,
        val status: String? = null,
        val duplicate: Boolean = false,
        val cause: Exception? = null
    ) : TripReportEmailResult()

    /** Local validation before any network call. */
    data class Invalid(val reason: String) : TripReportEmailResult()
}

/** Binary parts for multipart upload (already read from a content/file URI). */
data class TripReportEmailAttachmentBytes(
    val partName: String,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TripReportEmailAttachmentBytes) return false
        return partName == other.partName &&
            fileName == other.fileName &&
            mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = partName.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    companion object {
        const val PART_PDF = "reportPdf"
        const val PART_MAP = "routeMap"
    }
}
