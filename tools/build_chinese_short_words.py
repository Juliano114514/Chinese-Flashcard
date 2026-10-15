#!/usr/bin/env python3
"""Import/check reviewed Melo short-word PCM; no inference, downloads or CSV edits.

--import-lab requires the external lab's approved selection and gain-only set.
Only mono PCM16 payloads, pronunciation metadata and compact provenance enter
the project. Full feedback, models, dependencies and experimental graphs stay
in the lab. --check is standard-library-only and read-only.
"""
from __future__ import annotations

import argparse
from array import array
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import wave

import evaluate_melo_tts as audition

ROOT = audition.ROOT
ASSETS = ROOT / "core/media/src/main/assets/chinese-speech/short-words"
LOCK = ROOT / "tools/melo_resources/short-words-source.json"
KOTLIN = ROOT / "core/media/src/main/kotlin/com/example/chinese_flashcard/core/media/ChineseShortSpeech.kt"
RATE = 44100
MAX_SAMPLES = RATE * 4
HASH = re.compile(r"[0-9a-f]{64}")


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def lab_file(lab: Path, relative: str) -> Path:
    path = (lab / relative).resolve()
    if not path.is_relative_to(lab) or not path.is_file() or path.is_symlink():
        raise ValueError("Missing/escaped laboratory source")
    return path


def pcm_valid(payload: bytes, samples: int) -> None:
    if not 1 <= samples <= MAX_SAMPLES or len(payload) != samples * 2:
        raise ValueError("Short PCM sample/byte count outside bounds")
    values = array("h", payload)
    if sys.byteorder != "little":
        values.byteswap()
    peak = max(abs(value) for value in values) / 32768
    squares = sum((value / 32768) ** 2 for value in values)
    mean = sum(values) / (len(values) * 32768)
    if peak >= .9 or peak <= audition.MIN_AUDIBLE_PEAK or math.sqrt(squares / len(values)) <= audition.MIN_AUDIBLE_RMS:
        raise ValueError("Reviewed PCM has unsafe/inaudible level")
    if squares / len(values) - mean * mean <= 1e-12:
        raise ValueError("Reviewed PCM has no varying signal")


def import_lab(lab: Path) -> None:
    # Importing a new selection still requires prior human listening approval.
    from build_chinese_resources import reading_key
    audition.rows()
    lab = lab.resolve(strict=True)
    if lab == ROOT or lab.is_relative_to(ROOT):
        raise ValueError("Use the external experiment directory")
    verification = load(lab_file(lab, "final-verification.json"))
    if verification["status"] != "passed" or verification["sourceModelSha256"] != audition.MODEL_SHA256 or verification["sourceWordlistSha256"] != audition.CSV_SHA256:
        raise ValueError("Laboratory model/wordlist verification mismatch")
    selection = load(lab_file(lab, "selected/short-words/manifest.json"))
    levels = load(lab_file(lab, "level-match.json"))
    corpus = load(lab_file(lab, "corpus.json"))
    approved = selection["samples"]
    if len(approved) != 23 or len(levels) != 23:
        raise ValueError("Expected the reviewed 23-reading selection")
    entries, payloads, provenance = {}, {}, []
    for sample in approved:
        if sample["feedback"]["rating"] != "清楚且自然":
            raise ValueError("Sample lacks human listening acceptance")
        reviewed = lab_file(lab, sample["selectedWav"])
        if digest(reviewed.read_bytes()) != sample["wavSha256"]:
            raise ValueError("Reviewed source WAV changed")
        level = next(row for row in levels if row["caseId"] == sample["caseId"])
        if not level["processing"]["amplitudeOnly"] or not level["processing"]["preservedSampleCount"]:
            raise ValueError("Final candidate changed more than amplitude")
        wav_path = lab_file(lab, level["wav"])
        if digest(wav_path.read_bytes()) != level["wavSha256"]:
            raise ValueError("Level-matched WAV changed")
        with wave.open(str(wav_path), "rb") as audio:
            if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getcomptype()) != (1, 2, RATE, "NONE"):
                raise ValueError("Expected mono 44.1 kHz PCM16")
            frames = audio.getnframes()
            payload = audio.readframes(frames)
        pcm_valid(payload, frames)
        text, pinyin = sample["text"], sample["pinyin"]
        if not 1 <= len(text) <= 4:
            raise ValueError("Selection contains non-short content")
        key = reading_key(text, pinyin)
        phones = corpus["compiled"][sample["caseId"]]
        name = key + ".pcm"
        entries[key] = {"text": text, "pinyin": pinyin, "phoneIds": phones["phoneIds"], "tones": phones["tones"],
                        "path": "chinese-speech/short-words/" + name, "bytes": len(payload),
                        "samples": frames, "sha256": digest(payload)}
        payloads[name] = payload
        provenance.append({"key": key, "labCase": sample["caseId"], "method": sample["method"],
                           "reviewedWavSha256": sample["wavSha256"], "matchedWavSha256": level["wavSha256"],
                           "listeningAcceptance": "desktop source sample accepted",
                           "levelAdjustment": "amplitude only; matched to measured long-speech reference"})
    if len(entries) != len(approved):
        raise ValueError("Duplicate selected pronunciation key")
    index = {"schemaVersion": 1, "modelSha256": audition.MODEL_SHA256,
             "sampleRate": RATE, "channelCount": 1, "encoding": "pcm-s16le", "entries": entries}
    index_payload = json_bytes(index)
    ASSETS.mkdir(parents=True, exist_ok=True)
    for name, payload in payloads.items():
        (ASSETS / name).write_bytes(payload)
    (ASSETS / "index.json").write_bytes(index_payload)
    LOCK.write_bytes(json_bytes({"schemaVersion": 1, "sourceModelSha256": audition.MODEL_SHA256,
        "sourceWordlistSha256": audition.CSV_SHA256, "reviewDate": "2026-10-14",
        "index": {"bytes": len(index_payload), "sha256": digest(index_payload), "entries": len(entries)},
        "provenance": provenance, "evidenceBoundary": "Desktop human listening; no device acceptance claimed."}))
    audition.emit("reviewed_short_words_imported", entries=len(entries), pcmBytes=sum(map(len, payloads.values())),
                  indexBytes=len(index_payload), indexSha256=digest(index_payload))


def check() -> None:
    from build_chinese_resources import reading_key
    lock = load(LOCK)
    if lock["sourceModelSha256"] != audition.MODEL_SHA256 or lock["sourceWordlistSha256"] != audition.CSV_SHA256:
        raise ValueError("Reviewed short-word source identity changed")
    index_payload = (ASSETS / "index.json").read_bytes()
    if len(index_payload) != lock["index"]["bytes"] or digest(index_payload) != lock["index"]["sha256"]:
        raise ValueError("Reviewed short-word index changed")
    index = json.loads(index_payload)
    if index["schemaVersion"] != 1 or index["modelSha256"] != audition.MODEL_SHA256 or (index["sampleRate"], index["channelCount"], index["encoding"]) != (RATE, 1, "pcm-s16le"):
        raise ValueError("Reviewed PCM protocol mismatch")
    entries = index["entries"]
    if len(entries) != 23 or lock["index"]["entries"] != len(entries):
        raise ValueError("Reviewed pronunciation count mismatch")
    total = 0
    for key, entry in entries.items():
        if not HASH.fullmatch(key) or key != reading_key(entry["text"], entry["pinyin"]) or not 1 <= len(entry["text"]) <= 4:
            raise ValueError("Reviewed pronunciation identity mismatch")
        if entry["path"] != f"chinese-speech/short-words/{key}.pcm":
            raise ValueError("Unexpected reviewed PCM path")
        ids, tones = entry["phoneIds"], entry["tones"]
        if not 1 <= len(ids) <= 32 or len(ids) != len(tones) or any(i not in range(1, 103) for i in ids) or any(t not in range(6) for t in tones):
            raise ValueError("Invalid reviewed phone/tone sequence")
        payload = (ASSETS / (key + ".pcm")).read_bytes()
        if len(payload) != entry["bytes"] or digest(payload) != entry["sha256"]:
            raise ValueError("Reviewed PCM changed")
        pcm_valid(payload, entry["samples"])
        total += len(payload)
    if {p.name for p in ASSETS.iterdir()} != {"index.json"} | {key + ".pcm" for key in entries}:
        raise ValueError("Unindexed short-word assets")
    source = KOTLIN.read_text(encoding="utf-8")
    if f'INDEX_SHA256 = "{lock["index"]["sha256"]}"' not in source or f'INDEX_BYTES = {lock["index"]["bytes"]}' not in source:
        raise ValueError("Kotlin loader fingerprint does not match reviewed index")
    audition.emit("reviewed_short_words_checked", entries=len(entries), pcmBytes=total,
                  indexSha256=lock["index"]["sha256"])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--import-lab", type=Path)
    group.add_argument("--check", action="store_true")
    args = parser.parse_args()
    import_lab(args.import_lab) if args.import_lab else check()


if __name__ == "__main__":
    main()
