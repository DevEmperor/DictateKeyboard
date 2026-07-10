#!/usr/bin/env python3
"""Bundle Pipecat Smart Turn v3 with its Whisper feature extractor.

The upstream ONNX model accepts precomputed (80, 800) log-mel features. Dictate records 16 kHz
PCM, so this script prepends the exact preprocessing described by Pipecat's
`_whisper_features.py` to the official quantized CPU model. Keeping STFT and mel projection in
ONNX Runtime avoids a second FFT implementation on Android and keeps inference off the recorder
thread.

Usage:
    python tools/build-smart-turn-model.py SOURCE.onnx OUTPUT.onnx [--validate]
"""

from __future__ import annotations

import argparse
import hashlib
import math
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, compose, helper, numpy_helper

SAMPLE_RATE = 16_000
N_SAMPLES = SAMPLE_RATE * 8
N_FFT = 400
HOP_LENGTH = 160
N_MELS = 80
SOURCE_SHA256 = "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f"


def hertz_to_mel_slaney(freq: np.ndarray) -> np.ndarray:
    min_log_hertz = 1000.0
    min_log_mel = 15.0
    logstep = 27.0 / np.log(6.4)
    mels = 3.0 * freq / 200.0
    mask = freq >= min_log_hertz
    mels[mask] = min_log_mel + np.log(freq[mask] / min_log_hertz) * logstep
    return mels


def mel_to_hertz_slaney(mels: np.ndarray) -> np.ndarray:
    min_log_hertz = 1000.0
    min_log_mel = 15.0
    logstep = np.log(6.4) / 27.0
    freq = 200.0 * mels / 3.0
    mask = mels >= min_log_mel
    freq[mask] = min_log_hertz * np.exp(logstep * (mels[mask] - min_log_mel))
    return freq


def mel_filterbank() -> np.ndarray:
    mel_min = hertz_to_mel_slaney(np.array([0.0], dtype=np.float64))[0]
    mel_max = hertz_to_mel_slaney(np.array([SAMPLE_RATE / 2], dtype=np.float64))[0]
    mel_freqs = np.linspace(mel_min, mel_max, N_MELS + 2)
    filter_freqs = mel_to_hertz_slaney(mel_freqs)
    fft_freqs = np.linspace(0, SAMPLE_RATE // 2, N_FFT // 2 + 1)
    filter_diff = np.diff(filter_freqs)
    slopes = np.expand_dims(filter_freqs, 0) - np.expand_dims(fft_freqs, 1)
    down_slopes = -slopes[:, :-2] / filter_diff[:-1]
    up_slopes = slopes[:, 2:] / filter_diff[1:]
    filters = np.maximum(0.0, np.minimum(down_slopes, up_slopes))
    filters *= np.expand_dims(2.0 / (filter_freqs[2:] - filter_freqs[:-2]), 0)
    return filters


def scalar(name: str, value: float | int, dtype: np.dtype) -> onnx.TensorProto:
    return numpy_helper.from_array(np.asarray(value, dtype=dtype), name=name)


def vector(name: str, values: list[int]) -> onnx.TensorProto:
    return numpy_helper.from_array(np.asarray(values, dtype=np.int64), name=name)


def build(source: Path, destination: Path) -> None:
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    if source_hash != SOURCE_SHA256:
        raise ValueError(f"unexpected Smart Turn v3.2 CPU model checksum: {source_hash}")
    classifier = compose.add_prefix(onnx.load(source), "classifier/")
    classifier_input = "classifier/input_features"

    initializers = [
        vector("feature/axes_samples", [1]),
        scalar("feature/variance_epsilon", 1e-7, np.float32),
        vector("feature/pads", [0, N_FFT // 2, 0, N_FFT // 2]),
        scalar("feature/pad_value", 0.0, np.float64),
        scalar("feature/frame_step", HOP_LENGTH, np.int64),
        scalar("feature/frame_length", N_FFT, np.int64),
        numpy_helper.from_array(np.hanning(N_FFT + 1)[:-1].astype(np.float64), "feature/window"),
        scalar("feature/complex_real", 0, np.int64),
        scalar("feature/complex_imag", 1, np.int64),
        numpy_helper.from_array(mel_filterbank().astype(np.float64), "feature/mel_filters"),
        scalar("feature/mel_floor", 1e-10, np.float64),
        scalar("feature/ln_10", math.log(10.0), np.float64),
        vector("feature/slice_starts", [0]),
        vector("feature/slice_ends", [800]),
        vector("feature/slice_axes", [2]),
        vector("feature/reduce_feature_axes", [1, 2]),
        scalar("feature/eight", 8.0, np.float64),
        scalar("feature/four", 4.0, np.float64),
    ]
    nodes = [
        helper.make_node("ReduceMean", ["audio", "feature/axes_samples"], ["feature/mean"], keepdims=1),
        helper.make_node("Sub", ["audio", "feature/mean"], ["feature/centered"]),
        helper.make_node("Mul", ["feature/centered", "feature/centered"], ["feature/squared"]),
        helper.make_node(
            "ReduceMean", ["feature/squared", "feature/axes_samples"], ["feature/variance"], keepdims=1
        ),
        helper.make_node(
            "Add", ["feature/variance", "feature/variance_epsilon"], ["feature/variance_safe"]
        ),
        helper.make_node("Sqrt", ["feature/variance_safe"], ["feature/stddev"]),
        helper.make_node("Div", ["feature/centered", "feature/stddev"], ["feature/normalized_f32"]),
        helper.make_node(
            "Cast", ["feature/normalized_f32"], ["feature/normalized"], to=TensorProto.DOUBLE
        ),
        helper.make_node(
            "Pad",
            ["feature/normalized", "feature/pads", "feature/pad_value"],
            ["feature/padded"],
            mode="reflect",
        ),
        helper.make_node(
            "STFT",
            ["feature/padded", "feature/frame_step", "feature/window", "feature/frame_length"],
            ["feature/stft"],
            onesided=1,
        ),
        helper.make_node("Gather", ["feature/stft", "feature/complex_real"], ["feature/real"], axis=3),
        helper.make_node("Gather", ["feature/stft", "feature/complex_imag"], ["feature/imag"], axis=3),
        helper.make_node("Mul", ["feature/real", "feature/real"], ["feature/real_power"]),
        helper.make_node("Mul", ["feature/imag", "feature/imag"], ["feature/imag_power"]),
        helper.make_node("Add", ["feature/real_power", "feature/imag_power"], ["feature/power"]),
        helper.make_node("MatMul", ["feature/power", "feature/mel_filters"], ["feature/mel"]),
        helper.make_node("Max", ["feature/mel", "feature/mel_floor"], ["feature/mel_safe"]),
        helper.make_node("Log", ["feature/mel_safe"], ["feature/ln_mel"]),
        helper.make_node("Div", ["feature/ln_mel", "feature/ln_10"], ["feature/log_mel_frames"]),
        helper.make_node("Transpose", ["feature/log_mel_frames"], ["feature/log_mel"], perm=[0, 2, 1]),
        helper.make_node(
            "Slice",
            [
                "feature/log_mel",
                "feature/slice_starts",
                "feature/slice_ends",
                "feature/slice_axes",
            ],
            ["feature/log_mel_trimmed"],
        ),
        helper.make_node(
            "ReduceMax",
            ["feature/log_mel_trimmed", "feature/reduce_feature_axes"],
            ["feature/log_mel_max"],
            keepdims=1,
        ),
        helper.make_node("Sub", ["feature/log_mel_max", "feature/eight"], ["feature/log_mel_min"]),
        helper.make_node(
            "Max", ["feature/log_mel_trimmed", "feature/log_mel_min"], ["feature/log_mel_clipped"]
        ),
        helper.make_node("Add", ["feature/log_mel_clipped", "feature/four"], ["feature/log_mel_shifted"]),
        helper.make_node("Div", ["feature/log_mel_shifted", "feature/four"], ["feature/log_mel_final"]),
        helper.make_node("Cast", ["feature/log_mel_final"], [classifier_input], to=TensorProto.FLOAT),
    ]

    graph = helper.make_graph(
        nodes + list(classifier.graph.node),
        "dictate-smart-turn-v3.2",
        [helper.make_tensor_value_info("audio", TensorProto.FLOAT, ["batch", N_SAMPLES])],
        list(classifier.graph.output),
        initializer=initializers + list(classifier.graph.initializer),
        value_info=list(classifier.graph.value_info),
    )
    model = helper.make_model(
        graph,
        producer_name="Dictate Smart Turn model builder",
        opset_imports=list(classifier.opset_import),
        ir_version=classifier.ir_version,
    )
    model.doc_string = (
        "Pipecat Smart Turn v3.2 CPU model with the Pipecat Whisper log-mel feature extractor "
        "prepended. Input: normalized-range 16 kHz mono PCM, left-padded to 8 seconds."
    )
    onnx.checker.check_model(model)
    destination.parent.mkdir(parents=True, exist_ok=True)
    onnx.save_model(model, destination)


def reference_features(audio: np.ndarray) -> np.ndarray:
    x = audio.astype(np.float32)
    x = (x - x.mean()) / np.sqrt(x.var() + 1e-7)
    padded = np.pad(x.astype(np.float64), (N_FFT // 2, N_FFT // 2), mode="reflect")
    frames = np.lib.stride_tricks.sliding_window_view(padded, N_FFT)[::HOP_LENGTH]
    spec = np.fft.rfft(frames * np.hanning(N_FFT + 1)[:-1], axis=-1)
    power = np.abs(spec) ** 2
    mel = np.maximum(1e-10, power @ mel_filterbank().astype(np.float64))
    log_spec = np.log10(mel).T[:, :-1]
    log_spec = np.maximum(log_spec, log_spec.max() - 8.0)
    return ((log_spec + 4.0) / 4.0).astype(np.float32)[None, :, :]


def validate(source: Path, destination: Path) -> None:
    import onnxruntime as ort

    rng = np.random.default_rng(170)
    audio = np.zeros((1, N_SAMPLES), dtype=np.float32)
    spoken = rng.normal(0.0, 0.12, SAMPLE_RATE * 3).astype(np.float32)
    audio[0, -spoken.size :] = spoken
    expected_features = reference_features(audio[0])
    expected = ort.InferenceSession(str(source), providers=["CPUExecutionProvider"]).run(
        None, {"input_features": expected_features}
    )[0]

    debug_model = onnx.load(destination)
    debug_model.graph.output.append(
        helper.make_tensor_value_info("classifier/input_features", TensorProto.FLOAT, ["batch", N_MELS, 800])
    )
    debug_session = ort.InferenceSession(debug_model.SerializeToString(), providers=["CPUExecutionProvider"])
    actual, actual_features = debug_session.run(None, {"audio": audio})
    classifier_on_actual = ort.InferenceSession(str(source), providers=["CPUExecutionProvider"]).run(
        None, {"input_features": actual_features}
    )[0]
    feature_delta = np.abs(actual_features - expected_features)
    print(
        "feature delta: "
        f"max={feature_delta.max():.9g}, mean={feature_delta.mean():.9g}; "
        f"classifier(actual_features)={classifier_on_actual.item():.7f}"
    )
    np.testing.assert_allclose(actual_features, expected_features, rtol=2e-4, atol=2e-4)
    np.testing.assert_allclose(actual, classifier_on_actual, rtol=1e-6, atol=1e-6)
    np.testing.assert_allclose(actual, expected, rtol=2e-4, atol=2e-4)
    print(f"validated logits: expected={expected.item():.7f}, actual={actual.item():.7f}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--validate", action="store_true")
    args = parser.parse_args()
    build(args.source, args.destination)
    if args.validate:
        validate(args.source, args.destination)
    print(args.destination)


if __name__ == "__main__":
    main()
