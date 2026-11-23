package com.fit5120tp10.nextcross.client.permittedarea.transport

import com.fit5120tp10.nextcross.client.permittedarea.AreaBreachEvent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class NotificationDeliveryResult {
    data class Accepted(val statusCode: Int) : NotificationDeliveryResult()
    data class Rejected(val statusCode: Int) : NotificationDeliveryResult()
    data class Failed(val cause: Exception) : NotificationDeliveryResult()
}

fun interface NotificationEventSender {
    fun send(event: AreaBreachEvent): NotificationDeliveryResult
}

/** Minimal HTTP adapter for the current FastAPI notification-event prototype. */
class NotificationEventsHttpClient(
    endpointUrl: String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) : NotificationEventSender {
    private val endpoint = URL(endpointUrl)

    init {
        require(endpoint.protocol == "http" || endpoint.protocol == "https") {
            "Notification endpoint must use HTTP or HTTPS."
        }
        require(connectTimeoutMillis > 0) { "Connect timeout must be positive." }
        require(readTimeoutMillis > 0) { "Read timeout must be positive." }
    }

    override fun send(event: AreaBreachEvent): NotificationDeliveryResult {
        val body = NotificationEventRequest.from(event)
            .toJson()
            .toByteArray(StandardCharsets.UTF_8)
        var connection: HttpURLConnection? = null

        return try {
            connection = connectionFactory(endpoint)
            connection.requestMethod = HTTP_METHOD_POST
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty(HEADER_CONTENT_TYPE, JSON_CONTENT_TYPE)
            connection.setRequestProperty(HEADER_ACCEPT, JSON_ACCEPT)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { output -> output.write(body) }

            val statusCode = connection.responseCode
            consumeResponse(connection, statusCode)
            if (statusCode in HTTP_SUCCESS_RANGE) {
                NotificationDeliveryResult.Accepted(statusCode)
            } else {
                NotificationDeliveryResult.Rejected(statusCode)
            }
        } catch (exception: Exception) {
            NotificationDeliveryResult.Failed(exception)
        } finally {
            connection?.disconnect()
        }
    }

    fun deleteEventsForTrip(plannedTripId: String): NotificationDeliveryResult {
        require(plannedTripId.isNotBlank()) { "Trip identifier must not be blank." }
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(plannedTripId, StandardCharsets.UTF_8.name()).replace("+", "%20")
            connection = connectionFactory(URL("${endpoint.toString().trimEnd('/')}/$encoded"))
            connection.requestMethod = HTTP_METHOD_DELETE
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty(HEADER_ACCEPT, JSON_ACCEPT)
            val statusCode = connection.responseCode
            consumeResponse(connection, statusCode)
            if (statusCode in HTTP_SUCCESS_RANGE) {
                NotificationDeliveryResult.Accepted(statusCode)
            } else {
                NotificationDeliveryResult.Rejected(statusCode)
            }
        } catch (exception: Exception) {
            NotificationDeliveryResult.Failed(exception)
        } finally {
            connection?.disconnect()
        }
    }

    private fun consumeResponse(connection: HttpURLConnection, statusCode: Int) {
        runCatching {
            val response = if (statusCode >= HTTP_ERROR_STATUS_START) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            response?.use { input ->
                val buffer = ByteArray(RESPONSE_BUFFER_BYTES)
                while (input.read(buffer) != -1) {
                    // The prototype only needs the HTTP status; drain the body before disconnecting.
                }
            }
        }.onFailure { failure ->
            if (failure !is IOException) {
                throw failure
            }
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 10_000

        private const val HTTP_METHOD_POST = "POST"
        private const val HTTP_METHOD_DELETE = "DELETE"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val HEADER_ACCEPT = "Accept"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val JSON_ACCEPT = "application/json"
        private const val HTTP_ERROR_STATUS_START = 400
        private val HTTP_SUCCESS_RANGE = 200..299
        private const val RESPONSE_BUFFER_BYTES = 1_024
    }
}
