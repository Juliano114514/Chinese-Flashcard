#!/usr/bin/env python3
"""Prepare/check the selected Melo speech sidecar without rewriting the CSV.

The 170 MB model stays in ignored .gradle cache; Gradle copies it to generated
assets after SHA-256 verification. --check is standard-library-only and offline.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
import shutil
import unicodedata

import evaluate_melo_tts as audition

ROOT = audition.ROOT
ASSETS = ROOT / "core" / "media" / "src" / "main" / "assets" / "chinese-speech"


def canonical(value: str) -> str:
    return unicodedata.normalize("NFC", value.strip().lower())


def reading_key(text: str, pinyin: str, chunks: list[dict] | None = None) -> str:
    suffix = "\x1f".join(chunk["hanzi"] + "\x1e" + canonical(chunk["pinyin"]) for chunk in chunks or [])
    return hashlib.sha256((text.strip() + "\n" + canonical(pinyin) + "\n" + suffix).encode("utf-8")).hexdigest()


def readings() -> list[dict]:
    result = {}
    for row in audition.rows():
        for text, pinyin, chunks, category in [(row["组词"], row["拼音"], None, "word")] + [
            (example["hanzi"], example["pinyin"], example["chunks"], "example")
            for example in json.loads(row["例句JSON"])]:
            entry = {"text": text, "pinyin": pinyin}
            if chunks:
                entry["chunks"] = [{"hanzi": chunk["hanzi"], "pinyin": chunk["pinyin"]} for chunk in chunks]
            result[reading_key(text, pinyin, chunks)] = entry
        syllables = re.split(r"[\s'’\-]+", row["拼音"].strip())
        characters = list(row["组词"])
        if len(characters) == len(syllables):
            for character, syllable in zip(characters, syllables, strict=True):
                result[reading_key(character, syllable)] = {"text": character, "pinyin": syllable}
    return [dict(entry, key=key) for key, entry in sorted(result.items())]


def write_json(path: Path, value: object) -> dict:
    payload = (json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return {"bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}


def write_coverage(value: dict) -> None:
    path = ASSETS / "coverage.json"
    other_bytes = sum(item.stat().st_size for item in ASSETS.rglob("*") if item.is_file() and item != path)
    value = dict(value, sidecarBytes=0)
    for _ in range(5):
        payload = (json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")
        total = other_bytes + len(payload)
        if value["sidecarBytes"] == total:
            path.write_bytes(payload)
            return
        value["sidecarBytes"] = total
    raise ValueError("Chinese coverage byte size did not converge")


def build() -> None:
    source_lock = audition.verify_sources()
    audition.prepare_runtime()
    from melo_tts_frontend import compile_reading, UnsupportedReading
    ASSETS.mkdir(parents=True, exist_ok=True)
    tokens = {symbol: int(index) for symbol, index in (
        line.split() for line in (audition.CACHE / "tokens.txt").read_text(encoding="utf-8").splitlines())}
    syllables = {}
    for line in (audition.CACHE / "opencpop-strict.txt").read_text(encoding="utf-8").splitlines():
        base, phones = line.split("\t", 1)
        syllables[base] = [tokens[phone] for phone in phones.split()]
    phone_record = write_json(ASSETS / "phonemes.json", {"schemaVersion": 1, "syllables": syllables,
        "punctuation": {symbol: tokens[symbol] for symbol in [",", ".", "!", "?", "…", "'", "-"]},
        "blankTokenId": tokens["_"]})
    shards = {f"{index:02x}": {} for index in range(256)}
    unsupported = []
    for index, reading in enumerate(readings(), 1):
        key = reading.pop("key")
        try:
            compiled = compile_reading(reading["text"], reading["pinyin"], reading.get("chunks"))
            if not 1 <= len(compiled["phoneIds"]) <= 510:
                raise UnsupportedReading("Raw phone count is outside the model input bound")
            reading.update({"phoneIds": compiled["phoneIds"], "tones": compiled["tones"]})
        except UnsupportedReading as error:
            reading["unsupportedReason"] = str(error)
            unsupported.append({"key": key, **reading})
        shards[key[:2]][key] = reading
        if index % 5000 == 0:
            audition.emit("chinese_sidecar_progress", checked=index, unsupported=len(unsupported))
    shard_records = {}
    for prefix, entries in shards.items():
        name = f"shard_{prefix}.json"
        shard_records[name] = {**write_json(ASSETS / name, {"schemaVersion": 1, "entries": entries}), "entries": len(entries)}
    license_names = ["MELO_MODEL_MIT.txt", "MELO_CODE_MIT.txt", "TONE_SANDHI_APACHE-2.0.txt"]
    license_records = {}
    for name in license_names:
        target = ASSETS / "licenses" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(audition.CACHE / name, target)
        license_records[name] = {"bytes": target.stat().st_size, "sha256": audition.digest(target)}
    (ASSETS / "LICENSES.txt").write_text(
        "MeloTTS Chinese model and phoneme mapping: MyShell MIT.\n"
        "ToneSandhi derived source: Copyright (c) 2021 PaddlePaddle Authors, Apache-2.0.\n"
        "Python, jieba and pypinyin are build-time only; no source modules or GPL phonemizer are bundled.\n"
        "Chinese text/pinyin sidecar retains the vocabulary's existing source/license boundaries.\n\n" +
        "\n\n".join((audition.CACHE / name).read_text(encoding="utf-8") for name in license_names),
        encoding="utf-8", newline="\n")
    manifest = {"schemaVersion": 1, "engine": "melo-vits", "modelRevision": audition.MODEL_REVISION,
        "modelPath": "chinese-speech/model.onnx", "modelBytes": source_lock["sources"]["model.onnx"]["bytes"],
        "modelSha256": audition.MODEL_SHA256, "sourceWordlistSha256": audition.CSV_SHA256,
        "sampleRate": 44100, "speakerId": 1, "speed": 0.7, "noiseScale": 0.6, "noiseScaleW": 0.8,
        "addBlank": True, "padBoundary": True, "maxTokenCount": 510,
        "phonemesPath": "chinese-speech/phonemes.json", "phonemesSha256": phone_record["sha256"],
        "minimumRms": audition.MIN_AUDIBLE_RMS, "minimumPeak": audition.MIN_AUDIBLE_PEAK,
        "shards": shard_records, "licenses": license_records,
        "keyFormat": "SHA256(trimmedText+LF+NFC(trimmedLowerPinyin)+LF+chunks joined U+001F; hanzi+U+001E+NFC(trimmedLowerPinyin))"}
    from prepare_melo_duration import manifest as with_duration
    write_json(ASSETS / "manifest.json", with_duration(manifest))
    total = sum(record["entries"] for record in shard_records.values())
    write_coverage({"schemaVersion": 1, "wordCount": 13223,
        "exampleApplications": 26446, "uniqueReadingsIncludingWriting": total, "supported": total - len(unsupported),
        "unsupported": len(unsupported), "exceptions": unsupported,
        "evidenceBoundary": "Frontend conversion coverage, not full-corpus speech or device acceptance."})
    check()


def check() -> None:
    audition.rows()
    lock = audition.load_lock()
    duration_lock = json.loads((ROOT / "tools/melo_resources/duration-export.json").read_text(encoding="utf-8"))
    manifest = json.loads((ASSETS / "manifest.json").read_text(encoding="utf-8"))
    if (manifest["sourceModelSha256"] != audition.MODEL_SHA256 or manifest["modelSha256"] != duration_lock["sha256"] or manifest["modelRevision"] != audition.MODEL_REVISION
            or manifest["sourceWordlistSha256"] != audition.CSV_SHA256 or manifest["sampleRate"] != 44100
            or manifest["speed"] != 0.7 or not manifest["padBoundary"] or not manifest["addBlank"]):
        raise ValueError("Chinese speech manifest changed its selected model or protocol")
    if manifest["sourceModelBytes"] != lock["sources"]["model.onnx"]["bytes"] or manifest["modelBytes"] != duration_lock["bytes"]:
        raise ValueError("Unexpected model byte size")
    from prepare_melo_duration import manifest as with_duration
    if manifest != with_duration(manifest):
        raise ValueError("Duration export/cache identity does not match source protocol")
    phones_path = ASSETS / "phonemes.json"
    if audition.digest(phones_path) != manifest["phonemesSha256"]:
        raise ValueError("Changed Chinese phoneme table")
    table = json.loads(phones_path.read_text(encoding="utf-8"))
    valid_tokens = {token for phones in table["syllables"].values() for token in phones} | set(table["punctuation"].values())
    expected = {entry["key"] for entry in readings()}
    actual, unsupported = set(), 0
    for name, record in manifest["shards"].items():
        path = ASSETS / name
        audition.verify_file(path, record)
        entries = json.loads(path.read_text(encoding="utf-8"))["entries"]
        if len(entries) != record["entries"]:
            raise ValueError("Shard entry count mismatch")
        for key, entry in entries.items():
            if key in actual or key != reading_key(entry["text"], entry["pinyin"], entry.get("chunks")) or name != f"shard_{key[:2]}.json":
                raise ValueError("Chinese pronunciation key mismatch")
            actual.add(key)
            if "unsupportedReason" in entry:
                unsupported += 1
                continue
            ids, tones = entry["phoneIds"], entry["tones"]
            if not 1 <= len(ids) <= 510 or len(ids) != len(tones) or any(token not in valid_tokens for token in ids) or any(tone not in range(6) for tone in tones):
                raise ValueError("Invalid Chinese token/tone sequence")
    if actual != expected:
        raise ValueError("Chinese sidecar does not cover current word/example/writing requests")
    for name, record in manifest["licenses"].items():
        audition.verify_file(ASSETS / "licenses" / name, record)
    from build_chinese_short_words import check as check_short_words
    check_short_words()
    if any(path.suffix in {".onnx", ".py", ".dll", ".whl", ".npz"} for path in ASSETS.rglob("*")):
        raise ValueError("Model binary/build-time runtime must not be stored in tracked sidecar assets")
    sidecar_bytes = sum(path.stat().st_size for path in ASSETS.rglob("*") if path.is_file())
    coverage = json.loads((ASSETS / "coverage.json").read_text(encoding="utf-8"))
    if (coverage["sidecarBytes"] != sidecar_bytes or coverage["uniqueReadingsIncludingWriting"] != len(actual)
            or coverage["supported"] != len(actual) - unsupported or coverage["unsupported"] != unsupported):
        raise ValueError("Chinese coverage counts/resource byte size are stale")
    if sidecar_bytes + manifest["modelBytes"] > 200_000_000:
        raise ValueError("Chinese bundled resource budget exceeds 200 MB")
    audition.emit("chinese_resources_checked", readings=len(actual), supported=len(actual) - unsupported,
        unsupported=unsupported, resourceBytes=sidecar_bytes + manifest["modelBytes"], wordlistSha256=audition.CSV_SHA256)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--prepare", action="store_true")
    group.add_argument("--build", action="store_true")
    group.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.prepare:
        audition.prepare()
        from prepare_melo_duration import prepare as prepare_duration
        prepare_duration()
    elif args.build:
        build()
    else:
        check()


if __name__ == "__main__":
    main()
