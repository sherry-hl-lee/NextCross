param(
    [string]$BaseUrl = 'http://127.0.0.1:8000',
    [string]$DriverContext = 'Traffic stopped ahead of me.'
)
$ErrorActionPreference = 'Stop'
# Two real provider calls: synthetic event only, consumes provider quota.
$envelope = @{
    tripId = 'synthetic-two-turn-trip'
    selectedEvent = @{
        eventId = 'synthetic-braking-event'
        type = 'RAPID_DECELERATION'
        startOffsetSeconds = 180
        endOffsetSeconds = 183
        durationSeconds = 3
        dataQuality = 'UNKNOWN'
        detectorVersion = 'synthetic-test-v1'
        metrics = @{ speedBeforeKmh = 62; speedAfterKmh = 34; peakAccelerationMs2 = -4.8 }
    }
}
$base = $BaseUrl.TrimEnd('/')
$first = Invoke-RestMethod -Method Post -Uri "$base/api/v1/ai/reflection/question" -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes(($envelope | ConvertTo-Json -Depth 10)))
$first | ConvertTo-Json -Depth 10
if ($first.aiStatus -ne 'COMPLETED') { throw "Question failed: $($first.reason)" }
if ($first.eventId -ne $envelope.selectedEvent.eventId -or $first.tripId -ne $envelope.tripId) { throw 'Question trip/event mismatch' }
$envelope.questionAsked = $first.question.question
$envelope.driverContext = $DriverContext
$second = Invoke-RestMethod -Method Post -Uri "$base/api/v1/ai/reflection/advice" -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes(($envelope | ConvertTo-Json -Depth 10)))
$second | ConvertTo-Json -Depth 10
if ($second.aiStatus -ne 'COMPLETED') { throw "Advice failed: $($second.reason)" }
if ($second.eventId -ne $envelope.selectedEvent.eventId -or $second.tripId -ne $envelope.tripId) { throw 'Advice trip/event mismatch' }
Write-Host 'Both turns completed. Review wording manually; this is not a semantic-safety certification.'
