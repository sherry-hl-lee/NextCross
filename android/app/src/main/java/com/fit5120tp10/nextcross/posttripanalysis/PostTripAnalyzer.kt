package com.fit5120tp10.nextcross.posttripanalysis

/** Explicit pipeline: completed boundaries -> metrics -> score -> separate ranking. */
class PostTripAnalyzer(private val config: EventDetectionConfig = EventDetectionConfig()) {
    fun analyze(tripId: String, samples: List<PostTripGpsSample>): PostTripAnalysisResult {
        val detection = PostTripEventDetector(config).detect(tripId, samples)
        val scorer = EventSeverityScorer(config)
        val events = detection.events.map(scorer::score)
        return PostTripAnalysisResult(events, rankEventsForReview(events), detection.issues)
    }
}
