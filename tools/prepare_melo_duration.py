#!/usr/bin/env python3
"""Expose one existing duration tensor in the checksum-locked Melo ONNX.

No new operators, weights, inputs, training, inference or dependencies. This
fixed-model adapter appends a GraphProto output ValueInfo; every original graph
byte is preserved. Wire fields follow ONNX v1.19.1 onnx.proto3, not a general
protobuf converter: https://github.com/onnx/onnx/blob/v1.19.1/onnx/onnx.proto3
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import evaluate_melo_tts as audition

LOCK = audition.ROOT / "tools/melo_resources/duration-export.json"
OUTPUT = audition.CACHE / "model-duration.onnx"
DURATION = "/Ceil_output_0"


def varint(value: int) -> bytes:
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def read_varint(data: bytes, position: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 70, 7):
        if position >= len(data):
            raise ValueError("Truncated pinned ONNX")
        byte = data[position]
        position += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, position
    raise ValueError("Oversized ONNX varint")


def fields(data: bytes):
    position = 0
    while position < len(data):
        start = position
        tag, position = read_varint(data, position)
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise ValueError("Invalid ONNX field number")
        if wire == 0:
            _, end = read_varint(data, position)
            payload = position
        elif wire == 2:
            size, payload = read_varint(data, position)
            end = payload + size
        elif wire in (1, 5):
            payload, end = position, position + (8 if wire == 1 else 4)
        else:
            raise ValueError("Unexpected wire type in pinned ONNX")
        if end > len(data):
            raise ValueError("ONNX field exceeds pinned file")
        yield number, wire, start, payload, end
        position = end


def message(number: int, data: bytes) -> bytes:
    return varint(number * 8 + 2) + varint(len(data)) + data


def strings(data: bytes, number: int) -> list[str]:
    return [data[p:e].decode("utf-8") for n, w, _, p, e in fields(data) if n == number and w == 2]


def derive() -> bytes:
    path = audition.CACHE / "model.onnx"
    if path.stat().st_size != 170429550 or audition.digest(path) != audition.MODEL_SHA256:
        raise ValueError("Pinned source model changed")
    source = path.read_bytes()
    graphs = [(s, p, e) for n, w, s, p, e in fields(source) if n == 7 and w == 2]
    if len(graphs) != 1:
        raise ValueError("Expected one top-level inference graph")
    start, offset, end = graphs[0]
    graph = source[offset:end]
    outputs = []
    duration_nodes = 0
    for n, w, _, p, e in fields(graph):
        if w != 2:
            continue
        if n == 12:
            outputs.extend(strings(graph[p:e], 1))
        elif n == 1 and DURATION in strings(graph[p:e], 2):
            if strings(graph[p:e], 4) != ["Ceil"]:
                raise ValueError("Unexpected duration producer")
            duration_nodes += 1
    if outputs != ["y"] or duration_nodes != 1:
        raise ValueError("Pinned graph output/duration contract changed")
    # ValueInfoProto(type=TensorProto.FLOAT, shape=[N,1,L]).
    shape = message(1, message(2, b"N")) + message(1, b"\x08\x01") + message(1, message(2, b"L"))
    tensor_type = b"\x08\x01" + message(2, shape)
    value_info = message(1, DURATION.encode()) + message(2, message(1, tensor_type))
    expanded = graph + message(12, value_info)
    return source[:start] + message(7, expanded) + source[end:]


def prepare() -> None:
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    if lock["sourceSha256"] != audition.MODEL_SHA256:
        raise ValueError("Duration export source changed")
    if OUTPUT.is_file() and OUTPUT.stat().st_size == lock["bytes"] and audition.digest(OUTPUT) == lock["sha256"]:
        return
    payload = derive()
    if len(payload) != lock["bytes"] or hashlib.sha256(payload).hexdigest() != lock["sha256"]:
        raise ValueError("Derived model differs from locked duration export")
    temporary = OUTPUT.with_suffix(".part")
    temporary.write_bytes(payload)
    temporary.replace(OUTPUT)
    audition.emit("melo_duration_prepared", bytes=len(payload), sha256=lock["sha256"])


def manifest(base: dict) -> dict:
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    base = dict(base)
    for name in ("sourceModelSha256", "sourceModelBytes", "baseManifestSha256", "durationOutput", "hopSamples"):
        base.pop(name, None)
    base.update(modelPath="chinese-speech/model.onnx", modelSha256=audition.MODEL_SHA256, modelBytes=170429550)
    payload = (json.dumps(base, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode()
    return {**base, "sourceModelSha256": audition.MODEL_SHA256, "sourceModelBytes": 170429550,
            "baseManifestSha256": hashlib.sha256(payload).hexdigest(),
            "modelPath": "chinese-speech/model-duration.onnx", "modelSha256": lock["sha256"],
            "modelBytes": lock["bytes"], "durationOutput": DURATION, "hopSamples": 512}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare", action="store_true", required=True)
    parser.parse_args()
    prepare()
