param(
    [Parameter(Mandatory=$true)][string]$RecipientEmail,
    [string]$BaseUrl = 'http://127.0.0.1:8000',
    [string]$RequestId = [guid]::NewGuid().ToString()
)
$ErrorActionPreference = 'Stop'
# Real email send: only use a mailbox you control/are authorised to test.
# Keep RequestId unchanged if querying again after a timeout; do not auto-resend.
Write-Host "RequestId: $RequestId"
$report = Get-Content (Join-Path $PSScriptRoot '..\docs\report_preview_request.json') -Raw | ConvertFrom-Json
$base = $BaseUrl.TrimEnd('/')
$preview = Invoke-RestMethod -Uri "$base/api/v1/reports/preview" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes(($report | ConvertTo-Json -Depth 20)))
$preview.reportText
$answer = Read-Host "Send this synthetic report to $RecipientEmail now? Type SEND"
if ($answer -ne 'SEND') { Write-Host 'Cancelled'; return }
$body = @{requestId=$RequestId; recipientEmail=$RecipientEmail; confirmedByDriver=$true; report=$report}
Invoke-RestMethod -Uri "$base/api/v1/reports/email" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 20))) | ConvertTo-Json -Depth 20
