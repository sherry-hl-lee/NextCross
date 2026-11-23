"""Conservative trajectory continuity and candidate-event aggregation.

This is a speed-zone heuristic, not a road-network routing algorithm. Ambiguous
nearby zones are never resolved merely by choosing the smallest distance.
"""
from datetime import datetime, timezone
from math import asin, cos, radians, sin, sqrt
from .speed_models import AnalysisResponse, Interval, SpeedEvent, TimelinePoint
from .speed_rules import choose_point_context


def distance(a, b):
    lat1, lat2 = radians(a.latitude), radians(b.latitude)
    h = sin((lat2-lat1)/2)**2 + cos(lat1)*cos(lat2)*sin(radians(b.longitude-a.longitude)/2)**2
    return 6371000 * 2 * asin(min(1, sqrt(h)))


def analyze_trip(request, config, data):
    candidates, rules, periods, coverage = data
    samples = sorted(request.samples, key=lambda s: (s.observed_at_epoch_millis, s.sample_id))
    normalization = ["sorted_by_timestamp"] if samples != request.samples else []
    origin = request.started_at_epoch_millis
    offset = lambda s: (s.observed_at_epoch_millis-origin)/1000
    contexts, reasons, zone_ids = [], [], []
    duplicate_times = set()
    for a, b in zip(samples, samples[1:]):
        if a.observed_at_epoch_millis == b.observed_at_epoch_millis:
            duplicate_times.add(a.observed_at_epoch_millis)
    for s in samples:
        rows = candidates.get(s.sample_id, [])
        context = choose_point_context(rows, rules,
            datetime.fromtimestamp(s.observed_at_epoch_millis/1000, timezone.utc), periods, coverage)
        contexts.append(context)
        zones = {str(r["source_speed_zone_id"]) for r in rows}
        zone_ids.append(next(iter(zones)) if len(zones) == 1 else None)
        reason = None
        if s.observed_at_epoch_millis in duplicate_times:
            reason = "DUPLICATE_TIMESTAMP"
        elif s.speed_mps is None:
            reason = "MISSING_SPEED"
        elif s.speed_mps > config.maximum_speed_mps:
            reason = "UNRELIABLE_SPEED"
        elif s.accuracy_meters is None or s.accuracy_meters > config.maximum_accuracy_meters:
            reason = "GPS_ACCURACY_UNRELIABLE"
        elif len(zones) > 1:
            reason = "ROAD_MATCH_UNCERTAIN"
        elif context.speed_kmh is None:
            reason = context.reason or context.status
        reasons.append(reason)
    # Require adjacent, usable, same-zone evidence; gaps and impossible jumps break support.
    supported = set()
    intervals = []
    breaks = set()
    for i in range(1, len(samples)):
        a, b = samples[i-1], samples[i]
        dt = (b.observed_at_epoch_millis-a.observed_at_epoch_millis)/1000
        reason = None
        if dt > config.maximum_gap_seconds:
            reason = "GPS_GAP"
        elif dt > 0 and distance(a,b) > config.maximum_speed_mps*dt + 2*config.maximum_accuracy_meters:
            reason = "GPS_JUMP"
        if reason:
            breaks.add(i)
            intervals.append(Interval(start_offset_seconds=offset(a), end_offset_seconds=offset(b), reason=reason))
        elif dt > 0 and not reasons[i-1] and not reasons[i] and zone_ids[i] == zone_ids[i-1]:
            supported.update([i-1, i])
    for i in range(len(samples)):
        if reasons[i] is None and i not in supported:
            reasons[i] = "INSUFFICIENT_TRAJECTORY_SUPPORT"
        if reasons[i]:
            intervals.append(Interval(start_offset_seconds=offset(samples[i]),
                                      end_offset_seconds=offset(samples[i]), reason=reasons[i]))
    events, run = [], []
    def finish():
        if len(run) < config.minimum_valid_samples:
            run.clear()
            return
        first, last = samples[run[0]], samples[run[-1]]
        duration = (last.observed_at_epoch_millis-first.observed_at_epoch_millis)/1000
        if duration >= config.minimum_duration_seconds:
            ctx = contexts[run[0]]
            speeds = [samples[j].speed_mps * 3.6 for j in run]
            peak = max(speeds)
            events.append(SpeedEvent(event_id=f"E_SPEED_{len(events)+1:04d}", trip_id=request.trip_id,
                start_sample_id=first.sample_id, end_sample_id=last.sample_id,
                start_offset_seconds=offset(first), end_offset_seconds=offset(last), duration_seconds=duration,
                reference_limit_kmh=ctx.speed_kmh, peak_speed_kmh=round(peak,3),
                peak_exceedance_kmh=round(peak-ctx.speed_kmh,3),
                average_exceedance_kmh=round(sum(speeds)/len(speeds)-ctx.speed_kmh,3),
                peak_exceedance_percent=round((peak-ctx.speed_kmh)/ctx.speed_kmh*100,3),
                source_speed_zone_id=ctx.source_speed_zone_id, limit_status=ctx.status))
        run.clear()
    for i,s in enumerate(samples):
        ctx = contexts[i]
        key = (ctx.source_speed_zone_id, ctx.speed_kmh, ctx.status)
        if run:
            previous = contexts[run[-1]]
            if i in breaks or key != (previous.source_speed_zone_id,previous.speed_kmh,previous.status):
                finish()
        if reasons[i] or s.speed_mps * 3.6 <= ctx.speed_kmh + config.tolerance_kmh:
            finish()
        else:
            run.append(i)
    finish()
    valid = sum(r is None for r in reasons)
    if samples and offset(samples[0]) > config.maximum_gap_seconds:
        intervals.append(Interval(start_offset_seconds=0, end_offset_seconds=offset(samples[0]), reason="GPS_GAP"))
    end_offset = (request.ended_at_epoch_millis-origin)/1000
    if samples and end_offset-offset(samples[-1]) > config.maximum_gap_seconds:
        intervals.append(Interval(start_offset_seconds=offset(samples[-1]), end_offset_seconds=end_offset, reason="GPS_GAP"))
    return AnalysisResponse(trip_id=request.trip_id,
        analysis_status="INSUFFICIENT_DATA" if valid < 2 else "PARTIAL" if intervals else "COMPLETED",
        road_matching_status="UNKNOWN" if valid < 2 else "PARTIAL" if intervals else "MATCHED",
        processed_sample_count=len(samples), events=events,
        unmatched_intervals=sorted(intervals,key=lambda x:x.start_offset_seconds),
        timeline=[TimelinePoint(sample_id=s.sample_id, offset_seconds=offset(s), context=contexts[i],
                 match_status="MATCHED" if not reasons[i] else "AMBIGUOUS" if reasons[i]=="ROAD_MATCH_UNCERTAIN" else "UNKNOWN",
                 reason=reasons[i]) for i,s in enumerate(samples)],
        normalization=normalization, detection_config=config)
