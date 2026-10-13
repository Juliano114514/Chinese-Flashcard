#!/usr/bin/env python3
"""Prepare or verify frozen English teaching/speech assets without changing the CSV.

--prepare explicitly downloads fixed, checksum-locked sources to .gradle only.
--build uses those sources and a build-time eSpeak DLL; --check is offline/read-only
and needs only the Python standard library. --refresh-teaching updates provenance
indices/licenses from existing assets without downloading or loading native code.
No GPL code or NPZ is copied to assets.
"""
from __future__ import annotations

import argparse
import csv
import ctypes
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import shutil
import struct
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".gradle" / "english-resource-source"
LOCK = ROOT / "tools" / "english_resources" / "source-lock.json"
SPEECH = ROOT / "core" / "media" / "src" / "main" / "assets" / "english-speech"
TEACHING = ROOT / "core" / "data" / "src" / "main" / "assets" / "english-teaching"
CSV = ROOT / "wordlist.csv"
MODEL_REVISION = "84781d74e29ee25217551556398b42f80593a813"
KITTEN_CODE_REVISION = "8ed679c08e0976b236bd5d0dc4ec9ddd98b0da98"
IPA_REVISION = "43c3570eb3553bdd19fccd2bd0091534889af023"
VOICE = "expr-voice-2-f"
MODEL_NAME = "kitten-nano-0.8-int8.onnx"
STYLE_NAME = "bella.f32"
STYLE_SHA256 = "0f56274d033abbb4103140ff346e4f343c31dd0d2d3a45269a0988245291330e"
MAX_SOURCE_BYTES = 64 * 1024 * 1024
MAX_TOKEN_COUNT = 510
PUNCTUATION = ';:,.!?¡¿—…"«»“”()[]{}'
SYMBOLS = (
    "$" + ';:,.!?¡¿—…"«»"" ' + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    + "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ"
)
TOKEN_MAP = {symbol: index for index, symbol in enumerate(SYMBOLS)}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def text_hash(text: str) -> str:
    return sha256(text.strip().encode("utf-8"))


def write_json(path: Path, value: object, *, pretty: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
        indent=2 if pretty else None, separators=None if pretty else (",", ":")) + "\n",
        encoding="utf-8", newline="\n")


def split_meanings(text: str) -> list[str]:
    """Split only top-level semicolons, retaining parentheses and their content."""
    result: list[str] = []
    depth, start = 0, 0
    for index, character in enumerate(text):
        if character in "(（[":
            depth += 1
        elif character in ")）]":
            depth = max(0, depth - 1)
        elif character == ";" and depth == 0:
            fragment = text[start:index].strip()
            if fragment:
                result.append(fragment)
            start = index + 1
    fragment = text[start:].strip()
    if fragment:
        result.append(fragment)
    return list(dict.fromkeys(result))


def collect_texts() -> tuple[list[str], dict[str, int], int]:
    texts: set[str] = set()
    counts: dict[str, int] = {}

    def add(text: str, category: str, *, split: bool = False) -> None:
        text = text.strip()
        if not text:
            return
        if not any(character.isalnum() for character in text):
            counts["nonSpeechSymbol"] = counts.get("nonSpeechSymbol", 0) + 1
            return
        if len(text) > 2000:
            raise ValueError(f"English text exceeds 2000 characters: {category}")
        texts.add(text)
        counts[category] = counts.get(category, 0) + 1
        if split:
            texts.update(fragment for fragment in split_meanings(text)
                if any(character.isalnum() for character in fragment))

    with CSV.open(encoding="utf-8-sig", newline="") as source:
        rows = list(csv.DictReader(source))
    for row in rows:
        add(row["英文释义"], "primary", split=True)
        for column, category in (("本义解释JSON", "literal"), ("引申义解释JSON", "figurative")):
            for meaning in json.loads(row[column]):
                add(meaning, category, split=True)
        for part in json.loads(row["部件JSON"]):
            add(part["gloss"], "part", split=True)
        for sentence in json.loads(row["例句JSON"]):
            add(sentence["english"], "sentence")
            for chunk in sentence["chunks"]:
                add(chunk["gloss"], "sentenceChunk", split=True)
    return sorted(texts), counts, len(rows)


def primary_senses(text: str) -> list[str]:
    """Match domain splitEnglishMeanings, retaining its order and duplicate text."""
    result: list[str] = []
    depth, start = 0, 0
    for index, character in enumerate(text):
        if character in "([":
            depth += 1
        elif character in ")]":
            depth = max(0, depth - 1)
        elif character in ";；" and depth == 0:
            fragment = text[start:index].strip()
            if fragment:
                result.append(fragment)
            start = index + 1
    fragment = text[start:].strip()
    if fragment:
        result.append(fragment)
    return result


def word_index(ipa_entries: dict[str, str], speech_keys: set[str]) -> dict:
    entries = {}
    with CSV.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            word_id = row["词条ID"]
            if word_id in entries:
                raise ValueError("Duplicate source word ID")
            senses = []
            for text in primary_senses(row["英文释义"]):
                key = text_hash(text)
                is_spoken = any(character.isalnum() for character in text)
                if is_spoken and key not in speech_keys:
                    raise ValueError(f"Primary sense has no existing speech tokens: {word_id}")
                senses.append({"text": text, "speechHash": key if is_spoken else None,
                    "ipaKey": text.lower() if text.lower() in ipa_entries else None})
            entries[word_id] = {"primaryGloss": row["英文释义"], "senses": senses}
    return {"schemaVersion": 1, "sourceWordlistSha256": sha256(CSV.read_bytes()),
        "wordCount": len(entries), "entries": entries}


def refresh_teaching() -> None:
    """Reproduce cross-resource provenance from already generated frozen assets."""
    speech_manifest = json.loads((SPEECH / "manifest.json").read_text(encoding="utf-8"))
    if speech_manifest["sourceWordlistSha256"] != sha256(CSV.read_bytes()):
        raise ValueError("Cannot index a changed CSV against old speech assets")
    speech_keys: set[str] = set()
    for filename in speech_manifest["shards"]:
        speech_keys.update(json.loads((SPEECH / filename).read_text(encoding="utf-8"))["entries"])
    ipa_entries = json.loads((TEACHING / "ipa.json").read_text(encoding="utf-8"))["entries"]
    write_json(TEACHING / "word-index.json", word_index(ipa_entries, speech_keys))
    license_names = ["IPA_MIT.txt", "CMUDICT_BSD.txt", "CMUDICT_IPA_MIT.txt", "SYLLABIFY_MIT.txt"]
    sections = [
        "English IPA teaching data: sources and licenses\n\n"
        "ipa-dict en_US; fixed revision " + IPA_REVISION + ".\n"
        "Source: https://github.com/open-dict-data/ipa-dict\n"
        "US IPA data is based on cmudict-ipa by lingz, CMUdict by Carnegie Mellon University, "
        "and syllabify by Kyle Gorman.\n"
        "Processing: exact lowercase lookup for the current wordlist, with original IPA variants retained.\n"
        "The word-ID provenance index reproduces existing English teaching text; "
        "it retains the wordlist's existing mixed-source licensing and does not relicense it.\n",
    ]
    for name in license_names:
        sections.append("\n\n" + name + "\n\n" + (TEACHING / "licenses" / name).read_text(encoding="utf-8"))
    (TEACHING / "LICENSES.txt").write_text("".join(sections), encoding="utf-8", newline="\n")
    source_path = TEACHING / "sources.json"
    provenance = json.loads(source_path.read_text(encoding="utf-8"))
    provenance["sourceWordlistSha256"] = speech_manifest["sourceWordlistSha256"]
    provenance["generatedFiles"] = {name: {"sha256": sha256((TEACHING / name).read_bytes()),
        "bytes": (TEACHING / name).stat().st_size} for name in ("ipa.json", "word-index.json", "LICENSES.txt")}
    write_json(source_path, provenance, pretty=True)


def source_lock() -> dict[str, dict]:
    return json.loads(LOCK.read_text(encoding="utf-8"))


def verify_sources() -> None:
    for name, expected in source_lock().items():
        path = CACHE / name
        if not path.is_file() or sha256(path.read_bytes()) != expected["sha256"]:
            raise ValueError(f"Missing or changed frozen source: {name}; run --prepare")


def verify_native_tool() -> None:
    """Do not load a stale/tampered extracted DLL even when its wheel is intact."""
    directory = (CACHE / "espeak-wheel").resolve()
    with zipfile.ZipFile(CACHE / "espeakng_loader-0.2.4-py3-none-win_amd64.whl") as archive:
        for entry in archive.infolist():
            if entry.is_dir() or not (entry.filename == "espeakng_loader/espeak-ng.dll" or
                    entry.filename.startswith("espeakng_loader/espeak-ng-data/")):
                continue
            target = (directory / entry.filename).resolve()
            if not target.is_relative_to(directory) or not target.is_file() or (
                    sha256(target.read_bytes()) != sha256(archive.read(entry))):
                raise ValueError("Changed extracted build-time phonemizer; rerun --prepare")


def prepare() -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    for name, expected in source_lock().items():
        target = CACHE / name
        if target.is_file() and sha256(target.read_bytes()) == expected["sha256"]:
            continue
        # Every URL is fixed project configuration. No user-controlled remote URL.
        print(f"Downloading frozen source: {name}", flush=True)
        request = urllib.request.Request(expected["url"], headers={
            "User-Agent": "ChineseFlashcard-EnglishResources/1"})
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read(MAX_SOURCE_BYTES + 1)
        if len(data) > MAX_SOURCE_BYTES or sha256(data) != expected["sha256"]:
            raise ValueError(f"Frozen source size/checksum mismatch: {name}")
        target.write_bytes(data)
    verify_sources()
    wheel_root = (CACHE / "espeak-wheel").resolve()
    wheel = CACHE / "espeakng_loader-0.2.4-py3-none-win_amd64.whl"
    with zipfile.ZipFile(wheel) as archive:
        if len(archive.infolist()) > 4000:
            raise ValueError("Too many wheel entries")
        total = 0
        for entry in archive.infolist():
            # Do not run Python loader/install hooks. Extract only native data.
            if not (entry.filename == "espeakng_loader/espeak-ng.dll" or
                    entry.filename.startswith("espeakng_loader/espeak-ng-data/")):
                continue
            if entry.is_dir():
                continue
            total += entry.file_size
            target = (wheel_root / entry.filename).resolve()
            if (not target.is_relative_to(wheel_root) or entry.file_size > 32 * 1024 * 1024
                    or total > 64 * 1024 * 1024
                    or (entry.external_attr >> 16) & 0o170000 == 0o120000):
                raise ValueError("Invalid native-tool wheel entry")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
    print("Frozen sources prepared in task-local cache; no global installation.")


class EspeakPhonemizer:
    """Build-time calls to the documented eSpeak C API; no synthesis or network."""
    def __init__(self) -> None:
        directory = CACHE / "espeak-wheel" / "espeakng_loader"
        self.library = ctypes.CDLL(str(directory / "espeak-ng.dll"))
        library = self.library
        library.espeak_Initialize.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
        library.espeak_Initialize.restype = ctypes.c_int
        library.espeak_SetVoiceByName.argtypes = [ctypes.c_char_p]
        library.espeak_SetVoiceByName.restype = ctypes.c_int
        library.espeak_TextToPhonemes.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.c_int, ctypes.c_int]
        library.espeak_TextToPhonemes.restype = ctypes.c_char_p
        library.espeak_Info.argtypes = [ctypes.POINTER(ctypes.c_char_p)]
        library.espeak_Info.restype = ctypes.c_char_p
        library.espeak_Terminate.argtypes = []
        library.espeak_Terminate.restype = ctypes.c_int
        if library.espeak_Initialize(2, 0, str(directory / "espeak-ng-data").encode(), 0) <= 0:
            raise RuntimeError("eSpeak initialization failed")
        if library.espeak_SetVoiceByName(b"en-us") != 0:
            raise RuntimeError("eSpeak en-US voice selection failed")
        data_path = ctypes.c_char_p()
        self.version = library.espeak_Info(ctypes.byref(data_path)).decode("ascii")

    def segment(self, text: str) -> str:
        buffer = ctypes.create_string_buffer(text.encode("utf-8"))
        pointer = ctypes.c_void_p(ctypes.addressof(buffer))
        phonemes: list[str] = []
        for _ in range(128):
            if not pointer.value:
                break
            result = self.library.espeak_TextToPhonemes(ctypes.byref(pointer), 1, 2)
            if result:
                phonemes.append(result.decode("utf-8"))
        else:
            raise RuntimeError("eSpeak clause limit exceeded")
        # Language flags are metadata, not spoken phonemes.
        return re.sub(r"\([a-z]{2,3}(?:-[a-z]+)?\)", "", " ".join(phonemes))

    def phonemes(self, text: str) -> str:
        # Preserve source punctuation as prosody tokens, as in Kitten's frontend.
        segments = re.split(f"([{re.escape(PUNCTUATION)}]+)", text)
        result = "".join(segment if index % 2 else self.segment(segment)
            for index, segment in enumerate(segments) if segment.strip())
        return " ".join(re.findall(r"\w+|[^\w\s]", result))

    def close(self) -> None:
        self.library.espeak_Terminate()


def build() -> None:
    import numpy as np  # Needed only for safe, allow_pickle=False conversion.
    verify_sources()
    verify_native_tool()
    SPEECH.mkdir(parents=True, exist_ok=True)
    TEACHING.mkdir(parents=True, exist_ok=True)
    for target in (SPEECH / "licenses", TEACHING / "licenses"):
        target.mkdir(parents=True, exist_ok=True)
    config = json.loads((CACHE / "config.json").read_text(encoding="utf-8"))
    with np.load(CACHE / "voices.npz", allow_pickle=False) as voices:
        style = np.asarray(voices[VOICE], dtype="<f4")
    if style.shape != (400, 256) or not np.isfinite(style).all():
        raise ValueError("Unexpected Bella voice data")
    (SPEECH / STYLE_NAME).write_bytes(style.tobytes(order="C"))
    shutil.copyfile(CACHE / "kitten_tts_nano_v0_8.onnx", SPEECH / MODEL_NAME)
    shutil.copyfile(CACHE / "KITTEN_APACHE-2.0.txt", SPEECH / "licenses" / "KITTEN_APACHE-2.0.txt")
    shutil.copyfile(CACHE / "ORT_MIT.txt", SPEECH / "licenses" / "ORT_MIT.txt")
    (SPEECH / "LICENSES.txt").write_text(
        "English speech model and inference runtime\n\n"
        "KittenML Kitten TTS Nano 0.8 INT8: Apache-2.0; fixed model revision " + MODEL_REVISION + ".\n"
        "Model/style conversion retains one Bella voice; no training or voice cloning.\n"
        "The English text/token sidecar retains the existing wordlist's mixed-source licensing; "
        "the model license does not relicense those texts.\n"
        "Microsoft ONNX Runtime Android 1.31.0: MIT.\n"
        "eSpeak NG is a build-time tool only; its code and language data are absent from these assets.\n\n"
        + (CACHE / "KITTEN_APACHE-2.0.txt").read_text(encoding="utf-8")
        + "\n\nMicrosoft ONNX Runtime license\n\n"
        + (CACHE / "ORT_MIT.txt").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
    shutil.copyfile(CACHE / "MODEL_README.md", SPEECH / "MODEL_README.md")
    for name in ("IPA_MIT.txt", "CMUDICT_BSD.txt", "CMUDICT_IPA_MIT.txt"):
        shutil.copyfile(CACHE / name, TEACHING / "licenses" / name)
    # Upstream embeds this complete license in the first 21 source lines.
    license_lines = (CACHE / "SYLLABIFY_SOURCE.py").read_text(encoding="utf-8").splitlines()[1:21]
    (TEACHING / "licenses" / "SYLLABIFY_MIT.txt").write_text(
        "\n".join(line.removeprefix("# ").removeprefix("#") for line in license_lines) + "\n",
        encoding="utf-8", newline="\n")
    shutil.copyfile(CACHE / "IPA_README.md", TEACHING / "IPA_README.md")
    spec = importlib.util.spec_from_file_location("frozen_kitten_preprocess", CACHE / "kitten_preprocess.py")
    if spec is None or spec.loader is None:
        raise RuntimeError("Cannot load checksum-verified text preprocessor")
    preprocessor = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = preprocessor
    spec.loader.exec_module(preprocessor)
    normalize = preprocessor.TextPreprocessor(remove_punctuation=False)
    texts, categories, word_count = collect_texts()
    shards: dict[str, dict] = {f"{index:02x}": {} for index in range(256)}
    ignored_symbols: dict[str, int] = {}
    phonemizer = EspeakPhonemizer()
    max_tokens, chunk_count = 0, 0
    try:
        for index, text in enumerate(texts):
            chunks = []
            for chunk in preprocessor.chunk_text(normalize(text)):
                # An abbreviation followed by a closing parenthesis may leave a
                # punctuation-only tail. It has no speech and needs no model call.
                if not any(character.isalnum() for character in chunk):
                    continue
                phonemes = phonemizer.phonemes(chunk)
                if not any(character.isalpha() for character in phonemes):
                    raise ValueError(f"No spoken phonemes for text hash {text_hash(text)}")
                for character in phonemes:
                    if character not in TOKEN_MAP:
                        ignored_symbols[character] = ignored_symbols.get(character, 0) + 1
                tokens = [0] + [TOKEN_MAP[character] for character in phonemes if character in TOKEN_MAP] + [10, 0]
                if not 3 < len(tokens) <= MAX_TOKEN_COUNT:
                    raise ValueError("Invalid token count")
                chunks.append({"tokenIds": tokens, "styleRow": min(len(chunk), 399)})
                chunk_count += 1
                max_tokens = max(max_tokens, len(tokens))
            if not chunks:
                raise ValueError(f"Preprocessor removed all text: {text_hash(text)}")
            key = text_hash(text)
            shards[key[:2]][key] = {"text": text, "chunks": chunks}
            if index and index % 5000 == 0:
                print(f"Phonemized {index}/{len(texts)} frozen texts", flush=True)
    finally:
        phonemizer.close()
    shard_files = {}
    for prefix, entries in shards.items():
        path = SPEECH / f"shard_{prefix}.json"
        write_json(path, {"schemaVersion": 1, "entries": entries})
        shard_files[path.name] = {"sha256": sha256(path.read_bytes()), "bytes": path.stat().st_size,
            "entries": len(entries)}
    dictionary = {}
    for line in (CACHE / "ipa_en_US.txt").read_text(encoding="utf-8").splitlines():
        word, ipa = line.split("\t", 1)
        dictionary[word] = ipa
    ipa_entries = {text.lower(): dictionary[text.lower()] for text in texts if text.lower() in dictionary}
    write_json(TEACHING / "ipa.json", {"schemaVersion": 1, "dialect": "en-US", "entries": ipa_entries})
    write_json(TEACHING / "sources.json", {"schemaVersion": 1,
        "revision": IPA_REVISION, "url": "https://github.com/open-dict-data/ipa-dict",
        "dataSha256": source_lock()["ipa_en_US.txt"]["sha256"],
        "processing": "Exact lowercase lookup of current English texts; no generated or guessed IPA.",
        "licenses": ["licenses/IPA_MIT.txt", "licenses/CMUDICT_BSD.txt", "licenses/CMUDICT_IPA_MIT.txt", "licenses/SYLLABIFY_MIT.txt"]}, pretty=True)
    manifest = {"schemaVersion": 1, "modelPath": f"english-speech/{MODEL_NAME}",
        "stylePath": f"english-speech/{STYLE_NAME}", "styleRows": 400, "styleColumns": 256,
        "sampleRate": 24000, "speedPrior": config["speed_priors"][VOICE], "trimTailSamples": 5000,
        "hashPrefixChars": 2, "shardPattern": "english-speech/shard_{prefix}.json",
        "modelSha256": sha256((SPEECH / MODEL_NAME).read_bytes()),
        "styleSha256": sha256((SPEECH / STYLE_NAME).read_bytes()),
        "sourceWordlistSha256": sha256(CSV.read_bytes()), "modelRevision": MODEL_REVISION,
        "modelSource": "https://huggingface.co/KittenML/kitten-tts-nano-0.8-int8",
        "voice": "Bella", "voiceId": VOICE, "codeRevision": KITTEN_CODE_REVISION,
        "maxTokenCount": MAX_TOKEN_COUNT, "shards": shard_files,
        "tokenization": "Kitten TextCleaner symbol IDs; start 0, ending 10 and 0; per-chunk precomputed style row."}
    write_json(SPEECH / "manifest.json", manifest, pretty=True)
    report = {"schemaVersion": 1, "wordCount": word_count, "sourceWordlistSha256": manifest["sourceWordlistSha256"],
        "uniqueSpeechTexts": len(texts), "speechChunks": chunk_count, "missingSpeechTexts": 0,
        "sourceCategoryOccurrences": categories, "maximumTokenCount": max_tokens,
        "ipaExactMatches": len(ipa_entries), "ipaMissingTexts": len(texts) - len(ipa_entries),
        "ignoredNonModelSymbols": ignored_symbols, "buildTimeEspeakVersion": phonemizer.version,
        "runtimeGplCodeBundled": False, "runtimeNpzBundled": False,
        "speechAssetBytes": sum(path.stat().st_size for path in SPEECH.rglob("*") if path.is_file()),
        "limitations": ["Static resource checks do not prove Android audio, latency, or pronunciation quality.",
            "Imported English texts absent from this frozen sidecar require a device-provided offline English voice.",
            "IPA exact dictionary matches retain multiple pronunciations and are not context-disambiguated.",
            "Model license declarations do not establish training-data or voice rights beyond their stated grant."]}
    write_json(SPEECH / "coverage.json", report, pretty=True)
    refresh_teaching()
    print(json.dumps(report, ensure_ascii=False, indent=2))
    check()


def check() -> None:
    manifest = json.loads((SPEECH / "manifest.json").read_text(encoding="utf-8"))
    if manifest["modelRevision"] != MODEL_REVISION or manifest["sourceWordlistSha256"] != sha256(CSV.read_bytes()):
        raise ValueError("Frozen model/wordlist revision mismatch")
    if (manifest["modelSha256"] != source_lock()["kitten_tts_nano_v0_8.onnx"]["sha256"]
            or manifest["styleSha256"] != STYLE_SHA256
            or manifest["styleRows"] != 400 or manifest["styleColumns"] != 256
            or manifest["maxTokenCount"] != MAX_TOKEN_COUNT):
        raise ValueError("Frozen model/style contract mismatch")
    for filename, key in ((MODEL_NAME, "modelSha256"), (STYLE_NAME, "styleSha256")):
        if sha256((SPEECH / filename).read_bytes()) != manifest[key]:
            raise ValueError(f"Asset checksum mismatch: {filename}")
    style_bytes = (SPEECH / STYLE_NAME).read_bytes()
    if len(style_bytes) != manifest["styleRows"] * 256 * 4 or not all(
            math.isfinite(item[0]) for item in struct.iter_unpack("<f", style_bytes)):
        raise ValueError("Invalid raw float32 voice style")
    present: set[str] = set()
    for filename, expected in manifest["shards"].items():
        path = SPEECH / filename
        data = path.read_bytes()
        if sha256(data) != expected["sha256"] or len(data) != expected["bytes"]:
            raise ValueError(f"Shard checksum mismatch: {filename}")
        entries = json.loads(data)["entries"]
        if len(entries) != expected["entries"]:
            raise ValueError("Shard entry count mismatch")
        for key, entry in entries.items():
            if text_hash(entry["text"]) != key or filename != f"shard_{key[:2]}.json" or key in present:
                raise ValueError("Invalid speech text key")
            present.add(key)
            if not entry["chunks"]:
                raise ValueError("Empty speech chunks")
            for chunk in entry["chunks"]:
                tokens = chunk["tokenIds"]
                if not (3 < len(tokens) <= MAX_TOKEN_COUNT and tokens[0] == 0 and tokens[-2:] == [10, 0]
                        and all(type(token) is int and 0 <= token < len(SYMBOLS) for token in tokens)
                        and type(chunk["styleRow"]) is int and 0 <= chunk["styleRow"] < manifest["styleRows"]):
                    raise ValueError("Invalid token/style data")
    texts, _, word_count = collect_texts()
    if present != {text_hash(text) for text in texts}:
        raise ValueError("Frozen English speech coverage mismatch")
    ipa = json.loads((TEACHING / "ipa.json").read_text(encoding="utf-8"))
    if ipa["schemaVersion"] != 1 or ipa["dialect"] != "en-US" or not all(
            key == key.lower() and value.startswith("/") and value.endswith("/")
            for key, value in ipa["entries"].items()):
        raise ValueError("Invalid dictionary IPA entries")
    index = json.loads((TEACHING / "word-index.json").read_text(encoding="utf-8"))
    if index != word_index(ipa["entries"], present) or index["wordCount"] != word_count:
        raise ValueError("Word-ID provenance index differs from the original CSV or speech/IPA resources")
    teaching_sources = json.loads((TEACHING / "sources.json").read_text(encoding="utf-8"))
    if teaching_sources["sourceWordlistSha256"] != manifest["sourceWordlistSha256"]:
        raise ValueError("Teaching source wordlist snapshot mismatch")
    for filename in ("ipa.json", "word-index.json", "LICENSES.txt"):
        expected = teaching_sources["generatedFiles"][filename]
        data = (TEACHING / filename).read_bytes()
        if sha256(data) != expected["sha256"] or len(data) != expected["bytes"]:
            raise ValueError(f"Teaching asset checksum mismatch: {filename}")
    for root in (SPEECH, TEACHING):
        for path in root.rglob("*"):
            if path.is_file() and path.suffix.lower() in {".npz", ".npy", ".dll", ".so", ".py", ".whl"}:
                raise ValueError("Build-only native code or unsafe-format source found in assets")
    for root, names in ((SPEECH, ["KITTEN_APACHE-2.0.txt", "ORT_MIT.txt"]),
            (TEACHING, ["IPA_MIT.txt", "CMUDICT_BSD.txt", "CMUDICT_IPA_MIT.txt", "SYLLABIFY_MIT.txt"])):
        if any(not (root / "licenses" / name).is_file() for name in names):
            raise ValueError("Missing redistribution license")
        for name in names:
            if name in source_lock() and sha256((root / "licenses" / name).read_bytes()) != source_lock()[name]["sha256"]:
                raise ValueError("Changed redistribution license")
    combined = (SPEECH / "LICENSES.txt").read_text(encoding="utf-8")
    if any((SPEECH / "licenses" / name).read_text(encoding="utf-8") not in combined
            for name in ("KITTEN_APACHE-2.0.txt", "ORT_MIT.txt")):
        raise ValueError("Incomplete combined speech licenses")
    teaching_combined = (TEACHING / "LICENSES.txt").read_text(encoding="utf-8")
    if any((TEACHING / "licenses" / name).read_text(encoding="utf-8") not in teaching_combined
            for name in ("IPA_MIT.txt", "CMUDICT_BSD.txt", "CMUDICT_IPA_MIT.txt", "SYLLABIFY_MIT.txt")):
        raise ValueError("Incomplete combined teaching licenses")
    print(f"English resource check passed: {word_count} words, {len(present)} speech texts, {len(ipa['entries'])} exact IPA entries.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--prepare", action="store_true")
    actions.add_argument("--build", action="store_true")
    actions.add_argument("--check", action="store_true")
    actions.add_argument("--refresh-teaching", action="store_true")
    arguments = parser.parse_args()
    if arguments.prepare:
        prepare()
    elif arguments.build:
        build()
    elif arguments.refresh_teaching:
        refresh_teaching()
        check()
    else:
        check()


if __name__ == "__main__":
    main()
