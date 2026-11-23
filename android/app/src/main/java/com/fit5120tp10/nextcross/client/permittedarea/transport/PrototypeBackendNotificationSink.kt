package com.fit5120tp10.nextcross.client.permittedarea.transport

import com.fit5120tp10.nextcross.client.permittedarea.AreaBreachEvent
import com.fit5120tp10.nextcross.client.permittedarea.runtime.AreaBreachEventSink
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Prototype-only asynchronous handoff to the backend.
 *
 * Network work runs away from the location-reader callback. This intentionally has no persistence
 * or retry policy; those concerns remain outside the current Android prototype scope.
 */
class PrototypeBackendNotificationSink(
    private val sender: NotificationEventSender,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val onResult: (eventId: String, result: NotificationDeliveryResult) -> Unit = { _, _ -> }
) : AreaBreachEventSink {
    override fun onAreaBreach(event: AreaBreachEvent) {
        try {
            executor.execute {
                val result = try {
                    sender.send(event)
                } catch (exception: Exception) {
                    NotificationDeliveryResult.Failed(exception)
                }
                runCatching { onResult(event.eventId, result) }
            }
        } catch (exception: RuntimeException) {
            runCatching {
                onResult(event.eventId, NotificationDeliveryResult.Failed(exception))
            }
        }
    }
}
