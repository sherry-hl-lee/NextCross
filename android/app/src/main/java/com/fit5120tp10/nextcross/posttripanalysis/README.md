# Isolated experimental post-trip event analysis

This package is an opt-in, pure Kotlin algorithm prototype. It does **not** change the existing
`GpsAccelerationDetector`, recording service, UI, Room schema, backend, network requests, or Gradle
configuration. Its only optional legacy dependency is the read-only `GpsSpeedSample` adapter.

The 0–100 score is an **experimental event review priority**, not a validated crash-risk measure,
legal determination, safe-driving score, or proof of dangerous driving. Equal acceleration and
deceleration scores do not imply equal real-world safety significance. GPS-derived peaks are
sample-interval estimates, not instantaneous physical acceleration or pedal measurements.

## Files and responsibilities

| File | Responsibility |
| --- | --- |
| `EventDetectionConfig.kt` | All tunable detection, quality, merge, and scoring parameters; validation |
| `PostTripModels.kt` | Independent input, completed manoeuvre, score evidence, result and diagnostic models |
| `PostTripEventDetector.kt` | Boundaries and signed metrics only; fresh state for each completed-trip call |
| `EventSeverityScorer.kt` | Converts completed manoeuvres into scored events without altering boundaries |
| `EventReviewRanking.kt` | Non-mutating ranking and overall/per-type top helpers |
| `PostTripAnalyzer.kt` | Explicit detection → metrics → scoring → ranking pipeline |
| `LegacyGpsSampleAdapter.kt` | Optional copy adapter; preserves order and unknown fields |

Tests are in the matching `app/src/test/java/com/fit5120tp10/nextcross/posttripanalysis/` directory.

Existing files inspected read-only: `client/detection/DrivingEvent.kt`,
`client/detection/GpsAccelerationDetector.kt`, `client/recording/TripSampleProcessor.kt`,
`client/recording/storage/RecordingStorage.kt`, the project's Gradle/JUnit dependency declarations,
and existing JUnit examples. None is replaced or edited by this package.

## State machine and exact boundaries

State is local to `detect(tripId, samples)`: **idle → active(type) → pending end → completed**.
The detector never sorts input and never filters rows out before assigning indices. A start/end
sample index refers to the original input list. Pass a single, completed recording session per call.

1. **START:** a valid adjacent interval has `a >= rapidAccelerationThresholdMps2` or
   `a <= rapidDecelerationThresholdMps2`. The interval's **previous** point is the start anchor.
2. **CONTINUE:** while active, same-direction acceleration magnitude is at least the lower
   continuation threshold. This retains the weaker tail of a continuous manoeuvre.
3. **END:** a weak interval enters pending-end state. If the weak period exceeds the merge window,
   finish at the last confirmed active point, not at the later timeout point. A trailing pending
   period at trip end is likewise excluded. Completed events shorter than the minimum duration
   are not emitted.
4. **MERGE:** a new same-direction START interval may resume a pending event when the valid bridge
   **before that interval** is at most `mergeGapSeconds`. The bridge is then included in duration,
   net speed change, and missing-accuracy evidence; `mergedGapSeconds` makes this explicit. A mere
   continuation-level interval does not restart a pending event. Merge is rejected if the resulting
   net speed change would contradict the event's direction.
5. **SPLIT:** an opposite-direction interval reaching the continuation threshold ends the old event.
   If it also reaches the opposite START threshold, it begins a new event at the shared boundary
   sample. Smaller opposite fluctuations can be part of a valid short bridge, not a new event.
6. **Hard boundaries:** invalid speeds/known accuracy, incompatible time bases, invalid intervals,
   and long sample gaps cannot be merged, even with a large merge window. Invalid sample rows are
   discarded. Duplicate/reversed-time rows are discarded after ending the current event. A valid
   point following a long/too-short interval or time-base switch becomes a new baseline, with no
   acceleration attributed to the rejected interval.
7. **Trip end:** flush the active event at its last confirmed point. No state carries into another
   call, trip or device. An `endReason` records why processing closed the event.

Examples at 1-second intervals (speeds in m/s):

- `10, 14, 15` → one acceleration event, indices 0–2, duration 2 s, peak +4, average +2.5.
- `10, 14, 14.5, 18.5` → one merged event, indices 0–3, duration 3 s, bridge 1 s.
- `10, 14, 14.5, 15, 19` → two events; the 2-second weak gap exceeds the default window.
- `10, 14, 10, 6` → acceleration 0–1, then deceleration 1–3; peak deceleration stays negative.
- `10, 14, null, 18, 22` → two independent events at 0–1 and 3–4; no gap interpolation.

## Time and data quality

Use `elapsedRealtimeMillis` for intervals when available on **both** samples. Preserve wall-clock
`timestampMillis` for eventual display. Both missing means legacy timestamp fallback; mixed time
bases split the sequence. Never combine device boot sessions: monotonic clocks are only comparable
within one boot/session. With monotonic timestamps, duration remains correct through a wall-clock
change; stored wall-clock end time may be earlier than start time, so do not recompute duration
from those display timestamps.

Null/non-finite/negative speed is invalid. Known accuracy values must be finite, non-negative and
within their configured limits. Missing accuracy is allowed for compatibility with existing data,
but sets `hasMissingAccuracy`; it is **not** a claim that quality passed a measured confidence test.
These checks do not eliminate all GPS noise or validate driving safety. No smoothing, map matching,
road limit comparison, sensor fusion or truth/driver-identity verification is implemented here.

`issues` records rejected samples/intervals even when no events are returned. An empty event list
therefore cannot by itself establish that a trip was fully observed or safe.

## Default configuration (all experimental)

| Parameter | Default |
| --- | --- |
| Rapid acceleration / deceleration START | +3 / −3 m/s² |
| Same-direction continuation / significant reversal magnitude | 1 m/s² |
| Minimum interval / maximum sample gap | 0.5 / 2.5 s, inclusive |
| Merge window / minimum emitted event duration | 1 / 1 s |
| Maximum known horizontal / speed accuracy uncertainty | 50 m / 3 m/s |
| Acceleration / speed-change / duration normalization references | 6 m/s² / 10 m/s / 4 s |
| Acceleration / speed-change / duration weights | 0.50 / 0.35 / 0.15 |

Thresholds, references and timing limits must be finite with appropriate positive/negative signs.
Continuation cannot exceed either START magnitude. Weights must be finite, non-negative and sum
to 1 (floating tolerance `1e-9`). Weights may be zero. Merge window may be zero to disable merging.
Changes to scoring references/weights do not change detection results.

## Metrics and transparent scoring

`DetectedManoeuvre` retains IDs, type, original index/time boundaries, duration, before/after speeds,
absolute net speed change, signed peak/average acceleration, quality flags, bridge duration,
end reason and detector version. Average acceleration is the signed net speed change divided by
the accepted manoeuvre duration (including any explicitly merged valid bridge).

`DrivingEvent` wraps that completed manoeuvre and an `EventSeverityScore`; convenience properties
expose the requested event fields. The original metrics are not overwritten during scoring.

```text
A = clamp(abs(peakAccelerationMps2) / severeAccelerationReferenceMps2, 0, 1)
V = clamp(abs(speedAfterMps - speedBeforeMps) / severeSpeedChangeReferenceMps, 0, 1)
T = clamp(durationSeconds / longEventReferenceSeconds, 0, 1)
score = 100 × (accelerationWeight × A + speedChangeWeight × V + durationWeight × T)
```

Peak describes observed change intensity; net speed change describes the scale of the manoeuvre;
duration adds bounded context with the smallest default weight. Duration contributes at most 15
points under defaults, so a long weak event does not gain unlimited priority.

Evidence retains all three normalized components, each weighted point contribution, the exact
configuration snapshot, and scoring version. Defaults give the supplied comparison:

- A: peak −3.2 m/s², Δv 4 m/s, 1.5 s → **46.2916667**.
- B: peak −5.0 m/s², Δv 10 m/s, 3 s → **87.9166667**.

## Usage and ranking

```kotlin
val result = PostTripAnalyzer().analyze(
    tripId = recordingTripId,
    samples = listOf(
        PostTripGpsSample(timestampMillis = 0, speedMps = 15.0),
        PostTripGpsSample(timestampMillis = 1000, speedMps = 10.0),
        PostTripGpsSample(timestampMillis = 2000, speedMps = 5.0)
    )
)
val chronologicalEvents = result.events
val ranked = result.rankedEvents
val topThree = topEventsForReview(chronologicalEvents, limit = 3)
val acceleration = topRapidAcceleration(chronologicalEvents)
val deceleration = topRapidDeceleration(chronologicalEvents)
```

`rankEventsForReview` sorts by descending score, then original start index, then ID; exact ties
retain input order. It does not mutate input or delete events. Negative limits are rejected;
zero returns an empty list. Per-type helpers return null when that type is absent. Overall top-N
does not force diversity: a future UI can combine the per-type helpers without changing ranking.

IDs are deterministic for a fixed `tripId`, type and original index extent. Re-analysis of changed
data/config can preserve an ID while changing metrics, so a future persistence layer must retain
analysis revision/configuration and original input snapshot, not treat the ID as a content hash.

## Future integration points — not wired by this change

1. After recording is ended **and all samples are saved**, read a stable snapshot using the existing
   Room `gpsPointsForTrip(recordingTripId)` on a worker thread. Map into local `PostTripGpsSample`
   values, preserving nullable speed/accuracy and input order. Existing legacy samples can use
   `adaptLegacyGpsSamples`, which intentionally leaves unknown fields null.
2. Call `PostTripAnalyzer.analyze`; map indices back to the exact same snapshot for map highlights.
   Use the recording trip ID, not an unrelated planned-trip or notification ID.
3. To save results later, add an explicitly reviewed database migration for metrics, score evidence,
   configuration and revision. This prototype only returns in-memory results; it adds no storage.
4. UI wiring, parent sharing, AI calls and any new backend endpoints require separate approval and
   implementation. No production caller imports or invokes this package in this change.

## Tests

The existing JUnit 4 dependency is sufficient; no dependency/configuration edit is required.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.fit5120tp10.nextcross.posttripanalysis.*"
```

Coverage includes all requested scoring monotonicity/capping/configuration/sign/ranking checks,
the supplied A/B example, boundary independence from scoring, state-machine transitions and gaps,
invalid and missing evidence, original-index preservation, adapter behavior, deterministic repeated
analysis, trip isolation, and seeded generated-trip metric/boundary invariants.

Synthetic tests demonstrate implementation consistency, not field validation of the thresholds,
GPS accuracy, crash risk or safety outcomes. Real driving data needs a separate consented evaluation.

### Verification record — 2026-09-17

- Isolated package: **88 tests, 0 failures, 0 errors, 0 skipped** (7 test classes).
- Full `:app:testDebugUnitTest`: **131 tests, 0 failures, 0 errors, 0 skipped** (19 classes),
  comprising those 88 new tests and 43 existing tests.
- Used existing Android Studio JBR **25.0.2** at `D:\Android\jbr` and Gradle **9.1.0**, offline.
  The machine's default JDK was Java 8; the newer JDK was selected for the command only. No project
  or system Java configuration was edited.
- Regression tests cover integer-millisecond accumulation at fractional-second duration/merge
  boundaries, net-direction preservation across a large bridge, and invalid data while pending.
- Tracked-file diff and staged diff were empty. Only the new source package and corresponding
  test package were added on `feature/ai-driving-events`; no existing teammate file was modified.
- No emulator, real driving session, UI integration, database migration or live API call was tested
  or introduced. Results remain in-memory until an independently reviewed integration is added.

Reproduce with the existing JDK when the default Java is too old (from the Android project root):

```powershell
& 'D:\Android\jbr\bin\java.exe' '-Dorg.gradle.java.home=D:\Android\jbr' `
  -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain `
  --gradle-user-home C:\Users\nansong\.gradle :app:testDebugUnitTest --offline --no-daemon
```

JUnit XML reports: `app/build/test-results/testDebugUnitTest/`.
HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`.
