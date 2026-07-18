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

function Invoke-DictationRun {
    param(
        [Parameter(Mandatory)] [int]$Index,
        [Parameter(Mandatory)] [bool]$Warmup
    )

    # The settings screen's "Try out your setup" field and the Dictate mic button are stable at these
    # coordinates on the repository's Pixel 6 / API 34 benchmark AVD (1080 x 2400).
    & $Adb shell input tap 500 1350 | Out-Null
    & $Adb shell input keycombination 113 29 | Out-Null # Ctrl+A
    & $Adb shell input keyevent 67 | Out-Null           # Delete
    & $Adb logcat -c
    & $Adb shell input tap 1015 1625 | Out-Null
    Start-Sleep -Milliseconds 500

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

if (-not (Test-InputMethodShown)) {
    & $Adb shell input tap 300 2260 | Out-Null
    Start-Sleep -Milliseconds 700
}
if (-not (Test-InputMethodShown)) {
    throw 'Dictate Keyboard is not visible on the focused test field'
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
$deviceXml = '/sdcard/dictate-latency-benchmark.xml'
$hostXml = Join-Path $env:TEMP 'dictate-latency-benchmark.xml'
& $Adb shell uiautomator dump $deviceXml | Out-Null
& $Adb pull $deviceXml $hostXml | Out-Null
$hierarchy = Get-Content -LiteralPath $hostXml -Raw
if (-not $hierarchy.Contains($expectedTranscript)) {
    throw 'The committed transcript did not exactly match the expected German sentence'
}
& $Adb shell input tap 300 2260 | Out-Null
Start-Sleep -Milliseconds 700

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
