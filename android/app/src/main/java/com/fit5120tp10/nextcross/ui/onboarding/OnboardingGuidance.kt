package com.fit5120tp10.nextcross.ui.onboarding

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Each guide is independently eligible; no screen depends on another guide completing. */
enum class OnboardingGuide {
    HomeStartTrip,
    HomeEmailSettings,
    TripSetupLocations,
    TripSetupArea,
    TripSummaryReflection,
    TripReportShare
}

enum class CoachMarkMessagePlacement {
    Bottom,
    AboveTarget,
    BelowTarget,
    Automatic
}

@Stable
class OnboardingGuideController(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var revision by mutableIntStateOf(0)

    init {
        if (!preferences.getBoolean(KEY_INITIALIZED, false)) {
            preferences.edit()
                .putBoolean(KEY_INITIALIZED, true)
                // A pre-existing installation is an upgrade, not a new-user session.
                .putBoolean(KEY_AUTOMATIC_ENABLED, isFreshInstall(context))
                .apply()
        }
    }

    fun shouldShow(guide: OnboardingGuide): Boolean {
        revision
        return preferences.getBoolean(replayKey(guide), false) ||
            (preferences.getBoolean(KEY_AUTOMATIC_ENABLED, false) &&
                !preferences.getBoolean(seenKey(guide), false))
    }

    /** Completes only this guide. It never changes the eligibility of another screen. */
    fun complete(guide: OnboardingGuide) {
        preferences.edit()
            .putBoolean(seenKey(guide), true)
            .putBoolean(replayKey(guide), false)
            .apply()
        revision += 1
    }

    /** Lets the Home help button make every guide eligible once again. */
    fun restartAllGuides() {
        preferences.edit().apply {
            OnboardingGuide.entries.forEach { guide -> putBoolean(replayKey(guide), true) }
        }.apply()
        revision += 1
    }

    private companion object {
        const val PREFERENCES_NAME = "nextcross_onboarding"
        const val KEY_INITIALIZED = "initialized"
        const val KEY_AUTOMATIC_ENABLED = "automatic_enabled"

        fun seenKey(guide: OnboardingGuide) = "shown_${guide.name}"
        fun replayKey(guide: OnboardingGuide) = "replay_${guide.name}"

        @Suppress("DEPRECATION")
        fun isFreshInstall(context: Context): Boolean = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
                .let { it.firstInstallTime == it.lastUpdateTime }
        }.getOrDefault(false)
    }
}

@Composable
fun rememberOnboardingGuideController(context: Context): OnboardingGuideController {
    val appContext = context.applicationContext
    return remember(appContext) { OnboardingGuideController(appContext) }
}

/**
 * A UI-only coach mark. Tapping outside the action completes only the displayed
 * guide, so it can never silently cancel a later guide.
 */
@Composable
fun OnboardingGuideOverlay(
    message: String,
    actionLabel: String,
    onComplete: () -> Unit,
    targetBounds: Rect,
    messagePlacement: CoachMarkMessagePlacement = CoachMarkMessagePlacement.Automatic,
    modifier: Modifier = Modifier,
) {
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val estimatedCalloutHeight = with(density) { 150.dp.toPx() }
    val calloutHeight = estimatedCalloutHeight
    val margin = with(density) { 24.dp.toPx() }
    val belowTarget = targetBounds.bottom + calloutHeight + margin
    val aboveTarget = targetBounds.top - calloutHeight - margin
    val calloutTop = when (messagePlacement) {
        CoachMarkMessagePlacement.Bottom -> overlaySize.height * 0.62f
        CoachMarkMessagePlacement.AboveTarget -> aboveTarget
        CoachMarkMessagePlacement.BelowTarget -> belowTarget
        CoachMarkMessagePlacement.Automatic -> if (belowTarget <= overlaySize.height - margin) belowTarget else aboveTarget
    }.coerceIn(margin, (overlaySize.height - calloutHeight - margin).coerceAtLeast(margin))

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { overlaySize = it }
            .clickable(onClick = onComplete),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            drawRect(Color.Black.copy(alpha = 0.84f))
            val corner = CornerRadius(20.dp.toPx(), 20.dp.toPx())
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(targetBounds.left, targetBounds.top),
                size = Size(targetBounds.width, targetBounds.height),
                cornerRadius = corner,
                blendMode = BlendMode.Clear
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(targetBounds.left, targetBounds.top),
                size = Size(targetBounds.width, targetBounds.height),
                cornerRadius = corner,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .offset { IntOffset(0, calloutTop.toInt()) }
                .drawBehind {
                    drawRoundRect(
                        color = Color.White,
                        cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(8.dp.toPx(), 6.dp.toPx())
                            )
                        )
                    )
                }
                .padding(18.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = message,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .height(28.dp)
                    .border(1.dp, Color.White, RoundedCornerShape(8.dp))
                    .clickable(onClick = onComplete)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = actionLabel, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
