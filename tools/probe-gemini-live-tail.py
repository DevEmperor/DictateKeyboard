#!/usr/bin/env python3
"""Ask Gemini's Live endpoint what it really does around the end of a dictation (issue #372).

A realtime stop is a negotiation we cannot see from inside the app: the microphone closes, we send
`audioStreamEnd`, and everything after that is the provider's schedule. Guessing at it is how the
tail of a dictation came to be cut off — so this streams a clip at real-time pace with the exact
setup frame `GeminiRealtimeSession` sends, and timestamps every frame that comes back, relative to
the end of the audio.

What it answers:

  * how long after `audioStreamEnd` the closing `inputTranscription` arrives — the frame that carries
    the last word or two, and the only one that does,
  * whether `turnComplete` / `generationComplete` arrive at all (they do, and they also end every
    *turn*, so one can belong to a pause in the middle rather than to the stop),
  * whether the server ever closes the socket by itself (it does not),
  * whether a settled `inputTranscription` repeats earlier text or carries only its own turn.

Measured 2026-09-14 on `gemini-3.5-transcribe-live`: the closing final lands 0.26–0.51 s after
`audioStreamEnd` for a 6 s and for a 36 s dictation alike, `generationComplete` follows it, and the
socket stays open indefinitely.

**Probe audio must be real speech.** `espeak` and other formant synthesis is discarded by the Live
API's voice-activity detection without a word of complaint: setup is acked, the frames are accepted,
and nothing at all comes back — which reads exactly like a broken protocol. (The same clip
transcribes perfectly on the batch endpoint, which has no such gate.) `--say` therefore generates the
clip with Gemini's own TTS, which needs no credentials beyond the key already in hand.

Usage:
    GEMINI_API_KEY=... python3 tools/probe-gemini-live-tail.py --say "Ein Satz zum Vorlesen." --runs 3
    GEMINI_API_KEY=... python3 tools/probe-gemini-live-tail.py recording.wav --runs 3

A WAV given directly must be 16 kHz mono PCM16. Trailing silence is trimmed either way: it stands in
for the user pressing stop a moment after the last word, and that moment is exactly what hides the
bug — it gives the provider time the real complaint never had.

Needs the `websockets` package.
"""

import argparse
import asyncio
import base64
import json
import os
import struct
import sys
import time
import urllib.request
import wave

LIVE_MODEL = "gemini-3.5-transcribe-live"
TTS_MODEL = "gemini-3.1-flash-tts-preview"
LIVE_URL = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
)
TTS_URL = f"https://generativelanguage.googleapis.com/v1beta/models/{TTS_MODEL}:generateContent"

RATE = 16_000
CHUNK_MS = 100          # what the app's capture buffer works out to, near enough
SILENCE_LEVEL = 300     # PCM16 amplitude below which a frame counts as silence
GIVE_UP_S = 20.0        # the server never closes on its own; this is how long we humour it


def synthesize(text, key):
    """Speaks [text] with Gemini's TTS and returns 16 kHz mono PCM16."""
    body = {
        "contents": [{"parts": [{"text": f"Sprich ruhig und deutlich: {text}"}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "Kore"}}},
        },
    }
    req = urllib.request.Request(
        TTS_URL,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "x-goog-api-key": key},
    )
    answer = json.loads(urllib.request.urlopen(req, timeout=180).read())
    part = answer["candidates"][0]["content"]["parts"][0]["inlineData"]
    # TTS answers at 24 kHz; the Live API is fed 16 kHz, the same as the app's recorder.
    return resample(base64.b64decode(part["data"]), 24_000, RATE)


def resample(pcm, source_rate, target_rate):
    """Nearest-sample resampling — the endpoint is being timed, not the audio quality."""
    if source_rate == target_rate:
        return pcm
    samples = struct.unpack(f"<{len(pcm) // 2}h", pcm[: len(pcm) // 2 * 2])
    count = int(len(samples) * target_rate / source_rate)
    out = [samples[min(len(samples) - 1, i * source_rate // target_rate)] for i in range(count)]
    return struct.pack(f"<{len(out)}h", *out)


def trim_trailing_silence(pcm):
    """Cuts the quiet tail, so the clip ends where the speaking ends."""
    samples = struct.unpack(f"<{len(pcm) // 2}h", pcm[: len(pcm) // 2 * 2])
    end = len(samples)
    while end > 0 and abs(samples[end - 1]) < SILENCE_LEVEL:
        end -= 1
    return struct.pack(f"<{end}h", *samples[:end]) if end else pcm


def load_wav(path):
    with wave.open(path, "rb") as handle:
        if handle.getnchannels() != 1 or handle.getsampwidth() != 2 or handle.getframerate() != RATE:
            sys.exit(
                f"{path}: need {RATE} Hz mono PCM16, got {handle.getframerate()} Hz / "
                f"{handle.getnchannels()} ch / {handle.getsampwidth() * 8} bit"
            )
        return handle.readframes(handle.getnframes())


def setup_frame():
    """The setup `GeminiRealtimeSession` sends — keep it identical or the probe proves nothing."""
    return json.dumps({
        "setup": {
            "model": f"models/{LIVE_MODEL}",
            "generationConfig": {"responseModalities": ["TEXT"]},
            "inputAudioTranscription": {"languageCodes": [], "mode": "SMART"},
        }
    })


async def one_run(pcm, key, run, verbose):
    import websockets

    events = []
    ended_at = None
    closed_at = None
    finished = asyncio.Event()

    async with websockets.connect(LIVE_URL + key, max_size=None) as socket:
        started = time.monotonic()

        def note(kind, detail=""):
            events.append((time.monotonic() - started, kind, detail))
            if verbose:
                print(f"    [{events[-1][0]:6.2f}] {kind} {detail}")

        await socket.send(setup_frame())

        async def read():
            nonlocal closed_at
            try:
                async for raw in socket:
                    if isinstance(raw, bytes):
                        raw = raw.decode("utf-8", "replace")
                    frame = json.loads(raw)
                    if "setupComplete" in frame:
                        note("setupComplete")
                    content = frame.get("serverContent") or {}
                    if "interimInputTranscription" in content:
                        note("interim", repr(content["interimInputTranscription"].get("text", "")))
                    if "inputTranscription" in content:
                        note("FINAL", repr(content["inputTranscription"].get("text", "")))
                    if content.get("generationComplete"):
                        note("generationComplete")
                    if content.get("turnComplete"):
                        note("turnComplete")
                    if "voiceActivity" in frame:
                        activity = frame["voiceActivity"]
                        note("voiceActivity", f"{activity.get('type')} @ {activity.get('audioOffset')}")
            except Exception as failure:            # a normal close arrives as one of these too
                note("socket closed", type(failure).__name__)
            finally:
                closed_at = time.monotonic() - started
                finished.set()

        reader = asyncio.create_task(read())

        # Real-time pacing, because the provider's settling behaviour follows the microphone's clock.
        chunk = RATE * 2 * CHUNK_MS // 1000
        sent = 0
        audio_started = time.monotonic()
        while sent < len(pcm):
            piece = pcm[sent:sent + chunk]
            await socket.send(json.dumps({
                "realtimeInput": {
                    "audio": {
                        "data": base64.b64encode(piece).decode(),
                        "mimeType": f"audio/pcm;rate={RATE}",
                    }
                }
            }))
            sent += len(piece)
            await asyncio.sleep(max(0.0, audio_started + sent / (RATE * 2) - time.monotonic()))

        await socket.send(json.dumps({"realtimeInput": {"audioStreamEnd": True}}))
        ended_at = time.monotonic() - started
        note("audioStreamEnd sent")

        try:
            await asyncio.wait_for(finished.wait(), timeout=GIVE_UP_S)
        except asyncio.TimeoutError:
            note("gave up — server never closed")
        reader.cancel()

    finals = [(at, detail) for at, kind, detail in events if kind == "FINAL"]
    interims = [at for at, kind, _ in events if kind == "interim"]
    done = [at for at, kind, _ in events if kind in ("turnComplete", "generationComplete")]
    after_end = [at for at in done if at >= ended_at]
    return {
        "run": run,
        "last_final": (finals[-1][0] - ended_at) if finals else None,
        "last_text": (max([at for at, _ in finals] + interims) - ended_at) if (finals or interims) else None,
        "done": (after_end[0] - ended_at) if after_end else None,
        "closed": (closed_at - ended_at) if closed_at is not None else None,
        "turns": [detail for _, detail in finals],
    }


async def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("wav", nargs="?", help="16 kHz mono PCM16 WAV to stream")
    parser.add_argument("--say", help="synthesize this sentence instead of reading a WAV")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--verbose", action="store_true", help="print every frame as it arrives")
    parser.add_argument("--keep", help="write the streamed audio to this WAV")
    args = parser.parse_args()

    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        sys.exit("set GEMINI_API_KEY")
    if bool(args.wav) == bool(args.say):
        sys.exit("give either a WAV or --say")

    pcm = trim_trailing_silence(synthesize(args.say, key) if args.say else load_wav(args.wav))
    if args.keep:
        with wave.open(args.keep, "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(RATE)
            handle.writeframes(pcm)
    print(f"streaming {len(pcm) / (RATE * 2):.1f} s of audio, {args.runs} run(s)\n")

    rows = []
    for run in range(1, args.runs + 1):
        print(f"### run {run}")
        rows.append(await one_run(pcm, key, run, args.verbose))
        await asyncio.sleep(1)

    def show(value):
        return " never" if value is None else f"{value:6.2f}"

    print("\n=== seconds after audioStreamEnd ===")
    print(f"{'run':>4} {'last FINAL':>11} {'last text':>10} {'turn/gen done':>14} {'socket closed':>14}")
    for row in rows:
        print(
            f"{row['run']:>4} {show(row['last_final']):>11} {show(row['last_text']):>10} "
            f"{show(row['done']):>14} {show(row['closed']):>14}"
        )
    # Each its own turn until 2026-11-16; in SMART mode the whole transcript so far since 2026-11-17 (#488).
    print("\nsettled text, one line per turn:")
    for row in rows:
        for turn in row["turns"]:
            print(f"  {row['run']}: {turn}")


asyncio.run(main())
