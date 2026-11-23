package com.fit5120tp10.nextcross.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fit5120tp10.nextcross.R
import com.fit5120tp10.nextcross.client.tripreport.TripReportHistoryItem
import com.fit5120tp10.nextcross.client.tripreport.TripReportLocator
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuide
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuideController
import com.fit5120tp10.nextcross.ui.onboarding.OnboardingGuideOverlay
import com.fit5120tp10.nextcross.ui.onboarding.CoachMarkMessagePlacement
import com.fit5120tp10.nextcross.ui.validation.isValidEmailAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HomeBackground: Color @Composable get() = MaterialTheme.colorScheme.background
private val HomeNavy = Color(0xFF071A3A)
private val HomeForeground: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val HomePurple: Color @Composable get() = MaterialTheme.colorScheme.tertiary
private val HomeSoftPurple = Color(0xFFEDEBFF)
private val HomeMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
fun HomeScreen(
    onStartNewTrip: () -> Unit,
    onViewHistory: () -> Unit,
    onOpenLatestReport: (TripReportHistoryItem) -> Unit,
    recipientEmails: List<String>,
    defaultRecipientEmail: String,
    onSaveRecipientEmails: (emails: List<String>, defaultEmail: String) -> Unit,
    onboardingGuides: OnboardingGuideController
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val darkTheme = isSystemInDarkTheme()
    var historyItems by remember { mutableStateOf<List<TripReportHistoryItem>>(emptyList()) }
    var showPreferences by remember { mutableStateOf(false) }
    var editableEmails by remember { mutableStateOf<List<String>>(emptyList()) }
    var editableDefaultEmail by remember { mutableStateOf("") }
    var startTripBounds by remember { mutableStateOf<Rect?>(null) }
    var emailSettingsBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(context) {
        historyItems = withContext(Dispatchers.IO) {
            TripReportLocator.get(context).listHistoryItems()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.nextcross_brand_logo),
                    contentDescription = "NextCross",
                    colorFilter = if (darkTheme) ColorFilter.tint(HomeForeground) else null,
                    modifier = Modifier.size(32.dp),
                    contentScale = ContentScale.Fit
                )
                Image(
                    painter = painterResource(R.drawable.nextcross_brand_title),
                    contentDescription = "NextCross",
                    colorFilter = if (darkTheme) ColorFilter.tint(HomeForeground) else null,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .height(24.dp)
                        .width(132.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .border(1.dp, HomeForeground, CircleShape)
                        .clickable { onboardingGuides.restartAllGuides() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("?", color = HomeForeground, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .onGloballyPositioned { emailSettingsBounds = it.boundsInRoot() }
                        .clickable {
                            editableEmails = recipientEmails
                            editableDefaultEmail = defaultRecipientEmail
                            showPreferences = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.home_profile_icon),
                        contentDescription = "Profile and email settings",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(34.dp))

        Column {
            Text(
                text = "Ready for your next trip?",
                color = HomeForeground,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 32.sp
            )
            Text(
                text = "Drive safe out there.",
                modifier = Modifier.padding(top = 8.dp),
                color = HomeMuted,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(42.dp))

        StartTripCard(
            onClick = onStartNewTrip,
            modifier = Modifier.onGloballyPositioned { startTripBounds = it.boundsInRoot() }
        )
        }

        ActivitySection(
            latestReport = historyItems.firstOrNull(),
            onViewHistory = onViewHistory,
            onOpenLatestReport = onOpenLatestReport
        )
    }

    if (showPreferences) {
        RecipientPreferencesSheet(
            emails = editableEmails,
            defaultEmail = editableDefaultEmail,
            onDismiss = { showPreferences = false },
            onSave = { emails, defaultEmail ->
                onSaveRecipientEmails(emails, defaultEmail)
                showPreferences = false
            },
            onEmailsChange = { editableEmails = it },
            onDefaultEmailChange = { editableDefaultEmail = it }
        )
    }

    val activeHomeGuide = when {
        startTripBounds != null && onboardingGuides.shouldShow(OnboardingGuide.HomeStartTrip) -> OnboardingGuide.HomeStartTrip
        emailSettingsBounds != null && onboardingGuides.shouldShow(OnboardingGuide.HomeEmailSettings) -> OnboardingGuide.HomeEmailSettings
        else -> null
    }
    activeHomeGuide?.let { guide ->
        val isStartGuide = guide == OnboardingGuide.HomeStartTrip
        val targetBounds = if (isStartGuide) startTripBounds else emailSettingsBounds
        if (targetBounds != null) {
            OnboardingGuideOverlay(
                message = if (isStartGuide) {
                    "Set up and start a recorded trip."
                } else {
                    "Use this menu to save a parent email for future trips."
                },
                actionLabel = if (isStartGuide) "Next" else "Got it",
                onComplete = { onboardingGuides.complete(guide) },
                targetBounds = targetBounds,
                messagePlacement = CoachMarkMessagePlacement.Bottom
            )
        }
    }
}

internal fun isDuplicateRecipientEmail(emails: List<String>, index: Int): Boolean {
    val candidate = emails.getOrNull(index)?.trim().orEmpty()
    return candidate.isNotEmpty() && emails.indices.any { otherIndex ->
        otherIndex != index && emails[otherIndex].trim().equals(candidate, ignoreCase = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipientPreferencesSheet(
    emails: List<String>,
    defaultEmail: String,
    onDismiss: () -> Unit,
    onSave: (List<String>, String) -> Unit,
    onEmailsChange: (List<String>) -> Unit,
    onDefaultEmailChange: (String) -> Unit
) {
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var newlyAddedIndex by remember { mutableStateOf<Int?>(null) }
    var newEmailHasReceivedFocus by remember { mutableStateOf(false) }
    var preferenceError by remember { mutableStateOf<String?>(null) }
    var duplicateEmailIndex by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    fun discardEmptyNewEmail() {
        val index = newlyAddedIndex
        if (index != null && emails.getOrNull(index)?.isBlank() == true) {
            onEmailsChange(emails.filterIndexed { itemIndex, _ -> itemIndex != index })
        }
        if (editingIndex == index) editingIndex = null
        newlyAddedIndex = null
        newEmailHasReceivedFocus = false
    }

    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = {
        discardEmptyNewEmail()
        onDismiss()
    }, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(start = 24.dp, top = 8.dp, end = 24.dp)
            ) {
                Text(
                    text = "Saved emails (up to 5)",
                    color = HomeForeground,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                preferenceError?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(top = 8.dp),
                        color = Color(0xFFB3261E),
                        fontSize = 13.sp
                    )
                }

                emails.forEachIndexed { index, email ->
                    val isEditing = editingIndex == index
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, HomeMuted.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        if (isEditing) {
                            val isInvalid = email.isNotBlank() && !isValidEmailAddress(email)
                            val isDuplicate = duplicateEmailIndex == index
                            val fieldError = when {
                                isDuplicate -> "This email is already saved."
                                isInvalid -> "Enter a valid email address."
                                else -> null
                            }
                            OutlinedTextField(
                                value = email,
                                onValueChange = { value ->
                                    preferenceError = null
                                    duplicateEmailIndex = null
                                    onEmailsChange(emails.mapIndexed { itemIndex, item ->
                                        if (itemIndex == index) value else item
                                    })
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            if (newlyAddedIndex == index) newEmailHasReceivedFocus = true
                                            scope.launch {
                                                delay(250)
                                                scrollState.animateScrollTo(scrollState.maxValue)
                                            }
                                        } else if (
                                            newlyAddedIndex == index &&
                                            newEmailHasReceivedFocus &&
                                            email.isBlank()
                                        ) {
                                            onEmailsChange(emails.filterIndexed { itemIndex, _ -> itemIndex != index })
                                            newlyAddedIndex = null
                                            newEmailHasReceivedFocus = false
                                            editingIndex = null
                                        }
                                    },
                                label = { Text("Email") },
                                singleLine = true,
                                isError = fieldError != null,
                                supportingText = fieldError?.let { message -> { Text(message) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                            )
                        } else {
                            Text(text = email, color = HomeForeground, fontSize = 16.sp)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    if (newlyAddedIndex != null && newlyAddedIndex != index) discardEmptyNewEmail()
                                    onDefaultEmailChange(email.trim())
                                },
                                enabled = email.isNotBlank()
                            ) {
                                Text(
                                    text = if (email.isNotBlank() && defaultEmail == email.trim()) {
                                        "◉ Default"
                                    } else {
                                        "○ Set as default"
                                    },
                                    color = HomePurple
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                if (isEditing) {
                                    if (newlyAddedIndex == index && email.isBlank()) {
                                        onEmailsChange(emails.filterIndexed { itemIndex, _ -> itemIndex != index })
                                        newlyAddedIndex = null
                                        newEmailHasReceivedFocus = false
                                        editingIndex = null
                                    } else if (isDuplicateRecipientEmail(emails, index)) {
                                        duplicateEmailIndex = index
                                    } else {
                                        duplicateEmailIndex = null
                                        editingIndex = null
                                    }
                                } else {
                                    if (newlyAddedIndex != null) discardEmptyNewEmail()
                                    duplicateEmailIndex = null
                                    editingIndex = index
                                }
                            }) {
                                Text(text = if (isEditing) "Done" else "Edit", color = HomePurple)
                            }
                            TextButton(onClick = {
                                val removedEmail = email.trim()
                                val blankNewIndex = newlyAddedIndex
                                onEmailsChange(
                                    emails.filterIndexed { itemIndex, item ->
                                        itemIndex != index && !(itemIndex == blankNewIndex && item.isBlank())
                                    }
                                )
                                if (defaultEmail == removedEmail) onDefaultEmailChange("")
                                duplicateEmailIndex = null
                                editingIndex = null
                                newlyAddedIndex = null
                                newEmailHasReceivedFocus = false
                            }) {
                                Text(text = "Delete", color = HomePurple)
                            }
                        }
                    }
                }

                if (emails.size < 5) {
                    TextButton(
                        onClick = {
                            discardEmptyNewEmail()
                            duplicateEmailIndex = null
                            onEmailsChange(emails.filter { it.isNotBlank() } + "")
                            newlyAddedIndex = emails.count { it.isNotBlank() }
                            newEmailHasReceivedFocus = false
                            editingIndex = newlyAddedIndex
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(text = "+ Add email", color = HomePurple, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HomeMuted.copy(alpha = 0.22f))
                )
                Text(
                    text = "Stored locally on this device",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                    color = HomeMuted,
                    fontSize = 13.sp
                )
                Button(
                    onClick = {
                        val savedEmails = emails.map { it.trim() }.filter { it.isNotBlank() }
                        val duplicateIndex = emails.indices.lastOrNull { isDuplicateRecipientEmail(emails, it) }
                        preferenceError = if (savedEmails.any { !isValidEmailAddress(it) }) {
                            "Enter a valid email address before saving."
                        } else if (duplicateIndex != null) {
                            duplicateEmailIndex = duplicateIndex
                            editingIndex = duplicateIndex
                            "This email is already saved."
                        } else {
                            newlyAddedIndex = null
                            newEmailHasReceivedFocus = false
                            onSave(savedEmails, defaultEmail.takeIf { it in savedEmails }.orEmpty())
                            null
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
                        .height(52.dp)
                ) {
                    Text(text = "Save changes", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StartTripCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(136.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(HomeNavy)
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(R.drawable.home_trip_map_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Start a new trip",
                modifier = Modifier.weight(1f),
                color = Color.White,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )
            // This PNG has transparent margins: an 88 dp box renders a roughly 44 dp circle.
            Image(
                painter = painterResource(R.drawable.home_start_trip_button),
                contentDescription = null,
                modifier = Modifier.size(88.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun ActivitySection(
    latestReport: TripReportHistoryItem?,
    onViewHistory: () -> Unit,
    onOpenLatestReport: (TripReportHistoryItem) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Recent activity", color = HomeForeground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onViewHistory) {
                Text(text = "See all", color = HomePurple, fontWeight = FontWeight.SemiBold)
            }
        }

        if (latestReport == null) {
            EmptyActivityCard()
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenLatestReport(latestReport) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                ) {
                    ActivityIcon()
                }
                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        text = formatHistoryTimes(
                            latestReport.startedAtMillis,
                            latestReport.endedAtMillis
                        ),
                        color = HomeForeground,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${latestReport.startingPointLabel} → ${latestReport.destinationLabel}",
                        modifier = Modifier.padding(top = 3.dp),
                        color = HomeMuted,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
                Text(text = "›", color = HomeMuted, fontSize = 28.sp)
            }
        }
    }
}

@Composable
private fun EmptyActivityCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
        ) {
            ActivityIcon()
        }
        Text(
            text = "No reports yet.",
            modifier = Modifier.padding(start = 14.dp),
            color = HomeMuted,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun ActivityIcon() {
    Image(
        painter = painterResource(R.drawable.home_activity_icon),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
    )
}

private fun formatHistoryTimes(startedAtMillis: Long, endedAtMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val dateAndStartTime = DateTimeFormatter.ofPattern("d MMM · h:mm a").withZone(zone)
    val time = DateTimeFormatter.ofPattern("h:mm a").withZone(zone)
    return "${dateAndStartTime.format(Instant.ofEpochMilli(startedAtMillis))} – " +
        time.format(Instant.ofEpochMilli(endedAtMillis))
}
