package com.fit5120tp10.nextcross.client.tripreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class TripReportEmailHttpClientTest {
    @Test
    fun acceptedResponseParsesDuplicateFlag() {
        val body =
            """{"status":"accepted","tripId":"trip-a","clientRequestId":"00000000-0000-4000-8000-000000000001","duplicate":true}"""
        val client = TripReportEmailHttpClient(
            endpointUrl = "https://backend-8sxu.onrender.com/api/v1/trip-reports/email",
            connectionFactory = { fakeConnection(200, body) }
        )
        val result = client.send(metaJson = """{"tripId":"trip-a"}""")
        val accepted = result as TripReportEmailResult.Accepted
        assertEquals("trip-a", accepted.tripId)
        assertTrue(accepted.duplicate)
    }

    @Test
    fun failedResponseSurfacesBackendCode() {
        val body =
            """{"status":"failed","code":"SMTP_UNAVAILABLE","tripId":"trip-a","clientRequestId":"00000000-0000-4000-8000-000000000001","duplicate":false}"""
        val client = TripReportEmailHttpClient(
            endpointUrl = "https://backend-8sxu.onrender.com/api/v1/trip-reports/email",
            connectionFactory = { fakeConnection(502, body) }
        )
        val result = client.send(metaJson = """{"tripId":"trip-a"}""")
        val failed = result as TripReportEmailResult.Failed
        assertEquals(502, failed.httpStatus)
        assertEquals("SMTP_UNAVAILABLE", failed.code)
    }

    @Test
    fun multipartIncludesMetaAndPdfPartNames() {
        val captured = ByteArrayOutputStream()
        val client = TripReportEmailHttpClient(
            endpointUrl = "https://backend-8sxu.onrender.com/api/v1/trip-reports/email",
            connectionFactory = {
                fakeConnection(200, """{"status":"accepted","tripId":"t","clientRequestId":"id","duplicate":false}""") {
                    captured.write(it)
                }
            }
        )
        client.send(
            metaJson = """{"tripId":"t"}""",
            attachments = listOf(
                TripReportEmailAttachmentBytes(
                    partName = "reportPdf",
                    fileName = "trip-report.pdf",
                    mimeType = "application/pdf",
                    bytes = "%PDF-1.4".toByteArray(StandardCharsets.US_ASCII)
                )
            )
        )
        val wire = captured.toString(StandardCharsets.ISO_8859_1)
        assertTrue(wire.contains("name=\"meta\""))
        assertTrue(wire.contains("name=\"reportPdf\""))
        assertTrue(wire.contains("%PDF-1.4"))
    }

    private fun fakeConnection(
        status: Int,
        responseBody: String,
        onRequestBody: ((ByteArray) -> Unit)? = null
    ): HttpURLConnection {
        val responseBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        return object : HttpURLConnection(URL("https://backend-8sxu.onrender.com/api/v1/trip-reports/email")) {
            private val requestBody = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getOutputStream() = requestBody
            override fun getResponseCode(): Int {
                onRequestBody?.invoke(requestBody.toByteArray())
                return status
            }
            override fun getInputStream() = ByteArrayInputStream(responseBytes)
            override fun getErrorStream() = ByteArrayInputStream(responseBytes)
            override fun setRequestMethod(method: String) = Unit
            override fun setConnectTimeout(timeout: Int) = Unit
            override fun setReadTimeout(timeout: Int) = Unit
            override fun setDoOutput(dooutput: Boolean) = Unit
            override fun setRequestProperty(key: String?, value: String?) = Unit
            override fun setFixedLengthStreamingMode(contentLength: Int) = Unit
        }
    }
}
