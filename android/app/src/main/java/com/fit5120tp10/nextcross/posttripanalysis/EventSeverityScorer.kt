package com.fit5120tp10.nextcross.posttripanalysis

import kotlin.math.abs

/**
 * Scores completed events for review, independently of event detection.
 *
 * Components are clamped ratios in 0..1; weighted contributions and the total are
 * points in 0..100. This is an experimental review priority (复盘优先级), not a crash
 * probability or a judgement that an observed manoeuvre was unsafe.
 *
 * Context that is only known after the trip, such as overlapping a speed-limit event or
 * happening late in a long drive, is added by the review layer
 * (`client.posttrip.ReviewPriority`), not here.
 */
class EventSeverityScorer(private val config: EventDetectionConfig = EventDetectionConfig()) {
    fun score(event: DetectedManoeuvre): DrivingEvent {
        val accelerationComponent =
            (abs(event.peakAccelerationMps2) / config.severeAccelerationReferenceMps2)
                .coerceIn(0.0, 1.0)
        val speedChangeComponent =
            (event.deltaSpeedMps / config.severeSpeedChangeReferenceMps)
                .coerceIn(0.0, 1.0)
        val durationComponent =
            (event.durationSeconds / config.longEventReferenceSeconds)
                .coerceIn(0.0, 1.0)
        // The higher of the two speeds: where braking started, or what acceleration reached.
        val eventSpeedComponent =
            (maxOf(event.speedBeforeMps, event.speedAfterMps) / config.eventSpeedReferenceMps)
                .coerceIn(0.0, 1.0)

        // Clamp point totals as well to contain permitted floating-point weight tolerance.
        val accelerationContribution =
            (100.0 * config.accelerationWeight * accelerationComponent).coerceIn(0.0, 100.0)
        val speedChangeContribution =
            (100.0 * config.speedChangeWeight * speedChangeComponent).coerceIn(0.0, 100.0)
        val durationContribution =
            (100.0 * config.durationWeight * durationComponent).coerceIn(0.0, 100.0)
        val eventSpeedContribution =
            (100.0 * config.eventSpeedWeight * eventSpeedComponent).coerceIn(0.0, 100.0)

        return DrivingEvent(
            manoeuvre = event,
            scoreEvidence = EventSeverityScore(
                total = (
                    accelerationContribution + speedChangeContribution +
                        durationContribution + eventSpeedContribution
                    ).coerceIn(0.0, 100.0),
                accelerationComponent = accelerationComponent,
                speedChangeComponent = speedChangeComponent,
                durationComponent = durationComponent,
                accelerationContribution = accelerationContribution,
                speedChangeContribution = speedChangeContribution,
                durationContribution = durationContribution,
                configSnapshot = config,
                eventSpeedComponent = eventSpeedComponent,
                eventSpeedContribution = eventSpeedContribution
            )
        )
    }
}
