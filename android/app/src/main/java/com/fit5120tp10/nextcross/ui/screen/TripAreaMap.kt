package com.fit5120tp10.nextcross.ui.screen

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace
import com.fit5120tp10.nextcross.client.posttrip.ReviewEventType
import com.fit5120tp10.nextcross.client.routing.PlannedRoute
import com.fit5120tp10.nextcross.client.routing.PlannedRouteFailure
import com.fit5120tp10.nextcross.client.routing.PlannedRouteLocator
import com.fit5120tp10.nextcross.client.routing.PlannedRouteRequest
import com.fit5120tp10.nextcross.client.routing.PlannedRouteResult
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI

/** Shared real-map base used by Trip Setup and Pre-trip. */
data class TripMapCamera(
    val latitude: Double,
    val longitude: Double,
    val zoom: Float
)

/** A recorded location used to draw a completed trip on a Google map. */
internal data class TripRoutePoint(
    val latitude: Double,
    val longitude: Double,
    val recordedAtMillis: Long
)

/** Event geometry is typed so each family can use its own map treatment. */
internal data class TripRouteEventOverlay(
    val type: ReviewEventType,
    val points: List<GeographicPoint>
)

internal sealed interface PlannedRouteUiState {
    data object Unavailable : PlannedRouteUiState
    data object Loading : PlannedRouteUiState
    data class Available(val route: PlannedRoute) : PlannedRouteUiState
    data class Failed(val result: PlannedRouteResult.Failed) : PlannedRouteUiState
}

@Composable
internal fun rememberPlannedRoute(
    originPlace: SelectedPlace?,
    destinationPlace: SelectedPlace?,
    enabled: Boolean = true
): PlannedRouteUiState {
    val context = LocalContext.current
    var state by remember(originPlace, destinationPlace) {
        mutableStateOf<PlannedRouteUiState>(PlannedRouteUiState.Unavailable)
    }

    LaunchedEffect(originPlace, destinationPlace, enabled) {
        if (!enabled || originPlace == null || destinationPlace == null) {
            state = PlannedRouteUiState.Unavailable
            return@LaunchedEffect
        }
        state = PlannedRouteUiState.Loading
        state = withContext(Dispatchers.IO) {
            when (
                val result = PlannedRouteLocator.get(context).plan(
                    PlannedRouteRequest(originPlace, destinationPlace)
                )
            ) {
                is PlannedRouteResult.Success -> PlannedRouteUiState.Available(result.route)
                is PlannedRouteResult.Failed -> PlannedRouteUiState.Failed(result)
            }
        }
    }

    return state
}

@Composable
internal fun PlannedTripAreaMap(
    fragmentActivity: FragmentActivity?,
    fragmentTag: String,
    areaCentre: GeographicPoint?,
    radiusMeters: Double?,
    originPlace: SelectedPlace?,
    destinationPlace: SelectedPlace?,
    modifier: Modifier = Modifier,
    contentKey: Any? = Unit,
    initialCamera: TripMapCamera? = null,
    onCameraChanged: (TripMapCamera) -> Unit = {},
    renderOverlay: (GoogleMap) -> Unit = {}
) {
    val plannedRouteState = rememberPlannedRoute(originPlace, destinationPlace)
    val density = LocalContext.current.resources.displayMetrics.density
    Box(modifier = modifier) {
        TripAreaMap(
            fragmentActivity = fragmentActivity,
            fragmentTag = fragmentTag,
            areaCentre = areaCentre,
            radiusMeters = radiusMeters,
            originPlace = originPlace,
            destinationPlace = destinationPlace,
            modifier = Modifier.fillMaxSize(),
            contentKey = Pair(contentKey, plannedRouteState),
            initialCamera = initialCamera,
            onCameraChanged = onCameraChanged,
            renderOverlay = { map ->
                (plannedRouteState as? PlannedRouteUiState.Available)?.route?.let { route ->
                    map.renderPlannedRoute(route.points, density)
                }
                renderOverlay(map)
            }
        )
        (plannedRouteState as? PlannedRouteUiState.Failed)?.result?.let { failure ->
            Text(
                text = failure.routePreviewMessage(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .background(Color.White.copy(alpha = 0.9f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = Color(0xFF9C1B1B)
            )
        }
    }
}

private fun PlannedRouteResult.Failed.routePreviewMessage(): String = when (val reason = reason) {
    PlannedRouteFailure.InvalidEndpoints -> "Route preview unavailable: same locations"
    PlannedRouteFailure.MissingApiKey -> "Route preview unavailable: missing API key"
    PlannedRouteFailure.NoRouteFound -> "Route preview unavailable: no route found"
    is PlannedRouteFailure.ApiFailure -> "Route preview unavailable: ${reason.status}"
    is PlannedRouteFailure.TransportFailure -> "Route preview unavailable: network error"
}

@Composable
internal fun TripRouteMap(
    fragmentActivity: FragmentActivity?,
    fragmentTag: String,
    routePoints: List<TripRoutePoint>,
    eventTimes: List<Long>,
    originPlace: SelectedPlace?,
    destinationPlace: SelectedPlace?,
    plannedRoutePoints: List<GeographicPoint> = emptyList(),
    eventRouteOverlays: List<TripRouteEventOverlay> = emptyList(),
    modifier: Modifier = Modifier,
    contentKey: Any? = Unit,
    onMapReady: (GoogleMap?) -> Unit = {}
) {
    if (fragmentActivity == null) return

    val density = LocalContext.current.resources.displayMetrics.density
    val containerId = remember { View.generateViewId() }
    val latestPoints by rememberUpdatedState(routePoints)
    val latestEventTimes by rememberUpdatedState(eventTimes)
    val latestOrigin by rememberUpdatedState(originPlace)
    val latestDestination by rememberUpdatedState(destinationPlace)
    val latestPlannedRoutePoints by rememberUpdatedState(plannedRoutePoints)
    val latestEventRouteOverlays by rememberUpdatedState(eventRouteOverlays)
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var cameraFitted by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { context -> FragmentContainerView(context).apply { id = containerId } }
    )

    DisposableEffect(fragmentActivity, containerId, fragmentTag) {
        val fragmentManager = fragmentActivity.supportFragmentManager
        val mapFragment = (fragmentManager.findFragmentByTag(fragmentTag) as? SupportMapFragment)
            ?: SupportMapFragment.newInstance().also { fragment ->
                if (!fragmentManager.isStateSaved) {
                    fragmentManager.beginTransaction()
                        .replace(containerId, fragment, fragmentTag)
                        .commitNow()
                }
            }
        mapFragment.getMapAsync { map ->
            googleMap = map
            onMapReady(map)
            map.renderTripRoute(
                routePoints = latestPoints,
                eventTimes = latestEventTimes,
                originPlace = latestOrigin,
                destinationPlace = latestDestination,
                plannedRoutePoints = latestPlannedRoutePoints,
                eventRouteOverlays = latestEventRouteOverlays,
                density = density,
                moveCamera = !cameraFitted
            )
            cameraFitted = latestPoints.size >= 2
        }

        onDispose {
            onMapReady(null)
            if (!fragmentManager.isStateSaved) {
                fragmentManager.findFragmentByTag(fragmentTag)?.let { fragment ->
                    fragmentManager.beginTransaction().remove(fragment).commitNow()
                }
            }
        }
    }

    LaunchedEffect(googleMap, routePoints, eventTimes, originPlace, destinationPlace, plannedRoutePoints, eventRouteOverlays, density, contentKey) {
        googleMap?.let { map ->
            map.renderTripRoute(
                routePoints = routePoints,
                eventTimes = eventTimes,
                originPlace = originPlace,
                destinationPlace = destinationPlace,
                plannedRoutePoints = plannedRoutePoints,
                eventRouteOverlays = eventRouteOverlays,
                density = density,
                moveCamera = !cameraFitted
            )
            cameraFitted = routePoints.size >= 2
        }
    }
}

@Composable
internal fun TripAreaMap(
    fragmentActivity: FragmentActivity?,
    fragmentTag: String,
    areaCentre: GeographicPoint?,
    radiusMeters: Double?,
    originPlace: SelectedPlace?,
    destinationPlace: SelectedPlace?,
    modifier: Modifier = Modifier,
    contentKey: Any? = Unit,
    initialCamera: TripMapCamera? = null,
    onCameraChanged: (TripMapCamera) -> Unit = {},
    renderOverlay: (GoogleMap) -> Unit = {}
) {
    if (fragmentActivity == null) return

    val density = LocalContext.current.resources.displayMetrics.density
    val containerId = remember { View.generateViewId() }
    val latestCentre by rememberUpdatedState(areaCentre)
    val latestRadius by rememberUpdatedState(radiusMeters)
    val latestOrigin by rememberUpdatedState(originPlace)
    val latestDestination by rememberUpdatedState(destinationPlace)
    val latestOverlay by rememberUpdatedState(renderOverlay)
    val latestInitialCamera by rememberUpdatedState(initialCamera)
    val latestCameraChangedHandler by rememberUpdatedState(onCameraChanged)
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var fittedViewport by remember { mutableStateOf<TripAreaViewport?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context -> FragmentContainerView(context).apply { id = containerId } }
    )

    DisposableEffect(fragmentActivity, containerId, fragmentTag) {
        val fragmentManager = fragmentActivity.supportFragmentManager
        val mapFragment = (fragmentManager.findFragmentByTag(fragmentTag) as? SupportMapFragment)
            ?: SupportMapFragment.newInstance().also { fragment ->
                if (!fragmentManager.isStateSaved) {
                    fragmentManager.beginTransaction()
                        .replace(containerId, fragment, fragmentTag)
                        .commitNow()
                }
            }
        mapFragment.getMapAsync { map ->
            googleMap = map
            map.setOnCameraIdleListener {
                map.cameraPosition.toTripMapCamera()?.let(latestCameraChangedHandler)
            }
            val initialCamera = latestInitialCamera
            val viewport = latestCentre?.let { centre ->
                latestRadius?.let { radius ->
                    TripAreaViewport(centre, radius, latestOrigin, latestDestination)
                }
            }
            val shouldMoveCamera = initialCamera == null && viewport != null && viewport != fittedViewport
            map.renderTripArea(
                areaCentre = latestCentre,
                radiusMeters = latestRadius,
                originPlace = latestOrigin,
                destinationPlace = latestDestination,
                density = density,
                moveCamera = shouldMoveCamera
            )
            when {
                initialCamera != null -> {
                    map.moveCamera(initialCamera.toCameraUpdate())
                    fittedViewport = viewport
                }
                shouldMoveCamera -> fittedViewport = viewport
            }
            latestOverlay(map)
        }

        onDispose {
            if (!fragmentManager.isStateSaved) {
                fragmentManager.findFragmentByTag(fragmentTag)?.let { fragment ->
                    fragmentManager.beginTransaction().remove(fragment).commitNow()
                }
            }
        }
    }

    LaunchedEffect(
        googleMap,
        areaCentre,
        radiusMeters,
        originPlace,
        destinationPlace,
        density,
        contentKey,
        initialCamera
    ) {
        googleMap?.let { map ->
            val viewport = areaCentre?.let { centre ->
                radiusMeters?.let { radius ->
                    TripAreaViewport(centre, radius, originPlace, destinationPlace)
                }
            }
            val shouldMoveCamera = initialCamera == null && viewport != null && viewport != fittedViewport
            map.renderTripArea(
                areaCentre,
                radiusMeters,
                originPlace,
                destinationPlace,
                density,
                moveCamera = shouldMoveCamera
            )
            when {
                initialCamera != null && fittedViewport == null -> {
                    map.moveCamera(initialCamera.toCameraUpdate())
                    fittedViewport = viewport
                }
                shouldMoveCamera -> fittedViewport = viewport
            }
            renderOverlay(map)
        }
    }
}

private data class TripAreaViewport(
    val centre: GeographicPoint,
    val radiusMeters: Double,
    val origin: SelectedPlace?,
    val destination: SelectedPlace?
)

private fun CameraPosition.toTripMapCamera(): TripMapCamera? {
    val target = target ?: return null
    return TripMapCamera(target.latitude, target.longitude, zoom)
}

private fun TripMapCamera.toCameraUpdate() = CameraUpdateFactory.newCameraPosition(
    CameraPosition.Builder()
        .target(LatLng(latitude, longitude))
        .zoom(zoom)
        .build()
)

internal fun GoogleMap.renderTripArea(
    areaCentre: GeographicPoint?,
    radiusMeters: Double?,
    originPlace: SelectedPlace?,
    destinationPlace: SelectedPlace?,
    density: Float,
    moveCamera: Boolean
) {
    clear()
    val centre = areaCentre ?: return
    val safeRadius = radiusMeters?.takeIf { it > 0.0 } ?: return
    val latLngCentre = LatLng(centre.latitude, centre.longitude)

    addCircle(
        CircleOptions()
            .center(latLngCentre)
            .radius(safeRadius)
            .fillColor(android.graphics.Color.argb(70, 56, 161, 105))
            .strokeColor(android.graphics.Color.rgb(56, 161, 105))
            .strokeWidth(2f * density)
    )
    originPlace?.let { place ->
        addMarker(
            MarkerOptions()
                .position(LatLng(place.point.latitude, place.point.longitude))
                .title("Start: ${place.displayName}")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
    }
    destinationPlace?.let { place ->
        addMarker(
            MarkerOptions()
                .position(LatLng(place.point.latitude, place.point.longitude))
                .title("Destination: ${place.displayName}")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )
    }
    if (moveCamera) {
        fitTripAreaViewport(
            centre = latLngCentre,
            radiusMeters = safeRadius,
            origin = originPlace?.let { LatLng(it.point.latitude, it.point.longitude) },
            destination = destinationPlace?.let { LatLng(it.point.latitude, it.point.longitude) },
            density = density
        )
    }
}

private fun GoogleMap.fitTripAreaViewport(
    centre: LatLng,
    radiusMeters: Double,
    origin: LatLng?,
    destination: LatLng?,
    density: Float
) {
    val latitudeOffset = radiusMeters / 111_320.0
    val longitudeOffset = radiusMeters /
        (111_320.0 * cos(centre.latitude * PI / 180.0)).coerceAtLeast(0.01)
    val bounds = LatLngBounds.Builder()
        .include(LatLng(centre.latitude + latitudeOffset, centre.longitude))
        .include(LatLng(centre.latitude - latitudeOffset, centre.longitude))
        .include(LatLng(centre.latitude, centre.longitude + longitudeOffset))
        .include(LatLng(centre.latitude, centre.longitude - longitudeOffset))

    origin?.let(bounds::include)
    destination?.let(bounds::include)
    moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), (24f * density).toInt()))
}

private fun GoogleMap.renderTripRoute(
    routePoints: List<TripRoutePoint>,
    eventTimes: List<Long>,
    originPlace: SelectedPlace?,
    destinationPlace: SelectedPlace?,
    plannedRoutePoints: List<GeographicPoint>,
    eventRouteOverlays: List<TripRouteEventOverlay>,
    density: Float,
    moveCamera: Boolean
) {
    clear()
    val recordedPositions = routePoints.map { LatLng(it.latitude, it.longitude) }
    val plannedPositions = plannedRoutePoints.map { LatLng(it.latitude, it.longitude) }
    val positions = if (recordedPositions.size >= 2) recordedPositions else plannedPositions
    if (recordedPositions.size >= 2) {
        addPolyline(
            PolylineOptions()
                .addAll(recordedPositions)
                .color(android.graphics.Color.rgb(56, 161, 105))
                .width(5f * density)
        )
        if (eventRouteOverlays.isNotEmpty()) {
            eventRouteOverlays
                .filter { it.type == ReviewEventType.SPEED_LIMIT_EXCEEDANCE }
                .forEach { overlay ->
                    if (overlay.points.size >= 2) {
                    addPolyline(
                        PolylineOptions()
                            .addAll(overlay.points.map { LatLng(it.latitude, it.longitude) })
                            .color(android.graphics.Color.rgb(200, 68, 75))
                            .width(6f * density)
                    )
                }
            }
            eventRouteOverlays
                .filter { it.type != ReviewEventType.SPEED_LIMIT_EXCEEDANCE }
                .forEach { overlay ->
                    val point = overlay.points[overlay.points.size / 2]
                    addMarker(
                        MarkerOptions()
                            .position(LatLng(point.latitude, point.longitude))
                            .title(
                                if (overlay.type == ReviewEventType.RAPID_DECELERATION) {
                                    "Hard braking"
                                } else {
                                    "Hard acceleration"
                                }
                            )
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                    )
                }
        } else {
            eventTimes.forEach { eventTime ->
                val index = routePoints.indices.minByOrNull { pointIndex ->
                    abs(routePoints[pointIndex].recordedAtMillis - eventTime)
                } ?: return@forEach
                val fromIndex = (index - 1).coerceAtLeast(0)
                val toIndex = (index + 1).coerceAtMost(positions.lastIndex)
                if (fromIndex >= toIndex) return@forEach
                addPolyline(
                    PolylineOptions()
                        .add(positions[fromIndex], positions[toIndex])
                        .color(android.graphics.Color.rgb(200, 68, 75))
                        .width(6f * density)
                )
            }
        }
    } else if (plannedPositions.size >= 2) {
        renderPlannedRoute(plannedRoutePoints, density)
    }
    val startPosition = originPlace?.let { LatLng(it.point.latitude, it.point.longitude) }
        ?: positions.firstOrNull()
    val endPosition = destinationPlace?.let { LatLng(it.point.latitude, it.point.longitude) }
        ?: positions.lastOrNull()
    startPosition?.let { position ->
        addMarker(
            MarkerOptions()
                .position(position)
                .title("Trip start")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
    }
    endPosition?.takeIf { it != startPosition }?.let { position ->
        addMarker(
            MarkerOptions()
                .position(position)
                .title("Trip end")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )
    }
    if (moveCamera) {
        val cameraPositions = positions + listOfNotNull(startPosition, endPosition)
        if (cameraPositions.size == 1) {
            moveCamera(CameraUpdateFactory.newLatLngZoom(cameraPositions.first(), 15f))
        } else if (cameraPositions.size > 1) {
            val bounds = LatLngBounds.Builder().also { builder ->
                cameraPositions.forEach(builder::include)
            }.build()
            moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, (36f * density).toInt()))
        }
    }
}

private fun GoogleMap.renderPlannedRoute(
    points: List<GeographicPoint>,
    density: Float
) {
    if (points.size < 2) return
    addPolyline(
        PolylineOptions()
            .addAll(points.map { LatLng(it.latitude, it.longitude) })
            .color(android.graphics.Color.rgb(23, 105, 224))
            .width(5f * density)
    )
}
