#!/usr/bin/env python3
"""Inject a mono PCM16 WAV into a running Android emulator's virtual microphone."""

import argparse
import sys
import threading
import time
import wave
from pathlib import Path

import grpc


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("wav", type=Path)
    parser.add_argument("--stubs", type=Path, required=True)
    parser.add_argument("--target", default="localhost:8554")
    parser.add_argument("--packet-ms", type=int, default=300)
    args = parser.parse_args()

    sys.path.insert(0, str(args.stubs))
    import emulator_controller_pb2 as pb2
    import emulator_controller_pb2_grpc as pb2_grpc

    with wave.open(str(args.wav), "rb") as source:
        if source.getnchannels() != 1 or source.getsampwidth() != 2:
            raise ValueError("WAV must be mono PCM16")
        sample_rate = source.getframerate()
        pcm = source.readframes(source.getnframes())

    bytes_per_packet = max(2, sample_rate * 2 * args.packet_ms // 1000)
    audio_format = pb2.AudioFormat(
        samplingRate=sample_rate,
        channels=pb2.AudioFormat.Mono,
        format=pb2.AudioFormat.AUD_FMT_S16,
    )

    all_packets_sent = threading.Event()

    def packets():
        yield pb2.AudioPacket(format=audio_format)
        started = time.monotonic()
        sent_duration = 0.0
        for offset in range(0, len(pcm), bytes_per_packet):
            chunk = pcm[offset : offset + bytes_per_packet]
            yield pb2.AudioPacket(audio=chunk)
            sent_duration += len(chunk) / (sample_rate * 2)
            delay = started + sent_duration - time.monotonic()
            if delay > 0:
                time.sleep(delay)
        silence = b"\x00" * (sample_rate * 2 * 300 // 1000)
        yield pb2.AudioPacket(audio=silence)
        time.sleep(0.3)
        all_packets_sent.set()

    channel = grpc.insecure_channel(args.target)
    stub = pb2_grpc.EmulatorControllerStub(channel)
    call = stub.injectAudio.future(packets(), timeout=120)
    stream_deadline = time.monotonic() + len(pcm) / (sample_rate * 2) + 10
    while not all_packets_sent.wait(timeout=0.1):
        if call.done():
            call.result()
            raise RuntimeError("injectAudio ended before all audio packets were sent")
        if time.monotonic() >= stream_deadline:
            call.cancel()
            raise TimeoutError("injectAudio did not consume all audio packets")
    try:
        call.result(timeout=2)
    except grpc.FutureTimeoutError:
        # Some emulator builds consume the full paced stream but never acknowledge this stream-unary RPC.
        # Once every packet plus trailing silence has been delivered, cancelling only releases the client.
        call.cancel()


if __name__ == "__main__":
    main()
