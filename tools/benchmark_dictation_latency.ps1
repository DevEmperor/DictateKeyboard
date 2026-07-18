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
$deviceXml = "/sdcard/dictate-latency-benchmark-$PID.xml"
$hostXml = Join-Path $env:TEMP "dictate-latency-benchmark-$PID.xml"
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

function Get-FocusedFieldText {
    & $Adb shell uiautomator dump $deviceXml | Out-Null
    & $Adb pull $deviceXml $hostXml | Out-Null
    $hierarchy = [xml](Get-Content -LiteralPath $hostXml -Raw)
    $focusedTextFields = @($hierarchy.SelectNodes('//node[@focused="true" and contains(@class,"EditText")]'))
    if ($focusedTextFields.Count -ne 1) {
        throw "Expected exactly one focused EditText, found $($focusedTextFields.Count)"
    }
    [string]$focusedTextFields[0].text
}

function Clear-FocusedField {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        & $Adb shell input tap 500 1350 | Out-Null
        & $Adb shell input keycombination 113 29 | Out-Null # Ctrl+A
        & $Adb shell input keyevent 67 | Out-Null           # Delete
        Start-Sleep -Milliseconds 250
        if ((Get-FocusedFieldText).Length -eq 0) {
            return
        }
    }
    throw 'The focused benchmark field did not become empty'
}

function Invoke-DictationRun {
    param(
        [Parameter(Mandatory)] [int]$Index,
        [Parameter(Mandatory)] [bool]$Warmup
    )

    # The settings screen's "Try out your setup" field and the Dictate mic button are stable at these
    # coordinates on the repository's Pixel 6 / API 34 benchmark AVD (1080 x 2400).
    Clear-FocusedField
    & $Adb logcat -c
    & $Adb shell input tap 1015 1625 | Out-Null
    Start-Sleep -Milliseconds 500

    # injectAudio is a client-streaming RPC. Let its request iterator finish, then stop AudioRecord while
    # the injector continues waiting for the emulator's real completion status. This avoids making the
    # microphone lifetime depend on an acknowledgement that some emulator states only send after stop.
    $runKind = if ($Warmup) { 'warmup' } else { 'measurement' }
    $markerPrefix = Join-Path $env:TEMP "dictate-audio-$PID-$runKind-$Index"
    $sentMarker = "$markerPrefix-sent"
    $completedMarker = "$markerPrefix-completed"
    Remove-Item -LiteralPath $sentMarker, $completedMarker -Force -ErrorAction SilentlyContinue
    $injectArgs = @(
        $injector, $AudioFile,
        '--stubs', $GrpcStubs,
        '--target', $GrpcTarget,
        '--packet-ms', '300',
        '--sent-marker', $sentMarker,
        '--completed-marker', $completedMarker
    )
    $injectProcess = Start-Process -FilePath $InjectorPython -ArgumentList $injectArgs -PassThru -NoNewWindow
    try {
        $streamDeadline = (Get-Date).AddSeconds(30)
        while (-not (Test-Path -LiteralPath $sentMarker)) {
            if ($injectProcess.HasExited) {
                throw 'Audio injection exited before streaming completed'
            }
            if ((Get-Date) -gt $streamDeadline) {
                throw 'Audio injection did not finish streaming within 30 seconds'
            }
            Start-Sleep -Milliseconds 100
        }

        & $Adb shell input tap 1015 1625 | Out-Null
        if (-not $injectProcess.WaitForExit(10000)) {
            throw 'Audio injection did not receive an emulator completion status after recording stopped'
        }
        if (-not (Test-Path -LiteralPath $completedMarker)) {
            throw 'Audio injection did not report successful emulator completion'
        }
    } finally {
        if (-not $injectProcess.HasExited) {
            Stop-Process -Id $injectProcess.Id -Force
            $injectProcess.WaitForExit()
        }
        Remove-Item -LiteralPath $sentMarker, $completedMarker -Force -ErrorAction SilentlyContinue
    }

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
        total_ms     = [int](Get-RegexValue $logs 'phase=outputCommitted totalMs=(\d+)')
        terminal_ms  = [int](Get-RegexValue $logs 'phase=terminal:success totalMs=(\d+)')
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

# Validate the actual text committed into the Compose field while the IME remains visible. Closing and
# immediately reopening the keyboard is unrelated to transcription and destabilizes the API 34 renderer.
if ((Get-FocusedFieldText) -cne $expectedTranscript) {
    throw 'The committed transcript did not exactly match the expected German sentence'
}

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
