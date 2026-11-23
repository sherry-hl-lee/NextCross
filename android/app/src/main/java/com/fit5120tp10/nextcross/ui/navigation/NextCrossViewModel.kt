package com.fit5120tp10.nextcross.ui.navigation

import androidx.lifecycle.ViewModel
import com.fit5120tp10.nextcross.client.historicalcrash.CrashTimePeriod
import com.fit5120tp10.nextcross.client.permittedarea.PermittedArea
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace
import com.fit5120tp10.nextcross.client.trip.TripSession
import com.fit5120tp10.nextcross.client.tripreport.TripReportHistoryItem
import com.fit5120tp10.nextcross.ui.screen.LocationPermissionUiState
import com.fit5120tp10.nextcross.ui.screen.CrashCountFilter
import com.fit5120tp10.nextcross.ui.screen.DefaultCrashCountFilters
import com.fit5120tp10.nextcross.ui.screen.TripMapCamera
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NextCrossUiState(
    val showSplash: Boolean = true,
    val defaultRecipientEmail: String = "",
    val recipientEmails: List<String> = emptyList(),
    val selectedHistoryReport: TripReportHistoryItem? = null,
    val completedTripSession: TripSession? = null,
    val currentAiReflections: Map<String, Map<String, String>> = emptyMap(),
    val informedConsentAccepted: Boolean = false,
    val locationPermissionUiState: LocationPermissionUiState = LocationPermissionUiState.Required,
    val isFineLocationGranted: Boolean = false,
    val startTripAfterPermission: Boolean = false,
    val preTripTimeOfDay: CrashTimePeriod = CrashTimePeriod.Morning,
    val preTripCrashCountFilters: Set<CrashCountFilter> = DefaultCrashCountFilters,
    val reviewStartingPoint: String = "",
    val reviewDestination: String = "",
    val reviewOriginPlace: SelectedPlace? = null,
    val reviewDestinationPlace: SelectedPlace? = null,
    val reviewPermittedArea: PermittedArea? = null,
    val reviewMapCamera: TripMapCamera? = null,
    val reviewParentEmail: String = "",
    val setupIntegrationError: String? = null,
    val startTripError: String? = null,
    val plannedTripId: String = UUID.randomUUID().toString()
)

class NextCrossViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(NextCrossUiState())
    val uiState: StateFlow<NextCrossUiState> = mutableUiState.asStateFlow()

    fun update(transform: (NextCrossUiState) -> NextCrossUiState) {
        mutableUiState.update(transform)
    }

    fun finishSplash() = update { it.copy(showSplash = false) }

    fun setHomePreferences(defaultEmail: String, emails: List<String>) = update {
        it.copy(defaultRecipientEmail = defaultEmail, recipientEmails = emails)
    }

    fun setFineLocationGranted(granted: Boolean) = update {
        it.copy(
            isFineLocationGranted = granted,
            locationPermissionUiState = if (granted) LocationPermissionUiState.Required else it.locationPermissionUiState
        )
    }

    fun onLocationPermissionResult(granted: Boolean, shouldShowRationale: Boolean) = update {
        it.copy(
            isFineLocationGranted = granted,
            locationPermissionUiState = when {
                granted -> LocationPermissionUiState.Required
                shouldShowRationale -> LocationPermissionUiState.TryAgain
                else -> LocationPermissionUiState.OpenSettings
            }
        )
    }

    fun requestTripStartAfterPermission() = update { it.copy(startTripAfterPermission = true) }

    fun consumePermissionStartRequest() = update { it.copy(startTripAfterPermission = false) }

    fun selectHistoryReport(report: TripReportHistoryItem?) = update { it.copy(selectedHistoryReport = report) }

    fun setInformedConsentAccepted(accepted: Boolean) = update {
        it.copy(informedConsentAccepted = accepted)
    }

    fun setPreTripHistoricalFilters(
        timeOfDay: CrashTimePeriod,
        crashCountFilters: Set<CrashCountFilter>
    ) = update {
        it.copy(
            preTripTimeOfDay = timeOfDay,
            preTripCrashCountFilters = crashCountFilters
        )
    }

    fun resetForNewTrip() = update {
        it.copy(
            selectedHistoryReport = null,
            completedTripSession = null,
            currentAiReflections = emptyMap(),
            informedConsentAccepted = false,
            preTripTimeOfDay = CrashTimePeriod.Morning,
            preTripCrashCountFilters = DefaultCrashCountFilters,
            reviewStartingPoint = "",
            reviewDestination = "",
            reviewOriginPlace = null,
            reviewDestinationPlace = null,
            reviewPermittedArea = null,
            reviewMapCamera = null,
            reviewParentEmail = "",
            setupIntegrationError = null,
            startTripError = null,
            plannedTripId = UUID.randomUUID().toString()
        )
    }
}
