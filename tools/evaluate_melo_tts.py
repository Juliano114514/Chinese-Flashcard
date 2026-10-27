#!/usr/bin/env python3
"""Audition the fixed Melo FP32 model; never modify Android assets or the CSV.

--prepare downloads checksum-locked sources only into .gradle/melo-resource-source.
--samples uses the existing, archive-verified task-local Python runtime and writes
WAV files and a comparison page into .gradle/melo-speech-samples.
--check is offline/read-only; --audit checks pronunciation support without running
the model. Desktop output validity is not human listening or Android evidence.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone, timedelta
import hashlib
import json
from pathlib import Path
import platform
import sys
import tarfile
import time
import urllib.parse
import urllib.request
import wave
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".gradle" / "melo-resource-source"
OUTPUT = ROOT / ".gradle" / "melo-speech-samples"
PROBES = ROOT / ".gradle" / "melo-short-word-probes"
LEGACY = ROOT / ".gradle" / "chinese-speech-samples"
LOCK = ROOT / "tools" / "melo_resources" / "source-lock.json"
RUNTIME_LOCK = ROOT / "tools" / "melo_resources" / "runtime-lock.json"
CSV = ROOT / "wordlist.csv"
from wordlist_snapshot import load_snapshot, verify_snapshot

CSV_SHA256 = load_snapshot()["sha256"]
MODEL_REVISION = "a0d5c6a264c0ef92d70d8661d8cc502d79627cd6"
MODEL_SHA256 = "bf30582eb1b012250a35b1a4a80e7dfbcf8485e7bb9de0d95efbbeef0e4ad86d"
INPUTS = {"x", "x_lengths", "tones", "sid", "noise_scale", "length_scale", "noise_scale_w"}
MIN_AUDIBLE_RMS = 1e-4
MIN_AUDIBLE_PEAK = 1e-3
BOUNDARIES = [
    "这是桌面 CPU 离线推理，未接入 Android，也未测量手机延迟、内存或耗电。",
    "波形有效性检查仅排除空音频、非有限值和严重削波，音色、自然度与读音需要人工试听。",
    "Melo ONNX 转换版使用全零 BERT 特征，原 Python 模型演示不能作为此版音质证据。",
    "Kokoro 对照沿用上一 chat 的 INT8 样音及 0.9 倍语速；Melo 模型语速按每条标注，不是受控模型质量实验。",
    "暂不改 App、词库、学习数据或版本；你确认声线满意后才进入接入阶段。",
]


class NearSilentOutput(ValueError):
    """Finite PCM alone does not establish an audible vocabulary sample."""


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(data)
    return result.hexdigest()


def emit(event: str, **details: object) -> None:
    print(json.dumps({"event": event, **details}, ensure_ascii=False), flush=True)


def save_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def load_lock() -> dict:
    data = json.loads(LOCK.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1 or data.get("modelRevision") != MODEL_REVISION:
        raise ValueError("Unexpected Melo source revision")
    if data["sources"]["model.onnx"]["sha256"] != MODEL_SHA256:
        raise ValueError("Unexpected FP32 model checksum")
    return data


def cache_path(name: str) -> Path:
    if Path(name).name != name or name in (".", ".."):
        raise ValueError("Source filename must be a basename")
    path = CACHE / name
    if not path.resolve().is_relative_to(ROOT.resolve()) or path.is_symlink():
        raise ValueError("Cache path escapes the workspace")
    return path


def verify_file(path: Path, record: dict) -> None:
    if not path.is_file() or path.stat().st_size != record["bytes"] or digest(path) != record["sha256"]:
        raise ValueError(f"Missing or changed resource: {path.name}")


def verify_sources() -> dict:
    data = load_lock()
    for name, record in data["sources"].items():
        verify_file(cache_path(name), record)
    return data


def prepare() -> None:
    data = load_lock()
    CACHE.mkdir(parents=True, exist_ok=True)
    for name, record in data["sources"].items():
        path = cache_path(name)
        try:
            verify_file(path, record)
            emit("reuse", file=name, bytes=record["bytes"])
            continue
        except ValueError:
            pass
        parsed = urllib.parse.urlsplit(record["url"])
        if parsed.scheme != "https" or parsed.hostname not in {"huggingface.co", "raw.githubusercontent.com", "www.apache.org"} or parsed.username:
            raise ValueError("Only fixed official HTTPS source URLs are permitted")
        temporary = cache_path(name + ".part")
        request = urllib.request.Request(record["url"], headers={"User-Agent": "Chinese-Flashcard-Melo-Audition/1"})
        started = time.monotonic()
        total = 0
        last_report = 0.0
        emit("download", file=name, expectedBytes=record["bytes"])
        # Redirects are from fixed trusted model hosting, not user-provided URLs.
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as destination:
            while True:
                if time.monotonic() - started > 1200:
                    raise TimeoutError("Source download exceeded its overall deadline")
                block = response.read(1024 * 1024)
                if not block:
                    break
                total += len(block)
                if total > record["bytes"]:
                    raise ValueError("Source exceeds its locked byte limit")
                destination.write(block)
                if time.monotonic() - last_report > 10:
                    emit("download_progress", file=name, bytes=total, expectedBytes=record["bytes"])
                    last_report = time.monotonic()
        verify_file(temporary, record)
        temporary.replace(path)
        emit("download_verified", file=name, bytes=total, seconds=round(time.monotonic() - started, 2))
    verify_sources()


def prepare_runtime() -> dict:
    """Verify archives AND extracted executable/package files before importing."""
    lock = json.loads(RUNTIME_LOCK.read_text(encoding="utf-8"))
    source = ROOT / lock["cacheDirectory"]
    directory = source / lock["pythonDirectory"]
    checked = 0
    for name, record in lock["packages"].items():
        archive = source / record["archive"]
        verify_file(archive, record)

        def verify_member(member_name: str, payload: bytes) -> None:
            nonlocal checked
            target = directory / member_name
            if not target.resolve().is_relative_to(directory.resolve()) or target.is_symlink():
                raise ValueError("Runtime file escapes its task-local directory")
            if not target.is_file() or hashlib.sha256(target.read_bytes()).digest() != hashlib.sha256(payload).digest():
                raise ValueError(f"Changed extracted runtime file: {member_name}")
            checked += 1

        if archive.suffix == ".whl":
            with zipfile.ZipFile(archive) as packed:
                for member in packed.infolist():
                    if not member.is_dir() and any(member.filename.startswith(prefix) for prefix in record["prefixes"]):
                        verify_member(member.filename, packed.read(member))
        else:
            with tarfile.open(archive, "r:gz") as packed:
                for member in packed.getmembers():
                    relative = member.name.removeprefix(record["archivePrefix"])
                    if member.isfile() and any(relative.startswith(prefix) for prefix in record["prefixes"]):
                        stream = packed.extractfile(member)
                        if stream is None:
                            raise ValueError("Unreadable runtime archive member")
                        with stream:
                            verify_member(relative, stream.read())
        emit("runtime_verified", package=name, version=record["version"])
    sys.path.insert(0, str(directory))
    import numpy as np
    import onnxruntime as ort
    import jieba
    import pypinyin
    for name, module in {"numpy": np, "onnxruntime": ort, "jieba": jieba, "pypinyin": pypinyin}.items():
        if module.__version__ != lock["packages"][name]["version"] or not Path(module.__file__).resolve().is_relative_to(directory.resolve()):
            raise ValueError(f"Unexpected runtime package: {name}")
    # jieba's generated dictionary cache remains inside the ignored task directory.
    jieba.dt.tmp_dir = str(CACHE)
    jieba.dt.cache_file = "jieba-melo.cache"
    return {"python": sys.version, "platform": platform.platform(), "provider": "CPUExecutionProvider",
            "ortVersion": ort.__version__, "numpyVersion": np.__version__, "verifiedRuntimeFiles": checked,
            "packages": {name: record["version"] for name, record in lock["packages"].items()}}


def rows() -> list[dict]:
    snapshot = verify_snapshot(CSV)
    with CSV.open(encoding="utf-8-sig", newline="") as stream:
        result = list(csv.DictReader(stream))
    if (len(result) != snapshot["words"] or
            sum(len(json.loads(row["例句JSON"])) for row in result) != snapshot["examples"]):
        raise ValueError("Unexpected reviewed vocabulary or example count")
    return result


def sample_items() -> list[dict]:
    vocabulary = rows()
    by_word = {row["组词"]: row for row in vocabulary}
    examples = {}
    for row in vocabulary:
        for example in json.loads(row["例句JSON"]):
            examples[(example["hanzi"], example["pinyin"])] = example
    legacy = json.loads((LEGACY / "samples.json").read_text(encoding="utf-8"))
    result = []
    for index, sample in enumerate(legacy["samples"], 1):
        filename = sample["file"]
        if Path(filename).name != filename:
            raise ValueError("Unsafe legacy audio filename")
        path = LEGACY / filename
        if digest(path) != sample["sha256"]:
            raise ValueError("Legacy Kokoro comparison audio changed")
        example = examples.get((sample["text"], sample["pinyin"]))
        result.append({"id": f"legacy-{index:02d}", "group": "Kokoro 对照", "text": sample["text"],
                       "pinyin": sample["pinyin"], "chunks": example.get("chunks") if example else None,
                       "kokoroFile": f"../chinese-speech-samples/{filename}", "kokoroSha256": sample["sha256"],
                       "notes": [sample["note"]] if sample.get("note") else []})
    for word in ["谢谢", "妈妈", "学习", "一个", "不是", "很好", "女儿", "喜欢", "不要", "水果"]:
        row = by_word.get(word)
        if row is None:
            raise ValueError(f"Audition word is absent from the frozen CSV: {word}")
        result.append({"id": f"word-{row['词条ID']}", "group": "常用词与自然变调", "text": row["组词"],
                       "pinyin": row["拼音"], "notes": []})
    # Include two complete current examples with their existing teaching boundaries.
    for word in ["谢谢", "学习"]:
        row = by_word[word]
        example = json.loads(row["例句JSON"])[0]
        result.append({"id": f"sentence-{row['词条ID']}", "group": "完整例句", "text": example["hanzi"],
                       "pinyin": example["pinyin"], "chunks": example["chunks"], "notes": []})
    return result


def probe_items() -> list[dict]:
    selected = {"得", "地", "的", "银行", "行为", "音乐"}
    originals = [item for item in sample_items() if item["text"] in selected and item["id"].startswith("legacy-")]
    variants = [("raw-1", "原转换示例 · 1.0 倍", "raw", 1.0),
                ("raw-07", "仅减速 · 0.7 倍", "raw", 0.7),
                ("padded-07", "原 Melo 首尾边界 · 0.7 倍", "padded", 0.7),
                ("padded-stop-07", "原 Melo 首尾边界＋句末停顿 · 0.7 倍", "padded_stop", 0.7)]
    return [{**item, "id": item["id"] + "-" + slug, "group": item["text"] + " · 短词对照",
             "speed": speed, "inputBoundary": boundary, "variantLabel": label,
             "notes": item["notes"] + [label, "这是输入协议实验，是否减少漏读需要试听确认。"]}
            for item in originals for slug, label, boundary, speed in variants]


def synthesize_samples(threads: int, probes: bool = False) -> None:
    lock = verify_sources()
    runtime = prepare_runtime()
    import numpy as np
    import onnxruntime as ort
    from melo_tts_frontend import compile_reading, UnsupportedReading
    from melo_tts_preview import render_preview

    runtime["intraOpThreads"] = threads
    options = ort.SessionOptions()
    options.inter_op_num_threads = 1
    options.intra_op_num_threads = threads
    started = time.perf_counter()
    model = ort.InferenceSession(str(CACHE / "model.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
    load_seconds = time.perf_counter() - started
    meta = model.get_modelmeta().custom_metadata_map
    if {item.name for item in model.get_inputs()} != INPUTS or int(meta["sample_rate"]) != 44100 or int(meta["n_speakers"]) != 1:
        raise ValueError("Unexpected Melo model input or sample-rate contract")
    if meta.get("model_type") != "melo-vits" or len(model.get_outputs()) != 1 or model.get_outputs()[0].name != "y":
        raise ValueError("Unexpected Melo model output contract")
    emit("model_ready", loadSeconds=round(load_seconds, 3), sampleRate=44100, speakerId=int(meta["speaker_id"]))
    output = PROBES if probes else OUTPUT
    output.mkdir(parents=True, exist_ok=True)
    items = probe_items() if probes else sample_items()
    token_map = dict(line.split() for line in (CACHE / "tokens.txt").read_text(encoding="utf-8").splitlines())
    report = {"schemaVersion": 1, "title": "Melo 短词丢读排查 · 输入与慢速对照" if probes else "MeloTTS FP32 中文试听", "summary": {},
              "metadata": {"createdAt": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
                           "voice": "Melo 原模型单普通话声线", "speed": 1.0, "sampleRate": 44100,
                           "noiseScale": 0.6, "noiseScaleW": 0.8, "modelLoadSeconds": load_seconds,
                           "speedLabel": "1.0 / 0.7 倍，逐条标注" if probes else "1.0 倍"},
              "model": {"revision": MODEL_REVISION, "sha256": MODEL_SHA256, "metadata": meta,
                        "bytes": lock["sources"]["model.onnx"]["bytes"], "precision": "FP32", "bertFeatures": "zero"},
              "pins": {"modelRevision": MODEL_REVISION, "meloCodeRevision": lock["meloCodeRevision"],
                       "wordlistSha256": CSV_SHA256, "sourceLockSha256": digest(LOCK), "runtimeLockSha256": digest(RUNTIME_LOCK),
                       "frontendSha256": digest(ROOT / "tools" / "melo_tts_frontend.py"),
                       "generatorSha256": digest(Path(__file__)),
                       "previewSha256": digest(ROOT / "tools" / "melo_tts_preview.py")},
              "waveformThresholds": {"minimumRms": MIN_AUDIBLE_RMS, "minimumPeak": MIN_AUDIBLE_PEAK,
                                     "maximumClipFraction": 0.01},
              "runtime": runtime, "evidenceBoundaries": BOUNDARIES + ([
                  "短词排查每词四个变量明确的输入方案；慢速使用 length_scale=1/0.7，播放器采样率保持 44100 Hz。",
                  "首尾边界参考原 Melo G2P；句末停顿仅用于韵律实验。没有证明这些变体已解决漏读。",
              ] if probes else []), "samples": []}
    for index, item in enumerate(items, 1):
        sample = {key: value for key, value in item.items() if key != "chunks"}
        try:
            reading = compile_reading(item["text"], item["pinyin"], item.get("chunks"))
            phone_ids, tones = reading["phoneIds"], reading["tones"]
            sample["spokenPinyin"] = " ".join(reading["spokenSyllables"])
            sample["notes"].extend(reading["warnings"])
            if not phone_ids or len(phone_ids) != len(tones) or len(phone_ids) > 510:
                raise UnsupportedReading("Audition token length is empty, misaligned or exceeds 510")
            boundary = item.get("inputBoundary", "raw")
            if boundary == "padded_stop":
                phone_ids, tones = phone_ids + [int(token_map["."])], tones + [0]
            if boundary in {"padded", "padded_stop"}:
                phone_ids = [int(token_map["_"])] + phone_ids + [int(token_map["_"])]
                tones = [0] + tones + [0]
            elif boundary != "raw":
                raise ValueError("Unknown fixed audition input protocol")
            phones_before_blank, tones_before_blank = list(phone_ids), list(tones)
            if int(meta["add_blank"]):
                expanded_phones = [0] * (2 * len(phone_ids) + 1)
                expanded_tones = [0] * (2 * len(tones) + 1)
                expanded_phones[1::2], expanded_tones[1::2] = phone_ids, tones
                phone_ids, tones = expanded_phones, expanded_tones
            inputs = {"x": np.array([phone_ids], dtype=np.int64), "tones": np.array([tones], dtype=np.int64),
                      "x_lengths": np.array([len(phone_ids)], dtype=np.int64),
                      "sid": np.array([int(meta["speaker_id"])], dtype=np.int64),
                      "noise_scale": np.array([0.6], dtype=np.float32),
                      "length_scale": np.array([1.0 / item.get("speed", 1.0)], dtype=np.float32),
                      "noise_scale_w": np.array([0.8], dtype=np.float32)}
            inference_start = time.perf_counter()
            audio = model.run(["y"], inputs)[0].reshape(-1)
            seconds = time.perf_counter() - inference_start
            if not 1 <= len(audio) <= 44100 * 30 or not np.isfinite(audio).all():
                raise ValueError("Model output is empty, nonfinite or longer than 30 seconds")
            rms = float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)))
            peak = float(np.max(np.abs(audio)))
            clip_fraction = float(np.mean(np.abs(audio) > 1.0))
            if rms <= MIN_AUDIBLE_RMS or peak <= MIN_AUDIBLE_PEAK:
                sample.update({"rms": rms, "peak": peak, "audioSeconds": len(audio) / 44100,
                               "inferenceSeconds": seconds})
                raise NearSilentOutput(f"模型输出近乎静音（RMS={rms:.6g}，峰值={peak:.6g}），不能计为可用样音。")
            if float(np.std(audio)) <= 1e-6 or clip_fraction >= 0.01:
                raise ValueError("Waveform has no meaningful variation or has severe clipping")
            if clip_fraction:
                sample["notes"].append(f"原模型浮点输出有 {clip_fraction:.6%} 样本超过 PCM16 范围；已记录而未调整模型音量。")
            filename = f"{index:02d}-{item['id']}.wav"
            path = output / filename
            pcm = np.rint(np.clip(audio, -1, 1) * 32767).astype("<i2")
            with wave.open(str(path), "wb") as stream:
                stream.setnchannels(1)
                stream.setsampwidth(2)
                stream.setframerate(44100)
                stream.writeframes(pcm.tobytes())
            audio_seconds = len(audio) / 44100
            sample.update({"status": "ready", "file": filename, "sha256": digest(path), "frames": len(audio),
                           "audioSeconds": audio_seconds, "inferenceSeconds": seconds, "rtf": seconds / audio_seconds,
                           "finite": True, "rms": rms, "peak": peak, "clipFraction": clip_fraction,
                           "tailTrimSamples": 0, "tokenCount": len(phone_ids), "phonesBeforeBlank": phones_before_blank,
                           "tonesBeforeBlank": tones_before_blank})
            emit("sample_ready", index=index, total=len(items), text=item["text"], inferenceSeconds=round(seconds, 3),
                 audioSeconds=round(audio_seconds, 3), rtf=round(seconds / audio_seconds, 3))
        except NearSilentOutput as error:
            sample.update({"status": "near_silent", "error": str(error)})
            emit("sample_near_silent", index=index, text=item["text"], reason=str(error))
        except UnsupportedReading as error:
            sample.update({"status": "unsupported", "error": str(error)})
            emit("sample_unsupported", index=index, text=item["text"], reason=str(error))
        except Exception as error:
            sample.update({"status": "inference_error", "error": str(error)[:500]})
            emit("sample_error", index=index, text=item["text"], reason=str(error)[:300])
        report["samples"].append(sample)
    ready = [sample for sample in report["samples"] if sample["status"] == "ready"]
    report["summary"] = {"total": len(items), "supported": len(ready), "failed": len(items) - len(ready),
                         "audioSeconds": sum(sample["audioSeconds"] for sample in ready),
                         "inferenceSeconds": sum(sample["inferenceSeconds"] for sample in ready)}
    save_json(output / "samples.json", report)
    save_json(output / "exceptions.json", [sample for sample in report["samples"] if sample["status"] != "ready"])
    page = render_preview(output, report)
    emit("audition_complete", **report["summary"], page=str(page))
    if not ready or any(sample["status"] == "inference_error" for sample in report["samples"]):
        raise ValueError("Audition contains inference failures; inspect samples.json")


def check(output: Path = OUTPUT) -> None:
    verify_sources()
    rows()
    report = json.loads((output / "samples.json").read_text(encoding="utf-8"))
    if report["pins"]["sourceLockSha256"] != digest(LOCK) or report["pins"]["runtimeLockSha256"] != digest(RUNTIME_LOCK):
        raise ValueError("Audition used different source locks")
    if report["model"]["revision"] != MODEL_REVISION or report["model"]["sha256"] != MODEL_SHA256:
        raise ValueError("Audition used a different model")
    for key, name in (("frontendSha256", "melo_tts_frontend.py"), ("generatorSha256", "evaluate_melo_tts.py"),
                      ("previewSha256", "melo_tts_preview.py")):
        if report["pins"].get(key) != digest(ROOT / "tools" / name):
            raise ValueError("Audition used a different evaluation tool: " + name)
    ready = 0
    for sample in report["samples"]:
        if sample["status"] != "ready":
            if sample.get("file"):
                raise ValueError("Unsupported sample advertises a generated WAV")
            continue
        filename = sample["file"]
        if Path(filename).name != filename:
            raise ValueError("Unsafe generated WAV filename")
        path = output / filename
        if digest(path) != sample["sha256"]:
            raise ValueError("Generated audio checksum changed")
        with wave.open(str(path), "rb") as stream:
            if (stream.getnchannels(), stream.getsampwidth(), stream.getframerate(), stream.getnframes()) != (1, 2, 44100, sample["frames"]):
                raise ValueError("Unexpected WAV format")
        if sample["tailTrimSamples"] != 0 or not sample["finite"]:
            raise ValueError("Unexpected waveform processing")
        if sample["rms"] <= MIN_AUDIBLE_RMS or sample["peak"] <= MIN_AUDIBLE_PEAK:
            raise ValueError("Near-silent waveform was counted as a usable sample")
        ready += 1
    if ready != report["summary"]["supported"] or len(report["samples"]) != report["summary"]["total"]:
        raise ValueError("Audition sample statistics mismatch")
    if not (output / "index.html").is_file():
        raise ValueError("Comparison page is missing")
    emit("check_passed", samples=ready, exceptions=len(report["samples"]) - ready,
         wordlistSha256=CSV_SHA256, evidence="WAV/resource validity only; listening is pending")


def audit() -> None:
    verify_sources()
    prepare_runtime()
    from melo_tts_frontend import compile_reading, UnsupportedReading
    vocabulary = rows()
    readings = {}
    applications = 0
    for row in vocabulary:
        readings[(row["组词"], row["拼音"])] = None
        applications += 1
        for example in json.loads(row["例句JSON"]):
            readings[(example["hanzi"], example["pinyin"])] = example["chunks"]
            applications += 1
    failures, supported, warning_count = [], 0, 0
    for index, ((text, pinyin), chunks) in enumerate(readings.items(), 1):
        try:
            result = compile_reading(text, pinyin, chunks)
            supported += 1
            warning_count += bool(result["warnings"])
        except UnsupportedReading as error:
            failures.append({"text": text, "pinyin": pinyin, "error": str(error)})
        if index % 2000 == 0:
            emit("audit_progress", checked=index, total=len(readings), unsupported=len(failures))
    report = {"schemaVersion": 1, "wordlistSha256": CSV_SHA256, "wordCount": len(vocabulary),
              "exampleApplications": applications - len(vocabulary), "uniqueTextPinyin": len(readings),
              "supported": supported, "unsupported": len(failures), "warnings": warning_count,
              "exceptions": failures, "evidenceBoundary": "Frontend conversion only; no full-corpus model inference or listening."}
    save_json(OUTPUT / "frontend-coverage.json", report)
    emit("audit_complete", **{key: value for key, value in report.items() if key not in {"exceptions", "evidenceBoundary"}})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare", action="store_true")
    mode.add_argument("--samples", action="store_true")
    mode.add_argument("--probes", action="store_true", help="Compare six reported short words with slower/padded input variants")
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--audit", action="store_true")
    parser.add_argument("--threads", type=int, default=2, choices=range(1, 9))
    args = parser.parse_args()
    if args.prepare:
        prepare()
    elif args.samples:
        synthesize_samples(args.threads)
    elif args.probes:
        synthesize_samples(args.threads, probes=True)
        check(PROBES)
    elif args.audit:
        audit()
    else:
        check()


if __name__ == "__main__":
    main()
