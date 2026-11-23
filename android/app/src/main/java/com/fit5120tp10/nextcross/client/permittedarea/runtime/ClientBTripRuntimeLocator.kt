package com.fit5120tp10.nextcross.client.permittedarea.runtime

import android.content.Context
import android.util.Log
import com.fit5120tp10.nextcross.R
import com.fit5120tp10.nextcross.client.permittedarea.AreaBreachEvent
import com.fit5120tp10.nextcross.client.permittedarea.transport.NotificationDeliveryResult
import com.fit5120tp10.nextcross.client.permittedarea.transport.NotificationEventsHttpClient
import com.fit5120tp10.nextcross.client.permittedarea.transport.PrototypeBackendNotificationSink
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-local handoff for generated breach requests until the backend HTTP adapter is agreed.
 * It retains only the latest event and never receives a driver's location.
 */
object AreaBreachEventRelay : AreaBreachEventSink {
    private val listeners = CopyOnWriteArraySet<(AreaBreachEvent) -> Unit>()

    @Volatile
    var latestEvent: AreaBreachEvent? = null
        private set

    override fun onAreaBreach(event: AreaBreachEvent) {
        latestEvent = event
        listeners.forEach { listener ->
            // One future backend adapter must not prevent other observers or later polling cycles.
            runCatching { listener(event) }
        }
    }

    fun subscribe(listener: (AreaBreachEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}

/** Keeps one monitoring runtime alive across Activity recreation while Client A is recording. */
object ClientBTripRuntimeLocator {
    @Volatile
    private var runtime: ClientBTripRuntime? = null
    private var backendSink: AreaBreachEventSink? = null
    private var httpClient: NotificationEventsHttpClient? = null
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun get(context: Context): ClientBTripRuntime {
        val appContext = context.applicationContext
        return runtime ?: synchronized(this) {
            runtime ?: ClientBTripRuntime(
                context = appContext,
                eventSink = createEventSink(appContext),
                afterTripCloudCleanup = createCloudCleanup(appContext)
            ).also { runtime = it }
        }
    }

    private fun createEventSink(context: Context): AreaBreachEventSink {
        val prototypeBackendSink = createPrototypeBackendSink(context)
        return AreaBreachEventSink { event ->
            AreaBreachEventRelay.onAreaBreach(event)
            prototypeBackendSink?.onAreaBreach(event)
        }
    }

    private fun createCloudCleanup(context: Context): ((String) -> Unit)? {
        val client = httpClient(context) ?: return null
        return { tripId -> ioExecutor.execute { runCatching { client.deleteEventsForTrip(tripId) } } }
    }

    private fun createPrototypeBackendSink(context: Context): AreaBreachEventSink? {
        val client = httpClient(context) ?: return null
        return backendSink ?: PrototypeBackendNotificationSink(
            sender = client,
            onResult = ::logPrototypeDeliveryResult
        ).also { backendSink = it }
    }

    private fun httpClient(context: Context): NotificationEventsHttpClient? {
        if (!context.resources.getBoolean(R.bool.prototype_backend_notification_enabled)) return null
        val endpointUrl = context.getString(R.string.prototype_backend_notification_url).trim()
        if (endpointUrl.isEmpty()) return null
        return httpClient ?: NotificationEventsHttpClient(endpointUrl).also { httpClient = it }
    }

    private fun logPrototypeDeliveryResult(
        eventId: String,
        result: NotificationDeliveryResult
    ) {
        when (result) {
            is NotificationDeliveryResult.Accepted -> Log.i(
                PROTOTYPE_TRANSPORT_LOG_TAG,
                "Notification event $eventId accepted with HTTP ${result.statusCode}."
            )
            is NotificationDeliveryResult.Rejected -> Log.w(
                PROTOTYPE_TRANSPORT_LOG_TAG,
                "Notification event $eventId rejected with HTTP ${result.statusCode}."
            )
            is NotificationDeliveryResult.Failed -> Log.w(
                PROTOTYPE_TRANSPORT_LOG_TAG,
                "Notification event $eventId failed with ${result.cause.javaClass.simpleName}."
            )
        }
    }

    private const val PROTOTYPE_TRANSPORT_LOG_TAG = "ClientBNotification"
}
