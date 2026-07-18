[CmdletBinding()]
param(
    [ValidateRange(1, 99)]
    [int]$Runs = 5,
    [ValidateRange(0, 10)]
    [int]$WarmupRuns = 1,
    [string]$Adb = 'C:\Android\platform-tools\adb.exe',
    [string]$InjectorPython = "$env:TEMP\dictate-grpc-venv\Scripts\python.exe",
    [string]$AudioFile = "$env:TEMP\dictate-openrouter-tts-16k.wav",
    [string]$GrpcStubs = "$env:TEMP\dictate-grpc-stubs",
    [string]$GrpcTarget = 'localhost:8554',
    [ValidateRange(5, 180)]
    [int]$TerminalTimeoutSeconds = 45
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$env:PYTHONDONTWRITEBYTECODE = '1'

$injector = Join-Path $PSScriptRoot 'inject_emulator_audio.py'
foreach ($required in @($Adb, $InjectorPython, $AudioFile, $GrpcStubs, $injector)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required benchmark input does not exist: $required"
    }
}

$expectedTranscript = 'Dies ist ein simulierter Mikrofontest für DECT. OpenRouter soll diesen deutschen Satz schnell und zuverlässig transkribieren.'
$script:testFieldTapX = 300
$script:testFieldTapY = 2260

function Get-RegexValue {
    param(
        [Parameter(Mandatory)] [string]$InputText,
        [Parameter(Mandatory)] [string]$Pattern,
        [int]$Group = 1
    )
    $match = [regex]::Match($InputText, $Pattern)
    if (-not $match.Success) {
        throw "Expected log pattern was not found: $Pattern"
    }
    $match.Groups[$Group].Value
}

function Test-InputMethodShown {
    $state = (& $Adb shell dumpsys input_method) -join "`n"
    $state -match 'mInputShown=true'
}

function Get-TestEditor {
    $deviceXml = '/sdcard/dictate-latency-editor.xml'
    $hostXml = Join-Path $env:TEMP 'dictate-latency-editor.xml'
    & $Adb shell uiautomator dump $deviceXml | Out-Null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    & $Adb pull $deviceXml $hostXml | Out-Null
    [xml]$hierarchy = Get-Content -LiteralPath $hostXml -Raw
    $editors = @($hierarchy.SelectNodes('//node[@class="android.widget.EditText"]'))
    if ($editors.Count -ne 1) {
        return $null
    }
    return $editors[0]
}

function Set-TestFieldTapFromEditor {
    param([Parameter(Mandatory)] $Editor)

    $bounds = [regex]::Match([string]$Editor.bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $bounds.Success) {
        throw "Unexpected test field bounds: $($Editor.bounds)"
    }
    $script:testFieldTapX = [math]::Floor(([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
    $script:testFieldTapY = [math]::Floor(([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
}

function Ensure-TestScreen {
    if (Test-InputMethodShown) {
        & $Adb shell input keyevent 4 | Out-Null
        Start-Sleep -Milliseconds 500
    }
    & $Adb shell am start -n 'net.devemperor.dictate.debug/dev.patrickgold.florisboard.SettingsLauncherAlias' | Out-Null
    Start-Sleep -Milliseconds 700

    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $editor = Get-TestEditor
        if ($null -ne $editor) {
            Set-TestFieldTapFromEditor $editor
            return
        }
        & $Adb shell input keyevent 4 | Out-Null
        Start-Sleep -Milliseconds 500
    }
    throw 'The benchmark could not navigate to the Dictate test screen'
}

function Test-AudioRecorderActive {
    $dump = (& $Adb shell dumpsys media.audio_flinger) -join "`n"
    $inputThreads = [regex]::Matches(
        $dump,
        '(?ms)^-? ?Input thread .*?(?=^-? ?Input thread |^Output thread |^Reroute submix|\z)'
    )
    foreach ($thread in $inputThreads) {
        if ($thread.Value -match 'Standby: no' -and
            $thread.Value -match '\d+ Tracks of which [1-9]\d* are active') {
            return $true
        }
    }
    return $false
}

function Wait-AudioRecorderActive {
    $deadline = (Get-Date).AddSeconds(10)
    while (-not (Test-AudioRecorderActive) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 150
    }
    if (-not (Test-AudioRecorderActive)) {
        throw 'Dictate did not activate an Android AudioRecord client before audio injection'
    }
}

function Show-DictateKeyboard {
    if (-not (Test-InputMethodShown)) {
        & $Adb shell input tap $script:testFieldTapX $script:testFieldTapY | Out-Null
    }
    $deadline = (Get-Date).AddSeconds(7)
    while (-not (Test-InputMethodShown) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 150
    }
    if (-not (Test-InputMethodShown)) {
        throw 'Dictate Keyboard is not visible on the focused test field'
    }
}

function Reset-TestField {
    Ensure-TestScreen
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Show-DictateKeyboard
        & $Adb shell input keycombination 113 29 | Out-Null # Ctrl+A
        Start-Sleep -Milliseconds 150
        & $Adb shell input keyevent 67 | Out-Null           # Delete
        Start-Sleep -Milliseconds 300

        # UIAutomator cannot reliably inspect the Compose editor while the IME owns the lower window.
        # Hide it, verify the editor is actually empty, then restore the same IME before timing starts.
        & $Adb shell input keyevent 4 | Out-Null
        Start-Sleep -Milliseconds 500
        $editor = Get-TestEditor
        if ($null -ne $editor) {
            Set-TestFieldTapFromEditor $editor
        }
        if ($null -ne $editor -and [string]$editor.text -eq '') {
            Show-DictateKeyboard
            Start-Sleep -Milliseconds 300
            return
        }
    }
    throw 'The benchmark could not reset the Compose test field to an empty value'
}

function Invoke-DictationRun {
    param(
        [Parameter(Mandatory)] [int]$Index,
        [Parameter(Mandatory)] [bool]$Warmup
    )

    # The settings screen's "Try out your setup" field and the Dictate mic button are stable at these
    # coordinates on the repository's Pixel 6 / API 34 benchmark AVD (1080 x 2400).
    Reset-TestField
    & $Adb logcat -c
    & $Adb shell input tap 1015 1625 | Out-Null
    Wait-AudioRecorderActive
    Start-Sleep -Milliseconds 200

    & $InjectorPython $injector $AudioFile --stubs $GrpcStubs --target $GrpcTarget --packet-ms 300
    if ($LASTEXITCODE -ne 0) {
        throw "Audio injection failed with exit code $LASTEXITCODE"
    }

    & $Adb shell input tap 1015 1625 | Out-Null
    $deadline = (Get-Date).AddSeconds($TerminalTimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 250
        $logs = (& $Adb logcat -d -s 'DictateLatency:I' 'DictateHTTP:I' '*:S') -join "`n"
    } until ($logs -match 'phase=terminal:' -or (Get-Date) -gt $deadline)

    $outcome = Get-RegexValue $logs 'phase=terminal:([^ ]+) totalMs=\d+'
    if ($outcome -ne 'success') {
        throw "Dictation run $Index ended with outcome '$outcome'"
    }
    if ($logs -notmatch 'status=200') {
        throw "Dictation run $Index did not receive HTTP 200"
    }

    $connection = [regex]::Match(
        $logs,
        'connectionAcquiredMs=(\d+) family=([^ ]+) protocol=([^ ]+) reused=([^\r\n]+)'
    )
    [pscustomobject]@{
        run          = $Index
        warmup       = $Warmup
        total_ms     = [int](Get-RegexValue $logs 'phase=terminal:success totalMs=(\d+)')
        recorder_ms  = [int](Get-RegexValue $logs 'phase=recorderStopped phaseMs=(\d+)')
        gate_ms      = [int](Get-RegexValue $logs 'phase=speechGateCompleted phaseMs=(\d+)')
        provider_ms  = [int](Get-RegexValue $logs 'phase=providerCompleted phaseMs=(\d+)')
        finalize_ms  = [int](Get-RegexValue $logs 'phase=finalizeCompleted phaseMs=(\d+)')
        http_ms      = [int](Get-RegexValue $logs 'applicationAttempt=1 completedMs=(\d+)')
        ttfb_ms      = [int](Get-RegexValue $logs 'ttfbMs=(\d+)')
        connection_ms = if ($connection.Success) { [int]$connection.Groups[1].Value } else { $null }
        family       = if ($connection.Success) { $connection.Groups[2].Value } else { $null }
        protocol     = if ($connection.Success) { $connection.Groups[3].Value } else { $null }
        reused       = if ($connection.Success) { $connection.Groups[4].Value.Trim() -eq 'true' } else { $null }
    }
}

for ($i = 1; $i -le $WarmupRuns; $i++) {
    Invoke-DictationRun -Index $i -Warmup $true | ConvertTo-Json -Compress
}

$measurements = @()
for ($i = 1; $i -le $Runs; $i++) {
    $measurement = Invoke-DictationRun -Index $i -Warmup $false
    $measurements += $measurement
    $measurement | ConvertTo-Json -Compress
}

# Validate the actual text committed into the Compose field, not merely the provider's HTTP response.
& $Adb shell input keyevent 4 | Out-Null
Start-Sleep -Milliseconds 700
$editor = Get-TestEditor
if ($null -eq $editor -or [string]$editor.text -ne $expectedTranscript) {
    throw 'The committed transcript did not exactly match the expected German sentence'
}
Set-TestFieldTapFromEditor $editor
Show-DictateKeyboard

$sorted = @($measurements.total_ms | Sort-Object)
$middle = [math]::Floor($sorted.Count / 2)
$median = if ($sorted.Count % 2 -eq 1) {
    [double]$sorted[$middle]
} else {
    ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2
}

[pscustomobject]@{
    metric = 'warm_stop_to_committed_text_median_ms'
    runs = $Runs
    median_ms = $median
    min_ms = ($sorted | Measure-Object -Minimum).Minimum
    max_ms = ($sorted | Measure-Object -Maximum).Maximum
    transcript_guard = 'pass'
} | ConvertTo-Json -Compress

# Auto-Research scalar metric contract: the final non-empty line is exactly one finite number.
$median.ToString([System.Globalization.CultureInfo]::InvariantCulture)
