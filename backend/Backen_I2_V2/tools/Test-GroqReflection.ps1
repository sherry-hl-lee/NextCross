# Sends ONE synthetic event through the local backend to Groq; may consume API quota.
param([string]$BaseUrl = 'http://127.0.0.1:8000')
$payload = @{
    tripId = 'synthetic-test-trip'
    selectedEvent = @{
        eventId = 'synthetic-event-1'
        type = 'RAPID_DECELERATION'
        startOffsetSeconds = 301
        endOffsetSeconds = 304
        durationSeconds = 3
        dataQuality = 'GOOD'
        detectorVersion = 'accel-v1'
        metrics = @{
            speedBeforeKmh = 62
            speedAfterKmh = 34
            peakAccelerationMs2 = -4.8
        }
    }
}
$body = $payload | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/ai/reflection" -ContentType 'application/json' -Body $body | ConvertTo-Json -Depth 8
