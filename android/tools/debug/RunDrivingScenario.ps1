$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$localPropertiesPath = Join-Path $projectRoot 'local.properties'
$sdkAdbPath = $null
if (Test-Path $localPropertiesPath) {
    $sdkLine = Get-Content $localPropertiesPath | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    if ($sdkLine) {
        $sdkPath = $sdkLine.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
        $sdkAdbPath = Join-Path $sdkPath 'platform-tools\adb.exe'
    }
}

$candidatePaths = @(@(
    (Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    $sdkAdbPath,
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
) | Where-Object { $_ -and (Test-Path $_) })

if ($candidatePaths.Count -eq 0) {
    Write-Host "Android Debug Bridge (adb) was not found. Open Android Studio's Emulator, then install its platform-tools." -ForegroundColor Red
    exit 1
}

$adb = $candidatePaths[0]
$component = 'com.fit5120tp10.nextcross/com.fit5120tp10.nextcross.debug.DebugScenarioCommandReceiver'
$action = 'com.fit5120tp10.nextcross.debug.RUN_SCENARIO'
$titleWindowWidth = $Host.UI.RawUI.WindowSize.Width
$connectedDevice = & $adb devices | Where-Object { $_ -match "`tdevice$" } | Select-Object -First 1
if (-not $connectedDevice) {
    Write-Host "No running Android Emulator was found. Start the Emulator in Android Studio, then run this console again." -ForegroundColor Red
    exit 1
}

function Send-Scenario([string]$scenario, [string]$feedback) {
    & $adb shell am broadcast -n $component -a $action --es scenario $scenario | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "The road goes quiet. Make sure your trip has started in NextCross, then try again." -ForegroundColor Red
        return $false
    }
    if (-not [string]::IsNullOrWhiteSpace($feedback)) {
        Write-Host "`n$feedback" -ForegroundColor Green
    }
    return $true
}

function Write-Centered([string]$text, [ConsoleColor]$foregroundColor) {
    $leftPadding = [Math]::Max(0, [Math]::Floor(($titleWindowWidth - $text.Length) / 2))
    Write-Host ((' ' * $leftPadding) + $text) -ForegroundColor $foregroundColor
}

function Write-Title([string]$title, [ConsoleColor]$foregroundColor) {
    $titleBorder = '======================================================'
    Write-Host ''
    Write-Centered $titleBorder Gray
    Write-Centered $title $foregroundColor
    Write-Centered $titleBorder Gray
    Write-Host ''
}

Write-Title 'NEXTCROSS: FIRST DRIVE' Gray
Write-Host ''

Write-Host 'Mum finally hands you the keys to her old car.'
Write-Host 'The engine hums. Melbourne is waiting, and this is your first drive alone.'
Write-Host ''
Write-Host 'For today, every road is imaginary and every risk stays on the screen.' -ForegroundColor Yellow
[void](Read-Host 'Press ENTER to start the engine')

$remainingScenarios = [ordered]@{
    '1' = [pscustomobject]@{
        Decision = 'Stay in the left lane, match the traffic, and keep things boring. Mum would approve.'
        Scenario = 'steady'
        Feedback = 'You take a breath and hold a calm, steady pace. No drama. No horn. A small win.'
    }
    '2' = [pscustomobject]@{
        Decision = 'Slip toward the West Gate Freeway on-ramp. The road opens up, and your right foot gets curious.'
        Scenario = 'accelerate'
        Feedback = 'The engine suddenly growls and you are pushed back into the seat. Okay... that was a bit much.'
    }
    '3' = [pscustomobject]@{
        Decision = 'Take St Kilda Road. The cars ahead keep bunching up, but you trust your reflexes.'
        Scenario = 'brake'
        Feedback = 'Brake lights flash ahead. Your foot hits the pedal and your heart jumps with it.'
    }
    '4' = [pscustomobject]@{
        Decision = 'Mute the navigation and follow your instincts. You know a shortcut... probably.'
        Scenario = 'outside_area'
        Feedback = 'The familiar streets disappear behind you. This shortcut is starting to feel less certain.'
    }
}

while ($remainingScenarios.Count -gt 0) {
    Write-Host "`n`n"
    if ($remainingScenarios.Count -eq 4) {
        Write-Title 'COLLINS STREET, MELBOURNE CBD' Cyan
        Write-Host 'It is peak hour. Trams rattle past, the lights change, and impatient commuters fill every lane.'
        Write-Host 'You tighten your grip on the wheel. What do you do next?'
    } elseif ($remainingScenarios.Count -eq 1) {
        Write-Title 'ONE LAST TURN' Cyan
        Write-Host 'The city lights blur in the mirror. One decision remains before you can finally breathe out.'
        Write-Host 'What do you do next?'
    } else {
        Write-Title 'MELBOURNE AFTER DARK' Cyan
        Write-Host 'You have made it through another stretch of road, but the night is not over yet.'
        Write-Host 'Where does instinct take you next?'
    }
    Write-Host ''
    Write-Host ''
    foreach ($entry in $remainingScenarios.GetEnumerator()) {
        Write-Host "$($entry.Key). $($entry.Value.Decision)" -ForegroundColor White
    }

    $choice = Read-Host "Choose your next move (choices left: $($remainingScenarios.Count))"
    if (-not $remainingScenarios.Contains($choice)) {
        Write-Host 'That choice has already passed. Pick one of the roads still ahead.' -ForegroundColor Yellow
        continue
    }

    $selectedScenario = $remainingScenarios[$choice]
    [void](Read-Host 'Press ENTER when you are ready to commit')

    if (Send-Scenario $selectedScenario.Scenario $selectedScenario.Feedback) {
        $remainingScenarios.Remove($choice)
    }
}

Write-Host ''
Write-Host '======================================================'
Write-Host 'The city finally settles behind you. Your phone buzzes quietly in your pocket.' -ForegroundColor DarkGray
Start-Sleep -Seconds 2
[void](Send-Scenario 'status' '')
[void](Read-Host 'Press ENTER when you are ready to park')

Write-Host "`n`n`n"
Write-Title 'HOME AT LAST' Green
Write-Host 'You turn off the engine and let out the breath you have been holding.'
Write-Host 'End the trip in NextCross, then open Summary to see how the night went.'
