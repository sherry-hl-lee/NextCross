package com.fit5120tp10.nextcross.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.fit5120tp10.nextcross.client.permittedarea.location.AndroidSetupLocationProvider
import com.fit5120tp10.nextcross.client.permittedarea.location.CurrentLocationAddressResolver
import com.fit5120tp10.nextcross.client.permittedarea.location.SetupLocationRequest
import com.fit5120tp10.nextcross.client.permittedarea.location.SetupLocationResult
import com.fit5120tp10.nextcross.client.permittedarea.HaversineDistanceCalculator
import com.fit5120tp10.nextcross.client.permittedarea.PermittedArea
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.fit5120tp10.nextcross.client.permittedarea.places.ClientBPlaces
import com.fit5120tp10.nextcross.client.permittedarea.places.PlaceAutocompleteSession
import com.fit5120tp10.nextcross.client.permittedarea.places.PlaceSuggestion
import com.fit5120tp10.nextcross.client.permittedarea.places.PlacesResult
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace
import com.fit5120tp10.nextcross.client.permittedarea.places.TripEndpoint
import com.fit5120tp10.nextcross.client.permittedarea.places.TripPlaceSelection
import com.fit5120tp10.nextcross.client.permittedarea.places.TripPlaceSelectionResult
import com.fit5120tp10.nextcross.ui.theme.NextCrossTheme
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuide
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuideController
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuideOverlay
import com.fit5120tp10.nextcross.ui.onboarding.CoachMarkMessagePlacement
import com.fit5120tp10.nextcross.ui.validation.validateTripSetup
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import java.util.Locale

private val NextCrossBackground: Color @Composable get() = MaterialTheme.colorScheme.background
private val NextCrossNavy: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val NextCrossBlue = Color(0xFF0D6DB8)
private val NextCrossPaleBlue = Color(0xFFDCE9FF)
private val PreviewPlaceholderBackground = Color(0xFFEAF3EC)
private val PreviewPlaceholderForeground = Color(0xFF334155)
private const val MinimumPermittedAreaRadiusKilometres = 1.0
private const val MaximumPermittedAreaRadiusKilometres = 10.0

private enum class LocationField {
    STARTING_POINT,
    DESTINATION
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TripSetupScreen(
    onBackClick: () -> Unit = {},
    integrationError: String? = null,
    defaultParentEmail: String = "",
    savedParentEmails: List<String> = emptyList(),
    initialPlaceSelections: TripPlaceSelection = TripPlaceSelection(),
    initialStartingPoint: String = "",
    initialDestination: String = "",
    initialRadiusKilometres: Float? = null,
    initialParentEmail: String = defaultParentEmail,
    onboardingGuides: OnboardingGuideController? = null,
    onReviewTripDetails: (TripPlaceSelection, String, Double?) -> Unit = { _, _, _ -> },
    onTripAreaCameraChanged: (TripMapCamera) -> Unit = {}
) {
    val context = LocalContext.current
    var startingPointText by rememberSaveable(initialStartingPoint) { mutableStateOf(initialStartingPoint) }
    var destinationText by rememberSaveable(initialDestination) { mutableStateOf(initialDestination) }
    var placeSelections by remember(initialPlaceSelections) { mutableStateOf(initialPlaceSelections) }
    var selectedRadiusKilometres by rememberSaveable(initialRadiusKilometres) {
        mutableStateOf(initialRadiusKilometres)
    }
    var email by rememberSaveable(initialParentEmail) { mutableStateOf(initialParentEmail) }
    var hasAttemptedReview by rememberSaveable { mutableStateOf(false) }
    var currentLocationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var currentLocationRequest by remember { mutableStateOf<SetupLocationRequest?>(null) }
    var currentLocationAddressJob by remember { mutableStateOf<Job?>(null) }
    var currentLocationLookupGeneration by remember { mutableStateOf(0) }
    var activeLocationField by remember { mutableStateOf<LocationField?>(null) }
    var startingSuggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var destinationSuggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var placesError by remember { mutableStateOf<String?>(null) }
    var startingSession by remember { mutableStateOf<PlaceAutocompleteSession?>(null) }
    var destinationSession by remember { mutableStateOf<PlaceAutocompleteSession?>(null) }
    var locationsCardBounds by remember { mutableStateOf<Rect?>(null) }
    var permittedAreaBounds by remember { mutableStateOf<Rect?>(null) }
    val setupLocationProvider = remember(context.applicationContext) {
        AndroidSetupLocationProvider(context.applicationContext)
    }
    val currentLocationAddressResolver = remember(context.applicationContext) {
        CurrentLocationAddressResolver(context.applicationContext)
    }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            startingSession?.close()
            destinationSession?.close()
            currentLocationLookupGeneration += 1
            currentLocationRequest?.cancel()
            currentLocationAddressJob?.cancel()
        }
    }

    fun cancelCurrentLocationLookup() {
        currentLocationLookupGeneration += 1
        currentLocationRequest?.cancel()
        currentLocationRequest = null
        currentLocationAddressJob?.cancel()
        currentLocationAddressJob = null
    }

    fun closeSession(field: LocationField) {
        when (field) {
            LocationField.STARTING_POINT -> {
                startingSession?.close()
                startingSession = null
                startingSuggestions = emptyList()
            }
            LocationField.DESTINATION -> {
                destinationSession?.close()
                destinationSession = null
                destinationSuggestions = emptyList()
            }
        }
    }

    fun startSession(field: LocationField) {
        if (activeLocationField != field) {
            activeLocationField?.let(::closeSession)
        }
        activeLocationField = field
        if ((if (field == LocationField.STARTING_POINT) startingSession else destinationSession) != null) return
        when (val result = ClientBPlaces.get(context.applicationContext)) {
            is PlacesResult.Success -> {
                if (field == LocationField.STARTING_POINT) startingSession = result.value.newSession()
                else destinationSession = result.value.newSession()
            }
            is PlacesResult.Failure -> placesError = "Places is unavailable. Check the app configuration and try again."
        }
    }

    fun hideSuggestions() {
        activeLocationField = null
    }

    fun captureCurrentLocation() {
        if (currentLocationRequest != null || currentLocationAddressJob != null) return

        val lookupGeneration = currentLocationLookupGeneration + 1
        currentLocationLookupGeneration = lookupGeneration
        placeSelections = placeSelections.clear(TripEndpoint.ORIGIN)
        startingPointText = "Current location"
        currentLocationMessage = "Getting current location…"
        closeSession(LocationField.STARTING_POINT)
        hideSuggestions()
        var completedBeforeRequestReturned = false
        val request = setupLocationProvider.captureCurrentLocation locationResult@{ result ->
            completedBeforeRequestReturned = true
            if (lookupGeneration != currentLocationLookupGeneration) return@locationResult
            currentLocationRequest = null
            when (result) {
                is SetupLocationResult.Success -> {
                    currentLocationMessage = "Finding current address…"
                    currentLocationAddressJob = coroutineScope.launch {
                        val address = currentLocationAddressResolver.resolve(result.point)
                        if (lookupGeneration != currentLocationLookupGeneration) return@launch
                        currentLocationAddressJob = null
                        if (address == null) {
                            startingPointText = ""
                            activeLocationField = LocationField.STARTING_POINT
                            currentLocationMessage = "Address unavailable. Check your connection and tap Use current location to retry."
                        } else {
                            val selected = SelectedPlace.currentLocation(result.point, address)
                            placeSelections = placeSelections.select(TripEndpoint.ORIGIN, selected)
                            startingPointText = selected.displayName
                            currentLocationMessage = null
                        }
                    }
                }
                is SetupLocationResult.Unavailable -> {
                    placeSelections = placeSelections.clear(TripEndpoint.ORIGIN)
                    startingPointText = ""
                    activeLocationField = LocationField.STARTING_POINT
                    currentLocationMessage = "Current location is unavailable. Check Location is on and try again."
                }
            }
        }
        if (!completedBeforeRequestReturned) currentLocationRequest = request
    }

    val currentLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            captureCurrentLocation()
        } else {
            currentLocationMessage = "Location permission is required to use current location."
        }
    }

    val sameEndpoints = placeSelections.origin?.let { origin ->
        placeSelections.destination?.let(origin::isSameEndpointAs)
    } == true
    val validationErrors = if (hasAttemptedReview) {
        validateTripSetup(
            startingPoint = startingPointText,
            startingPointSelected = placeSelections.origin != null,
            destination = destinationText,
            destinationSelected = placeSelections.destination != null,
            sameEndpoints = sameEndpoints,
            email = email
        )
    } else {
        null
    }

    val calculatedPermittedArea = remember(placeSelections.origin, placeSelections.destination) {
        when (val result = placeSelections.calculatePermittedArea()) {
            is TripPlaceSelectionResult.Ready -> result.value
            is TripPlaceSelectionResult.NotReady -> null
        }
    }

    LaunchedEffect(calculatedPermittedArea) {
        if (selectedRadiusKilometres == null) {
            selectedRadiusKilometres = calculatedPermittedArea?.radiusKilometres?.toFloat()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = NextCrossBackground,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        BackNavigationIcon(NextCrossNavy)
                    }
                },
                title = {
                    Text(
                        text = "Trip Setup",
                        color = NextCrossNavy,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NextCrossBackground
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(NextCrossBackground)
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                TripLocationsCard(
                    modifier = Modifier.onGloballyPositioned { locationsCardBounds = it.boundsInRoot() },
                    startingPointText = startingPointText,
                    destinationText = destinationText,
                    startingPointError = validationErrors?.startingPoint,
                    destinationError = validationErrors?.destination,
                    activeLocationField = activeLocationField,
                    startingPointQuery = startingPointText,
                    destinationQuery = destinationText,
                    filteredSuggestions = if (activeLocationField == LocationField.STARTING_POINT) startingSuggestions else destinationSuggestions,
                    currentLocationMessage = currentLocationMessage,
                    onStartingPointChange = {
                        cancelCurrentLocationLookup()
                        placeSelections = placeSelections.clear(TripEndpoint.ORIGIN)
                        startingPointText = it
                        currentLocationMessage = null
                        placesError = null
                        if (it.isBlank()) {
                            closeSession(LocationField.STARTING_POINT)
                        } else {
                            startSession(LocationField.STARTING_POINT)
                            startingSession?.updateQuery(it) { result ->
                                when (result) {
                                    is PlacesResult.Success -> {
                                        placesError = null
                                        startingSuggestions = result.value.take(3)
                                    }
                                    is PlacesResult.Failure -> placesError = "Places search is unavailable. Please try again."
                                }
                            }
                        }
                    },
                    onDestinationChange = {
                        placeSelections = placeSelections.clear(TripEndpoint.DESTINATION)
                        destinationText = it
                        placesError = null
                        if (it.isBlank()) {
                            closeSession(LocationField.DESTINATION)
                        } else {
                            startSession(LocationField.DESTINATION)
                            destinationSession?.updateQuery(it) { result ->
                                when (result) {
                                    is PlacesResult.Success -> {
                                        placesError = null
                                        destinationSuggestions = result.value.take(3)
                                    }
                                    is PlacesResult.Failure -> placesError = "Places search is unavailable. Please try again."
                                }
                            }
                        }
                    },
                    onStartingPointFocusChanged = { isFocused ->
                        if (isFocused) startSession(LocationField.STARTING_POINT)
                        else if (activeLocationField == LocationField.STARTING_POINT) {
                            placeSelections = placeSelections.clear(TripEndpoint.ORIGIN)
                            closeSession(LocationField.STARTING_POINT)
                            activeLocationField = null
                        }
                    },
                    onDestinationFocusChanged = { isFocused ->
                        if (isFocused) startSession(LocationField.DESTINATION)
                        else if (activeLocationField == LocationField.DESTINATION) {
                            placeSelections = placeSelections.clear(TripEndpoint.DESTINATION)
                            closeSession(LocationField.DESTINATION)
                            activeLocationField = null
                        }
                    },
                    onUseCurrentLocation = {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PermissionChecker.PERMISSION_GRANTED
                        ) {
                            captureCurrentLocation()
                        } else {
                            currentLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    onStartingPointSelected = { suggestion ->
                        startingSession?.select(suggestion) { result ->
                            when (result) {
                                is PlacesResult.Success -> {
                                    placeSelections = placeSelections.select(TripEndpoint.ORIGIN, result.value)
                                    startingPointText = result.value.displayName
                                    currentLocationMessage = null
                                    placesError = null
                                    startingSession = null
                                    startingSuggestions = emptyList()
                                    activeLocationField = null
                                }
                                is PlacesResult.Failure -> placesError = "Place details are unavailable. Please try again."
                            }
                        }
                    },
                    onDestinationSelected = { suggestion ->
                        destinationSession?.select(suggestion) { result ->
                            when (result) {
                                is PlacesResult.Success -> {
                                    placeSelections = placeSelections.select(TripEndpoint.DESTINATION, result.value)
                                    destinationText = result.value.displayName
                                    placesError = null
                                    destinationSession = null
                                    destinationSuggestions = emptyList()
                                    activeLocationField = null
                                }
                                is PlacesResult.Failure -> placesError = "Place details are unavailable. Please try again."
                            }
                        }
                    }
                )
            }

            item {
                Box(Modifier.onGloballyPositioned { permittedAreaBounds = it.boundsInRoot() }) {
                    TripAreaMapPreview(
                        placeSelections = placeSelections,
                        permittedArea = calculatedPermittedArea,
                        selectedRadiusKilometres = selectedRadiusKilometres,
                        onRadiusChange = { selectedRadiusKilometres = it },
                        onCameraChanged = onTripAreaCameraChanged
                    )
                }
            }

            item {
                ParentContactCard(
                    email = email,
                    emailError = validationErrors?.email,
                    onEmailChange = { email = it },
                    savedEmails = savedParentEmails
                )
            }

            item {
                Text(
                    text = "Active-trip recording data is stored on this device.",
                    color = NextCrossNavy.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (placesError != null || integrationError != null) {
                item {
                    Text(
                        text = placesError ?: integrationError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    validationErrors?.destination?.takeIf { sameEndpoints }?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = {
                            hasAttemptedReview = true
                            val errors = validateTripSetup(
                                startingPoint = startingPointText,
                                startingPointSelected = placeSelections.origin != null,
                                destination = destinationText,
                                destinationSelected = placeSelections.destination != null,
                                sameEndpoints = sameEndpoints,
                                email = email
                            )
                            if (!errors.hasErrors) {
                                onReviewTripDetails(
                                    placeSelections,
                                    email,
                                    selectedRadiusKilometres?.toDouble()
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = currentLocationRequest == null && currentLocationAddressJob == null,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            text = "Review Trip Details",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (locationsCardBounds != null && onboardingGuides?.shouldShow(OnboardingGuide.TripSetupLocations) == true) {
        OnboardingGuideOverlay(
            message = "Enter your starting point and destination.",
            actionLabel = "Got it",
            onComplete = { onboardingGuides.complete(OnboardingGuide.TripSetupLocations) },
            targetBounds = requireNotNull(locationsCardBounds),
            messagePlacement = CoachMarkMessagePlacement.AboveTarget
        )
    }

    if (calculatedPermittedArea != null && permittedAreaBounds != null &&
        onboardingGuides?.shouldShow(OnboardingGuide.TripSetupArea) == true
    ) {
        OnboardingGuideOverlay(
            message = "This is your permitted driving area. If you leave it, NextCross will send a notification to the parent email provided for this trip.",
            actionLabel = "Got it",
            onComplete = { onboardingGuides.complete(OnboardingGuide.TripSetupArea) },
            targetBounds = requireNotNull(permittedAreaBounds),
            messagePlacement = CoachMarkMessagePlacement.AboveTarget
        )
    }
}

@Composable
private fun TripLocationsCard(
    modifier: Modifier = Modifier,
    startingPointText: String,
    destinationText: String,
    startingPointError: String?,
    destinationError: String?,
    activeLocationField: LocationField?,
    startingPointQuery: String,
    destinationQuery: String,
    filteredSuggestions: List<PlaceSuggestion>,
    currentLocationMessage: String?,
    onStartingPointChange: (String) -> Unit,
    onDestinationChange: (String) -> Unit,
    onStartingPointFocusChanged: (Boolean) -> Unit,
    onDestinationFocusChanged: (Boolean) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onStartingPointSelected: (PlaceSuggestion) -> Unit,
    onDestinationSelected: (PlaceSuggestion) -> Unit
) {
    var locationsContainerTopY by remember { mutableStateOf<Float?>(null) }
    var startingInputCenterY by remember { mutableStateOf<Float?>(null) }
    var destinationInputCenterY by remember { mutableStateOf<Float?>(null) }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(
                start = 18.dp,
                top = 18.dp,
                end = 18.dp,
                bottom = 18.dp
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        locationsContainerTopY = coordinates.positionInRoot().y
                    }
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val containerTopY = locationsContainerTopY
                    val startY = startingInputCenterY?.let { it - (containerTopY ?: 0f) }
                    val destinationY = destinationInputCenterY?.let { it - (containerTopY ?: 0f) }
                    if (containerTopY != null && startY != null && destinationY != null) {
                        drawRouteRail(
                            startingCenterY = startY,
                            destinationCenterY = destinationY
                        )
                    }
                }

                Column {
                    LocationInputRow(
                        value = startingPointText,
                        placeholder = "Select a starting place",
                        error = startingPointError,
                        onValueChange = onStartingPointChange,
                        onFocusChanged = onStartingPointFocusChanged,
                        onPositioned = { coordinates ->
                            val position = coordinates.positionInRoot()
                            startingInputCenterY = position.y + coordinates.size.height / 2f
                        }
                    )
                    LocationSupplementRow(
                        showCurrentLocation = activeLocationField == LocationField.STARTING_POINT &&
                            startingPointQuery.isBlank(),
                        showSuggestions = activeLocationField == LocationField.STARTING_POINT &&
                            startingPointQuery.isNotBlank(),
                    currentLocationMessage = currentLocationMessage,
                        error = startingPointError,
                        filteredSuggestions = filteredSuggestions,
                        onUseCurrentLocation = onUseCurrentLocation,
                        onSuggestionSelected = onStartingPointSelected
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    LocationInputRow(
                        value = destinationText,
                        placeholder = "Select a destination",
                        error = destinationError,
                        onValueChange = onDestinationChange,
                        onFocusChanged = onDestinationFocusChanged,
                        onPositioned = { coordinates ->
                            val position = coordinates.positionInRoot()
                            destinationInputCenterY = position.y + coordinates.size.height / 2f
                        }
                    )
                    LocationSupplementRow(
                        showCurrentLocation = false,
                        showSuggestions = activeLocationField == LocationField.DESTINATION &&
                            destinationQuery.isNotBlank(),
                        currentLocationMessage = null,
                        error = destinationError,
                        filteredSuggestions = filteredSuggestions,
                        onUseCurrentLocation = {},
                        onSuggestionSelected = onDestinationSelected
                    )
                }
            }
        }
    }
}

@Composable
private fun LocationInputRow(
    value: String,
    placeholder: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onPositioned: (LayoutCoordinates) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned(onPositioned),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(26.dp))
        LocationInput(
            placeholder = placeholder,
            value = value,
            error = error,
            onValueChange = onValueChange,
            onFocusChanged = onFocusChanged,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LocationSupplementRow(
    showCurrentLocation: Boolean,
    showSuggestions: Boolean,
    currentLocationMessage: String?,
    error: String?,
    filteredSuggestions: List<PlaceSuggestion>,
    onUseCurrentLocation: () -> Unit,
    onSuggestionSelected: (PlaceSuggestion) -> Unit
) {
    val hasContent = showCurrentLocation || showSuggestions || currentLocationMessage != null || error != null
    if (!hasContent) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.width(26.dp))
            Spacer(modifier = Modifier.weight(1f))
        }
        return
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(26.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showCurrentLocation) {
                TextButton(
                    onClick = onUseCurrentLocation,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LocationIcon()
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Use current location",
                            color = NextCrossBlue,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            if (currentLocationMessage != null) {
                Text(
                    text = currentLocationMessage,
                    color = NextCrossNavy.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (showSuggestions) {
                LocationSuggestions(
                    filteredSuggestions = filteredSuggestions,
                    onSelected = onSuggestionSelected
                )
            }
            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun LocationSuggestions(
    filteredSuggestions: List<PlaceSuggestion>,
    onSelected: (PlaceSuggestion) -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (filteredSuggestions.isEmpty()) {
            Text(
                text = "No matching places",
                color = NextCrossNavy.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            filteredSuggestions.take(3).forEach { suggestion ->
                Card(
                    onClick = { onSelected(suggestion) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = suggestion.primaryText,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = suggestion.secondaryText,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawRouteRail(
    startingCenterY: Float,
    destinationCenterY: Float
) {
    val railAxisX = 4.dp.toPx()
    val strokeWidth = 2.dp.toPx()
    val startingNodeRadius = 7.dp.toPx()

    drawLine(
        color = NextCrossPaleBlue,
        start = Offset(railAxisX, startingCenterY + startingNodeRadius + strokeWidth / 2f),
        end = Offset(railAxisX, destinationCenterY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Butt
    )
    drawCircle(
        color = NextCrossBlue,
        radius = startingNodeRadius,
        center = Offset(railAxisX, startingCenterY),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
    )
    drawCircle(
        color = NextCrossBlue,
        radius = 6.dp.toPx(),
        center = Offset(railAxisX, destinationCenterY - 3.dp.toPx())
    )
    drawCircle(
        color = Color.White,
        radius = 2.dp.toPx(),
        center = Offset(railAxisX, destinationCenterY - 3.dp.toPx())
    )
    drawLine(
        color = NextCrossBlue,
        start = Offset(railAxisX - 4.dp.toPx(), destinationCenterY + 1.dp.toPx()),
        end = Offset(railAxisX, destinationCenterY + 7.dp.toPx()),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    drawLine(
        color = NextCrossBlue,
        start = Offset(railAxisX, destinationCenterY + 7.dp.toPx()),
        end = Offset(railAxisX + 4.dp.toPx(), destinationCenterY + 1.dp.toPx()),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

@Composable
private fun LocationInput(
    placeholder: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .onFocusChanged { onFocusChanged(it.isFocused) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        placeholder = { Text(placeholder) },
        isError = error != null
    )
}

@Composable
private fun LocationIcon() {
    Canvas(modifier = Modifier.size(18.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val iconColor = NextCrossBlue
        drawCircle(
            color = iconColor,
            radius = 4.dp.toPx(),
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
        )
        drawLine(
            color = iconColor,
            start = Offset(center.x, 0f),
            end = Offset(center.x, size.height),
            strokeWidth = 1.5.dp.toPx()
        )
        drawLine(
            color = iconColor,
            start = Offset(0f, center.y),
            end = Offset(size.width, center.y),
            strokeWidth = 1.5.dp.toPx()
        )
    }
}

@Composable
private fun TripAreaMapPreview(
    placeSelections: TripPlaceSelection,
    permittedArea: PermittedArea?,
    selectedRadiusKilometres: Float?,
    onRadiusChange: (Float) -> Unit,
    onCameraChanged: (TripMapCamera) -> Unit
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity
    val origin = placeSelections.origin
    val minimumRadiusKilometres = if (permittedArea != null && origin != null) {
        max(
            MinimumPermittedAreaRadiusKilometres,
            HaversineDistanceCalculator.distanceKilometres(permittedArea.centre, origin.point)
        )
    } else {
        MinimumPermittedAreaRadiusKilometres
    }
    val maximumRadiusKilometres = max(
        MaximumPermittedAreaRadiusKilometres,
        permittedArea?.radiusKilometres ?: MinimumPermittedAreaRadiusKilometres
    )
    val effectiveRadiusKilometres = (selectedRadiusKilometres?.toDouble()
        ?: permittedArea?.radiusKilometres
        ?: minimumRadiusKilometres)
        .coerceIn(minimumRadiusKilometres, maximumRadiusKilometres)
    val adjustedPermittedArea = permittedArea?.copy(
        radiusKilometres = effectiveRadiusKilometres
    )

    Column {
        Text(
            text = "Trip area preview",
            color = NextCrossNavy,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(26.dp)),
            color = PreviewPlaceholderBackground
        ) {
            if (adjustedPermittedArea != null && fragmentActivity != null) {
                PlannedTripAreaMap(
                    fragmentActivity = fragmentActivity,
                    fragmentTag = "trip_setup_map_preview",
                    areaCentre = adjustedPermittedArea.centre,
                    radiusMeters = adjustedPermittedArea.radiusKilometres * 1_000.0,
                    originPlace = placeSelections.origin,
                    destinationPlace = placeSelections.destination,
                    modifier = Modifier.fillMaxSize(),
                    onCameraChanged = onCameraChanged
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Select locations to preview the trip area.",
                        color = PreviewPlaceholderForeground,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (adjustedPermittedArea != null) {
            PermittedAreaSlider(
                radiusKilometres = effectiveRadiusKilometres,
                minimumRadiusKilometres = minimumRadiusKilometres,
                maximumRadiusKilometres = maximumRadiusKilometres,
                onRadiusChange = onRadiusChange
            )
        }
    }
}

@Composable
private fun PermittedAreaSlider(
    radiusKilometres: Double,
    minimumRadiusKilometres: Double,
    maximumRadiusKilometres: Double,
    onRadiusChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Permitted area",
                color = NextCrossNavy,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = String.format(Locale.US, "%.1f km", radiusKilometres),
                color = NextCrossNavy,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = radiusKilometres.toFloat(),
            onValueChange = onRadiusChange,
            valueRange = minimumRadiusKilometres.toFloat()..maximumRadiusKilometres.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = NextCrossBlue,
                activeTrackColor = NextCrossBlue,
                inactiveTrackColor = NextCrossPaleBlue
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = String.format(Locale.US, "%.1f km", minimumRadiusKilometres),
                color = NextCrossNavy.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = String.format(Locale.US, "%.1f km", maximumRadiusKilometres),
                color = NextCrossNavy.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ParentContactCard(
    email: String,
    emailError: String?,
    onEmailChange: (String) -> Unit,
    savedEmails: List<String>
) {
    var savedEmailsExpanded by remember { mutableStateOf(false) }
    var showAllSavedEmails by remember { mutableStateOf(false) }
    var shouldSuggestSavedEmails by remember { mutableStateOf(false) }
    val alternatives = savedEmails
        .map { it.trim() }
        .filter { it.isNotBlank() && it != email.trim() }
        .distinct()
    val suggestions = if (showAllSavedEmails) {
        alternatives
    } else {
        alternatives.filter { candidate ->
            email.isNotBlank() && candidate.contains(email.trim(), ignoreCase = true)
        }
    }
    LaunchedEffect(email, savedEmails, shouldSuggestSavedEmails) {
        if (!shouldSuggestSavedEmails || showAllSavedEmails || email.isBlank()) {
            return@LaunchedEffect
        }
        delay(500)
        savedEmailsExpanded = alternatives.any { candidate ->
            candidate.contains(email.trim(), ignoreCase = true)
        }
    }
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Parent contact",
                color = NextCrossNavy,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Email address *",
                color = NextCrossNavy,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box {
                OutlinedTextField(
                    value = email,
                    onValueChange = { value ->
                        onEmailChange(value)
                        showAllSavedEmails = false
                        shouldSuggestSavedEmails = true
                        savedEmailsExpanded = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("Enter an email address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    isError = emailError != null,
                    supportingText = emailError?.let { message -> { Text(message) } },
                    trailingIcon = if (alternatives.isEmpty()) null else {
                        {
                            IconButton(onClick = {
                                showAllSavedEmails = true
                                shouldSuggestSavedEmails = false
                                savedEmailsExpanded = true
                            }) {
                                Text(text = "▼", color = NextCrossNavy, fontSize = 14.sp)
                            }
                        }
                    }
                )
                DropdownMenu(
                    expanded = savedEmailsExpanded && suggestions.isNotEmpty(),
                    onDismissRequest = {
                        savedEmailsExpanded = false
                        showAllSavedEmails = false
                    },
                    modifier = Modifier.width(300.dp),
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp
                ) {
                    suggestions.forEach { savedEmail ->
                        DropdownMenuItem(
                            onClick = {
                                onEmailChange(savedEmail)
                                savedEmailsExpanded = false
                                showAllSavedEmails = false
                                shouldSuggestSavedEmails = false
                            },
                            text = {
                                Text(
                                    text = savedEmail,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = NextCrossNavy,
                                    textAlign = TextAlign.Start
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun TripSetupScreenPreview() {
    NextCrossTheme {
        TripSetupScreen()
    }
}
