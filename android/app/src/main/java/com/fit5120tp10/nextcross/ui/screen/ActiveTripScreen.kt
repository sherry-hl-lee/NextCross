package com.fit5120tp10.nextcross.ui.screen

import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import com.fit5120tp10.nextcross.client.navigation.TripNavigationDestination
import com.fit5120tp10.nextcross.client.navigation.TripNavigationLocator
import com.fit5120tp10.nextcross.client.navigation.TripNavigationRouteResult
import com.fit5120tp10.nextcross.client.navigation.TripNavigationState
import com.fit5120tp10.nextcross.client.network.InternetConnection
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace
import com.fit5120tp10.nextcross.client.speedlimit.SpeedLimitExceedanceSeverity
import com.fit5120tp10.nextcross.client.speedlimit.SpeedLimitWarningState
import com.fit5120tp10.nextcross.ui.theme.NextCrossTheme
import com.google.android.libraries.navigation.SupportNavigationFragment
import com.google.android.libraries.navigation.CustomControlPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

private val ActiveTripBackground: Color @Composable get() = MaterialTheme.colorScheme.background
private val ActiveTripNavy = Color(0xFF071A3A)
private val ActiveTripBlue = Color(0xFF1769E0)
private const val NavigationFragmentTag = "active_trip_navigation"
private const val OverspeedBannerDurationMillis = 1_000L

private data class OverspeedPalette(val background: Color, val foreground: Color)

private fun SpeedLimitExceedanceSeverity.palette(): OverspeedPalette = when (this) {
    SpeedLimitExceedanceSeverity.Low -> OverspeedPalette(Color(0xFFFFF1B8), ActiveTripNavy)
    SpeedLimitExceedanceSeverity.Moderate -> OverspeedPalette(Color(0xFFC58B1B), ActiveTripNavy)
    SpeedLimitExceedanceSeverity.High -> OverspeedPalette(Color(0xFFF28C28), ActiveTripNavy)
    SpeedLimitExceedanceSeverity.Severe -> OverspeedPalette(Color(0xFFB42318), Color.White)
    SpeedLimitExceedanceSeverity.Extreme -> OverspeedPalette(Color(0xFF741B36), Color.White)
}

@Composable
fun ActiveTripScreen(
    startingPoint: String,
    destination: String,
    destinationPlace: SelectedPlace?,
    recordingTripId: String?,
    fragmentActivity: FragmentActivity?,
    onCancelTrip: () -> Unit,
    onEndTrip: () -> Unit
) {
    var showEndTripConfirmation by remember { mutableStateOf(false) }
    var showReturnConfirmation by remember { mutableStateOf(false) }
    var navigationHostReady by remember { mutableStateOf(false) }
    var routeRequested by remember(destinationPlace) { mutableStateOf(false) }
    var navigationMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var internetAvailable by remember { mutableStateOf<Boolean?>(null) }
    var showOfflineBanner by remember { mutableStateOf(false) }
    LaunchedEffect(context.applicationContext) {
        InternetConnection.observe(context.applicationContext).collect { internetAvailable = it }
    }
    LaunchedEffect(internetAvailable) {
        if (internetAvailable == false) {
            delay(1_500)
            showOfflineBanner = true
        } else {
            showOfflineBanner = false
        }
    }
    val navigationClient = remember(context.applicationContext) {
        TripNavigationLocator.get(context.applicationContext)
    }
    val speedLimitState = rememberActiveTripSpeedLimitState(recordingTripId)
    val currentSpeedLimitState by speedLimitState
    val currentSeverity = currentSpeedLimitState.severity
    var previousSeverity by remember(recordingTripId) { mutableStateOf<SpeedLimitExceedanceSeverity?>(null) }
    var bannerSeverity by remember(recordingTripId) { mutableStateOf<SpeedLimitExceedanceSeverity?>(null) }
    var bannerVisible by remember(recordingTripId) { mutableStateOf(false) }

    LaunchedEffect(recordingTripId, currentSeverity) {
        val shouldShow = currentSeverity?.let { current ->
            previousSeverity?.let { current.ordinal > it.ordinal } ?: true
        } ?: false
        previousSeverity = currentSeverity
        bannerVisible = false
        if (shouldShow) {
            bannerSeverity = currentSeverity
            bannerVisible = true
            delay(OverspeedBannerDurationMillis)
            bannerVisible = false
        }
    }

    LaunchedEffect(navigationMessage) {
        val message = navigationMessage ?: return@LaunchedEffect
        delay(4_000)
        if (navigationMessage == message) {
            navigationMessage = null
        }
    }

    fun endTrip() {
        navigationClient.stopGuidance()
        onEndTrip()
    }

    fun cancelTrip() {
        navigationClient.stopGuidance()
        onCancelTrip()
    }

    BackHandler(enabled = !showEndTripConfirmation && !showReturnConfirmation) {
        showReturnConfirmation = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ActiveTripBackground)
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (showOfflineBanner) {
                OfflineNavigationBanner()
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (fragmentActivity != null && destinationPlace != null) {
                    NavigationSdkMap(
                        fragmentActivity = fragmentActivity,
                        onAttached = { navigationHostReady = true },
                        onReturnClick = { showReturnConfirmation = true },
                        onEndTripClick = { showEndTripConfirmation = true },
                        modifier = Modifier.fillMaxSize().navigationBarsPadding()
                    )

                    SpeedLimitIndicator(
                        state = currentSpeedLimitState,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 168.dp, end = 16.dp)
                            .width(132.dp)
                    )

                    bannerSeverity?.let { severity ->
                        ActiveTripOverspeedBanner(
                            severity = severity,
                            visible = bannerVisible,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                        )
                    }

                    DisposableEffect(navigationHostReady, destinationPlace, navigationClient) {
                        if (!navigationHostReady) {
                            onDispose { }
                        } else {
                            val navigationDestination = TripNavigationDestination.fromSelectedPlace(destinationPlace)
                            fun startGuidance() {
                                if (routeRequested) return
                                routeRequested = true
                                navigationClient.startGuidanceTo(navigationDestination) { result ->
                                    navigationMessage = when (result) {
                                        TripNavigationRouteResult.GuidanceStarted -> null
                                        is TripNavigationRouteResult.Failed ->
                                            "Navigation is unavailable right now. Your trip is still being recorded."
                                    }
                                }
                            }

                            navigationClient.prepare(fragmentActivity) { state, _ ->
                                when (state) {
                                    TripNavigationState.Ready -> startGuidance()
                                    TripNavigationState.Failed -> {
                                        navigationMessage = "Navigation is unavailable right now. Your trip is still being recorded."
                                    }
                                    else -> Unit
                                }
                            }

                            onDispose {
                                navigationClient.release()
                            }
                        }
                    }
                } else {
                    NavigationUnavailableState(
                        startingPoint = startingPoint,
                        destination = destination,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                navigationMessage?.let { message ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .clickable { navigationMessage = null },
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.96f),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = ActiveTripNavy
                        )
                    }
                }
            }
        }

        if (showEndTripConfirmation) {
            EndTripConfirmationDialog(
                onDismiss = { showEndTripConfirmation = false },
                onConfirmEndTrip = {
                    showEndTripConfirmation = false
                    endTrip()
                }
            )
        }

        if (showReturnConfirmation) {
            ReturnToPreTripConfirmationDialog(
                onDismiss = { showReturnConfirmation = false },
                onConfirmReturn = {
                    showReturnConfirmation = false
                    cancelTrip()
                }
            )
        }
    }
}

@Composable
private fun ReturnToPreTripConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirmReturn: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "End this trip?") },
        text = { Text(text = "This will stop recording and discard this unfinished trip.") },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "Continue trip") }
        },
        confirmButton = {
            TextButton(onClick = onConfirmReturn) {
                Text(text = "End and return")
            }
        }
    )
}

@Composable
private fun ActiveTripOverspeedBanner(
    severity: SpeedLimitExceedanceSeverity,
    visible: Boolean,
    modifier: Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 }
    ) {
        OverspeedBanner(severity = severity, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OfflineNavigationBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Text(
            text = "No internet connection. Navigation updates may be unavailable.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun NavigationSdkMap(
    fragmentActivity: FragmentActivity,
    onAttached: () -> Unit,
    onReturnClick: () -> Unit,
    onEndTripClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerId = remember { View.generateViewId() }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            FrameLayout(viewContext).apply {
                addView(
                    FragmentContainerView(viewContext).apply { id = containerId },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                // The Navigation SDK does not consistently expose its own return control, so use
                // the same visible control as the pre-trip map and keep the confirmation here.
                addView(
                    ComposeView(viewContext).apply {
                        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                        setContent {
                            NextCrossTheme {
                                Surface(
                                    modifier = Modifier.size(44.dp),
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.78f),
                                    shadowElevation = 4.dp
                                ) {
                                    IconButton(
                                        onClick = onReturnClick,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        BackNavigationIcon(Color.Black)
                                    }
                                }
                            }
                        }
                        elevation = 20f
                    },
                    FrameLayout.LayoutParams(44.dpToPixels(viewContext), 44.dpToPixels(viewContext)).apply {
                        gravity = Gravity.TOP or Gravity.START
                        leftMargin = 16.dpToPixels(viewContext)
                        topMargin = 160.dpToPixels(viewContext)
                    }
                )
            }
        }
    )

    DisposableEffect(fragmentActivity, containerId) {
        val fragmentManager = fragmentActivity.supportFragmentManager
        val navigationFragment =
            fragmentManager.findFragmentByTag(NavigationFragmentTag) as? SupportNavigationFragment
                ?: SupportNavigationFragment.newInstance().also { fragment ->
                    if (!fragmentManager.isStateSaved) {
                        fragmentManager.beginTransaction()
                            .replace(containerId, fragment, NavigationFragmentTag)
                            .commitNow()
                    }
                }

        val recordingFooterControl = ComposeView(fragmentActivity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NextCrossTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TripRecordingFooter(
                            onEndTripClick = onEndTripClick,
                            modifier = Modifier.widthIn(max = 320.dp)
                        )
                    }
                }
            }
        }

        navigationFragment.setCustomControl(
            recordingFooterControl,
            CustomControlPosition.FOOTER
        )

        onAttached()

        onDispose {
            navigationFragment.setCustomControl(null, CustomControlPosition.FOOTER)
            if (!fragmentManager.isStateSaved && !fragmentManager.isDestroyed) {
                fragmentManager.findFragmentByTag(NavigationFragmentTag)?.let { fragment ->
                    // Active Trip can leave while Android is processing an Activity resume transaction.
                    // Queue removal instead of mutating the FragmentManager synchronously in onDispose.
                    fragmentManager.beginTransaction().remove(fragment).commit()
                }
            }
        }
    }
}

private fun Int.dpToPixels(context: android.content.Context): Int {
    return (this * context.resources.displayMetrics.density).toInt()
}

@Composable
private fun NavigationUnavailableState(
    startingPoint: String,
    destination: String,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, color = ActiveTripBackground) {
        Text(
            text = "Navigation needs a selected destination.\n${startingPoint.ifBlank { "Starting point" }} → ${destination.ifBlank { "Destination" }}",
            modifier = Modifier.padding(24.dp),
            color = ActiveTripNavy
        )
    }
}

@Composable
fun TripRecordingFooter(
    onEndTripClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.96f),
        shadowElevation = 5.dp,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = ActiveTripBlue
                ) {}
                Spacer(modifier = Modifier.size(10.dp))
                Text(text = "Recording", color = ActiveTripNavy)
            }
            Button(
                onClick = onEndTripClick,
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActiveTripNavy,
                    contentColor = Color.White
                )
            ) {
                Text(text = "End Trip")
            }
        }
    }
}

@Composable
private fun SpeedLimitIndicator(
    state: ActiveTripSpeedLimitUiState,
    modifier: Modifier = Modifier
) {
    val palette = state.severity
        ?.takeIf { state.warningState == SpeedLimitWarningState.Active }
        ?.palette()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.96f),
        border = palette?.let { BorderStroke(2.dp, it.background) },
        shadowElevation = 5.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.currentSpeedKmh?.toString() ?: "—",
                        color = ActiveTripNavy,
                        fontSize = if (palette != null) 26.sp else 22.sp,
                        fontWeight = if (palette != null) FontWeight.Bold else FontWeight.SemiBold
                    )
                    Text(
                        text = "km/h",
                        color = ActiveTripNavy.copy(alpha = 0.64f)
                    )
                }
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(21.dp),
                    color = palette?.background ?: Color(0xFFF4F7FE),
                    border = BorderStroke(
                        2.dp,
                        if (palette != null) ActiveTripNavy else ActiveTripNavy.copy(alpha = 0.42f)
                    )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = state.limitKmh?.toString() ?: "—",
                            color = palette?.foreground ?: ActiveTripNavy,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            if (!state.isLimitAvailable) {
                Text(
                    text = if (state.currentSpeedKmh == null) "Waiting for GPS" else "Limit unavailable",
                    color = ActiveTripNavy.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun OverspeedBanner(
    severity: SpeedLimitExceedanceSeverity,
    modifier: Modifier = Modifier
) {
    val palette = severity.palette()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = palette.background,
        shadowElevation = 5.dp
    ) {
        Text(
            text = "You are over the speed limit",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = palette.foreground,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun EndTripConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirmEndTrip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "End this trip?") },
        text = { Text(text = "This will stop recording and create your trip report.") },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "Continue trip") }
        },
        confirmButton = {
            TextButton(onClick = onConfirmEndTrip) {
                Text(text = "End trip")
            }
        }
    )
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun TripRecordingFooterPreview() {
    NextCrossTheme {
        TripRecordingFooter(onEndTripClick = {})
    }
}
