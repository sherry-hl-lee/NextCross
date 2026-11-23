package com.fit5120tp10.nextcross.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fit5120tp10.nextcross.client.historicalcrash.CrashCluster
import com.fit5120tp10.nextcross.client.historicalcrash.CrashIndividualPoint
import com.fit5120tp10.nextcross.client.historicalcrash.CrashTimePeriod
import com.fit5120tp10.nextcross.client.historicalcrash.HistoricalCrashContext

private val HistoricalCrashBackground = Color(0xFFF4F7FE)
private val HistoricalCrashNavy = Color(0xFF071A3A)
private val HistoricalCrashBlue = Color(0xFF1769E0)
private val HistoricalAmber = Color(0xFFF2A900)
private val HistoricalTimeOfDayButtonWidth = 100.dp
private val SelectedTimeOfDayBackground = Color(0xFFE7F0FF)

internal sealed interface HistoricalCrashSelection {
    data class Cluster(val value: CrashCluster) : HistoricalCrashSelection
    data class Individual(val value: CrashIndividualPoint) : HistoricalCrashSelection
}

internal val CrashTimePeriod.displayLabel: String
    get() = when (this) {
        CrashTimePeriod.Morning -> "Morning"
        CrashTimePeriod.Daytime -> "Daytime"
        CrashTimePeriod.Night -> "Night"
        CrashTimePeriod.LateNight -> "Late night"
    }

@Composable
internal fun HistoricalCrashMarker(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(46.dp),
        shape = CircleShape,
        color = HistoricalAmber,
        shadowElevation = 4.dp,
        border = BorderStroke(3.dp, Color.White.copy(alpha = 0.9f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = count.toString(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun HistoricalIndividualCrashPoint(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(13.dp),
        shape = CircleShape,
        color = HistoricalAmber,
        shadowElevation = 2.dp,
        border = BorderStroke(2.dp, Color.White)
    ) {}
}

@Composable
internal fun HistoricalContextSummaryBar(
    selectedTimeOfDay: CrashTimePeriod,
    showTimeOfDayMenu: Boolean,
    onTimeOfDayClick: () -> Unit,
    onTimeOfDayDismiss: () -> Unit,
    onTimeOfDaySelected: (CrashTimePeriod) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(HistoricalAmber, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Historical injury crashes",
                    color = HistoricalCrashNavy,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Involving drivers aged 18–25",
                    color = HistoricalCrashNavy.copy(alpha = 0.66f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Box {
                Surface(
                    onClick = onTimeOfDayClick,
                    modifier = Modifier.width(HistoricalTimeOfDayButtonWidth),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, HistoricalCrashNavy.copy(alpha = 0.26f))
                ) {
                    Text(
                        text = "${selectedTimeOfDay.displayLabel}  ▾",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 9.dp),
                        color = HistoricalCrashNavy,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center
                    )
                }
                DropdownMenu(
                    expanded = showTimeOfDayMenu,
                    onDismissRequest = onTimeOfDayDismiss,
                    modifier = Modifier.width(HistoricalTimeOfDayButtonWidth),
                    shape = RoundedCornerShape(12.dp),
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp
                ) {
                    CrashTimePeriod.entries.forEach { timeOfDay ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = timeOfDay.displayLabel,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (timeOfDay == selectedTimeOfDay) {
                                        HistoricalCrashBlue
                                    } else {
                                        HistoricalCrashNavy
                                    },
                                    textAlign = TextAlign.Center
                                )
                            },
                            onClick = { onTimeOfDaySelected(timeOfDay) },
                            modifier = Modifier.background(
                                if (timeOfDay == selectedTimeOfDay) {
                                    SelectedTimeOfDayBackground
                                } else {
                                    Color.Transparent
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HistoricalCrashDetailSheet(
    selection: HistoricalCrashSelection,
    context: HistoricalCrashContext,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCluster = selection is HistoricalCrashSelection.Cluster
    val crashCount = when (selection) {
        is HistoricalCrashSelection.Cluster -> selection.value.crashCount
        is HistoricalCrashSelection.Individual -> selection.value.crashCount
    }
    val fatalities = when (selection) {
        is HistoricalCrashSelection.Cluster -> selection.value.fatalities
        is HistoricalCrashSelection.Individual -> selection.value.fatalities
    }
    val seriousInjuries = when (selection) {
        is HistoricalCrashSelection.Cluster -> selection.value.seriousInjuries
        is HistoricalCrashSelection.Individual -> selection.value.seriousInjuries
    }
    val location = when (selection) {
        is HistoricalCrashSelection.Cluster -> selection.value.locationLabel
        is HistoricalCrashSelection.Individual -> selection.value.locationLabel
    }
    val period = listOfNotNull(context.periodStart?.take(4), context.periodEnd?.take(4))
        .joinToString("–")
        .ifBlank { "Recorded period unavailable" }
    val contextLine = when (selection) {
        is HistoricalCrashSelection.Cluster -> {
            "Nearby crash group · ${context.timePeriod.displayLabel} · $period"
        }
        is HistoricalCrashSelection.Individual -> {
            val date = selection.value.crashDate ?: "Date unavailable"
            "$date · ${selection.value.timePeriod?.displayLabel ?: context.timePeriod.displayLabel}"
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
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
                Text(
                    text = if (isCluster) "Hotspot details" else "Crash details",
                    color = HistoricalCrashNavy,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose) {
                    Text("×", color = HistoricalCrashNavy, fontSize = 30.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(HistoricalAmber, CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (isCluster) "Historical injury crashes" else "Historical injury crash",
                        color = HistoricalCrashNavy,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Filter: Involving drivers aged 18–25",
                        color = HistoricalCrashNavy.copy(alpha = 0.64f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = location,
                    color = HistoricalCrashNavy,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = contextLine,
                    color = HistoricalCrashNavy.copy(alpha = 0.64f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrashMetricTile(crashCount.toString(), if (isCluster) "Crashes" else "Crash", Modifier.weight(1f))
                CrashMetricTile(fatalities.toString(), "Deaths", Modifier.weight(1f))
                CrashMetricTile(seriousInjuries.toString(), "Serious injuries", Modifier.weight(1f))
            }
            Text(
                text = "Deaths and serious-injury counts include all people involved.",
                color = HistoricalCrashNavy.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SelectedTimeOfDayBackground
            ) {
                Text(
                    text = "Historical context only — not live risk or a crash prediction.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = HistoricalCrashBlue,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun CrashMetricTile(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = HistoricalCrashBackground
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                color = if (value == "0") HistoricalCrashNavy else HistoricalAmber,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                color = HistoricalCrashNavy,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
