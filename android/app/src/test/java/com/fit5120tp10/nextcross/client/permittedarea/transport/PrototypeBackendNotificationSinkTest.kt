package com.fit5120tp10.nextcross.client.permittedarea.transport

import com.fit5120tp10.nextcross.client.permittedarea.AreaBreachEvent
import com.fit5120tp10.nextcross.client.permittedarea.AreaBreachEventType
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeBackendNotificationSinkTest {
    @Test
    fun delegatesEventOffThePublisherBoundaryAndReportsResult() {
        val sentEvents = mutableListOf<AreaBreachEvent>()
        val results = mutableListOf<NotificationDeliveryResult>()
        val sink = PrototypeBackendNotificationSink(
            sender = NotificationEventSender { event ->
                sentEvents += event
                NotificationDeliveryResult.Accepted(201)
            },
            executor = DIRECT_EXECUTOR,
            onResult = { _, result -> results += result }
        )
        val event = event()

        sink.onAreaBreach(event)

        assertEquals(listOf(event), sentEvents)
        assertEquals(listOf(NotificationDeliveryResult.Accepted(201)), results)
    }

    @Test
    fun senderExceptionDoesNotEscapeIntoLocationPublisher() {
        val results = mutableListOf<NotificationDeliveryResult>()
        val sink = PrototypeBackendNotificationSink(
            sender = NotificationEventSender { throw IOException("offline") },
            executor = DIRECT_EXECUTOR,
            onResult = { _, result -> results += result }
        )

        sink.onAreaBreach(event())

        assertTrue(results.single() is NotificationDeliveryResult.Failed)
    }

    private fun event(): AreaBreachEvent {
        return AreaBreachEvent(
            tripId = "planned-trip-001",
            eventId = "breach-001",
            eventType = AreaBreachEventType.PermittedAreaBreach,
            occurredAtEpochMillis = 1_788_231_600_000L,
            parentEmail = "parent@example.com"
        )
    }

    companion object {
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
