param(
    [Parameter(Mandatory=$true)][string]$RecipientEmail,
    [string]$BaseUrl = 'http://localhost:8000',
    [string]$RequestId = ([guid]::NewGuid().ToString()),
    [string]$PdfPath = '',
    [string]$MapPath = ''
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$meta = Get-Content (Join-Path $PSScriptRoot '..\docs\trip_report_email_meta.json') -Raw | ConvertFrom-Json
$meta.parentEmail = $RecipientEmail
$meta.clientRequestId = $RequestId
$meta.includeDetailedRoute = ($MapPath -ne '')
Write-Host "Request ID: $RequestId (reuse this SAME ID after timeout; do not auto-resend)."
Write-Host 'Synthetic real-send test. Only use an authorised test recipient.'
if ((Read-Host 'Type SEND to submit one real email') -cne 'SEND') { return }
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(120)
$form = [System.Net.Http.MultipartFormDataContent]::new()
try {
    $text = [System.Net.Http.StringContent]::new(($meta | ConvertTo-Json -Depth 10), [System.Text.Encoding]::UTF8, 'application/json')
    $form.Add($text, 'meta')
    foreach ($item in @(@{Part='reportPdf';Path=$PdfPath}, @{Part='routeMap';Path=$MapPath})) {
        if ($item.Path -eq '') { continue }
        $resolved = (Resolve-Path $item.Path).Path
        $mime = 'application/pdf'
        if ($item.Part -eq 'routeMap') {
            $ext = [System.IO.Path]::GetExtension($resolved).ToLowerInvariant()
            if ($ext -eq '.png') { $mime = 'image/png' }
            elseif ($ext -in @('.jpg','.jpeg')) { $mime = 'image/jpeg' }
            else { throw 'Map must be PNG or JPEG.' }
        }
        $content = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($resolved))
        $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new($mime)
        $form.Add($content, $item.Part, [System.IO.Path]::GetFileName($resolved))
    }
    $response = $client.PostAsync(($BaseUrl.TrimEnd('/') + '/api/v1/trip-reports/email'), $form).GetAwaiter().GetResult()
    try {
        Write-Host ('HTTP ' + [int]$response.StatusCode)
        $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    } finally { $response.Dispose() }
} finally { $form.Dispose(); $client.Dispose() }
