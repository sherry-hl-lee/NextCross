# Client B backend-notification prototype handoff

## Scope

This change belongs to the Android Client B permitted-area module. It adds the transport boundary
from an existing `AreaBreachEvent` to the team's current FastAPI prototype. It does not change:

- `App.kt`;
- `TripSetupScreen.kt`;
- any Compose layout or navigation;
- the Python backend;
- real Email or SMS provider delivery.

The transport is intentionally prototype-only: it uses an in-process background executor and has
no durable queue or retry policy.

## Android changes

The new `client/permittedarea/transport` package contains:

- `NotificationEventRequest`: maps the domain event to the backend's exact camelCase JSON;
- `NotificationEventsHttpClient`: POSTs the JSON with platform `HttpURLConnection`;
- `PrototypeBackendNotificationSink`: keeps network I/O off the location-reader callback.

`ClientBTripRuntimeLocator` now creates a composite event sink. Every event still reaches
`AreaBreachEventRelay` for existing observers and, in an enabled prototype build, is also submitted
to the backend.

Debug builds are configured for the standard Android Emulator host alias:

```text
http://10.0.2.2:8000/api/v1/notification-events
```

The configuration is in:

```text
app/src/debug/res/values/prototype_backend_config.xml
```

For a physical device, replace `10.0.2.2` in the debug resource with the development machine's LAN
address. Release/main resources disable this prototype transport by default. Cleartext HTTP is
enabled only by the debug manifest; a deployed build should use HTTPS.

## UI-owner handoff

The transport becomes active when `ClientBTripRuntimeLocator.get(context)` creates the Client B
runtime. The current main UI still calls `TripRecordingCoordinator` directly, so it does not yet
start Client B monitoring or produce an `AreaBreachEvent`.

The UI owner still needs to perform the documented, narrow orchestration change:

1. create or recover one stable planned-trip ID;
2. obtain a real permitted-area centre and radius;
3. call `runtime.saveReviewedTrip(configuration)` and `runtime.selectCurrentPlannedTrip(tripId)`;
4. replace the existing direct start call with `runtime.startTrip(tripId)`;
5. replace the existing direct end call with `runtime.endTrip()`.

The transport requires no additional Compose callback and no UI network call. The UI must not call
both `TripRecordingCoordinator.startRecording()` and `ClientBTripRuntime.startTrip()` for the same
button action because the runtime already owns the recording call.

Current limitation: `TripSetupScreen` displays a prototype permitted-area radius and does not expose
real centre/radius values through its callback. The UI owner must decide that interface before an
end-to-end breach can be generated from the main flow.

## Backend-owner handoff

Android sends:

```http
POST /api/v1/notification-events
Content-Type: application/json; charset=utf-8
Accept: application/json
```

```json
{
  "tripId": "planned-trip-001",
  "eventId": "breach-001",
  "eventType": "PERMITTED_AREA_BREACH",
  "occurredAtEpochMillis": 1788231600000,
  "parentEmail": "parent@example.com",
  "parentMobileNumber": "+61400000000"
}
```

Important contract details:

- `eventType` uses `AreaBreachEventType.wireValue`; Android does not send the Kotlin enum name;
- the timestamp is a JSON number in epoch milliseconds;
- the request contains no location, centre, radius, route, or continuous GPS data;
- both `parentEmail` and `parentMobileNumber` remain present because the current backend schema
  requires both fields.

Including `parentMobileNumber` gives a future backend SMS adapter the destination it needs, but it
does not send a real SMS by itself. The backend team still owns the SMS provider integration,
number normalisation/validation, delivery status, and credentials. The current backend gateway only
logs prototype Email and SMS messages.

The current Android prototype treats any HTTP 2xx response as accepted. It does not parse provider
delivery state, retry failures, or persist unsent events.

## Manual integration check

1. Run the FastAPI prototype on the development machine at `0.0.0.0:8000`.
2. Install a debug build on the Android Emulator.
3. Complete the UI-owner runtime wiring described above.
4. Trigger one inside-to-outside transition.
5. Confirm Logcat under tag `ClientBNotification` reports HTTP 201 or 200.
6. Confirm the backend terminal prints the matching prototype Email and SMS log entries.

Unit tests cover the six-field JSON mapping, the `PERMITTED_AREA_BREACH` wire value, HTTP request
headers/body, 2xx/non-2xx classification, and isolation of sender exceptions from the event
publisher.
