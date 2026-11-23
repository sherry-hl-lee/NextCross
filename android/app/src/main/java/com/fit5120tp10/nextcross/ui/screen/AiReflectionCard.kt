package com.fit5120tp10.nextcross.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.fit5120tp10.nextcross.client.aireflection.AI_REFLECTION_DISCLAIMER
import com.fit5120tp10.nextcross.client.aireflection.AiReflectionClient
import com.fit5120tp10.nextcross.client.aireflection.AiReflectionLocator
import com.fit5120tp10.nextcross.client.aireflection.AiReflectionResult
import com.fit5120tp10.nextcross.client.aireflection.AiReflectionUiState
import com.fit5120tp10.nextcross.client.aireflection.KILOMETRES_PER_HOUR_PER_METRE_PER_SECOND
import com.fit5120tp10.nextcross.client.aireflection.ReflectionAdvice
import com.fit5120tp10.nextcross.client.aireflection.ReflectionEventFactsFactory
import com.fit5120tp10.nextcross.client.aireflection.ReflectionQuestion
import com.fit5120tp10.nextcross.client.aireflection.reasonMessageForEvent
import com.fit5120tp10.nextcross.client.aireflection.userMessage
import com.fit5120tp10.nextcross.client.posttrip.PostTripReviewEvent
import com.fit5120tp10.nextcross.client.posttrip.ReviewEventType
import com.fit5120tp10.nextcross.client.posttrip.ReviewItem
import com.fit5120tp10.nextcross.client.posttrip.ReviewPriority
import com.fit5120tp10.nextcross.client.posttrip.displayText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ReflectionNavy = Color(0xFF071A3A)
private val ReflectionBlue = Color(0xFF1769E0)
private val ReflectionGreen = Color(0xFF38A169)
private val ReflectionRed = Color(0xFFC8444B)
private val ReflectionOrange = Color(0xFFC97818)
private val ReflectionChip = Color(0xFFF4F7FE)

/**
 * Post-trip AI reflection for one recorded event.
 *
 * Turn one asks the driver what was happening; turn two answers with advice based on that
 * reply. Both turns go to the NextCross backend, which owns the provider key and the
 * prompts. Only the numbers of the selected event and the driver's own sentence are sent:
 * no route, no coordinates, no absolute timestamps.
 *
 * Candidates are the highest review-priority event of each family (above the limit, hard
 * braking, rapid acceleration), see [ReflectionEventFactsFactory.candidates]. Nothing is sent
 * until the driver taps "Start reflection", so opening the summary, switching events or a
 * speed-analysis result arriving later never spends AI quota and never interrupts an answer
 * in progress: each event keeps its own conversation.
 *
 * [events] may be the local events alone or the list after backend speed events have been
 * merged in ([com.fit5120tp10.nextcross.client.posttrip.mergeReviewEvents]); the card simply
 * re-ranks whatever it is given.
 *
 * The card renders nothing when no event can be discussed, and it never hides the recorded
 * data when the backend is unavailable.
 */
@Composable
fun AiReflectionCard(
    events: List<PostTripReviewEvent>,
    tripStartMillis: Long?,
    completedReflectionTexts: Map<String, String> = emptyMap(),
    onConversationComplete: (tripId: String, eventId: String, text: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    client: AiReflectionClient = AiReflectionLocator.get(LocalContext.current)
) {
    val candidates = remember(events, tripStartMillis) {
        ReflectionEventFactsFactory.candidates(ReviewPriority.rank(events, tripStartMillis))
    }
    if (candidates.isEmpty()) return
    val counts = remember(events) { ReflectionEventFactsFactory.countsOf(events) }

    // Selection is kept by event id, not by position, so a candidate added later does not move it.
    var selectedEventId by remember { mutableStateOf<String?>(null) }
    val selected = candidates.firstOrNull { it.eventId == selectedEventId } ?: candidates.first()
    val facts = remember(selected, counts, tripStartMillis) {
        ReflectionEventFactsFactory.from(
            selected.event,
            tripStartMillis,
            counts,
            ReflectionEventFactsFactory.roadContextFor(selected)
        )
    }

    // One conversation and one draft per event id.
    val states = remember { mutableStateMapOf<String, AiReflectionUiState>() }
    val drafts = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    val eventId = selected.eventId
    val tripId = selected.event.tripId
    val savedReflection = completedReflectionTexts[eventId]
    val state: AiReflectionUiState =
        if (facts == null) AiReflectionUiState.Idle else states[eventId] ?: AiReflectionUiState.NotStarted
    val draft = drafts[eventId].orEmpty()

    fun startReflection() {
        val currentFacts = facts ?: return
        // A second tap while a request is in flight must not send another one.
        val inFlight = states[eventId]
        if (inFlight == AiReflectionUiState.AskingQuestion || inFlight is AiReflectionUiState.Advising) return
        states[eventId] = AiReflectionUiState.AskingQuestion
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                client.askForQuestion(tripId, currentFacts)
            }
            states[eventId] = when (result) {
                is AiReflectionResult.Completed -> AiReflectionUiState.AwaitingDriver(result.value)
                is AiReflectionResult.Unavailable -> AiReflectionUiState.Unavailable(result.reason)
                is AiReflectionResult.Rejected -> AiReflectionUiState.NotAvailableForEvent(result.reason)
                is AiReflectionResult.Failed -> AiReflectionUiState.Failed(result.detail)
            }
        }
    }

    fun sendDriverContext(question: ReflectionQuestion) {
        val currentFacts = facts ?: return
        val answer = draft.trim()
        if (answer.isEmpty() || states[eventId] is AiReflectionUiState.Advising) return
        states[eventId] = AiReflectionUiState.Advising(question, answer)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                client.askForAdvice(tripId, currentFacts, question, answer)
            }
            states[eventId] = when (result) {
                is AiReflectionResult.Completed -> {
                    onConversationComplete(tripId, eventId, reflectionReportText(question, answer, result.value))
                    AiReflectionUiState.Advised(question, answer, result.value)
                }
                is AiReflectionResult.Unavailable ->
                    AiReflectionUiState.Unavailable(result.reason, question)
                is AiReflectionResult.Rejected ->
                    AiReflectionUiState.NotAvailableForEvent(result.reason)
                is AiReflectionResult.Failed ->
                    AiReflectionUiState.Failed(result.detail, question)
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReflectionHeader()
            if (candidates.size > 1) {
                ReflectionEventSelector(
                    items = candidates,
                    selectedEventId = eventId,
                    onSelect = { selectedEventId = it }
                )
            }
            ReflectionEventFactsRow(selected)

            if (savedReflection != null) {
                ReflectionNotice("Completed reflection", ReflectionGreen)
                Text(savedReflection, color = ReflectionNavy, style = MaterialTheme.typography.bodySmall)
            } else when (val current = state) {
                AiReflectionUiState.Idle -> ReflectionNotice(
                    "This event does not have enough recorded detail to discuss.",
                    ReflectionNavy.copy(alpha = .7f)
                )

                AiReflectionUiState.NotStarted -> StartReflectionPrompt(onStart = { startReflection() })

                AiReflectionUiState.AskingQuestion ->
                    ReflectionProgress("Asking about this event…")

                is AiReflectionUiState.AwaitingDriver -> {
                    ReflectionQuestionBlock(current.question)
                    QuickReplies(current.question.quickReplies) { drafts[eventId] = it }
                    DriverContextField(
                        value = draft,
                        onValueChange = { drafts[eventId] = it },
                        onSend = { sendDriverContext(current.question) }
                    )
                }

                is AiReflectionUiState.Advising -> {
                    ReflectionQuestionBlock(current.question)
                    DriverAnswerBubble(current.driverContext)
                    ReflectionProgress("Thinking about what you told it…")
                }

                is AiReflectionUiState.Advised -> {
                    ReflectionQuestionBlock(current.question)
                    DriverAnswerBubble(current.driverContext)
                    ReflectionAdviceBlock(current.advice)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LinkText("Answer again") {
                            drafts[eventId] = current.driverContext
                            states[eventId] = AiReflectionUiState.AwaitingDriver(current.question)
                        }
                        LinkText("Start over") {
                            drafts[eventId] = ""
                            states.remove(eventId)
                            startReflection()
                        }
                    }
                }

                is AiReflectionUiState.NotAvailableForEvent ->
                    ReflectionNotice(reasonMessageForEvent(current.reason), ReflectionOrange)

                is AiReflectionUiState.Unavailable -> {
                    current.question?.let { ReflectionQuestionBlock(it) }
                    ReflectionNotice(current.reason.userMessage(), ReflectionOrange)
                    LinkText("Try again") { startReflection() }
                }

                is AiReflectionUiState.Failed -> {
                    current.question?.let { ReflectionQuestionBlock(it) }
                    // current.detail stays in the state for the developer log; it is not
                    // wording a driver can use, so it is not rendered.
                    ReflectionNotice("Reflection could not be completed.", ReflectionRed)
                    LinkText("Try again") { startReflection() }
                }
            }

            Text(
                text = AI_REFLECTION_DISCLAIMER,
                color = ReflectionNavy.copy(alpha = .55f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun reflectionReportText(
    question: ReflectionQuestion,
    driverContext: String,
    advice: ReflectionAdvice
): String = buildString {
    appendLine("AI question")
    appendLine(question.headline)
    appendLine(question.question)
    appendLine()
    appendLine("Your response")
    appendLine(driverContext)
    appendLine()
    appendLine("AI response")
    appendLine(advice.acknowledgement)
    advice.advice.forEach { appendLine("- $it") }
    appendLine(advice.encouragement)
    append(AI_REFLECTION_DISCLAIMER)
}

/**
 * No build-variant badge here. Where an answer came from is a diagnostic, and
 * [AiReflectionLocator] logs it instead, so a DEBUG screenshot never shows a hostname or a
 * mock label as if it were part of the reflection.
 */
@Composable
private fun ReflectionHeader() {
    Text(
        text = "Reflect with AI",
        modifier = Modifier.fillMaxWidth(),
        color = ReflectionNavy,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun ReflectionEventSelector(
    items: List<ReviewItem>,
    selectedEventId: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            val event = item.event
            val selected = event.eventId == selectedEventId
            Surface(
                modifier = Modifier.clickable { onSelect(event.eventId) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) ReflectionBlue else ReflectionChip
            ) {
                Text(
                    text = event.type.shortLabel(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    color = if (selected) Color.White else ReflectionNavy.copy(alpha = .8f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * Always visible, so the driver can see the recorded numbers behind the reflection.
 *
 * The time into the trip is deliberately not repeated here: the reflection already states
 * it, and showing the same instant twice at two precisions only produces two numbers that
 * disagree once they are rounded.
 */
@Composable
private fun ReflectionEventFactsRow(item: ReviewItem) {
    val event = item.event
    val duration = "%.1f".format(event.durationSeconds)
    val details = event.speedLimitDetails
    val facts = if (details != null) {
        listOf(
            event.type.shortLabel(),
            "peak ${details.peakSpeedKmh.toInt()} km/h · limit ${details.referenceLimitKmh}",
            "${duration}s"
        )
    } else {
        val before = (event.speedBeforeMps * KILOMETRES_PER_HOUR_PER_METRE_PER_SECOND).toInt()
        val after = (event.speedAfterMps * KILOMETRES_PER_HOUR_PER_METRE_PER_SECOND).toInt()
        listOf(event.type.shortLabel(), "$before → $after km/h", "${duration}s")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = facts.joinToString("  ·  "),
            color = ReflectionNavy.copy(alpha = .7f),
            style = MaterialTheme.typography.bodySmall
        )
        val tags = item.annotations.mapNotNull { it.displayText() }
        if (tags.isNotEmpty()) {
            Text(
                text = tags.joinToString("  ·  "),
                color = ReflectionOrange,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** Nothing has been sent yet; one tap starts turn one for the selected event. */
@Composable
private fun StartReflectionPrompt(onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Pick an event, then start when you are ready. Nothing is sent before you tap.",
            color = ReflectionNavy.copy(alpha = .7f),
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ReflectionBlue,
                contentColor = Color.White
            )
        ) {
            Text("Start reflection")
        }
    }
}

@Composable
private fun ReflectionProgress(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = ReflectionBlue
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = ReflectionNavy.copy(alpha = .7f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ReflectionQuestionBlock(question: ReflectionQuestion) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = question.headline,
            color = ReflectionNavy,
            style = MaterialTheme.typography.bodyMedium
        )
        Surface(shape = RoundedCornerShape(14.dp), color = ReflectionBlue.copy(alpha = .08f)) {
            Text(
                text = question.question,
                modifier = Modifier.padding(12.dp),
                color = ReflectionNavy,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** Tapping a reply fills the text box; the driver can still edit it before sending. */
@Composable
private fun QuickReplies(replies: List<String>, onPick: (String) -> Unit) {
    if (replies.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        replies.forEach { reply ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(reply) },
                shape = RoundedCornerShape(12.dp),
                color = ReflectionChip
            ) {
                Text(
                    text = reply,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    color = ReflectionNavy.copy(alpha = .85f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DriverContextField(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Or describe it in your own words") },
            minLines = 1,
            maxLines = 3,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ReflectionBlue,
                unfocusedBorderColor = ReflectionNavy.copy(alpha = .25f),
                cursorColor = ReflectionBlue
            )
        )
        Button(
            onClick = onSend,
            enabled = value.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ReflectionBlue,
                contentColor = Color.White
            )
        ) {
            Text("Send")
        }
    }
}

@Composable
private fun DriverAnswerBubble(driverContext: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ReflectionChip
    ) {
        Text(
            text = driverContext,
            modifier = Modifier.padding(12.dp),
            color = ReflectionNavy.copy(alpha = .85f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ReflectionAdviceBlock(advice: ReflectionAdvice) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = advice.acknowledgement,
            color = ReflectionNavy,
            style = MaterialTheme.typography.bodyMedium
        )
        advice.advice.forEach { suggestion ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(
                    modifier = Modifier
                        .size(width = 3.dp, height = 18.dp)
                        .background(ReflectionGreen)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = suggestion,
                    color = ReflectionNavy.copy(alpha = .85f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Text(
            text = advice.encouragement,
            color = ReflectionGreen,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ReflectionNotice(message: String, colour: Color) {
    Text(text = message, color = colour, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LinkText(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier.clickable(onClick = onClick),
        color = ReflectionBlue,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        textDecoration = TextDecoration.Underline
    )
}

private fun ReviewEventType.shortLabel(): String = when (this) {
    ReviewEventType.RAPID_ACCELERATION -> "Rapid acceleration"
    ReviewEventType.RAPID_DECELERATION -> "Hard braking"
    ReviewEventType.SPEED_LIMIT_EXCEEDANCE -> "Above the limit"
}
