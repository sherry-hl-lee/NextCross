package com.fit5120tp10.nextcross.client.tripreport

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Minimal multipart adapter for POST /api/v1/trip-reports/email. */
class TripReportEmailHttpClient(
    endpointUrl: String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) {
    private val endpoint = URL(endpointUrl)

    init {
        require(endpoint.protocol == "http" || endpoint.protocol == "https") {
            "Trip report email endpoint must use HTTP or HTTPS."
        }
        require(connectTimeoutMillis > 0) { "Connect timeout must be positive." }
        require(readTimeoutMillis > 0) { "Read timeout must be positive." }
    }

    fun send(
        metaJson: String,
        attachments: List<TripReportEmailAttachmentBytes> = emptyList()
    ): TripReportEmailResult {
        val boundary = "----NextCrossBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val body = buildMultipartBody(boundary, metaJson, attachments)
        var connection: HttpURLConnection? = null
        return try {
            connection = connectionFactory(endpoint)
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val statusCode = connection.responseCode
            val responseText = readBody(connection, statusCode)
            parseResponse(statusCode, responseText)
        } catch (exception: Exception) {
            TripReportEmailResult.Failed(cause = exception)
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        metaJson: String,
        attachments: List<TripReportEmailAttachmentBytes>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeAscii(value: String) {
            out.write(value.toByteArray(StandardCharsets.US_ASCII))
        }
        fun writeUtf8(value: String) {
            out.write(value.toByteArray(StandardCharsets.UTF_8))
        }

        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"meta\"\r\n")
        writeAscii("Content-Type: application/json; charset=utf-8\r\n\r\n")
        writeUtf8(metaJson)
        writeAscii("\r\n")

        for (part in attachments) {
            writeAscii("--$boundary\r\n")
            writeAscii(
                "Content-Disposition: form-data; name=\"${part.partName}\"; filename=\"${part.fileName}\"\r\n"
            )
            writeAscii("Content-Type: ${part.mimeType}\r\n\r\n")
            out.write(part.bytes)
            writeAscii("\r\n")
        }
        writeAscii("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun readBody(connection: HttpURLConnection, statusCode: Int): String {
        val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun parseResponse(statusCode: Int, body: String): TripReportEmailResult {
        val json = runCatching { if (body.isBlank()) null else JSONObject(body) }.getOrNull()
        val status = json?.optString("status")?.takeIf { it.isNotBlank() }
        val code = json?.optString("code")?.takeIf { it.isNotBlank() }
        val tripId = json?.optString("tripId").orEmpty()
        val clientRequestId = json?.optString("clientRequestId").orEmpty()
        val duplicate = json?.optBoolean("duplicate", false) == true

        if (statusCode in 200..299 && status.equals("accepted", ignoreCase = true)) {
            return TripReportEmailResult.Accepted(
                tripId = tripId,
                clientRequestId = clientRequestId,
                duplicate = duplicate
            )
        }
        return TripReportEmailResult.Failed(
            httpStatus = statusCode,
            code = code,
            status = status,
            duplicate = duplicate
        )
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 60_000
    }
}
