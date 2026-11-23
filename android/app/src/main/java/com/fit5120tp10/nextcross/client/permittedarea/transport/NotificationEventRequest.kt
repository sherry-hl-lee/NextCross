package com.fit5120tp10.nextcross.client.permittedarea.transport

import com.fit5120tp10.nextcross.client.permittedarea.AreaBreachEvent

/** Exact camelCase request contract accepted by the notification-event backend prototype. */
data class NotificationEventRequest(
    val tripId: String,
    val eventId: String,
    val eventType: String,
    val occurredAtEpochMillis: Long,
    val parentEmail: String
) {
    fun toJson(): String = buildString {
        append('{')
        appendJsonField("tripId", tripId)
        append(',')
        appendJsonField("eventId", eventId)
        append(',')
        appendJsonField("eventType", eventType)
        append(',')
        appendJsonField("occurredAtEpochMillis", occurredAtEpochMillis)
        append(',')
        appendJsonField("parentEmail", parentEmail)
        append('}')
    }

    companion object {
        fun from(event: AreaBreachEvent): NotificationEventRequest {
            return NotificationEventRequest(
                tripId = event.tripId,
                eventId = event.eventId,
                eventType = event.eventType.wireValue,
                occurredAtEpochMillis = event.occurredAtEpochMillis,
                parentEmail = event.parentEmail
            )
        }
    }
}

private fun StringBuilder.appendJsonField(name: String, value: String) {
    appendJsonString(name)
    append(':')
    appendJsonString(value)
}

private fun StringBuilder.appendJsonField(name: String, value: Long) {
    appendJsonString(name)
    append(':')
    append(value)
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < JSON_CONTROL_CHARACTER_LIMIT) {
                    append("\\u")
                    appendHexDigit(character.code shr 12)
                    appendHexDigit(character.code shr 8)
                    appendHexDigit(character.code shr 4)
                    appendHexDigit(character.code)
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

private fun StringBuilder.appendHexDigit(value: Int) {
    append(HEX_DIGITS[value and 0x0F])
}

private const val JSON_CONTROL_CHARACTER_LIMIT = 0x20
private const val HEX_DIGITS = "0123456789abcdef"
