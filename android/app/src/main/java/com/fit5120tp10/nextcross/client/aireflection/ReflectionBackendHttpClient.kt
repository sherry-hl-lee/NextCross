package com.fit5120tp10.nextcross.client.aireflection

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/** Raw transport outcome. The body is only returned when the backend answered. */
sealed interface BackendCallResult {
    data class Success(val body: String) : BackendCallResult

    /** HTTP 422: the request shape was wrong. [body] carries sanitised field locations. */
    data class ValidationError(val body: String) : BackendCallResult

    /**
     * [statusCode] is 0 when the request never reached the backend. [detail] is a short,
     * truncated copy of the backend's own error text, kept for debug logs only.
     */
    data class Unavailable(
        val reason: AiUnavailableReason,
        val statusCode: Int = 0,
        val detail: String = ""
    ) : BackendCallResult
}

/**
 * Minimal HTTPS client for the NextCross backend's two reflection routes, in the same shape
 * as the other NextCross HTTP clients: one fixed base URL, an injectable connection factory
 * for tests, no redirects, no retries, and never a thrown exception for the caller.
 *
 * No API key is involved. The provider credential lives on the backend, which is the whole
 * reason the app calls the backend instead of the provider.
 */
class ReflectionBackendHttpClient(
    baseUrl: String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) {
    private val endpointBase = baseUrl.trim().trimEnd('/')

    init {
        val probe = URL(endpointBase)
        require(probe.protocol == "https" || probe.protocol == "http") {
            "The AI reflection endpoint must use HTTP or HTTPS."
        }
        require(connectTimeoutMillis > 0) { "Connect timeout must be positive." }
        require(readTimeoutMillis > 0) { "Read timeout must be positive." }
    }

    /** POSTs [requestBody] to `{base}/{path}`. */
    fun post(path: String, requestBody: String): BackendCallResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$endpointBase/${path.trim('/')}")
            connection = connectionFactory(url).apply {
                requestMethod = HTTP_METHOD_POST
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty(HEADER_CONTENT_TYPE, JSON_CONTENT_TYPE)
                setRequestProperty(HEADER_ACCEPT, JSON_ACCEPT)
            }
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(StandardCharsets.UTF_8))
            }
            when (val statusCode = connection.responseCode) {
                in HTTP_SUCCESS_RANGE -> BackendCallResult.Success(readBody(connection.inputStream))
                422 -> BackendCallResult.ValidationError(
                    runCatching { readBody(connection.errorStream) }.getOrDefault("")
                )
                // The two-turn routes are new; an older deployment answers 404 here.
                404 -> unavailable(AiUnavailableReason.ENDPOINT_NOT_FOUND, statusCode, connection)
                429 -> unavailable(AiUnavailableReason.RATE_LIMITED, statusCode, connection)
                401, 403 -> unavailable(AiUnavailableReason.PROVIDER_AUTHENTICATION, statusCode, connection)
                in HTTP_SERVER_ERROR_RANGE ->
                    unavailable(AiUnavailableReason.PROVIDER_UNAVAILABLE, statusCode, connection)
                else -> unavailable(AiUnavailableReason.PROVIDER_REQUEST_REJECTED, statusCode, connection)
            }
        } catch (_: SocketTimeoutException) {
            BackendCallResult.Unavailable(AiUnavailableReason.TIMEOUT)
        } catch (_: IOException) {
            BackendCallResult.Unavailable(AiUnavailableReason.NETWORK_ERROR)
        } catch (_: Exception) {
            BackendCallResult.Unavailable(AiUnavailableReason.NETWORK_ERROR)
        } finally {
            connection?.disconnect()
        }
    }

    private fun unavailable(
        reason: AiUnavailableReason,
        statusCode: Int,
        connection: HttpURLConnection
    ): BackendCallResult.Unavailable {
        val detail = runCatching { readBody(connection.errorStream) }.getOrDefault("")
        return BackendCallResult.Unavailable(
            reason = reason,
            statusCode = statusCode,
            detail = detail.take(MAX_ERROR_DETAIL_CHARACTERS)
        )
    }

    private fun readBody(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input -> input.readBytes().toString(StandardCharsets.UTF_8) }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 10_000

        /**
         * The backend calls the AI provider with a 20s timeout of its own, and a Render free
         * instance can cold-start for the better part of a minute, so this is deliberately
         * longer than the other NextCross clients.
         */
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 45_000

        private const val MAX_ERROR_DETAIL_CHARACTERS = 300
        private const val HTTP_METHOD_POST = "POST"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val HEADER_ACCEPT = "Accept"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val JSON_ACCEPT = "application/json"
        private val HTTP_SUCCESS_RANGE = 200..299
        private val HTTP_SERVER_ERROR_RANGE = 500..599
    }
}
