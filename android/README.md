# NextCross Android

NextCross is an Android road-safety prototype for supported driving trips. A driver sets a start and destination, records a trip with location permission, reviews recorded driving events, and can share a trip report with a parent contact.

The app is built with Kotlin and Jetpack Compose. It stores trip data on-device with Room and uses Google Maps Platform services for place selection, planned-route display, and navigation views.

> This is a prototype, not a safety-critical navigation or enforcement system. A displayed driving event is recorded data for post-trip discussion; it does not establish unsafe driving, fault, or the cause of an event.

## What is implemented

- Trip setup with start and destination place search and a selected parent email, followed by informed consent during trip review.
- A circular permitted trip area derived from the selected origin and destination; the active-trip runtime monitors potential area breaches.
- Foreground GPS recording while a trip is active, including recorded position, speed when supplied by Android, and location accuracy.
- Active-trip navigation UI with speed-limit lookup and warning state when a usable limit is available.
- Post-trip analysis for hard acceleration and hard braking from recorded GPS/speed samples.
- Optional backend speed analysis, merged with local post-trip events when available.
- Historical crash context on the pre-trip map, with loading, unavailable, and no-configuration states.
- Post-trip summary and report screens with the recorded route, event details, and optional route-map attachment.
- Per-event, two-turn AI reflection. The driver must start the conversation and provide a response before a reflection is requested; completed reflections are stored locally with the report.
- Local report history and report sharing. Newly created reports retain their parent email and expire after 10 calendar days.

## Important behaviour and limits

- A recorded route is a polyline through saved GPS samples; it is **not** road-snapped navigation geometry. Sparse or inaccurate samples can make the line look imprecise.
- Location, speed-limit, historical-crash, AI-reflection, and report-email behaviour depends on device permissions, Google configuration, network availability, and the configured backend endpoints.
- Email delivery is an explicit user action. A successful client request does not replace checking the receiving inbox.
- The app includes no AI provider key. It sends the selected event facts and the driver's typed response to the configured reflection backend only after the driver starts a reflection.
- Development emulators often provide static or sparse locations. A short simulated trip may legitimately produce insufficient route samples or no detected events.

## Requirements

- Android Studio with its supported bundled JDK.
- Android SDK 36 installed.
- A device or emulator running Android API 24 or later.
- A Google Maps Platform key with the APIs required by the team configuration (at minimum Maps/Navigation and Places where those screens are used).
- Network access for live backend integrations.

## Configure local secrets

The repository contains safe fallback values in `local.defaults.properties`. Do not put real keys in source control.

1. Create an untracked file named `secrets.properties` in the repository root.
2. Add your restricted keys:

```properties
PLACES_API_KEY=your_restricted_google_key
MAPS_API_KEY=your_restricted_google_key
```

`MAPS_API_KEY` is supplied to the application manifest for the Navigation SDK / Maps Platform. `PLACES_API_KEY` is used by place search and can be the same restricted key when your Google Cloud restrictions permit it.

The live backend base URLs are Android string resources under `app/src/main/res/values/`:

- `historical_crash_config.xml`
- `speed_limit_config.xml`
- `ai_reflection_config.xml`
- `prototype_backend_config.xml`

Use project-approved endpoints only. Do not place credentials in those XML files.

## Build and run

1. Open this repository in Android Studio.
2. Sync Gradle and select the `app` run configuration.
3. Add `secrets.properties` as described above.
4. Run on an emulator or Android device.
5. Grant precise location permission when requested.

From a terminal on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat testDebugUnitTest
```

For meaningful recording verification, use a device or emulator route that supplies multiple time-ordered GPS locations with speed and accuracy. End the trip before opening the summary.

## User flow

```text
Splash → Home → Trip setup → Pre-trip map → Review trip details
      → Active trip recording → Trip summary → Trip report / Share
                                           ↘ Report history
```

The navigation routes are defined in `ui/navigation/NextCrossDestination.kt`; the Compose navigation graph is in `ui/navigation/NextCrossNavGraph.kt`.

## Architecture

| Area | Responsibility |
| --- | --- |
| `ui/screen` | Compose screens and reusable visual components. |
| `ui/navigation` | Application routes, navigation flow, and shared UI state. |
| `client/recording` | Foreground location recording, write coordination, and local GPS/event storage. |
| `client/permittedarea` | Place selection, allowed-area configuration, breach monitoring, and notification transport adapter. |
| `client/posttrip` | Loads completed-trip samples, performs local analysis, and merges optional speed-analysis results. |
| `posttripanalysis` | Pure detection, metrics, scoring, and ranking logic with unit tests. |
| `client/aireflection` | Explicit two-turn reflection client and backend request/response mapping. |
| `client/tripreport` | Local report lifecycle, 10-day retention, text payload construction, email transport, and attachments. |
| `client/recording/storage` | Room entities and migrations for trips, GPS points, driving events, reports, and saved AI reflections. |

### Local data lifecycle

1. The foreground service writes GPS samples and locally detected manoeuvre events to Room for the active recording trip.
2. When the trip ends, a compact `TripReport` is saved with report metadata and the parent email selected for that trip.
3. Post-trip analysis reads the same trip's saved GPS sample order to render a route and calculate reviewable events.
4. Each completed AI reflection is saved by trip ID and event ID, so completing a new reflection does not replace an earlier one.
5. Expired reports and their associated local trip/GPS/event/reflection data are purged after the retention window.

## Testing

Unit tests cover the main deterministic client logic, including:

- post-trip event detection, boundaries, scoring, and ranking;
- permitted-area calculations and breach monitoring;
- recording write sequencing;
- planned-route parsing and request contracts;
- speed-analysis mapping and response handling;
- AI reflection event-fact mapping and backend result mapping;
- report retention, share payloads, and email transport validation.

Run all local unit tests with:

```powershell
.\gradlew.bat testDebugUnitTest
```

Manual checks are still required for permissions, real GPS acquisition, Google Maps / Places rendering, backend availability, foreground-service behaviour, and email delivery.

## Privacy notes

- Precise location is required to record a trip and draw its recorded route.
- Selecting detailed-route sharing can attach a map snapshot containing location information.
- AI reflection is opt-in per event; no reflection request is sent merely by opening a summary or switching event tabs.
- The app stores newly created reports locally for the documented retention period and associates them with the parent email selected for that trip. Legacy reports created before email persistence may not have a saved email.

## Repository conventions

- Keep real Google keys in untracked `secrets.properties` only.
- Keep changes scoped; UI code should call existing client contracts rather than duplicating recording or analysis logic.
- Preserve the distinction between unit-test coverage, build success, and manual device verification in pull requests.
