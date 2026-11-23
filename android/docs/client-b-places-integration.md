# Client B Places (New) integration handoff

This change provides the Places SDK and Client B APIs, but deliberately does not modify `App.kt`,
any Compose screen, `MainActivity`, Client A, Room, backend transport, Maps SDK, or Navigation SDK.
The UI owner can integrate the APIs after the current origin/destination fields are ready.

The app does not declare or use a Maps SDK map surface. The official Places 5.1.1 artifact does,
however, bring `play-services-maps` transitively because its `LOCATION` model is `LatLng`. If a
separately approved Navigation SDK integration happens later, follow Google's Navigation setup
guide and exclude that transitive Maps artifact; Navigation SDK bundles its own compatible map
implementation. Do not add a separate Maps SDK dependency or build a Maps map layer now.

## Build and local key setup

- Places SDK for Android is pinned to `com.google.android.libraries.places:places:5.1.1`.
- The Maps Platform Secrets Gradle Plugin is pinned to `2.0.1`.
- `local.defaults.properties` contains only `PLACES_API_KEY=DEFAULT_API_KEY`, so CI and developers
  without a key can still build. The placeholder makes `ClientBPlaces.get(...)` return
  `PlacesFailure.MissingApiKey`; it never sends a request.
- A developer who has an approved restricted key creates the ignored file `secrets.properties`:

  ```properties
  PLACES_API_KEY=the-approved-key
  ```

Never commit that file or the real key. The key is still recoverable from a built APK, so Android
application restrictions and API restrictions in Google Cloud are mandatory.

## Public integration API

Obtain the process-local facade once outside a Composable, preferably in a ViewModel or another
lifecycle owner:

```kotlin
when (val result = ClientBPlaces.get(context)) {
    is PlacesResult.Success -> result.value
    is PlacesResult.Failure -> // show configuration/service failure
}
```

For each origin or destination typing-to-selection flow:

1. Call `ClientBPlacesAutocomplete.newSession()` once when the user begins editing that field.
2. Call `session.updateQuery(text, callback)` only from a real user text-change event.
3. Display the returned `PlaceSuggestion` list. It contains at most three entries.
4. Call `session.select(suggestion, callback)` once for the chosen suggestion.
5. The success value is `SelectedPlace(displayName, placeId, point: GeographicPoint)`.
6. Selection automatically ends the session. Call `session.close()` if the user abandons the
   input, clears it, or switches fields before selection.

Do not create sessions or call `updateQuery` from the Composable body, screen-resume handling, or
an effect that can rerun without new user input. The Client B implementation suppresses duplicate
queries and repeated selection clicks, but the UI must still retain the facade/session in stable
lifecycle state. A future programmatic autocomplete UI must also display Google's required
attribution when it is not shown on a Google-branded map.

## Creating the permitted-trip configuration

Maintain an immutable `TripPlaceSelection`. When the text of a previously selected endpoint is
edited, immediately call `clear(endpoint)` before starting a fresh Places session. Add only a
successful `SelectedPlace` through `select(endpoint, place)`.

```kotlin
val result = selections.createConfiguration(
    tripId = tripId,
    parentContact = parentContact
)
```

`createConfiguration` returns `TripPlaceSelectionResult.NotReady(MissingOrigin)` or
`NotReady(MissingDestination)` until both selected places contain real coordinates. Only pass a
`Ready<PermittedTripConfiguration>` to `ClientBTripRuntime.saveReviewedTrip`, and only start the
runtime after that save succeeds. Do not substitute the current GPS position, Canvas coordinates,
mock coordinates, or fixed constants.

The calculation delegates to `PermittedAreaCalculator.calculate(origin, destination)`:

- centre: great-circle midpoint of the two selected `GeographicPoint` values;
- radius: Haversine distance from the centre to an endpoint, plus 3.0 km.

## Cost controls and expected request pattern

The implementation enforces:

- no autocomplete request below three trimmed Unicode characters;
- a 350 ms debounce;
- no second request for an unchanged query;
- cancellation/suppression of stale query results;
- at most three returned suggestions;
- one new `AutocompleteSessionToken` per `newSession()` flow;
- one selection request per session;
- exactly `DISPLAY_NAME`, `ID`, and `LOCATION` in Place Details (New);
- no automatic query from construction, recomposition, resume, or repeated selection.

For one endpoint, let `Q` be the number of times the user pauses for at least 350 ms after entering
three or more characters with a changed query. That endpoint uses `Q` Autocomplete (New) requests
and one Place Details (New) request after selection. Therefore a complete origin + destination flow
uses:

```text
Q_origin + Q_destination + 2 Place Details requests
```

The low-volume happy path, where the user types each endpoint continuously and pauses once, is
approximately four Places requests total: two autocomplete requests and two details requests. If
the user pauses twice per field, it is approximately six. Short input, the same query repeated by
UI state, and repeated selection clicks add zero requests. An abandoned session has its already
issued autocomplete requests but no terminating details request.

In debug builds, Logcat tag `ClientBPlacesUsage` reports counters without query, name, Place ID, or
coordinates. `ClientBPlacesAutocomplete.debugUsageSnapshot()` returns:

- sessions started/ended;
- Autocomplete (New) requests actually issued;
- Place Details (New) requests actually issued;
- below-minimum queries, duplicate queries, and duplicate selections suppressed.

Use these counters during a fixed test script and compare them with Google Cloud usage. Cancellation
is best effort and cannot guarantee that an already issued request is not billed.

## Google Cloud owner checklist

The project owner, not the Android code author, must:

1. Attach the intended billing account and enable **Places API (New)** only for this integration.
2. Create a dedicated Android API key restricted to package
   `com.fit5120tp10.nextcross` and every approved debug/release signing SHA-1 fingerprint.
3. Add an API restriction allowing **Places API (New)**; do not leave the key unrestricted.
4. In Google Maps Platform > Quotas, set the lowest approved limits for both autocomplete and
   place-details methods. Derive the limits from the planned number of demo flows and the request
   formula above, add only small operational headroom, and set quota-usage alerts below the cap.
5. Create a project/service-scoped Cloud Billing budget equal to the approved demo allowance, with
   notifications at conservative thresholds such as 25%, 50%, 75%, 90%, and 100%.
6. Route quota and budget notifications to an actively monitored owner channel and review the
   Billing dashboard after every test session (billing data can lag).
7. Disable the key or reduce quotas to zero when the demo/testing window is over.

Quota limits are the main hard usage control. Budget alerts only notify and do not automatically
stop requests or charges. The owner must verify the current SKU/free-usage terms before approving a
limit. Requesting `DISPLAY_NAME` can affect the Place Details billing tier, so do not assume that
the request is free merely because only three fields are requested.

Official references:

- https://developers.google.com/maps/documentation/places/android-sdk/place-autocomplete
- https://developers.google.com/maps/documentation/places/android-sdk/session-pricing
- https://developers.google.com/maps/documentation/places/android-sdk/details-place
- https://developers.google.com/maps/documentation/places/android-sdk/config
- https://developers.google.com/maps/billing-and-pricing/manage-costs
- https://developers.google.com/maps/documentation/navigation/android-sdk/android-studio-setup
