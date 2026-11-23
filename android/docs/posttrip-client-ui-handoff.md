# Post-trip client integration: UI handoff

## Ownership and scope

The client integration is implemented; UI files are unchanged. The current Summary still
reads the legacy live `driving_events` table until its owner connects the new API.
No network upload, backend speed-limit analysis, AI request, or answer persistence is implemented.
The existing `posttripanalysis` detector, thresholds and ranking are unchanged.

## Public API

Create a coordinator in the UI owner's ViewModel/repository setup:

```kotlin
val analysis = PostTripAnalysisLocator.create(context.applicationContext)

// Collect in the ViewModel's coroutine scope, after requesting End Trip.
// IMPORTANT: this is the recording session ID, not the planned-trip ID.
analysis.observeCompletedTrip(recordingTripId).collect { state ->
    // Publish state to the screen using the UI owner's existing state pattern.
}

// Alternatively, obtain only the terminal result:
val result = analysis.analyzeCompletedTrip(recordingTripId)
```

Types live in `com.fit5120tp10.nextcross.client.posttrip`.
The flow is cold: each collection emits Loading and one terminal state, recomputing analysis.
Retry by collecting again. Cancel the previous collection when switching trips; cancellation
propagates instead of becoming a Failed result. There is no shared mutable analysis state.
Keep the coordinator in the ViewModel, not inside repeated composable rendering.

## State contract

| State | Meaning / UI responsibility |
| --- | --- |
| Idle | Optional initial state before requesting analysis |
| Loading(tripId) | Reading a completed snapshot or analyzing |
| Ready | Contains chronologicalEvents, rankedEvents, full routePoints, issues and analysisRevision |
| Ready with empty events | Analysis succeeded without qualifying local events; not a safety certification |
| InsufficientData | Not enough usable data; display reason separately from zero events |
| Failed | Missing/incomplete trip, invalid event mapping, database or analysis failure |

Examples: steady valid GPS produces Ready with both event lists empty; an empty trip
produces InsufficientData with a one-second coverage explanation; an unfinished trip
produces Failed("Trip recording is not fully complete."). A valid accelerating trace
produces Ready with its event's routePoints matching the exact analyzed sample range.

The conservative MVP coverage rule requires at least one uninterrupted second of usable
intervals, based on detector diagnostics, unless a qualifying event already exists. This is
an integration policy, not a change to detector thresholds. Issues retain input sample indexes.

## Completion and ordering

`TripRecordingCoordinator.endRecording()` still returns the existing command result.
That return means end was requested, not that disk writes have finished.

`RecordingWriteGate` closes sample acceptance, synchronously unregisters the attached GPS
tracker, then queues the completed-trip marker behind all accepted writes on the existing
single-thread executor. Late GPS callbacks and delayed tracker starts are rejected.
An insert/sample write failure prevents the completion marker. Detector reset is also queued
on the writer executor to avoid racing pending sample processing.

The production snapshot source queues its read on that same executor. Within a Room
transaction it verifies `endedAtMillis != null` and loads the trip's GPS. Thus requesting
analysis immediately after End Trip waits behind pending writes without blocking the UI.
If the source runs before completion was requested it returns Failed, rather than analyzing
a moving snapshot. Trips interrupted by process death remain incomplete; recovery is not
part of this integration. Existing completed trips can be reanalyzed.

`gpsPointsForTrip()` now uses `ORDER BY id ASC`: the stored acquisition/insertion sequence,
including duplicate or reversed wall-clock timestamps. Such timestamps remain available
to the detector as diagnostics instead of being silently reordered. This shared DAO query
also changes the ordering used by the existing Summary; no UI source was edited.

All production recording writes must use the gate and FIFO executor. The existing debug
scenario receiver executes on that executor; it is a test injector, not a new production writer.

## Map and event contract

Each event exposes its ID, type, start/end sample indexes and timestamps, duration, speeds,
signed peak/average acceleration, reviewPriorityScore, missing-accuracy flag and routePoints.
`routePoints` is the inclusive start/end slice of the exact snapshot used for detection.
Out-of-range event indexes or invalid event coordinates produce Failed, never a guessed marker.
Use these supplied points for event highlights rather than querying or sorting GPS again.

`rankedEvents` retains every local event in detector review order. The screen may use
`rankedEvents.take(1)` or `.take(3)` for initial review while preserving chronologicalEvents.
The experimental 0–100 score means review priority, not danger, crash risk or safety.

Room v1 lacks monotonic time and speed accuracy. They remain null; recorded nullable speed
and horizontal accuracy are preserved. There is no schema migration in this implementation.

## Recompute, versions and future backend/AI

MVP recomputes from the completed snapshot; results are not persisted. `analysisRevision`
is SHA-256 over the integration/default-detector version tag, trip ID and ordered stored rows.
Bump the version tag if the production detector configuration, scoring or mapping changes.
It is not an event ID, and event ID alone is not a content hash.

`mergeReviewEvents(ready, backendEvents)` is the future speed-event merge point. It validates
trip/revision, event type, indexes, route slice and unique event IDs, then returns all events
in sample order. The backend adapter must map server events onto the original snapshot.
There is deliberately no invented cross-type ranking or HTTP contract. Agree the upload
format, units, retry/idempotency behavior, event selection and API responses with the backend owner.

Future AI records should retain `(tripId, eventId, analysisRevision)`. Send selected structured
event facts to the AI endpoint; full-route upload for backend speed analysis is a separate concern.
Persist exact event versions before relying on long-lived AI answers across app/config updates.

## Verification

Run `./gradlew.bat :app:testDebugUnitTest --offline`.
New tests cover completion ordering, late callbacks, failed writes, trip isolation,
Loading/Ready, exact map ranges, incomplete trips, empty/unreliable/steady traces,
invalid indexes, deterministic revisions, missing accuracy, cancellation and backend merge checks.
The complete debug unit-test suite passed after this change. Device GPS/service lifecycle
and the future UI wiring still need on-device integration verification.
