package com.fit5120tp10.nextcross.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fit5120tp10.nextcross.client.tripreport.TripReportHistoryItem
import com.fit5120tp10.nextcross.client.tripreport.TripReportLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ReportHistoryBackground: Color @Composable get() = MaterialTheme.colorScheme.background
private val ReportHistoryNavy: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val ReportHistoryMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportHistoryScreen(
    onBackClick: () -> Unit,
    onOpenReport: (TripReportHistoryItem) -> Unit
) {
    val context = LocalContext.current.applicationContext
    var historyItems by remember { mutableStateOf<List<TripReportHistoryItem>>(emptyList()) }

    LaunchedEffect(context) {
        historyItems = withContext(Dispatchers.IO) {
            TripReportLocator.get(context).listHistoryItems()
        }
    }

    Scaffold(
        containerColor = ReportHistoryBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Report history",
                        color = ReportHistoryNavy,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        BackNavigationIcon(ReportHistoryNavy)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ReportHistoryBackground,
                    navigationIconContentColor = ReportHistoryNavy,
                    titleContentColor = ReportHistoryNavy
                )
            )
        }
    ) { innerPadding ->
        if (historyItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ReportHistoryBackground)
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No completed reports yet.",
                    color = ReportHistoryMuted,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ReportHistoryBackground)
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 24.dp,
                    vertical = 20.dp
                )
            ) {
                items(historyItems, key = { it.tripId }) { item ->
                    HistoryReportRow(
                        item = item,
                        onClick = { onOpenReport(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryReportRow(
    item: TripReportHistoryItem,
    onClick: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text(
                    text = formatReportHistoryTimes(item.startedAtMillis, item.endedAtMillis),
                    color = ReportHistoryNavy,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${item.startingPointLabel} → ${item.destinationLabel}",
                    modifier = Modifier.padding(top = 6.dp),
                    color = ReportHistoryMuted,
                    fontSize = 15.sp
                )
            }
        }
    }
}

private fun formatReportHistoryTimes(startedAtMillis: Long, endedAtMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val dateAndStartTime = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a").withZone(zone)
    val time = DateTimeFormatter.ofPattern("h:mm a").withZone(zone)
    return "${dateAndStartTime.format(Instant.ofEpochMilli(startedAtMillis))} – " +
        time.format(Instant.ofEpochMilli(endedAtMillis))
}
