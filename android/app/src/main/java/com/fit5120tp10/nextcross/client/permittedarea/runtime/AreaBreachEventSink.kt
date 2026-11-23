package com.fit5120tp10.nextcross.client.permittedarea.runtime

import com.fit5120tp10.nextcross.client.permittedarea.AreaBreachEvent

/**
 * Handoff boundary for a newly generated Client B breach event.
 *
 * A later backend adapter can implement this interface. It should return quickly after durably
 * accepting the event into its own outbox; network I/O must not block the location reader. The
 * runtime itself does not send email, SMS, HTTP, or continuous location data.
 */
fun interface AreaBreachEventSink {
    fun onAreaBreach(event: AreaBreachEvent)
}
