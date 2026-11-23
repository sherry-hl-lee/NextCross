package com.fit5120tp10.nextcross.ui.screen

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace
import com.fit5120tp10.nextcross.client.posttrip.PostTripAnalysisLocator
import com.fit5120tp10.nextcross.client.posttrip.PostTripAnalysisUiState
import com.fit5120tp10.nextcross.client.posttrip.PostTripReviewEvent
import com.fit5120tp10.nextcross.client.posttrip.ReviewPriority
import com.fit5120tp10.nextcross.client.posttrip.ReviewEventType
import com.fit5120tp10.nextcross.client.posttrip.displayText
import com.fit5120tp10.nextcross.client.tripreport.TripReportLocator
import com.fit5120tp10.nextcross.client.tripreport.TripReportEmailLocator
import com.fit5120tp10.nextcross.client.tripreport.TripReportEmailResult
import com.fit5120tp10.nextcross.client.tripreport.TripReportEmailSendStore
import com.fit5120tp10.nextcross.client.tripreport.ShareEventKind
import com.fit5120tp10.nextcross.client.tripreport.ShareEventLine
import com.fit5120tp10.nextcross.client.tripreport.isReportableReflectionEventId
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuide
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuideController
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuideOverlay
import com.fit5120tp10.nextcross.ui.onboarding.CoachMarkMessagePlacement
import com.fit5120tp10.nextcross.client.tripreport.TripReportShareAttachment
import com.fit5120tp10.nextcross.client.tripreport.TripReportShareSelection
import com.google.android.gms.maps.GoogleMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val ReportBackground: Color @Composable get() = MaterialTheme.colorScheme.background
private val ReportNavy: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val ReportBlue = Color(0xFF1769E0)
private val ReportRed = Color(0xFFC8444B)
private val ReportOrange = Color(0xFFC97818)
private val ReportGreen = Color(0xFF267B55)
private val ReportAiAccent = Color(0xFF3769B8)
private val ReportUserAccent = Color(0xFF6B50A0)
internal fun reportEventsFor(events: List<PostTripReviewEvent>): List<PostTripReviewEvent> =
    events.filter { it.type != ReviewEventType.SPEED_LIMIT_EXCEEDANCE }

internal fun shareEventsFor(state: PostTripAnalysisUiState): List<ShareEventLine>? {
    val ready = state as? PostTripAnalysisUiState.Ready ?: return null
    return reportEventsFor(ready.chronologicalEvents).map { event ->
        ShareEventLine(
            kind = when (event.type) {
                ReviewEventType.RAPID_ACCELERATION -> ShareEventKind.HARD_ACCELERATION
                ReviewEventType.RAPID_DECELERATION -> ShareEventKind.HARD_BRAKING
                ReviewEventType.SPEED_LIMIT_EXCEEDANCE -> error("Speed events are excluded from reports")
            },
            startMillis = event.startTimestampMillis,
            durationSeconds = event.durationSeconds
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TripReportScreen(
    startingPoint: String,
    destination: String,
    tripId: String?,
    originPlace: SelectedPlace?,
    destinationPlace: SelectedPlace?,
    startedAtMillis: Long?,
    endedAtMillis: Long?,
    parentEmail: String,
    reflectionTexts: Map<String, String>,
    fragmentActivity: FragmentActivity?,
    onboardingGuides: OnboardingGuideController? = null,
    onBackClick: () -> Unit,
    onDone: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val analysisCoordinator = remember(context.applicationContext) {
        PostTripAnalysisLocator.create(context.applicationContext)
    }
    var analysisState by remember(tripId) {
        mutableStateOf<PostTripAnalysisUiState>(
            if (tripId == null) PostTripAnalysisUiState.Idle else PostTripAnalysisUiState.Loading(tripId)
        )
    }
    var includeRoute by remember(tripId) { mutableStateOf(false) }
    var includeReflection by remember(tripId) { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var storedReflectionTexts by remember(tripId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var routeMap by remember(tripId) { mutableStateOf<GoogleMap?>(null) }
    var shareButtonBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(tripId) {
        storedReflectionTexts = tripId?.let { reportTripId ->
            withContext(Dispatchers.IO) {
                TripReportLocator.get(context.applicationContext).aiReflectionsForTrip(reportTripId)
                    .associate { it.eventId to it.text }
            }
        }.orEmpty()
    }
    val availableReflectionText = (storedReflectionTexts + reflectionTexts)
        .filterKeys(::isReportableReflectionEventId)
        .values.distinct().joinToString("\n\n")

    LaunchedEffect(tripId, analysisCoordinator) {
        if (tripId == null) {
            analysisState = PostTripAnalysisUiState.Idle
            return@LaunchedEffect
        }
        analysisCoordinator.observeCompletedTrip(tripId).collect { state -> analysisState = state }
    }

    val readyAnalysis = analysisState as? PostTripAnalysisUiState.Ready
    val shareEvents = shareEventsFor(analysisState)
    val plannedRouteState = rememberPlannedRoute(
        originPlace = originPlace,
        destinationPlace = destinationPlace,
        enabled = readyAnalysis?.routePoints?.size?.let { it < 2 } != false
    )
    val recordedRoute = readyAnalysis?.routePoints.orEmpty()
    val plannedRoute = (plannedRouteState as? PlannedRouteUiState.Available)?.route?.points.orEmpty()
    val canIncludeRoute = recordedRoute.isNotEmpty() || plannedRoute.isNotEmpty()

    Scaffold(
        containerColor = ReportBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Trip Report",
                        color = ReportNavy,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = { IconButton(onClick = onBackClick) { BackNavigationIcon(ReportNavy) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ReportBackground,
                    navigationIconContentColor = ReportNavy,
                    titleContentColor = ReportNavy
                )
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (shareEvents != null) showShareSheet = true
                            else {
                                val message = when (analysisState) {
                                    is PostTripAnalysisUiState.Loading -> "Trip analysis is still loading. Please wait."
                                    else -> "Trip analysis is unavailable for this report."
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                            .onGloballyPositioned { shareButtonBounds = it.boundsInRoot() },
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) { Text("Share report", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface, contentColor = ReportNavy
                        )
                    ) { Text("Done", fontWeight = FontWeight.Bold) }
                }
            }
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
            ReportDocument(
                startingPoint = startingPoint.ifBlank { "Starting point" },
                destination = destination.ifBlank { "Destination" },
                startedAtMillis = startedAtMillis,
                endedAtMillis = endedAtMillis,
                state = analysisState,
                includeRoute = includeRoute,
                reflectionText = availableReflectionText.takeIf { includeReflection },
                fragmentActivity = fragmentActivity,
                tripId = tripId,
                routePoints = recordedRoute,
                plannedRoutePoints = plannedRoute,
                originPlace = originPlace,
                destinationPlace = destinationPlace,
                onRouteMapReady = { routeMap = it }
            )
            ReportIncludeOptions(
                includeRoute = includeRoute,
                routeEnabled = canIncludeRoute,
                onRouteChanged = { includeRoute = it },
                includeReflection = includeReflection,
                reflectionAvailable = availableReflectionText.isNotBlank(),
                onReflectionChanged = { includeReflection = it }
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
    }

    if (showShareSheet) {
        ShareReportSheet(
            parentEmail = parentEmail,
            tripId = tripId,
            includeRoute = includeRoute,
            includeReflection = includeReflection,
            routeMap = routeMap,
            shareEvents = shareEvents,
            onDismiss = { showShareSheet = false }
        )
    }

    if (shareButtonBounds != null && onboardingGuides?.shouldShow(OnboardingGuide.TripReportShare) == true) {
        OnboardingGuideOverlay(
            message = "Choose what to include, then share this report with your parent.",
            actionLabel = "Got it",
            onComplete = { onboardingGuides.complete(OnboardingGuide.TripReportShare) },
            targetBounds = requireNotNull(shareButtonBounds),
            messagePlacement = CoachMarkMessagePlacement.AboveTarget
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ShareReportSheet(
    parentEmail: String,
    tripId: String?,
    includeRoute: Boolean,
    includeReflection: Boolean,
    routeMap: GoogleMap?,
    shareEvents: List<ShareEventLine>?,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val emailClient = remember(context.applicationContext) {
        TripReportEmailLocator.get(context.applicationContext)
    }
    var emailSendStatus by remember(tripId) {
        mutableStateOf(tripId?.let(emailClient::sendStatus) ?: TripReportEmailSendStore.Status.NotSent)
    }
    var emailResultMessage by remember(tripId) {
        mutableStateOf<String?>(
            when (emailSendStatus) {
                TripReportEmailSendStore.Status.Accepted -> "This trip report has already been emailed."
                TripReportEmailSendStore.Status.Pending -> "The previous email's outcome is uncertain. This trip cannot be sent again."
                TripReportEmailSendStore.Status.NotSent -> null
            }
        )
    }
    var isSendingEmail by remember { mutableStateOf(false) }
    var otherAppsError by remember { mutableStateOf<String?>(null) }
    val emailCanSend = tripId != null && shareEvents != null && parentEmail.isNotBlank() &&
        (!includeRoute || routeMap != null)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Share your report", color = ReportNavy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Choose an app, then review the recipient and send there.", color = ReportNavy.copy(alpha = .65f), style = MaterialTheme.typography.bodyMedium)
            ShareTargetRow(
                title = "Email",
                detail = when {
                    parentEmail.isBlank() -> "No email address was saved for this trip."
                    tripId == null -> "This trip report is not ready to send."
                    includeRoute && routeMap == null -> "Preparing the route map attachment…"
                    isSendingEmail -> "Sending to $parentEmail…"
                    emailSendStatus == TripReportEmailSendStore.Status.Accepted -> "Already emailed for this trip"
                    emailSendStatus == TripReportEmailSendStore.Status.Pending -> "Previous send outcome uncertain"
                    else -> "Sends to $parentEmail"
                },
                enabled = emailCanSend && !isSendingEmail &&
                    emailSendStatus == TripReportEmailSendStore.Status.NotSent,
                onClick = {
                    val reportTripId = tripId ?: return@ShareTargetRow
                    val currentEvents = shareEvents ?: return@ShareTargetRow
                    isSendingEmail = true
                    emailResultMessage = null
                    scope.launch {
                        val attachments = if (includeRoute) {
                            routeMap?.createRouteMapAttachment(context.cacheDir, reportTripId)?.let(::listOf)
                                ?: emptyList()
                        } else emptyList()
                        if (includeRoute && attachments.isEmpty()) {
                            isSendingEmail = false
                            emailResultMessage = "Could not prepare the route map attachment. Please try again."
                            return@launch
                        }
                        val result = withContext(Dispatchers.IO) {
                            emailClient.sendToParent(
                                tripId = reportTripId,
                                parentEmail = parentEmail,
                                selection = TripReportShareSelection(
                                    includeDetailedRoute = includeRoute,
                                    includeReflectionConversation = includeReflection
                                ),
                                events = currentEvents,
                                attachments = attachments
                            )
                        }
                        isSendingEmail = false
                        emailSendStatus = emailClient.sendStatus(reportTripId)
                        emailResultMessage = when (result) {
                            is TripReportEmailResult.Accepted -> {
                                "Report successfully sent to $parentEmail."
                            }
                            is TripReportEmailResult.Failed -> {
                                if (emailSendStatus == TripReportEmailSendStore.Status.Pending) {
                                    "Email outcome uncertain. This trip cannot be sent again from this device."
                                } else "Could not send the report. Please try again."
                            }
                            is TripReportEmailResult.Invalid -> result.reason
                        }
                    }
                }
            )
            emailResultMessage?.let { message ->
                Text(
                    message,
                    color = if (emailSendStatus == TripReportEmailSendStore.Status.Accepted) ReportGreen else ReportNavy.copy(alpha = .72f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (emailSendStatus == TripReportEmailSendStore.Status.Accepted) FontWeight.Medium else FontWeight.Normal
                )
            }
            Text("Other apps", color = ReportNavy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ShareTargetRow(
                title = "Other apps",
                detail = "Choose from installed apps",
                enabled = tripId != null && shareEvents != null,
                onClick = {
                    val reportTripId = tripId ?: return@ShareTargetRow
                    val currentEvents = shareEvents ?: return@ShareTargetRow
                    otherAppsError = null
                    scope.launch {
                        val payload = withContext(Dispatchers.IO) {
                            TripReportLocator.get(context.applicationContext).buildSharePayload(
                                tripId = reportTripId,
                                selection = TripReportShareSelection(
                                    includeDetailedRoute = includeRoute,
                                    includeReflectionConversation = includeReflection
                                ),
                                events = currentEvents
                            )
                        }
                        if (payload == null) {
                            otherAppsError = "Trip report is still being saved. Please try again."
                            return@launch
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, payload.plainText)
                            putExtra(Intent.EXTRA_TITLE, payload.subject)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share report"))
                        onDismiss()
                    }
                }
            )
            otherAppsError?.let { error ->
                Text(error, color = ReportNavy.copy(alpha = .72f), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ShareTargetRow(title: String, detail: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(70.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = ReportNavy, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, color = ReportNavy.copy(alpha = .62f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReportDocument(
    startingPoint: String,
    destination: String,
    startedAtMillis: Long?,
    endedAtMillis: Long?,
    state: PostTripAnalysisUiState,
    includeRoute: Boolean,
    reflectionText: String?,
    fragmentActivity: FragmentActivity?,
    tripId: String?,
    routePoints: List<com.fit5120tp10.nextcross.client.posttrip.ReviewRoutePoint>,
    plannedRoutePoints: List<GeographicPoint>,
    originPlace: SelectedPlace?,
    destinationPlace: SelectedPlace?,
    onRouteMapReady: (GoogleMap?) -> Unit
) {
    val ready = state as? PostTripAnalysisUiState.Ready
    val reportEvents = ready?.chronologicalEvents?.let(::reportEventsFor).orEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("NEXTCROSS · COMPLETED TRIP", color = ReportNavy.copy(alpha = .58f), style = MaterialTheme.typography.labelLarge)
            Text("Trip report", color = ReportNavy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(reportDateTime(startedAtMillis, endedAtMillis), color = ReportNavy.copy(alpha = .66f), style = MaterialTheme.typography.bodyMedium)
            Text("$startingPoint → $destination", color = ReportNavy, style = MaterialTheme.typography.bodyLarge)
            ReportDivider()
            Row(modifier = Modifier.fillMaxWidth()) {
                ReportMetric("Duration", reportDuration(startedAtMillis, endedAtMillis), Modifier.weight(1f))
                ReportMetric("Distance recorded", reportDistance(ready?.routePoints), Modifier.weight(1f))
            }
            ReportDivider()
            Text("Driving events", color = ReportNavy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            when (state) {
                is PostTripAnalysisUiState.Ready -> {
                    val tagsByEventId = remember(reportEvents, startedAtMillis) {
                        ReviewPriority.rank(
                            reportEvents,
                            startedAtMillis ?: state.routePoints.firstOrNull()?.timestampMillis
                        ).items.associate { item ->
                            item.eventId to item.annotations.mapNotNull { it.displayText() }
                        }
                    }
                    if (reportEvents.isEmpty()) {
                        Text(
                            "No acceleration or braking events recorded for this trip.",
                            color = ReportGreen,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        reportEvents.forEach { event ->
                            ReportEventDetail(event, includeRoute, tagsByEventId[event.eventId].orEmpty())
                        }
                    }
                }
                is PostTripAnalysisUiState.Loading -> Text("Analysing recorded driving data…", color = ReportNavy.copy(alpha = .66f), style = MaterialTheme.typography.bodyMedium)
                is PostTripAnalysisUiState.InsufficientData -> Text(state.reason, color = ReportNavy.copy(alpha = .66f), style = MaterialTheme.typography.bodyMedium)
                is PostTripAnalysisUiState.Failed -> Text(state.message, color = ReportRed, style = MaterialTheme.typography.bodyMedium)
                PostTripAnalysisUiState.Idle -> Text("Recorded driving data is unavailable.", color = ReportNavy.copy(alpha = .66f), style = MaterialTheme.typography.bodyMedium)
            }
            if (!reflectionText.isNullOrBlank()) {
                ReportDivider()
                Text("AI reflection conversation", color = ReportNavy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                ReportReflectionConversation(reflectionText)
            }
            if (includeRoute) {
                ReportDivider()
                Text("Recorded route", color = ReportNavy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Highlighted segments show where recorded driving events occurred.", color = ReportNavy.copy(alpha = .66f), style = MaterialTheme.typography.bodySmall)
                if (fragmentActivity != null) {
                    TripRouteMap(
                        fragmentActivity = fragmentActivity,
                        fragmentTag = "trip-report-route-${tripId ?: "preview"}",
                        routePoints = routePoints.map { TripRoutePoint(it.latitude, it.longitude, it.timestampMillis) },
                        eventTimes = reportEvents.map(PostTripReviewEvent::startTimestampMillis),
                        originPlace = originPlace,
                        destinationPlace = destinationPlace,
                        plannedRoutePoints = plannedRoutePoints,
                        eventRouteOverlays = reportEvents
                            .map { event ->
                                TripRouteEventOverlay(
                                    type = event.type,
                                    points = event.routePoints.map { GeographicPoint(it.latitude, it.longitude) }
                                )
                            },
                        modifier = Modifier.fillMaxWidth().height(210.dp),
                        contentKey = tripId,
                        onMapReady = onRouteMapReady
                    )
                } else {
                    Text("Route map is unavailable in this preview.", color = ReportNavy.copy(alpha = .66f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private suspend fun GoogleMap.createRouteMapAttachment(
    cacheDir: File,
    tripId: String
): TripReportShareAttachment? = suspendCancellableCoroutine { continuation ->
    snapshot { bitmap ->
        val attachment = bitmap?.let { snapshot ->
            val file = File(cacheDir, "nextcross-route-$tripId.jpg")
            val saved = runCatching {
                FileOutputStream(file).use { output ->
                    snapshot.compress(Bitmap.CompressFormat.JPEG, 80, output)
                }
                file.length() in 1..(2 * 1024 * 1024)
            }.getOrDefault(false)
            if (saved) {
                TripReportShareAttachment(
                    uriString = android.net.Uri.fromFile(file).toString(),
                    mimeType = "image/jpeg",
                    displayName = "nextcross-route-$tripId.jpg"
                )
            } else null
        }
        if (continuation.isActive) continuation.resume(attachment)
    }
}

private data class ReportReflectionParts(
    val question: String,
    val userResponse: String,
    val aiResponse: String
)

private fun parseReportReflection(text: String): ReportReflectionParts? {
    val normalized = text.replace("\r\n", "\n").trim()
    val questionHeader = "AI question\n"
    val userHeader = "\n\nYour response\n"
    val aiHeader = "\n\nAI response\n"
    if (!normalized.startsWith(questionHeader)) return null
    val userStart = normalized.indexOf(userHeader, startIndex = questionHeader.length)
    if (userStart < 0) return null
    val aiStart = normalized.indexOf(aiHeader, startIndex = userStart + userHeader.length)
    if (aiStart < 0) return null
    return ReportReflectionParts(
        question = normalized.substring(questionHeader.length, userStart).trim(),
        userResponse = normalized.substring(userStart + userHeader.length, aiStart).trim(),
        aiResponse = normalized.substring(aiStart + aiHeader.length).trim()
    )
}

@Composable
private fun ReportReflectionConversation(text: String) {
    val conversations = remember(text) {
        text.replace("\r\n", "\n").trim()
            .split("\n\nAI question\n")
            .filter { it.isNotBlank() }
            .mapIndexed { index, part ->
                parseReportReflection(if (index == 0) part else "AI question\n$part")
            }
    }
    if (conversations.isEmpty() || conversations.any { it == null }) {
        Text(text, color = ReportNavy, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        conversations.filterNotNull().forEachIndexed { index, parts ->
            if (index > 0) ReportDivider()
            ReportReflectionPart("AI question", parts.question, ReportAiAccent)
            ReportReflectionPart("Your response", parts.userResponse, ReportUserAccent)
            ReportReflectionPart("AI response", parts.aiResponse, ReportAiAccent)
        }
    }
}

@Composable
private fun ReportReflectionPart(label: String, body: String, accent: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = .08f)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(body, color = ReportNavy, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReportEventDetail(event: PostTripReviewEvent, includeRoute: Boolean, tags: List<String>) {
    val (label, accent) = when (event.type) {
        ReviewEventType.RAPID_DECELERATION -> "Hard braking" to ReportRed
        ReviewEventType.RAPID_ACCELERATION -> "Hard acceleration" to ReportOrange
        ReviewEventType.SPEED_LIMIT_EXCEEDANCE -> error("Speed events are excluded from reports")
    }
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = .08f)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = ReportNavy, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Recorded at ${reportEventTime(event.startTimestampMillis)}", color = ReportNavy.copy(alpha = .72f), style = MaterialTheme.typography.bodyMedium)
            Text("Duration ${String.format(Locale.getDefault(), "%.1f s", event.durationSeconds)}", color = ReportNavy.copy(alpha = .72f), style = MaterialTheme.typography.bodyMedium)
            if (tags.isNotEmpty()) {
                Text(
                    text = tags.joinToString("  ·  "),
                    color = accent,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                if (includeRoute) "This event's route segment is highlighted below."
                else "Route segment is not included in this report.",
                color = accent,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ReportIncludeOptions(
    includeRoute: Boolean,
    routeEnabled: Boolean,
    onRouteChanged: (Boolean) -> Unit,
    includeReflection: Boolean,
    reflectionAvailable: Boolean,
    onReflectionChanged: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Choose what to include", color = ReportNavy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("✓  Trip summary and driving events", color = ReportNavy, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("Always included", color = ReportNavy.copy(alpha = .62f), style = MaterialTheme.typography.bodySmall)
            }
            ReportDivider()
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = includeRoute,
                    enabled = routeEnabled,
                    onCheckedChange = onRouteChanged,
                    colors = CheckboxDefaults.colors(checkedColor = ReportBlue)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text("Detailed recorded route", color = ReportNavy.copy(alpha = if (routeEnabled) 1f else .45f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        if (routeEnabled) "Includes precise location information."
                        else "No recorded route is available for this trip.",
                        color = ReportNavy.copy(alpha = .62f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (reflectionAvailable) {
                ReportDivider()
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = includeReflection,
                        onCheckedChange = onReflectionChanged,
                        colors = CheckboxDefaults.colors(checkedColor = ReportBlue)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text("AI reflection conversation", color = ReportNavy, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("Includes your answer and AI feedback.", color = ReportNavy.copy(alpha = .62f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = ReportNavy.copy(alpha = .62f), style = MaterialTheme.typography.bodySmall)
        Text(value, color = ReportNavy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReportDivider() = Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ReportNavy.copy(alpha = .1f)))

private fun reportDateTime(startedAtMillis: Long?, endedAtMillis: Long?): String {
    if (startedAtMillis == null) return "Recorded time unavailable"
    val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(startedAtMillis))
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(startedAtMillis))
    val end = endedAtMillis?.let { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it)) }
    return "$date · $time${end?.let { "–$it" }.orEmpty()}"
}

private fun reportEventTime(timeMillis: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timeMillis))

private fun reportDuration(startedAtMillis: Long?, endedAtMillis: Long?): String {
    if (startedAtMillis == null || endedAtMillis == null || endedAtMillis < startedAtMillis) return "Unavailable"
    val minutes = (endedAtMillis - startedAtMillis) / 60_000L
    val seconds = ((endedAtMillis - startedAtMillis) / 1_000L) % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

private fun reportDistance(points: List<com.fit5120tp10.nextcross.client.posttrip.ReviewRoutePoint>?): String {
    if (points == null || points.size < 2) return "Unavailable"
    val metres = points.zipWithNext().sumOf { (from, to) -> reportDistanceMetres(from.latitude, from.longitude, to.latitude, to.longitude) }
    return String.format(Locale.getDefault(), "%.1f km", metres / 1_000.0)
}

private fun reportDistanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val latitudeDelta = Math.toRadians(lat2 - lat1)
    val longitudeDelta = Math.toRadians(lon2 - lon1)
    val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
    return 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
}
