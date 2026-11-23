package com.fit5120tp10.nextcross.client.tripreport

/** Compact row for report history list. */
data class TripReportHistoryItem(
    val tripId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val startingPointLabel: String,
    val destinationLabel: String,
    val parentEmail: String? = null
)

/** Young-driver choices before opening the system share sheet. */
data class TripReportShareSelection(
    val includeDetailedRoute: Boolean = false,
    val includeReflectionConversation: Boolean = false
)

/** Driving-event kinds included in a parent-facing Trip Report. */
enum class ShareEventKind {
    HARD_ACCELERATION,
    HARD_BRAKING
}

/**
 * One analysed driving event for parent-facing share text.
 * UI maps from post-trip [chronologicalEvents]; do not use live detector DB counts.
 */
data class ShareEventLine(
    val kind: ShareEventKind,
    val startMillis: Long,
    val durationSeconds: Double
) {
    val label: String
        get() = when (kind) {
            ShareEventKind.HARD_ACCELERATION -> "Hard acceleration"
            ShareEventKind.HARD_BRAKING -> "Hard braking"
        }
}

fun List<ShareEventLine>.hardAccelerationCount(): Int =
    count { it.kind == ShareEventKind.HARD_ACCELERATION }

fun List<ShareEventLine>.hardBrakingCount(): Int =
    count { it.kind == ShareEventKind.HARD_BRAKING }

/**
 * File produced by the report UI for one trip only.
 * Client does not render maps or PDFs — UI captures/exports, then passes URIs here.
 */
data class TripReportShareAttachment(
    /** `content://` or `file://` URI string safe to hand to [android.content.Intent]. */
    val uriString: String,
    /** e.g. `image/png`, `image/jpeg`, `application/pdf`. */
    val mimeType: String,
    val displayName: String? = null
)

/**
 * Shareable payload for one completed trip only.
 * Opening the share sheet does not send by itself — UI still must hand this to
 * [android.content.Intent.ACTION_SEND] / [android.content.Intent.ACTION_SEND_MULTIPLE].
 */
data class TripReportSharePayload(
    val tripId: String,
    val plainText: String,
    val subject: String,
    val attachments: List<TripReportShareAttachment> = emptyList()
) {
    val hasAttachments: Boolean get() = attachments.isNotEmpty()
}
