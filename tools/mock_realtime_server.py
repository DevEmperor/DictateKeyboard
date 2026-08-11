#!/usr/bin/env python3
"""
A stand-in for a self-hosted streaming transcription server (issue #249).

It speaks the slice of the OpenAI realtime protocol that DictateKeyboard uses, and nothing else: accept
the socket, swallow the audio, and emit transcripts on a timer. It does not listen to a word you say —
which is the point. Testing the client end (does it connect without a key, does audio flow, do partials
land in the field, does the final one arrive before the session closes) should not depend on having a GPU
and a model, and a server that always says the same thing is far easier to judge than one that guesses.

Usage:
    pip install websockets
    python3 mock_realtime_server.py [--port 8080] [--require-key]

Then in Dictate: Providers -> add a custom endpoint, base URL http://<this machine>:8080/v1/,
turn on "Real-time streaming", and switch global real-time transcription on.

Every connection prints what it receives, so a hold that produces no audio is visible here immediately.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import sys

try:
    import websockets
except ImportError:
    sys.exit("needs the websockets package:  pip install websockets")

# What the fake transcript builds up to, one piece per delta.
PIECES = ["This ", "is ", "the ", "mock ", "server ", "talking. ", "Nothing ", "here ", "heard ", "you."]

# How long to wait after the last audio frame before calling the utterance finished.
SILENCE_S = 0.8


class Session:
    def __init__(self, ws, peer: str):
        self.ws = ws
        self.peer = peer
        self.audio_bytes = 0
        self.frames = 0
        self.said = 0
        self.timer: asyncio.Task | None = None

    def log(self, msg: str) -> None:
        print(f"[{self.peer}] {msg}", flush=True)

    async def send(self, payload: dict) -> None:
        await self.ws.send(json.dumps(payload))

    async def on_audio(self, b64: str) -> None:
        self.frames += 1
        self.audio_bytes += len(base64.b64decode(b64))
        # One delta per few frames, so the field visibly grows while speaking.
        if self.frames % 8 == 0 and self.said < len(PIECES):
            self.said += 1
            await self.send({
                "type": "conversation.item.input_audio_transcription.delta",
                "delta": PIECES[self.said - 1],
            })
        # Restart the "they stopped talking" timer on every frame.
        if self.timer:
            self.timer.cancel()
        self.timer = asyncio.create_task(self.finish_after_silence())

    async def finish_after_silence(self) -> None:
        try:
            await asyncio.sleep(SILENCE_S)
        except asyncio.CancelledError:
            return
        await self.finish()

    async def finish(self) -> None:
        if self.said == 0:
            return
        text = "".join(PIECES[: self.said]).strip()
        self.log(f"final: {text!r}")
        await self.send({
            "type": "conversation.item.input_audio_transcription.completed",
            "transcript": text,
        })
        self.said = 0


async def handler(ws, require_key: bool):
    peer = f"{ws.remote_address[0]}:{ws.remote_address[1]}"
    auth = ws.request.headers.get("Authorization") if hasattr(ws, "request") else None
    print(f"[{peer}] connected  path={getattr(ws, 'path', '?')}  auth={'yes' if auth else 'none'}", flush=True)
    if require_key and not auth:
        print(f"[{peer}] rejected: no Authorization header", flush=True)
        await ws.close(code=4001, reason="missing key")
        return

    session = Session(ws, peer)
    try:
        async for raw in ws:
            if isinstance(raw, bytes):
                # Not this protocol's shape, but worth seeing if a client ever sends it.
                session.log(f"binary frame, {len(raw)} bytes")
                continue
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                session.log(f"not JSON: {raw[:120]}")
                continue
            kind = msg.get("type", "?")
            if kind == "input_audio_buffer.append":
                await session.on_audio(msg.get("audio", ""))
            elif kind == "input_audio_buffer.commit":
                session.log("commit")
                if session.timer:
                    session.timer.cancel()
                await session.finish()
            elif kind == "session.update":
                session.log(f"session.update: {json.dumps(msg.get('session', {}))[:200]}")
                await session.send({"type": "session.updated", "session": msg.get("session", {})})
            else:
                session.log(f"-> {kind}")
    except websockets.ConnectionClosed:
        pass
    finally:
        if session.timer:
            session.timer.cancel()
        session.log(f"closed after {session.frames} frames / {session.audio_bytes} bytes of audio")


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--require-key", action="store_true", help="reject connections without a bearer token")
    args = ap.parse_args()

    async with websockets.serve(lambda ws: handler(ws, args.require_key), args.host, args.port):
        print(f"listening on ws://{args.host}:{args.port}  (endpoint: /v1/realtime)", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
