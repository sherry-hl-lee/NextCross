package com.fit5120tp10.nextcross.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.fit5120tp10.nextcross.client.historicalcrash.CrashTimePeriod
import com.fit5120tp10.nextcross.client.historicalcrash.CrashCluster
import com.fit5120tp10.nextcross.client.historicalcrash.CrashIndividualPoint
import com.fit5120tp10.nextcross.client.historicalcrash.HistoricalCrashContext
import com.fit5120tp10.nextcross.client.historicalcrash.HistoricalCrashContextLocator
import com.fit5120tp10.nextcross.client.historicalcrash.HistoricalCrashContextRequest
import com.fit5120tp10.nextcross.client.historicalcrash.HistoricalCrashContextResult
import com.fit5120tp10.nextcross.client.permittedarea.PermittedArea as PermittedTripArea
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace
import com.fit5120tp10.nextcross.client.permittedarea.runtime.ClientBTripRuntimeLocator
import com.fit5120tp10.nextcross.ui.theme.NextCrossTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlin.math.cos
import kotlin.math.ln

private val PreTripBackground: Color @Composable get() = MaterialTheme.colorScheme.background
private val PreTripNavy = Color(0xFF071A3A)
private val PreTripText: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val PreTripBlue = Color(0xFF1769E0)
private val MapBackground = Color(0xFFEAF3EC)
private val MapRoad = Color(0xFFF8FCF8)
private val MapRoadEdge = Color(0xFFD5E4D9)
private val MapMinorRoad = Color(0xFFDDEBE0)
private val PermittedArea = Color(0x4538A169)
private val PermittedAreaEdge = Color(0x8838A169)
private const val PreTripMapFragmentTag = "pre_trip_map"

enum class CrashCountFilter(val label: String) {
    EightOrMore("8+ crashes"),
    FiveToSeven("5–7 crashes"),
    TwoToFour("2–4 crashes"),
    Individual("Individual crash")
}

internal val DefaultCrashCountFilters = setOf(
    CrashCountFilter.EightOrMore,
    CrashCountFilter.FiveToSeven
)

enum class LocationPermissionUiState {
    Required,
    TryAgain,
    OpenSettings
}

@Composable
fun PreTripMapScreen(
    startingPoint: String,
    destination: String,
    originPlace: SelectedPlace? = null,
    destinationPlace: SelectedPlace? = null,
    permittedArea: PermittedTripArea? = null,
    initialCamera: TripMapCamera? = null,
    fragmentActivity: FragmentActivity? = null,
    plannedTripId: String = "",
    onBackClick: () -> Unit = {},
    permissionUiState: LocationPermissionUiState = LocationPermissionUiState.Required,
    isFineLocationGranted: Boolean = false,
    startTripError: String? = null,
    initialTimeOfDay: CrashTimePeriod = CrashTimePeriod.Morning,
    initialCrashCountFilters: Set<CrashCountFilter> = DefaultCrashCountFilters,
    onHistoricalFiltersChanged: (CrashTimePeriod, Set<CrashCountFilter>) -> Unit = { _, _ -> },
    onStartTrip: () -> Boolean = { false },
    onRequestLocationPermission: () -> Unit = {}
) {
    val context = LocalContext.current
    var showLocationPermissionSheet by remember { mutableStateOf(false) }
    var selectedTimeOfDay by remember(plannedTripId) { mutableStateOf(initialTimeOfDay) }
    var showTimeOfDayMenu by remember { mutableStateOf(false) }
    var historicalContext by remember { mutableStateOf<HistoricalCrashContext?>(null) }
    var selectedCrash by remember { mutableStateOf<HistoricalCrashSelection?>(null) }
    var mapPermittedArea by remember(plannedTripId, permittedArea) { mutableStateOf(permittedArea) }
    var isHistoricalContextLoading by remember { mutableStateOf(false) }
    var historicalContextError by remember { mutableStateOf<String?>(null) }
    var showHistoricalLoadingNotice by remember { mutableStateOf(false) }
    var selectedCrashCountFilters by remember(plannedTripId) { mutableStateOf(initialCrashCountFilters) }
    var showCrashCountFilterMenu by remember { mutableStateOf(false) }

    fun updateCrashCountFilters(filters: Set<CrashCountFilter>) {
        selectedCrashCountFilters = filters
        onHistoricalFiltersChanged(selectedTimeOfDay, filters)
    }

    LaunchedEffect(isFineLocationGranted) {
        if (isFineLocationGranted) {
            showLocationPermissionSheet = false
        }
    }

    LaunchedEffect(isHistoricalContextLoading) {
        if (isHistoricalContextLoading) {
            delay(250)
            showHistoricalLoadingNotice = true
        } else {
            showHistoricalLoadingNotice = false
        }
    }

    LaunchedEffect(historicalContextError) {
        val error = historicalContextError ?: return@LaunchedEffect
        delay(4_000)
        if (historicalContextError == error) {
            historicalContextError = null
        }
    }

    LaunchedEffect(plannedTripId, selectedTimeOfDay, permittedArea) {
        selectedCrash = null
        historicalContext = null
        historicalContextError = null
        isHistoricalContextLoading = true

        val reviewedTrip = ClientBTripRuntimeLocator.get(context).getReviewedTrip(plannedTripId)
        val selectedPermittedArea = reviewedTrip?.permittedArea ?: permittedArea
        mapPermittedArea = selectedPermittedArea
        if (selectedPermittedArea == null) {
            isHistoricalContextLoading = false
            historicalContextError = "Historical crash data is not available for this trip."
            return@LaunchedEffect
        }

        val result = runCatching {
            withContext(Dispatchers.IO) {
                HistoricalCrashContextLocator.get(context).lookup(
                    HistoricalCrashContextRequest.fromPermittedArea(
                        permittedArea = selectedPermittedArea,
                        timePeriod = selectedTimeOfDay
                    )
                )
            }
        }.getOrNull()

        when (result) {
            is HistoricalCrashContextResult.Success -> historicalContext = result.value
            else -> historicalContextError = "Historical crash data is unavailable right now."
        }
        isHistoricalContextLoading = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PreTripBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (fragmentActivity != null) {
                PlannedTripAreaMap(
                    fragmentActivity = fragmentActivity,
                    fragmentTag = PreTripMapFragmentTag,
                    areaCentre = historicalContext?.areaCentre ?: mapPermittedArea?.centre,
                    radiusMeters = historicalContext?.radiusMeters
                        ?: mapPermittedArea?.radiusKilometres?.times(1_000.0),
                    originPlace = originPlace,
                    destinationPlace = destinationPlace,
                    modifier = Modifier.fillMaxSize(),
                    contentKey = Triple(historicalContext, selectedCrashCountFilters, selectedCrash),
                    initialCamera = initialCamera,
                    renderOverlay = { map ->
                        map.renderHistoricalCrashOverlay(
                            context = historicalContext?.takeIf { it.available },
                            density = context.resources.displayMetrics.density,
                            visibleFilters = selectedCrashCountFilters,
                            selectedCrash = selectedCrash,
                            onCrashSelected = { selectedCrash = it }
                        )
                    }
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MapBackground
                ) {}
            }

            Surface(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 40.dp)
                    .size(44.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.78f),
                shadowElevation = 4.dp
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.fillMaxSize()
                ) {
                    BackNavigationIcon(Color.Black)
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { showCrashCountFilterMenu = true }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CrashFilterIcon()
                        Text(
                            text = "Hotspots (${selectedCrashCountFilters.size}) ▾",
                            color = PreTripText,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                DropdownMenu(
                    expanded = showCrashCountFilterMenu,
                    onDismissRequest = { showCrashCountFilterMenu = false },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = "Show crash locations",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = PreTripText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    val allFiltersSelected = selectedCrashCountFilters.size == CrashCountFilter.entries.size
                    val allFiltersState = when {
                        selectedCrashCountFilters.isEmpty() -> ToggleableState.Off
                        allFiltersSelected -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
                    val toggleAllFilters = {
                        updateCrashCountFilters(if (allFiltersSelected) {
                            emptySet()
                        } else {
                            CrashCountFilter.entries.toSet()
                        })
                    }
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TriStateCheckbox(
                                    state = allFiltersState,
                                    onClick = toggleAllFilters,
                                    colors = CheckboxDefaults.colors(checkedColor = PreTripBlue)
                                )
                                Text("All locations", color = PreTripText)
                            }
                        },
                        onClick = toggleAllFilters
                    )
                    CrashCountFilter.entries.forEach { filter ->
                        val isSelected = filter in selectedCrashCountFilters
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            updateCrashCountFilters(if (checked) {
                                                selectedCrashCountFilters + filter
                                            } else {
                                                selectedCrashCountFilters - filter
                                            })
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = PreTripBlue)
                                    )
                                    Text(filter.label, color = PreTripText)
                                }
                            },
                            onClick = {
                                updateCrashCountFilters(if (isSelected) {
                                    selectedCrashCountFilters - filter
                                } else {
                                    selectedCrashCountFilters + filter
                                })
                            }
                        )
                    }
                    TextButton(
                        onClick = { updateCrashCountFilters(emptySet()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear filters", color = PreTripBlue)
                    }
                }
            }

            val historicalContextNotice = when {
                isHistoricalContextLoading && showHistoricalLoadingNotice -> "Loading historical crash data…"
                else -> historicalContextError
            }

            historicalContextNotice?.let { message ->
                HistoricalContextNotice(
                    message = message,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 358.dp)
                )
            }

            if (selectedCrash == null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 236.dp),
                ) {
                    HistoricalContextSummaryBar(
                        selectedTimeOfDay = selectedTimeOfDay,
                        showTimeOfDayMenu = showTimeOfDayMenu,
                        onTimeOfDayClick = { showTimeOfDayMenu = true },
                        onTimeOfDayDismiss = { showTimeOfDayMenu = false },
                        onTimeOfDaySelected = { selection ->
                            selectedTimeOfDay = selection
                            onHistoricalFiltersChanged(selection, selectedCrashCountFilters)
                            showTimeOfDayMenu = false
                        }
                    )
                }
            }

            val bottomSheetModifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)

            when {
                selectedCrash != null && historicalContext != null -> {
                    HistoricalCrashDetailSheet(
                        modifier = bottomSheetModifier,
                        selection = selectedCrash!!,
                        context = historicalContext!!,
                        onClose = { selectedCrash = null }
                    )
                }
                showLocationPermissionSheet -> {
                LocationPermissionSheet(
                    modifier = bottomSheetModifier,
                    permissionUiState = permissionUiState,
                    onRequestLocationPermission = onRequestLocationPermission,
                    onNotNow = { showLocationPermissionSheet = false }
                )
                }
                else -> {
                    PreTripBottomSheet(
                        modifier = bottomSheetModifier,
                        startTripError = startTripError,
                        onStartTrip = {
                            if (!onStartTrip()) {
                                showLocationPermissionSheet = true
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoricalContextNotice(
    message: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 320.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = PreTripText,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun GoogleMap.renderHistoricalCrashOverlay(
    context: HistoricalCrashContext?,
    density: Float,
    visibleFilters: Set<CrashCountFilter>,
    selectedCrash: HistoricalCrashSelection?,
    onCrashSelected: (HistoricalCrashSelection) -> Unit
) {
    if (context == null) return

    context.clusters.filter { it.crashCount.toCrashCountFilter() in visibleFilters }.forEach { cluster ->
        addMarker(
            MarkerOptions()
                .position(LatLng(cluster.latitude, cluster.longitude))
                .title(cluster.locationLabel)
                .icon(
                    if (cluster.crashCount == 1) {
                        individualCrashMarkerIcon(
                            density = density,
                            isSelected = selectedCrash.matches(cluster)
                        )
                    } else {
                        clusterMarkerIcon(
                            count = cluster.crashCount,
                            density = density,
                            isSelected = selectedCrash.matches(cluster)
                        )
                    }
                )
                .anchor(0.5f, 0.5f)
        )?.tag = HistoricalCrashSelection.Cluster(cluster)
    }
    context.individualPoints.filter { it.crashCount.toCrashCountFilter() in visibleFilters }.forEach { point ->
        addMarker(
            MarkerOptions()
                .position(LatLng(point.latitude, point.longitude))
                .title(point.locationLabel)
                .icon(
                    if (point.crashCount == 1) {
                        individualCrashMarkerIcon(
                            density = density,
                            isSelected = selectedCrash.matches(point)
                        )
                    } else {
                        clusterMarkerIcon(
                            count = point.crashCount,
                            density = density,
                            isSelected = selectedCrash.matches(point)
                        )
                    }
                )
                .anchor(0.5f, 0.5f)
        )?.tag = HistoricalCrashSelection.Individual(point)
    }

    setOnMarkerClickListener { marker ->
        (marker.tag as? HistoricalCrashSelection)?.let(onCrashSelected) != null
    }
}

private fun Int.toCrashCountFilter(): CrashCountFilter = when {
    this >= 8 -> CrashCountFilter.EightOrMore
    this >= 5 -> CrashCountFilter.FiveToSeven
    this >= 2 -> CrashCountFilter.TwoToFour
    else -> CrashCountFilter.Individual
}

@Composable
private fun CrashFilterIcon() {
    val iconColor = PreTripText
    Canvas(modifier = Modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        val top = size.height * .16f
        val middle = size.height * .53f
        drawLine(iconColor, Offset(size.width * .12f, top), Offset(size.width * .88f, top), stroke, cap = StrokeCap.Round)
        drawLine(iconColor, Offset(size.width * .12f, top), Offset(size.width * .5f, middle), stroke, cap = StrokeCap.Round)
        drawLine(iconColor, Offset(size.width * .88f, top), Offset(size.width * .5f, middle), stroke, cap = StrokeCap.Round)
        drawLine(iconColor, Offset(size.width * .5f, middle), Offset(size.width * .5f, size.height * .88f), stroke, cap = StrokeCap.Round)
    }
}

private fun HistoricalCrashSelection?.matches(cluster: CrashCluster): Boolean {
    return (this as? HistoricalCrashSelection.Cluster)?.value?.hotspotId == cluster.hotspotId
}

private fun HistoricalCrashSelection?.matches(point: CrashIndividualPoint): Boolean {
    return (this as? HistoricalCrashSelection.Individual)?.value?.accidentId == point.accidentId
}

private fun clusterMarkerIcon(
    count: Int,
    density: Float,
    isSelected: Boolean
) = BitmapDescriptorFactory.fromBitmap(
    Bitmap.createBitmap(
        ((if (isSelected) 64 else 52) * density).toInt(),
        ((if (isSelected) 64 else 52) * density).toInt(),
        Bitmap.Config.ARGB_8888
    ).also { bitmap ->
        val canvas = AndroidCanvas(bitmap)
        val centre = bitmap.width / 2f
        val markerRadius = if (isSelected) bitmap.width * 0.34f else bitmap.width * 0.42f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (isSelected) {
            paint.color = android.graphics.Color.argb(74, 248, 171, 0)
            canvas.drawCircle(centre, centre, bitmap.width * 0.48f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.color = android.graphics.Color.argb(235, 255, 255, 255)
            canvas.drawCircle(centre, centre, markerRadius + 4f * density, paint)
            paint.style = Paint.Style.FILL
        }
        paint.color = android.graphics.Color.rgb(248, 171, 0)
        canvas.drawCircle(centre, centre, markerRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(centre, centre, markerRadius, paint)
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 18f * density
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val baseline = centre - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(count.toString(), centre, baseline, paint)
    }
)

private fun individualCrashMarkerIcon(
    density: Float,
    isSelected: Boolean
) = BitmapDescriptorFactory.fromBitmap(
    Bitmap.createBitmap(
        ((if (isSelected) 36 else 20) * density).toInt(),
        ((if (isSelected) 36 else 20) * density).toInt(),
        Bitmap.Config.ARGB_8888
    ).also { bitmap ->
        val canvas = AndroidCanvas(bitmap)
        val centre = bitmap.width / 2f
        val markerRadius = if (isSelected) bitmap.width * 0.17f else bitmap.width * 0.3f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (isSelected) {
            paint.color = android.graphics.Color.argb(74, 248, 171, 0)
            canvas.drawCircle(centre, centre, bitmap.width * 0.44f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.color = android.graphics.Color.argb(235, 255, 255, 255)
            canvas.drawCircle(centre, centre, markerRadius + 4f * density, paint)
            paint.style = Paint.Style.FILL
        }
        paint.color = android.graphics.Color.rgb(248, 171, 0)
        canvas.drawCircle(centre, centre, markerRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(centre, centre, markerRadius, paint)
    }
)

private fun historicalMarkerOffset(
    latitude: Double,
    longitude: Double,
    context: HistoricalCrashContext,
    maxWidth: Dp,
    maxHeight: Dp
): Pair<Dp, Dp> {
    val northMeters = (latitude - context.areaCentre.latitude) * 111_320.0
    val eastMeters = (longitude - context.areaCentre.longitude) * 111_320.0 *
        cos(Math.toRadians(context.areaCentre.latitude))
    val xFraction = (0.5f + (eastMeters / context.radiusMeters * 0.3).toFloat())
        .coerceIn(0.16f, 0.84f)
    val yFraction = (0.47f - (northMeters / context.radiusMeters * 0.3).toFloat())
        .coerceIn(0.2f, 0.7f)
    return (maxWidth * xFraction) to (maxHeight * yFraction)
}

@Composable
private fun PreTripMapControls(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MapControlIcon(modifier = Modifier.size(42.dp)) {
            drawCircle(
                color = PreTripNavy.copy(alpha = 0.72f),
                center = center,
                radius = size.minDimension * 0.22f,
                style = Stroke(width = 2.dp.toPx())
            )
            drawLine(
                color = PreTripNavy.copy(alpha = 0.72f),
                start = Offset(center.x, size.height * 0.12f),
                end = Offset(center.x, size.height * 0.88f),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = PreTripNavy.copy(alpha = 0.72f),
                start = Offset(size.width * 0.12f, center.y),
                end = Offset(size.width * 0.88f, center.y),
                strokeWidth = 2.dp.toPx()
            )
        }
        MapControlIcon(modifier = Modifier.size(42.dp)) {
            drawCircle(
                color = PreTripBlue.copy(alpha = 0.82f),
                center = center,
                radius = size.minDimension * 0.23f,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = PreTripBlue,
                center = center,
                radius = size.minDimension * 0.08f
            )
            drawLine(
                color = PreTripBlue,
                start = Offset(center.x, size.height * 0.08f),
                end = Offset(center.x, size.height * 0.3f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun MapControlIcon(
    modifier: Modifier,
    content: DrawScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.9f),
        shadowElevation = 2.dp
    ) {
        Canvas(modifier = Modifier.fillMaxSize(), onDraw = content)
    }
}

@Composable
private fun PreTripBottomSheet(
    modifier: Modifier = Modifier,
    startTripError: String?,
    onStartTrip: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFD0D5DD))
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheckIcon()
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Trip setup complete",
                    color = PreTripBlue,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = "Ready to start?",
                color = PreTripText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Text(
                text = "Recording has not started.",
                color = PreTripText.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodyMedium
            )
            if (startTripError != null) {
                Text(
                    text = startTripError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                onClick = onStartTrip,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = "Start Trip",
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun LocationPermissionSheet(
    modifier: Modifier = Modifier,
    permissionUiState: LocationPermissionUiState,
    onRequestLocationPermission: () -> Unit,
    onNotNow: () -> Unit
) {
    val (title, description, actionText) = when (permissionUiState) {
        LocationPermissionUiState.Required -> Triple(
            "Location permission required",
            "Allow location access to start recording this trip.",
            "Allow location access"
        )
        LocationPermissionUiState.TryAgain -> Triple(
            "Location access needed",
            "Precise location is required to start recording this trip.",
            "Try again"
        )
        LocationPermissionUiState.OpenSettings -> Triple(
            "Location access needed",
            "Enable Precise location in Settings to start recording this trip.",
            "Open Settings"
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFD0D5DD))
            )
            Text(
                text = title,
                color = PreTripText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Text(
                text = description,
                color = PreTripText.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onRequestLocationPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(text = actionText, fontSize = 16.sp)
            }
            TextButton(onClick = onNotNow) {
                Text(text = "Not now")
            }
        }
    }
}

@Composable
private fun CheckIcon() {
    Canvas(modifier = Modifier.size(28.dp)) {
        drawCircle(
            color = PreTripBlue,
            center = center,
            radius = size.minDimension / 2f
        )
        drawLine(
            color = Color.White,
            start = Offset(size.width * 0.27f, size.height * 0.52f),
            end = Offset(size.width * 0.44f, size.height * 0.68f),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(size.width * 0.44f, size.height * 0.68f),
            end = Offset(size.width * 0.75f, size.height * 0.34f),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawPreTripMap() {
    val start = Offset(size.width * 0.22f, size.height * 0.27f)
    val end = Offset(size.width * 0.76f, size.height * 0.64f)
    val midpoint = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)

    drawMapTexture()
    drawCircle(
        color = PermittedArea,
        center = midpoint,
        radius = size.minDimension * 0.27f
    )
    drawCircle(
        color = PermittedAreaEdge,
        center = midpoint,
        radius = size.minDimension * 0.27f,
        style = Stroke(width = 2.dp.toPx())
    )

    val route = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(
            size.width * 0.3f,
            size.height * 0.35f,
            size.width * 0.45f,
            size.height * 0.43f,
            size.width * 0.53f,
            size.height * 0.5f
        )
        cubicTo(
            size.width * 0.61f,
            size.height * 0.56f,
            size.width * 0.7f,
            size.height * 0.61f,
            end.x,
            end.y
        )
    }
    drawPath(
        path = route,
        color = PreTripBlue,
        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPreTripMarker(start)
    drawPreTripMarker(end)

}

private fun DrawScope.drawMapTexture() {
    val roads = listOf(
        Triple(Offset(-size.width * 0.12f, size.height * 0.2f), Offset(size.width * 1.08f, size.height * 0.48f), 44f),
        Triple(Offset(size.width * 0.08f, size.height * 1.08f), Offset(size.width * 0.72f, -size.height * 0.08f), 34f),
        Triple(Offset(-size.width * 0.1f, size.height * 0.78f), Offset(size.width * 1.08f, size.height * 0.88f), 24f)
    )
    roads.forEach { (from, to, width) ->
        drawLine(MapRoadEdge, from, to, width)
        drawLine(MapRoad, from, to, width - 7f)
    }
    drawLine(
        color = MapMinorRoad,
        start = Offset(size.width * 0.04f, size.height * 0.08f),
        end = Offset(size.width * 0.92f, size.height * 0.72f),
        strokeWidth = 2.dp.toPx()
    )
    drawLine(
        color = MapMinorRoad,
        start = Offset(size.width * 0.04f, size.height * 0.62f),
        end = Offset(size.width * 0.86f, size.height * 0.12f),
        strokeWidth = 2.dp.toPx()
    )
    drawLine(
        color = MapMinorRoad,
        start = Offset(size.width * 0.55f, -size.height * 0.05f),
        end = Offset(size.width * 0.98f, size.height * 0.98f),
        strokeWidth = 2.dp.toPx()
    )
}

private fun DrawScope.drawPreTripMarker(center: Offset) {
    drawCircle(color = PreTripBlue, center = center, radius = 13.dp.toPx())
    drawCircle(color = Color.White, center = center, radius = 5.dp.toPx())
    drawLine(
        color = PreTripBlue,
        start = Offset(center.x - 7.dp.toPx(), center.y + 9.dp.toPx()),
        end = Offset(center.x, center.y + 18.dp.toPx()),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round
    )
    drawLine(
        color = PreTripBlue,
        start = Offset(center.x, center.y + 18.dp.toPx()),
        end = Offset(center.x + 7.dp.toPx(), center.y + 9.dp.toPx()),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round
    )
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun PreTripMapScreenPreview() {
    NextCrossTheme {
        PreTripMapScreen(
            startingPoint = "Current location",
            destination = "Monash University Clayton"
        )
    }
}
