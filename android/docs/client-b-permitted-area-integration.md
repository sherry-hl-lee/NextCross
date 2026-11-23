# Client B permitted-area business logic and integration guide

This document describes the callable Client B business APIs added for Epic 2. It is an integration
guide for the UI and backend teams; it does not prescribe UI layout or an HTTP endpoint.

## Scope and ownership

The implementation in `client/permittedarea` provides:

- one-time setup GPS capture for a fixed permitted-area centre;
- fixed circular permitted-area configuration and local persistence;
- active-trip location reads through Client A's existing recording database;
- Haversine distance and inside/outside checks;
- breach transition state management;
- one breach event for each `inside -> outside` transition;
- a transport-neutral breach-event handoff interface.

It does not provide:

- Compose screens or UI layout;
- a second GPS recorder or another Room database;
- an HTTP endpoint or request adapter;
- Email/SMS delivery, retries, or backend idempotency.

The integration branch does not modify `App.kt`, any UI Screen, `MainActivity`, Client A's
recording classes, or Client A's Room schema.

## Main data types

All types below are in:

```kotlin
com.fit5120tp10.nextcross.client.permittedarea
```

### Planned-trip configuration

```kotlin
val configuration = PermittedTripConfiguration(
    tripId = plannedTripId,
    permittedArea = PermittedArea(
        centre = GeographicPoint(
            latitude = capturedLatitude,
            longitude = capturedLongitude
        ),
        radiusKilometres = selectedRadiusKilometres
    ),
    parentContact = ParentNotificationContact(
        email = parentEmail.trim(),
        mobileNumber = parentMobile.trim()
    )
)
```

Rules enforced by these models:

- latitude must be between `-90` and `90`;
- longitude must be between `-180` and `180`;
- radius must be finite and greater than `0` kilometres;
- planned-trip ID, email, and mobile number must not be blank;
- the permitted-area centre is immutable after the configuration is created.

Email and mobile format validation remains a UI responsibility. Under the current acceptance
criteria, both email and mobile are required.

## UI integration points

### 1. Create or recover a planned-trip ID

Use one stable ID for the whole setup/review/start flow. A UUID is suitable.

```kotlin
val runtime = ClientBTripRuntimeLocator.get(appContext)

val plannedTripId = runtime.getCurrentPlannedTripId()
    ?: UUID.randomUUID().toString().also(runtime::selectCurrentPlannedTrip)
```

`plannedTripId` is the parent-planned identifier. Client A creates a separate recording ID when
the trip starts; Client B persists the association between the two IDs.

### 2. Capture the fixed centre once during setup

```kotlin
val provider = AndroidSetupLocationProvider(appContext)

val request = provider.captureCurrentLocation { result ->
    when (result) {
        is SetupLocationResult.Success -> {
            val fixedCentre: GeographicPoint = result.point
            // Store in setup UI state. Do not replace it with active-trip locations.
        }

        is SetupLocationResult.Unavailable -> {
            // Keep Review disabled and show an appropriate UI message.
            val reason: String = result.reason
        }
    }
}
```

The UI must request precise location permission before calling this API. The provider asks Android
for one GPS fix, unregisters immediately after completion, and times out after 15 seconds. If the
screen leaves while a request is pending, call:

```kotlin
request.cancel()
```

Unavailable reason constants are defined on `AndroidSetupLocationProvider`, including permission
not granted, GPS disabled/unavailable, timeout, cancellation, and invalid fix.

### 3. Validate and save when Review Trip Details is selected

Before navigation, the UI must require:

- a successfully captured fixed centre;
- a valid radius greater than zero;
- a valid, non-empty parent email;
- a valid, non-empty parent mobile number.

After UI validation, create the configuration and save it:

```kotlin
runtime.saveReviewedTrip(configuration)
runtime.selectCurrentPlannedTrip(configuration.tripId)
```

To recover a saved configuration:

```kotlin
val configuration = runtime.getReviewedTrip(plannedTripId)
```

The existing review screen should display values from this configuration:

- `configuration.permittedArea.radiusKilometres`;
- `configuration.parentContact.email`;
- `configuration.parentContact.mobileNumber`;
- alert channels: Email and SMS.

Client B stores this configuration under the app-private `noBackupFilesDir`. It does not modify
Client A's Room database.

### 4. Start Trip

After precise location permission has been granted, call:

```kotlin
when (val result = runtime.startTrip(plannedTripId)) {
    is ClientBTripStartResult.Started -> {
        val recordingSession = result.recordingSession
        val plannedToRecordingBinding = result.binding

        // Continue the existing UI flow. recordingSession can be kept for Trip Summary.
    }

    ClientBTripStartResult.MissingPlannedTripConfiguration -> Unit
    ClientBTripStartResult.ClientBRuntimeAlreadyActive -> Unit
    is ClientBTripStartResult.LocationUnavailable -> Unit
    is ClientBTripStartResult.RecordingRejected -> Unit
}
```

`ClientBTripRuntime.startTrip()` already calls Client A's
`TripRecordingCoordinator.startRecording()` internally, then starts the permitted-area monitor over
Client A's recorded GPS rows. The UI must not additionally call `startRecording()` for the same
button action.

`LocationUnavailable.reason` can be:

- `FineLocationPermissionRequired`;
- `GpsProviderDisabled`;
- `GpsProviderUnavailable`.

When start succeeds, `Started.recordingSession` is the exact Client A `TripSession` created by that
operation. It is intended for the existing active-trip and summary UI flow.

### 5. End Trip

```kotlin
when (val result = runtime.endTrip()) {
    is TripCommandResult.Success -> {
        val completedSession = result.session
        // Continue to the existing Trip Summary flow.
    }

    is TripCommandResult.Rejected -> Unit
}
```

`ClientBTripRuntime.endTrip()` makes permitted-area checking inactive before it calls Client A's
`TripRecordingCoordinator.endRecording()`. Once it returns, no later GPS row can create a breach
event for the ended trip. The UI must not additionally call `endRecording()` for the same action.

The smallest integration into the current UI is therefore:

- keep the existing Screen composables and navigation unchanged;
- in the existing start helper, replace the direct coordinator call with `runtime.startTrip(...)`;
- use `Started.recordingSession` wherever the UI currently uses the coordinator's start session;
- in the existing End callback, replace the direct coordinator call with `runtime.endTrip()`;
- use the returned successful end session for Trip Summary as before.

## Boundary and breach behaviour

The stateless checker is callable independently:

```kotlin
val result = PermittedAreaBoundaryChecker().check(
    permittedArea = configuration.permittedArea,
    currentLocation = currentPointOrNull
)
```

Its rules are:

- `distance <= radius` means inside;
- `distance > radius` means outside;
- a null/missing location returns `LocationUnavailable` and never implies a breach.

`PermittedAreaBreachMonitor` adds active-trip state:

- `inside -> outside`: produce one new breach event;
- `outside -> outside`: do not produce another event;
- `outside -> inside`: reset breach state;
- the next `inside -> outside`: produce a new event;
- after `endTrip()`: all observations are inert.

The production runtime obtains active-trip observations from the existing
`RecordingDao.gpsPointsForTrip(recordingTripId)` read interface. It does not request GPS itself and
does not write movement data.

## Backend handoff

The transport-neutral output interface is:

```kotlin
fun interface AreaBreachEventSink {
    fun onAreaBreach(event: AreaBreachEvent)
}
```

Until an HTTP contract is agreed, the app-wide runtime publishes to `AreaBreachEventRelay`.
A backend adapter can subscribe without changing boundary logic:

```kotlin
val subscription = AreaBreachEventRelay.subscribe { event ->
    // Hand the event to a backend queue/HTTP adapter here.
}

// Release when the adapter is permanently disposed.
subscription.close()
```

Each event contains only:

```text
tripId: String
eventId: String
eventType: PERMITTED_AREA_BREACH
occurredAtEpochMillis: Long
parentEmail: String
parentMobileNumber: String
```

`tripId` is the planned-trip ID. `eventId` is a UUID generated for one breach transition.
`occurredAtEpochMillis` uses the timestamp of Client A's GPS observation when available.

The event deliberately excludes the driver's coordinates, permitted-area centre/radius, route,
and continuous location history.

No endpoint, URL/body duplication rule, authentication scheme, HTTP status code, phone wire
format, partial Email/SMS result, retry rule, or duplicate response has been hard-coded. Those
remain backend contract decisions. Client B only guarantees that it does not create repeated events
while the driver remains outside; backend delivery idempotency should use `eventId`.

## New files and teammate-owned files

All production files are under:

```text
app/src/main/java/com/fit5120tp10/nextcross/client/permittedarea/
```

Unit tests are under:

```text
app/src/test/java/com/fit5120tp10/nextcross/client/permittedarea/
```

No teammate-owned or shared source file is changed on this integration branch. The UI integration
described above is intentionally left for the UI owner.
