package com.fit5120tp10.nextcross.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import android.widget.Toast
import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace
import com.fit5120tp10.nextcross.client.posttrip.PostTripAnalysisLocator
import com.fit5120tp10.nextcross.client.posttrip.PostTripAnalysisUiState
import com.fit5120tp10.nextcross.client.posttrip.PostTripReviewEvent
import com.fit5120tp10.nextcross.client.posttrip.ReviewPriority
import com.fit5120tp10.nextcross.client.posttrip.ReviewItem
import com.fit5120tp10.nextcross.client.posttrip.ReviewEventType
import com.fit5120tp10.nextcross.client.posttrip.SpeedCheckState
import com.fit5120tp10.nextcross.client.posttrip.displayText
import com.fit5120tp10.nextcross.client.tripreport.TripReportLocator
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuide
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuideController
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuideOverlay
import com.fit5120tp10.nextcross.ui.onboarding.CoachMarkMessagePlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fit5120tp10.nextcross.ui.theme.NextCrossTheme
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val SummaryBackground: Color @Composable get() = MaterialTheme.colorScheme.background
private val SummaryNavy: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val SummaryBlue = Color(0xFF1769E0)
private val SummaryGreen = Color(0xFF38A169)
private val SummarySoftRed = Color(0xFFFFF3F3)
private val SummaryRed = Color(0xFFC8444B)
private val SummarySoftOrange = Color(0xFFFFF5E8)
private val SummaryOrange = Color(0xFFC97818)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TripSummaryScreen(
    startingPoint: String,
    destination: String,
    tripId: String? = null,
    originPlace: SelectedPlace? = null,
    destinationPlace: SelectedPlace? = null,
    onBackClick: () -> Unit,
    onDone: () -> Unit,
    startedAtMillis: Long? = null,
    endedAtMillis: Long? = null,
    onReviewReport: () -> Unit = {},
    reflectionTexts: Map<String, String> = emptyMap(),
    onAiConversationComplete: (tripId: String, eventId: String, text: String) -> Unit = { _, _, _ -> },
    showAiReflection: Boolean = true,
    onboardingGuides: OnboardingGuideController? = null
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity
    val analysisCoordinator = remember(context.applicationContext) {
        PostTripAnalysisLocator.create(context.applicationContext)
    }
    var analysisState by remember(tripId) {
        mutableStateOf<PostTripAnalysisUiState>(
            if (tripId == null) PostTripAnalysisUiState.Idle else PostTripAnalysisUiState.Loading(tripId)
        )
    }
    var speedRetryAttempt by remember(tripId) { mutableStateOf(0) }
    var selectedEventType by remember { mutableStateOf<String?>(null) }
    var storedReflectionTexts by remember(tripId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reflectionBounds by remember { mutableStateOf<Rect?>(null) }
    val configuration = LocalConfiguration.current
    val screenHeightPixels = with(LocalDensity.current) { configuration.screenHeightDp.dp.toPx() }
    val reflectionIsVisible = reflectionBounds?.let { bounds ->
        bounds.bottom > 0f && bounds.top < screenHeightPixels
    } == true

    LaunchedEffect(tripId) {
        storedReflectionTexts = tripId?.let { reportTripId ->
            withContext(Dispatchers.IO) {
                TripReportLocator.get(context.applicationContext).aiReflectionsForTrip(reportTripId)
                    .associate { it.eventId to it.text }
            }
        }.orEmpty()
    }

    LaunchedEffect(tripId, analysisCoordinator, speedRetryAttempt) {
        if (tripId == null) {
            analysisState = PostTripAnalysisUiState.Idle
            return@LaunchedEffect
        }
        val states = if (speedRetryAttempt == 0) {
            analysisCoordinator.observeCompletedTrip(tripId)
        } else {
            analysisCoordinator.retrySpeedCheck(tripId)
        }
        states.collect { state -> analysisState = state }
    }

    val readyAnalysis = analysisState as? PostTripAnalysisUiState.Ready
    val plannedRouteState = rememberPlannedRoute(
        originPlace = originPlace,
        destinationPlace = destinationPlace,
        enabled = readyAnalysis?.routePoints?.size?.let { it < 2 } != false
    )

    Scaffold(
        containerColor = SummaryBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Trip summary",
                        color = SummaryNavy,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) { BackNavigationIcon(SummaryNavy) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SummaryBackground,
                    navigationIconContentColor = SummaryNavy,
                    titleContentColor = SummaryNavy
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TripCompletedSection()
            TripReportCard(
                startingPoint = startingPoint.ifBlank { "Monash University Clayton" },
                destination = destination.ifBlank { "Melbourne CBD" },
                startedAtMillis = startedAtMillis,
                endedAtMillis = endedAtMillis,
                distanceKilometres = readyAnalysis?.routePoints?.let(::calculateTripDistanceKilometres),
                tripId = tripId,
                fragmentActivity = fragmentActivity,
                routePoints = readyAnalysis?.routePoints.orEmpty(),
                plannedRoutePoints = (plannedRouteState as? PlannedRouteUiState.Available)
                    ?.route
                    ?.points
                    .orEmpty(),
                originPlace = originPlace,
                destinationPlace = destinationPlace,
                state = analysisState,
                onHardBrakingClick = { selectedEventType = "RAPID_DECELERATION" },
                onHardAccelerationClick = { selectedEventType = "RAPID_ACCELERATION" },
                onSpeedLimitClick = { selectedEventType = "SPEED_LIMIT_EXCEEDANCE" },
                onRetrySpeedCheck = { speedRetryAttempt += 1 }
            )
            if (showAiReflection && readyAnalysis?.chronologicalEvents?.isNotEmpty() == true) {
                Box(Modifier.onGloballyPositioned { reflectionBounds = it.boundsInRoot() }) {
                    AiReflectionCard(
                        events = readyAnalysis.chronologicalEvents,
                        tripStartMillis = startedAtMillis
                            ?: readyAnalysis.routePoints.firstOrNull()?.timestampMillis,
                        completedReflectionTexts = storedReflectionTexts + reflectionTexts,
                        onConversationComplete = onAiConversationComplete
                    )
                }
            }
            ReportReadyCard(onReview = {
                when (val current = analysisState) {
                    PostTripAnalysisUiState.Idle, is PostTripAnalysisUiState.Loading ->
                        Toast.makeText(context, "Trip analysis is still loading. Please wait.", Toast.LENGTH_SHORT).show()
                    is PostTripAnalysisUiState.Ready -> onReviewReport()
                    is PostTripAnalysisUiState.InsufficientData, is PostTripAnalysisUiState.Failed -> onReviewReport()
                }
            })
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text(text = "Done") }
        }
    }

    selectedEventType?.let { eventType ->
        val selectedEvents = readyAnalysis?.chronologicalEvents.orEmpty().filter { it.type.name == eventType }
        if (selectedEvents.isNotEmpty()) {
            DrivingEventDetailsSheet(
                title = eventType.toDisplayLabel(),
                events = selectedEvents,
                allEvents = readyAnalysis?.chronologicalEvents.orEmpty(),
                tripStartMillis = startedAtMillis ?: readyAnalysis?.routePoints?.firstOrNull()?.timestampMillis,
                onDismiss = { selectedEventType = null }
            )
        }
    }

    if (showAiReflection && reflectionIsVisible && readyAnalysis?.chronologicalEvents?.isNotEmpty() == true &&
        onboardingGuides?.shouldShow(OnboardingGuide.TripSummaryReflection) == true
    ) {
        OnboardingGuideOverlay(
            message = "Review a driving event and reflect on it with AI.",
            actionLabel = "Got it",
            onComplete = { onboardingGuides.complete(OnboardingGuide.TripSummaryReflection) },
            targetBounds = requireNotNull(reflectionBounds),
            messagePlacement = CoachMarkMessagePlacement.Automatic
        )
    }
}

@Composable
private fun TripCompletedSection() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(88.dp)) {
                drawCircle(SummaryBlue.copy(alpha = 0.12f))
            }
            Canvas(modifier = Modifier.size(52.dp)) {
                drawCircle(SummaryBlue)
                drawLine(Color.White, Offset(size.width * .26f, size.height * .52f), Offset(size.width * .44f, size.height * .68f), 3.dp.toPx(), cap = StrokeCap.Round)
                drawLine(Color.White, Offset(size.width * .44f, size.height * .68f), Offset(size.width * .76f, size.height * .32f), 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text("Trip completed", color = SummaryNavy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Your local trip report is ready.", color = SummaryNavy.copy(alpha = .7f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TripReportCard(
    startingPoint: String,
    destination: String,
    startedAtMillis: Long?,
    endedAtMillis: Long?,
    distanceKilometres: Double?,
    tripId: String?,
    fragmentActivity: FragmentActivity?,
    routePoints: List<com.fit5120tp10.nextcross.client.posttrip.ReviewRoutePoint>,
    plannedRoutePoints: List<GeographicPoint>,
    originPlace: SelectedPlace?,
    destinationPlace: SelectedPlace?,
    state: PostTripAnalysisUiState,
    onHardBrakingClick: () -> Unit,
    onHardAccelerationClick: () -> Unit,
    onSpeedLimitClick: () -> Unit,
    onRetrySpeedCheck: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Column {
            if (fragmentActivity != null) {
                TripRouteMap(
                    fragmentActivity = fragmentActivity,
                    fragmentTag = "trip-summary-route-${tripId ?: "preview"}",
                    routePoints = routePoints.map { point ->
                        TripRoutePoint(point.latitude, point.longitude, point.timestampMillis)
                    },
                    eventTimes = (state as? PostTripAnalysisUiState.Ready)
                        ?.chronologicalEvents
                        ?.map(PostTripReviewEvent::startTimestampMillis)
                        .orEmpty(),
                    originPlace = originPlace,
                    destinationPlace = destinationPlace,
                    plannedRoutePoints = plannedRoutePoints,
                    eventRouteOverlays = (state as? PostTripAnalysisUiState.Ready)
                        ?.chronologicalEvents
                        ?.map { event ->
                            TripRouteEventOverlay(
                                type = event.type,
                                points = event.routePoints.map { GeographicPoint(it.latitude, it.longitude) }
                            )
                        }
                        .orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                    contentKey = tripId
                )
            } else if (routePoints.isEmpty()) {
                RouteUnavailablePreview()
            }
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TripRouteEndpoints(startingPoint = startingPoint, destination = destination)
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SummaryNavy.copy(alpha = .1f)))
                TripMetricsGrid(
                    startedAtMillis = startedAtMillis,
                    endedAtMillis = endedAtMillis,
                    distanceKilometres = distanceKilometres
                )
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SummaryNavy.copy(alpha = .1f)))
                DrivingEventsContent(
                    state = state,
                    onHardBrakingClick = onHardBrakingClick,
                    onHardAccelerationClick = onHardAccelerationClick,
                    onSpeedLimitClick = onSpeedLimitClick,
                    onRetrySpeedCheck = onRetrySpeedCheck
                )
            }
        }
    }
}

@Composable
private fun RouteUnavailablePreview() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(SummaryBlue.copy(alpha = .06f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Route will appear after GPS points are recorded.",
            color = SummaryNavy.copy(alpha = .62f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun TripMetricsGrid(
    startedAtMillis: Long?,
    endedAtMillis: Long?,
    distanceKilometres: Double?
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TripMetric("Trip duration", formatTripDuration(startedAtMillis, endedAtMillis), Modifier.weight(1f))
        TripMetric("Recorded", formatRecordedTime(startedAtMillis, endedAtMillis), Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TripMetric("Trip date", formatTripDate(startedAtMillis), Modifier.weight(1f))
        TripMetric("Distance", formatTripDistance(distanceKilometres), Modifier.weight(1f))
    }
}

private fun formatTripDuration(startedAtMillis: Long?, endedAtMillis: Long?): String {
    if (startedAtMillis == null || endedAtMillis == null || endedAtMillis < startedAtMillis) {
        return "Unavailable"
    }
    return "${(endedAtMillis - startedAtMillis) / 60_000L} min"
}

private fun formatRecordedTime(startedAtMillis: Long?, endedAtMillis: Long?): String {
    if (startedAtMillis == null || endedAtMillis == null) {
        return "Unavailable"
    }
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return "${formatter.format(Date(startedAtMillis))} \u2013 ${formatter.format(Date(endedAtMillis))}"
}

private fun formatTripDate(startedAtMillis: Long?): String {
    if (startedAtMillis == null) return "Unavailable"
    return SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(startedAtMillis))
}

private fun formatTripDistance(distanceKilometres: Double?): String {
    if (distanceKilometres == null) return "Unavailable"
    return String.format(Locale.getDefault(), "%.1f km", distanceKilometres)
}

private fun calculateTripDistanceKilometres(
    points: List<com.fit5120tp10.nextcross.client.posttrip.ReviewRoutePoint>
): Double? {
    if (points.size < 2) return null
    val earthRadiusKilometres = 6_371.0
    val distance = points.zipWithNext().sumOf { (from, to) ->
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val a = sin(latitudeDelta / 2).let { it * it } +
            cos(fromLatitude) * cos(toLatitude) * sin(longitudeDelta / 2).let { it * it }
        2 * earthRadiusKilometres * atan2(sqrt(a), sqrt(1 - a))
    }
    return distance.takeIf { it.isFinite() && it >= 0.0 }
}

@Composable
internal fun TripRouteEndpoints(
    startingPoint: String,
    destination: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RouteMarkerIcon()
        Spacer(modifier = Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(startingPoint, color = SummaryNavy, style = MaterialTheme.typography.bodyMedium)
            Text(destination, color = SummaryNavy, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun RouteMarkerIcon() {
    Canvas(modifier = Modifier.size(width = 26.dp, height = 58.dp)) {
        val top = Offset(size.width / 2f, size.height * .16f)
        val bottom = Offset(size.width / 2f, size.height * .84f)
        drawLine(SummaryBlue, top.copy(y = size.height * .3f), bottom.copy(y = size.height * .7f), 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(SummaryBlue, center = top, radius = 5.dp.toPx())
        drawCircle(SummaryGreen, center = bottom, radius = 5.dp.toPx())
    }
}

@Composable
private fun TripMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = SummaryNavy.copy(alpha = .65f), style = MaterialTheme.typography.labelMedium)
        Text(value, color = SummaryNavy, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DrivingEventsContent(
    state: PostTripAnalysisUiState,
    onHardBrakingClick: () -> Unit,
    onHardAccelerationClick: () -> Unit,
    onSpeedLimitClick: () -> Unit,
    onRetrySpeedCheck: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val redRowBackground = if (darkTheme) Color(0xFF472A31) else SummarySoftRed
    val orangeRowBackground = if (darkTheme) Color(0xFF443221) else SummarySoftOrange
    val badgeLabel = when (state) {
        PostTripAnalysisUiState.Idle -> "Unavailable"
        is PostTripAnalysisUiState.Loading -> "Analysing"
        is PostTripAnalysisUiState.Ready -> when (state.speedCheck) {
            SpeedCheckState.Checking -> "Checking road speed"
            SpeedCheckState.StillChecking -> "Still checking road speed"
            is SpeedCheckState.Failed -> "Speed check unavailable"
            is SpeedCheckState.Done -> "Recorded data"
        }
        is PostTripAnalysisUiState.InsufficientData -> "Data unavailable"
        is PostTripAnalysisUiState.Failed -> "Analysis failed"
    }
    val brakingValue = when (state) {
        is PostTripAnalysisUiState.Ready -> formatEventCount(
            state.chronologicalEvents.count { it.type == ReviewEventType.RAPID_DECELERATION }
        )
        is PostTripAnalysisUiState.Loading -> "Analysing"
        else -> "Unavailable"
    }
    val accelerationValue = when (state) {
        is PostTripAnalysisUiState.Ready -> formatEventCount(
            state.chronologicalEvents.count { it.type == ReviewEventType.RAPID_ACCELERATION }
        )
        is PostTripAnalysisUiState.Loading -> "Analysing"
        else -> "Unavailable"
    }
    // Count Active Trip warning episodes (local SpeedLimitExceedance), not backend analysis.
    val speedLimitValue = when (state) {
        is PostTripAnalysisUiState.Ready -> formatEventCount(
            state.chronologicalEvents.count { it.type == ReviewEventType.SPEED_LIMIT_EXCEEDANCE }
        )
        is PostTripAnalysisUiState.Loading -> "Analysing"
        else -> "Unavailable"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Driving events", color = SummaryNavy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Surface(shape = RoundedCornerShape(12.dp), color = SummaryBlue.copy(alpha = .1f)) {
                Text(badgeLabel, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = SummaryBlue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
        DrivingEventRow(
            "Hard braking", brakingValue, redRowBackground, Color(0xFFFFE0E1),
            onClick = if (state is PostTripAnalysisUiState.Ready && state.chronologicalEvents.any { it.type == ReviewEventType.RAPID_DECELERATION }) onHardBrakingClick else null
        ) { BrakingIcon() }
        DrivingEventRow(
            "Hard acceleration", accelerationValue, orangeRowBackground, Color(0xFFFFE5C0),
            onClick = if (state is PostTripAnalysisUiState.Ready && state.chronologicalEvents.any { it.type == ReviewEventType.RAPID_ACCELERATION }) onHardAccelerationClick else null
        ) { AccelerationIcon() }
        DrivingEventRow(
            "Above the speed limit", speedLimitValue, redRowBackground, Color(0xFFFFE0E1),
            onClick = if (state is PostTripAnalysisUiState.Ready && state.chronologicalEvents.any { it.type == ReviewEventType.SPEED_LIMIT_EXCEEDANCE }) onSpeedLimitClick else null
        ) { SpeedLimitIcon() }
        if (state is PostTripAnalysisUiState.Ready && state.speedCheck is SpeedCheckState.Failed) {
            OutlinedButton(onClick = onRetrySpeedCheck, modifier = Modifier.align(Alignment.End)) {
                Text("Retry")
            }
        }
        if (state is PostTripAnalysisUiState.Ready && state.chronologicalEvents.isEmpty()) {
            Text(
                text = "No rapid acceleration or hard braking was detected for this trip.",
                color = SummaryNavy.copy(alpha = .72f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
        if (state is PostTripAnalysisUiState.InsufficientData) {
            Text(state.reason, color = SummaryNavy.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
        }
        if (state is PostTripAnalysisUiState.Failed) {
            Text(state.message, color = SummaryRed, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatEventCount(count: Int): String =
    "$count ${if (count == 1) "event" else "events"}"

@Composable
private fun ReportReadyCard(onReview: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = SummaryBlue.copy(alpha = .12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "✓",
                        color = SummaryBlue,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Your trip report",
                    color = SummaryNavy,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Review the details before sharing",
                    color = SummaryNavy.copy(alpha = .62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(onClick = onReview) {
                Text("Review", color = SummaryBlue, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DrivingEventRow(
    label: String,
    value: String,
    background: Color,
    iconBackground: Color,
    onClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        shape = RoundedCornerShape(14.dp),
        color = background
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(26.dp), shape = CircleShape, color = iconBackground) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(label, modifier = Modifier.weight(1f), color = SummaryNavy, style = MaterialTheme.typography.bodyMedium)
            Text(value, color = SummaryNavy, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DrivingEventDetailsSheet(
    title: String,
    events: List<PostTripReviewEvent>,
    allEvents: List<PostTripReviewEvent>,
    tripStartMillis: Long?,
    onDismiss: () -> Unit
) {
    val ranking = remember(allEvents, tripStartMillis) {
        ReviewPriority.rank(allEvents, tripStartMillis)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, color = SummaryNavy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            events.forEachIndexed { index, event ->
                DrivingEventDetail(event, ranking.item(event.eventId))
                if (index != events.lastIndex) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SummaryNavy.copy(alpha = .1f)))
                }
            }
            Text(
                text = "Close",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 12.dp),
                color = SummaryBlue,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun DrivingEventDetail(event: PostTripReviewEvent, reviewItem: ReviewItem?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(formatEventTime(event.startTimestampMillis), color = SummaryNavy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        DetailRow("Duration", String.format(Locale.getDefault(), "%.1f s", event.durationSeconds))
        DetailRow("Review priority", reviewItem?.priority?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "Unavailable")
        reviewItem?.annotations?.mapNotNull { it.displayText() }?.takeIf { it.isNotEmpty() }?.let { tags ->
            Text(
                text = tags.joinToString("  ·  "),
                color = SummaryOrange,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = when (event.type) {
                ReviewEventType.RAPID_DECELERATION -> "A rapid decrease in speed was recorded."
                ReviewEventType.RAPID_ACCELERATION -> "A rapid increase in speed was recorded."
                ReviewEventType.SPEED_LIMIT_EXCEEDANCE -> event.speedLimitDetails?.let { details ->
                    "Road limit ${details.referenceLimitKmh} km/h · peak speed ${String.format(Locale.getDefault(), "%.0f", details.peakSpeedKmh)} km/h"
                } ?: "Road speed details are unavailable."
            },
            color = SummaryNavy.copy(alpha = .72f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = SummaryNavy.copy(alpha = .65f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = SummaryNavy, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatEventTime(recordedAtMillis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(recordedAtMillis))

private fun String.toDisplayLabel(): String = when (this) {
    "RAPID_DECELERATION" -> "Hard braking"
    "RAPID_ACCELERATION" -> "Hard acceleration"
    "SPEED_LIMIT_EXCEEDANCE" -> "Above the speed limit"
    else -> this
}

@Composable
private fun BrakingIcon() {
    Canvas(modifier = Modifier.size(14.dp)) {
        drawLine(SummaryRed, Offset(size.width / 2f, size.height * .2f), Offset(size.width / 2f, size.height * .62f), 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(
            color = SummaryRed,
            center = Offset(size.width / 2f, size.height * .82f),
            radius = 1.3.dp.toPx()
        )
    }
}

@Composable
private fun AccelerationIcon() {
    Canvas(modifier = Modifier.size(14.dp)) {
        drawLine(SummaryOrange, Offset(size.width / 2f, size.height * .82f), Offset(size.width / 2f, size.height * .2f), 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(SummaryOrange, Offset(size.width * .25f, size.height * .42f), Offset(size.width / 2f, size.height * .18f), 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(SummaryOrange, Offset(size.width * .75f, size.height * .42f), Offset(size.width / 2f, size.height * .18f), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun SpeedLimitIcon() {
    Canvas(modifier = Modifier.size(14.dp)) {
        drawCircle(color = SummaryRed, center = center, radius = 5.dp.toPx())
        drawCircle(color = Color.White, center = center, radius = 2.4.dp.toPx())
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun TripSummaryScreenPreview() {
    NextCrossTheme {
        TripSummaryScreen(
            startingPoint = "Monash University Clayton",
            destination = "Melbourne CBD",
            onBackClick = {},
            onDone = {}
        )
    }
}
