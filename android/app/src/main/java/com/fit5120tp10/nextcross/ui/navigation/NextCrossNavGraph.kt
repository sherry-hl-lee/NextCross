package com.fit5120tp10.nextcross.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.fit5120tp10.nextcross.client.permittedarea.ParentNotificationContact
import com.fit5120tp10.nextcross.client.permittedarea.places.TripPlaceSelection
import com.fit5120tp10.nextcross.client.permittedarea.places.TripPlaceSelectionResult
import com.fit5120tp10.nextcross.client.permittedarea.runtime.ClientBTripRuntimeLocator
import com.fit5120tp10.nextcross.client.permittedarea.runtime.ClientBTripStartResult
import com.fit5120tp10.nextcross.client.recording.RecordingLocator
import com.fit5120tp10.nextcross.client.trip.TripCommandResult
import com.fit5120tp10.nextcross.client.tripreport.SaveCompletedTripReportRequest
import com.fit5120tp10.nextcross.client.tripreport.TripReportLocator
import com.fit5120tp10.nextcross.ui.screen.ActiveTripScreen
import com.fit5120tp10.nextcross.ui.screen.HomeScreen
import com.fit5120tp10.nextcross.ui.screen.LocationPermissionUiState
import com.fit5120tp10.nextcross.ui.screen.NextCrossSplashScreen
import com.fit5120tp10.nextcross.ui.screen.PreTripMapScreen
import com.fit5120tp10.nextcross.ui.screen.ReportHistoryScreen
import com.fit5120tp10.nextcross.ui.screen.ReviewTripDetailsScreen
import com.fit5120tp10.nextcross.ui.screen.TripReportScreen
import com.fit5120tp10.nextcross.ui.screen.TripSetupScreen
import com.fit5120tp10.nextcross.ui.screen.TripSummaryScreen
import com.fit5120tp10.nextcross.ui.onboarding.rememberOnboardingGuideController
import kotlinx.coroutines.delay

@Composable
fun NextCrossNavGraph(
    navController: NavHostController,
    viewModel: NextCrossViewModel,
    appContext: Context,
    fragmentActivity: FragmentActivity?,
    homePreferences: android.content.SharedPreferences,
    requestFineLocationPermission: () -> Unit,
    openAppLocationSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val runtime = remember(appContext) { ClientBTripRuntimeLocator.get(appContext) }
    val onboardingGuides = rememberOnboardingGuideController(appContext)

    fun startRecordingWhenPermitted() {
        viewModel.update { it.copy(startTripError = null) }
        try {
            when (val result = runtime.startTrip(state.plannedTripId)) {
                is ClientBTripStartResult.Started -> {
                    viewModel.update { it.copy(completedTripSession = result.recordingSession) }
                    navController.navigate(NextCrossDestination.ActiveTrip) {
                        popUpTo(NextCrossDestination.PreTripMap) { inclusive = false }
                    }
                }
                ClientBTripStartResult.MissingPlannedTripConfiguration -> viewModel.update {
                    it.copy(startTripError = "Trip setup is unavailable. Review the selected places first.")
                }
                ClientBTripStartResult.ClientBRuntimeAlreadyActive -> viewModel.update {
                    it.copy(startTripError = "A trip is already active.")
                }
                is ClientBTripStartResult.LocationUnavailable -> viewModel.update {
                    it.copy(startTripError = "Location is unavailable. Check precise location and GPS, then try again.")
                }
                is ClientBTripStartResult.RecordingRejected -> viewModel.update {
                    it.copy(startTripError = "Recording could not be started. Please try again.")
                }
            }
        } catch (_: Exception) {
            viewModel.update { it.copy(startTripError = "Trip could not be started. Please try again.") }
        }
    }

    LaunchedEffect(state.startTripAfterPermission, state.isFineLocationGranted) {
        if (state.startTripAfterPermission && state.isFineLocationGranted) {
            viewModel.consumePermissionStartRequest()
            startRecordingWhenPermitted()
        }
    }

    NavHost(navController = navController, startDestination = NextCrossDestination.Splash) {
        composable(NextCrossDestination.Splash) {
            LaunchedEffect(Unit) {
                delay(1500)
                viewModel.finishSplash()
                navController.navigate(NextCrossDestination.Home) {
                    popUpTo(NextCrossDestination.Splash) { inclusive = true }
                }
            }
            NextCrossSplashScreen()
        }
        composable(NextCrossDestination.Home) {
            HomeScreen(
                onStartNewTrip = {
                    viewModel.resetForNewTrip()
                    navController.navigate(NextCrossDestination.TripSetup)
                },
                onViewHistory = { navController.navigate(NextCrossDestination.ReportHistory) },
                onOpenLatestReport = { report ->
                    viewModel.selectHistoryReport(report)
                    navController.navigate(NextCrossDestination.tripReport(report.tripId))
                },
                defaultRecipientEmail = state.defaultRecipientEmail,
                recipientEmails = state.recipientEmails,
                onboardingGuides = onboardingGuides,
                onSaveRecipientEmails = { emails, defaultEmail ->
                    viewModel.setHomePreferences(defaultEmail, emails)
                    homePreferences.edit().putString("common_recipient_emails", emails.joinToString("\n"))
                        .putString("default_recipient_email", defaultEmail).apply()
                }
            )
        }
        composable(NextCrossDestination.ReportHistory) {
            ReportHistoryScreen(
                onBackClick = { navController.popBackStack() },
                onOpenReport = { report ->
                    viewModel.selectHistoryReport(report)
                    navController.navigate(NextCrossDestination.tripReport(report.tripId))
                }
            )
        }
        composable(
            route = NextCrossDestination.TripReportPattern,
            arguments = listOf(navArgument(NextCrossDestination.TripReportArgument) { type = NavType.StringType })
        ) { entry ->
            val tripId = entry.arguments?.getString(NextCrossDestination.TripReportArgument)
            val historyReport = state.selectedHistoryReport?.takeIf { it.tripId == tripId }
            TripReportScreen(
                startingPoint = historyReport?.startingPointLabel ?: state.reviewStartingPoint,
                destination = historyReport?.destinationLabel ?: state.reviewDestination,
                tripId = historyReport?.tripId ?: state.completedTripSession?.tripId,
                originPlace = if (historyReport == null) state.reviewOriginPlace else null,
                destinationPlace = if (historyReport == null) state.reviewDestinationPlace else null,
                startedAtMillis = historyReport?.startedAtMillis ?: state.completedTripSession?.startedAtMillis,
                endedAtMillis = historyReport?.endedAtMillis ?: state.completedTripSession?.endedAtMillis,
                parentEmail = historyReport?.parentEmail ?: state.reviewParentEmail,
                reflectionTexts = state.currentAiReflections[historyReport?.tripId ?: state.completedTripSession?.tripId].orEmpty(),
                fragmentActivity = fragmentActivity,
                onboardingGuides = onboardingGuides,
                onBackClick = { navController.popBackStack() },
                onDone = { navController.popBackStack() }
            )
        }
        composable(NextCrossDestination.TripSummary) {
            fun returnToHome() {
                viewModel.resetForNewTrip()
                navController.navigate(NextCrossDestination.Home) {
                    popUpTo(NextCrossDestination.Home) { inclusive = false }
                    launchSingleTop = true
                }
            }

            TripSummaryScreen(
                startingPoint = state.reviewStartingPoint,
                destination = state.reviewDestination,
                tripId = state.completedTripSession?.tripId,
                originPlace = state.reviewOriginPlace,
                destinationPlace = state.reviewDestinationPlace,
                startedAtMillis = state.completedTripSession?.startedAtMillis,
                endedAtMillis = state.completedTripSession?.endedAtMillis,
                onReviewReport = {
                    val tripId = state.completedTripSession?.tripId ?: return@TripSummaryScreen
                    viewModel.selectHistoryReport(null)
                    navController.navigate(NextCrossDestination.tripReport(tripId))
                },
                reflectionTexts = state.currentAiReflections[state.completedTripSession?.tripId].orEmpty(),
                onboardingGuides = onboardingGuides,
                onAiConversationComplete = { tripId, eventId, text ->
                    viewModel.update { current ->
                        current.copy(currentAiReflections = current.currentAiReflections + (
                            tripId to (current.currentAiReflections[tripId].orEmpty() + (eventId to text))
                        ))
                    }
                    RecordingLocator.io.execute {
                        runCatching {
                            TripReportLocator.get(appContext).saveAiReflection(tripId, eventId, text)
                        }
                    }
                },
                showAiReflection = true,
                onBackClick = ::returnToHome,
                onDone = ::returnToHome
            )
        }
        composable(NextCrossDestination.ActiveTrip) {
            ActiveTripScreen(
                startingPoint = state.reviewStartingPoint,
                destination = state.reviewDestination,
                destinationPlace = state.reviewDestinationPlace,
                recordingTripId = state.completedTripSession?.tripId,
                fragmentActivity = fragmentActivity,
                onCancelTrip = {
                    when (runtime.cancelTrip()) {
                        is TripCommandResult.Success -> {
                            viewModel.update { it.copy(completedTripSession = null, startTripError = null) }
                            navController.popBackStack()
                        }
                        is TripCommandResult.Rejected -> Unit
                    }
                },
                onEndTrip = {
                    when (val result = runtime.endTrip()) {
                        is TripCommandResult.Success -> {
                            viewModel.update { it.copy(completedTripSession = result.session) }
                            result.session.endedAtMillis?.let { endedAt ->
                                RecordingLocator.io.execute {
                                    runCatching {
                                        TripReportLocator.get(appContext).saveCompletedTripReport(
                                            SaveCompletedTripReportRequest(
                                                recordingTripId = result.session.tripId,
                                                plannedTripId = state.plannedTripId,
                                                startedAtMillis = result.session.startedAtMillis,
                                                endedAtMillis = endedAt,
                                                startingPointLabel = state.reviewStartingPoint,
                                                destinationLabel = state.reviewDestination,
                                                parentEmail = state.reviewParentEmail
                                            )
                                        )
                                    }
                                }
                            }
                            navController.navigate(NextCrossDestination.TripSummary) {
                                // A completed trip must not return to its active or setup screens.
                                popUpTo(NextCrossDestination.Home) { inclusive = false }
                            }
                        }
                        is TripCommandResult.Rejected -> Unit
                    }
                }
            )
        }
        composable(NextCrossDestination.PreTripMap) {
            PreTripMapScreen(
                startingPoint = state.reviewStartingPoint,
                destination = state.reviewDestination,
                originPlace = state.reviewOriginPlace,
                destinationPlace = state.reviewDestinationPlace,
                permittedArea = state.reviewPermittedArea,
                initialCamera = state.reviewMapCamera,
                fragmentActivity = fragmentActivity,
                plannedTripId = state.plannedTripId,
                onBackClick = { navController.popBackStack() },
                permissionUiState = state.locationPermissionUiState,
                isFineLocationGranted = state.isFineLocationGranted,
                startTripError = state.startTripError,
                initialTimeOfDay = state.preTripTimeOfDay,
                initialCrashCountFilters = state.preTripCrashCountFilters,
                onHistoricalFiltersChanged = viewModel::setPreTripHistoricalFilters,
                onStartTrip = {
                    if (state.isFineLocationGranted) {
                        startRecordingWhenPermitted()
                        true
                    } else {
                        viewModel.requestTripStartAfterPermission()
                        false
                    }
                },
                onRequestLocationPermission = {
                    viewModel.requestTripStartAfterPermission()
                    if (state.isFineLocationGranted) startRecordingWhenPermitted()
                    else if (state.locationPermissionUiState == LocationPermissionUiState.OpenSettings) {
                        openAppLocationSettings()
                    }
                    else requestFineLocationPermission()
                }
            )
        }
        composable(NextCrossDestination.ReviewTripDetails) {
            ReviewTripDetailsScreen(
                startingPoint = state.reviewStartingPoint,
                destination = state.reviewDestination,
                parentEmail = state.reviewParentEmail,
                originPlace = state.reviewOriginPlace,
                destinationPlace = state.reviewDestinationPlace,
                permittedArea = state.reviewPermittedArea,
                fragmentActivity = fragmentActivity,
                initialCamera = state.reviewMapCamera,
                isConsentAccepted = state.informedConsentAccepted,
                onConsentAcceptedChange = viewModel::setInformedConsentAccepted,
                onBackClick = { navController.popBackStack() },
                onContinue = { navController.navigate(NextCrossDestination.PreTripMap) }
            )
        }
        composable(NextCrossDestination.TripSetup) {
            TripSetupScreen(
                onBackClick = { navController.popBackStack() },
                integrationError = state.setupIntegrationError,
                defaultParentEmail = state.defaultRecipientEmail,
                savedParentEmails = state.recipientEmails,
                initialPlaceSelections = TripPlaceSelection(
                    origin = state.reviewOriginPlace,
                    destination = state.reviewDestinationPlace
                ),
                initialStartingPoint = state.reviewStartingPoint,
                initialDestination = state.reviewDestination,
                initialRadiusKilometres = state.reviewPermittedArea?.radiusKilometres?.toFloat(),
                initialParentEmail = state.reviewParentEmail.ifBlank { state.defaultRecipientEmail },
                onboardingGuides = onboardingGuides,
                onTripAreaCameraChanged = { camera -> viewModel.update { it.copy(reviewMapCamera = camera) } },
                onReviewTripDetails = { selections, parentEmail, selectedRadiusKilometres ->
                    when (val result = selections.createConfiguration(
                        tripId = state.plannedTripId,
                        parentContact = ParentNotificationContact(parentEmail.trim())
                    )) {
                        is TripPlaceSelectionResult.Ready -> {
                            try {
                                val configuration = result.value.copy(
                                    permittedArea = result.value.permittedArea.copy(
                                        radiusKilometres = selectedRadiusKilometres?.takeIf { it.isFinite() && it > 0.0 }
                                            ?: result.value.permittedArea.radiusKilometres
                                    )
                                )
                                runtime.saveReviewedTrip(configuration)
                                runtime.selectCurrentPlannedTrip(configuration.tripId)
                                viewModel.update {
                                    it.copy(
                                        reviewStartingPoint = selections.origin!!.displayName,
                                        reviewDestination = selections.destination!!.displayName,
                                        reviewOriginPlace = selections.origin,
                                        reviewDestinationPlace = selections.destination,
                                        reviewPermittedArea = configuration.permittedArea,
                                        reviewParentEmail = configuration.parentContact.email,
                                        setupIntegrationError = null
                                    )
                                }
                                navController.navigate(NextCrossDestination.ReviewTripDetails)
                            } catch (_: Exception) {
                                viewModel.update {
                                    it.copy(setupIntegrationError = "Trip setup could not be saved. Please try again.")
                                }
                            }
                        }
                        is TripPlaceSelectionResult.NotReady -> viewModel.update {
                            it.copy(setupIntegrationError = "Select real Places results for both starting point and destination.")
                        }
                    }
                }
            )
        }
    }
}
