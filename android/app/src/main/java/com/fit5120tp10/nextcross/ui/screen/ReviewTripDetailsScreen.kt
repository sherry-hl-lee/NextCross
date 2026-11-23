package com.fit5120tp10.nextcross.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.fit5120tp10.nextcross.client.permittedarea.PermittedArea
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace
import com.fit5120tp10.nextcross.ui.theme.NextCrossTheme

private val ReviewBackground: Color @Composable get() = MaterialTheme.colorScheme.background
private val ReviewNavy: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val ReviewBlue = Color(0xFF1769E0)
private val ReviewDivider = Color(0xFFDCE3ED)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReviewTripDetailsScreen(
    startingPoint: String,
    destination: String,
    parentEmail: String,
    originPlace: SelectedPlace? = null,
    destinationPlace: SelectedPlace? = null,
    permittedArea: PermittedArea? = null,
    fragmentActivity: FragmentActivity? = null,
    initialCamera: TripMapCamera? = null,
    isConsentAccepted: Boolean = false,
    onConsentAcceptedChange: (Boolean) -> Unit = {},
    onBackClick: () -> Unit = {},
    onContinue: () -> Unit = {}
) {
    var showConsentStatement by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ReviewBackground,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        BackNavigationIcon(ReviewNavy)
                    }
                },
                title = {
                    Text(
                        text = "Review Trip Details",
                        color = ReviewNavy,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ReviewBackground
                )
            )
        },
        bottomBar = {
            ContinueFooter(
                enabled = isConsentAccepted,
                onContinue = onContinue
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(ReviewBackground)
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                ReviewMapCard(
                    originPlace = originPlace,
                    destinationPlace = destinationPlace,
                    permittedArea = permittedArea,
                    fragmentActivity = fragmentActivity,
                    initialCamera = initialCamera
                )
            }
            item {
                TripDetailsCard(
                    startingPoint = startingPoint,
                    destination = destination,
                    parentEmail = parentEmail
                )
            }
            item {
                ConsentCard(
                    acknowledged = isConsentAccepted,
                    onAcknowledgedChange = onConsentAcceptedChange,
                    onReadStatement = { showConsentStatement = true }
                )
            }
        }
    }

    if (showConsentStatement) {
        InformedConsentSheet(onDismiss = { showConsentStatement = false })
    }
}

@Composable
private fun ConsentAcknowledgement(
    acknowledged: Boolean,
    onAcknowledgedChange: (Boolean) -> Unit,
    onReadStatement: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onAcknowledgedChange(!acknowledged) }
        ) {
            Checkbox(
                checked = acknowledged,
                onCheckedChange = onAcknowledgedChange
            )
            Text(
                text = "Informed consent",
                color = ReviewNavy,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = "Read the informed consent statement",
            modifier = Modifier
                .padding(start = 48.dp, top = 2.dp)
                .clickable(onClick = onReadStatement),
            color = ReviewBlue,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = TextDecoration.Underline
        )
    }
}

@Composable
private fun ConsentCard(
    acknowledged: Boolean,
    onAcknowledgedChange: (Boolean) -> Unit,
    onReadStatement: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        ConsentAcknowledgement(
            acknowledged = acknowledged,
            onAcknowledgedChange = onAcknowledgedChange,
            onReadStatement = onReadStatement
        )
    }
}

@Composable
private fun ContinueFooter(
    enabled: Boolean,
    onContinue: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Button(
                onClick = onContinue,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
                )
            ) {
                Text(
                    text = "Continue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun InformedConsentSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
        ) {
            Text(
                text = "Informed consent",
                modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp),
                color = ReviewNavy,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    ConsentSection(
                        icon = ConsentIconType.Device,
                        title = "Recorded on this device",
                        body = "Your location, speed and driving events are recorded from Start Trip to End Trip. After the trip, necessary coordinates and timestamps may be sent for a road speed check.",
                        showDivider = true
                    )
                }
                item {
                    ConsentSection(
                        icon = ConsentIconType.Email,
                        title = "A parent email if you leave the area",
                        body = "Your parent receives the trip label and event time. The email does not include your exact route or coordinates.",
                        showDivider = true
                    )
                }
                item {
                    ConsentSection(
                        icon = ConsentIconType.Ai,
                        title = "AI reflection is optional, after driving",
                        body = "Using reflection sends selected driving-event facts and your answer to an AI service. They are not automatically sent to your parent.",
                        showDivider = true
                    )
                }
                item {
                    ConsentSection(
                        icon = ConsentIconType.Share,
                        title = "You choose whether to share the report",
                        body = "You can reopen or delete reports in Recent reports and review the content before sharing.",
                        showDivider = false
                    )
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 20.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = "Got it",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ConsentSection(
    icon: ConsentIconType,
    title: String,
    body: String,
    showDivider: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            ConsentIcon(type = icon)
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    color = ReviewNavy,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = body,
                    color = ReviewNavy.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ReviewDivider)
            )
        }
    }
}

private enum class ConsentIconType { Device, Email, Ai, Share }

@Composable
private fun ConsentIcon(type: ConsentIconType) {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = ReviewBlue.copy(alpha = 0.1f)
    ) {
        Canvas(modifier = Modifier.padding(10.dp)) {
            val stroke = 1.8.dp.toPx()
            when (type) {
                ConsentIconType.Device -> {
                    drawRoundRect(
                        color = ReviewBlue,
                        topLeft = Offset(size.width * .28f, size.height * .1f),
                        size = androidx.compose.ui.geometry.Size(size.width * .44f, size.height * .8f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                        style = Stroke(width = stroke)
                    )
                    drawCircle(
                        color = ReviewBlue,
                        radius = 1.2.dp.toPx(),
                        center = Offset(size.width * .5f, size.height * .78f)
                    )
                }
                ConsentIconType.Email -> {
                    drawRoundRect(
                        color = ReviewBlue,
                        topLeft = Offset(size.width * .08f, size.height * .24f),
                        size = androidx.compose.ui.geometry.Size(size.width * .84f, size.height * .55f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                        style = Stroke(width = stroke)
                    )
                    drawLine(ReviewBlue, Offset(size.width * .12f, size.height * .3f), Offset(size.width * .5f, size.height * .56f), stroke)
                    drawLine(ReviewBlue, Offset(size.width * .5f, size.height * .56f), Offset(size.width * .88f, size.height * .3f), stroke)
                }
                ConsentIconType.Ai -> {
                    drawCircle(
                        color = ReviewBlue,
                        radius = size.minDimension * .16f,
                        center = Offset(size.width * .5f, size.height * .5f),
                        style = Stroke(width = stroke)
                    )
                    drawLine(ReviewBlue, Offset(size.width * .5f, 0f), Offset(size.width * .5f, size.height * .22f), stroke)
                    drawLine(ReviewBlue, Offset(size.width * .5f, size.height * .78f), Offset(size.width * .5f, size.height), stroke)
                    drawLine(ReviewBlue, Offset(0f, size.height * .5f), Offset(size.width * .22f, size.height * .5f), stroke)
                    drawLine(ReviewBlue, Offset(size.width * .78f, size.height * .5f), Offset(size.width, size.height * .5f), stroke)
                }
                ConsentIconType.Share -> {
                    val top = Offset(size.width * .72f, size.height * .22f)
                    val left = Offset(size.width * .25f, size.height * .5f)
                    val bottom = Offset(size.width * .72f, size.height * .78f)
                    drawLine(ReviewBlue, top, left, stroke)
                    drawLine(ReviewBlue, left, bottom, stroke)
                    listOf(top, left, bottom).forEach { point ->
                        drawCircle(color = ReviewBlue, radius = 3.2.dp.toPx(), center = point)
                    }
                }
            }
        }
    }
}

@Composable
private fun MapLegend() {
    Row(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(4.dp)
                .background(ReviewBlue, RoundedCornerShape(2.dp))
        )
        Text("Planned route", color = ReviewNavy.copy(alpha = 0.68f), style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.width(10.dp))
        Surface(
            modifier = Modifier.size(14.dp),
            shape = CircleShape,
            color = Color(0xFFBEE8C9),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38A169))
        ) {}
        Text("Permitted area", color = ReviewNavy.copy(alpha = 0.68f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReviewMapCard(
    originPlace: SelectedPlace?,
    destinationPlace: SelectedPlace?,
    permittedArea: PermittedArea?,
    fragmentActivity: FragmentActivity?,
    initialCamera: TripMapCamera?
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            ) {
                if (permittedArea != null && fragmentActivity != null) {
                    PlannedTripAreaMap(
                        fragmentActivity = fragmentActivity,
                        fragmentTag = "review_trip_map",
                        areaCentre = permittedArea.centre,
                        radiusMeters = permittedArea.radiusKilometres * 1_000.0,
                        originPlace = originPlace,
                        destinationPlace = destinationPlace,
                        modifier = Modifier.fillMaxSize(),
                        initialCamera = initialCamera
                    )
                } else {
                    Text(
                        text = "Map preview unavailable",
                        modifier = Modifier.align(Alignment.Center),
                        color = ReviewNavy.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            MapLegend()
        }
    }
}

@Composable
private fun TripDetailsCard(
    startingPoint: String,
    destination: String,
    parentEmail: String
) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Text(
                text = "Trip details",
                color = ReviewNavy,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            TripRouteEndpoints(
                startingPoint = startingPoint,
                destination = destination
            )
            SummaryDivider()
            SummaryRow(label = "Parent email", value = parentEmail)
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.42f),
            color = ReviewNavy.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.58f),
            color = ReviewNavy,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun SummaryDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .height(1.dp)
            .background(ReviewDivider)
    )
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun ReviewTripDetailsScreenPreview() {
    NextCrossTheme {
        ReviewTripDetailsScreen(
            startingPoint = "Current location",
            destination = "Monash University Clayton",
            parentEmail = "parent@example.com"
        )
    }
}
