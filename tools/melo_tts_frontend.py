#!/usr/bin/env python3
"""Compile recorded Mandarin readings for the local Melo listening preview.

This module never downloads resources or changes the vocabulary. It verifies its
three frontend sources against the shared lock. Phones and tones are returned WITHOUT the
model's add_blank transformation; apply that once according to model metadata.

The pinned upstream ToneSandhi is used for 一/不/third-tone rules. Its dictionary
neutral-tone guesses are disabled because the supplied pinyin already specifies
the intended reading. Its internal pypinyin calls receive recorded tones instead
of guessing polyphonic characters. Jieba word boundaries remain an approximation.
"""
from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
import hashlib
import importlib.util
import json
import logging
from pathlib import Path
import sys
import threading
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / ".gradle" / "melo-resource-source"
PYTHON_BUNDLE = ROOT / ".gradle" / "chinese-resource-source" / "python"
LOCK = ROOT / "tools" / "melo_resources" / "source-lock.json"
MAX_TEXT = 1000
MAX_PINYIN = 2000
MAX_SEGMENTATION_STATES = 20_000
TONE_MARKS = {"\u0304": 1, "\u0301": 2, "\u030c": 3, "\u0300": 4}
PUNCTUATION = {
    ",": ",", "，": ",", "、": ",", ":": ",", "：": ",", ";": ",", "；": ",",
    ".": ".", "。": ".", "!": "!", "！": "!", "?": "?", "？": "?", "…": "…",
    "'": "'", '"': "'", "“": "'", "”": "'", "‘": "'", "’": "'",
    "(": "'", ")": "'", "（": "'", "）": "'", "[": "'", "]": "'",
    "《": "'", "》": "'", "「": "'", "」": "'", "—": "-", "–": "-", "-": "-",
}


class UnsupportedReading(ValueError):
    """The complete reading cannot be compiled safely; no partial audio is valid."""


@dataclass(frozen=True)
class _Syllable:
    base: str
    tone: int

    @property
    def numbered(self) -> str:
        return f"{self.base}{self.tone}"


@dataclass(frozen=True)
class _Unit:
    text: str
    syllable: _Syllable | None


def _hanzi(character: str) -> bool:
    point = ord(character)
    return (0x3400 <= point <= 0x4DBF or 0x4E00 <= point <= 0x9FFF
            or 0xF900 <= point <= 0xFAFF or 0x20000 <= point <= 0x2FFFF
            or 0x30000 <= point <= 0x323AF)


def _letter(character: str) -> bool:
    return character.isalpha() and all(
        "a" <= part <= "z" or unicodedata.combining(part)
        for part in unicodedata.normalize("NFD", character.lower())
    )


@lru_cache(maxsize=1)
def _source_lock() -> dict:
    if not LOCK.is_file() or LOCK.stat().st_size > 64 * 1024:
        raise UnsupportedReading("The shared Melo source lock is unavailable")
    value = json.loads(LOCK.read_text(encoding="utf-8"))
    if value.get("schemaVersion") != 1 or not isinstance(value.get("sources"), dict):
        raise UnsupportedReading("The shared Melo source lock is invalid")
    return value["sources"]


def _bounded_source(name: str) -> str:
    path = SOURCE / name
    if not path.is_file() or path.stat().st_size > 256 * 1024:
        raise UnsupportedReading(f"Missing or oversized locked source: {name}")
    data = path.read_bytes()
    expected = _source_lock().get(name, {})
    if len(data) != expected.get("bytes") or hashlib.sha256(data).hexdigest() != expected.get("sha256"):
        raise UnsupportedReading(f"Locked frontend source verification failed: {name}")
    return data.decode("utf-8")


class _Frontend:
    def __init__(self) -> None:
        if not PYTHON_BUNDLE.is_dir():
            raise UnsupportedReading("The locked local Python dependencies are unavailable")
        sys.path.insert(0, str(PYTHON_BUNDLE))
        import jieba
        import jieba.posseg as posseg
        from pypinyin import Style

        jieba.setLogLevel(logging.ERROR)
        self.posseg = posseg
        self.final_style = Style.FINALS_TONE3
        self.tokens: dict[str, int] = {}
        for line in _bounded_source("tokens.txt").splitlines():
            if not line.strip():
                continue
            symbol, number = line.split()
            index = int(number)
            if symbol in self.tokens or index < 0:
                raise UnsupportedReading("Invalid locked token table")
            self.tokens[symbol] = index
        self.phones: dict[str, tuple[str, ...]] = {}
        for line in _bounded_source("opencpop-strict.txt").splitlines():
            if not line.strip():
                continue
            syllable, phones = line.split("\t", 1)
            values = tuple(phones.split())
            if not values or syllable in self.phones or any(phone not in self.tokens for phone in values):
                raise UnsupportedReading("The locked syllable and token tables do not agree")
            self.phones[syllable] = values
        if any(symbol not in self.tokens for symbol in set(PUNCTUATION.values()) | {"_"}):
            raise UnsupportedReading("The locked token table lacks a required punctuation symbol")
        self.max_syllable = max(map(len, self.phones))

        # Only this verified local module is executed; do not import downloaded
        # arbitrary paths or accept source names from vocabulary input.
        _bounded_source("tone_sandhi.py")
        spec = importlib.util.spec_from_file_location("_flashcard_melo_tone_sandhi", SOURCE / "tone_sandhi.py")
        if spec is None or spec.loader is None:
            raise UnsupportedReading("Cannot load the locked ToneSandhi source")
        self.sandhi_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.sandhi_module)

        class RecordedToneSandhi(self.sandhi_module.ToneSandhi):
            def _neural_sandhi(self, word, pos, finals):
                return finals

        self.sandhi = RecordedToneSandhi()
        self.lock = threading.RLock()

    def syllable(self, value: str) -> _Syllable | None:
        letters: list[str] = []
        tones: list[int] = []
        for character in unicodedata.normalize("NFD", value.lower()):
            if character in TONE_MARKS:
                tones.append(TONE_MARKS[character])
            else:
                letters.append(character)
        if len(tones) > 1:
            return None
        base = unicodedata.normalize("NFC", "".join(letters)).replace("ü", "v")
        if base not in self.phones:
            return None
        return _Syllable(base, tones[0] if tones else 5)

    def split_syllables(self, runs: list[str], count: int) -> tuple[list[_Syllable], bool]:
        """Find at most two complete analyses, honoring explicit run boundaries."""
        source = "".join(runs)
        ends: dict[int, int] = {}
        complete_syllables: dict[int, int] = {}
        offset = 0
        for run in runs:
            # An explicitly delimited, valid syllable is one syllable. Never
            # manufacture extra readings (xué -> xu5 + e2, xian -> xi5 + an5)
            # merely to meet the expected number of characters.
            if self.syllable(run) is not None:
                complete_syllables[offset] = offset + len(run)
            for index in range(offset, offset + len(run)):
                ends[index] = offset + len(run)
            offset += len(run)
        # At most two paths per state suffice to detect ambiguity. Processing
        # offsets in order prevents a later path from escaping ambiguity checks.
        states: dict[int, dict[int, list[tuple[_Syllable, ...]]]] = {0: {0: [()]}}
        state_count = 1
        for start in range(len(source)):
            entries = states.pop(start, {})
            for used, paths in entries.items():
                if used >= count:
                    continue
                candidates = ([complete_syllables[start]] if start in complete_syllables
                              else range(start + 1, min(ends[start], start + self.max_syllable) + 1))
                for end in candidates:
                    syllable = self.syllable(source[start:end])
                    if syllable is None:
                        continue
                    remaining = len(source) - end
                    if remaining > (count - used - 1) * self.max_syllable or remaining < count - used - 1:
                        continue
                    bucket = states.setdefault(end, {})
                    if used + 1 not in bucket:
                        state_count += 1
                        if state_count > MAX_SEGMENTATION_STATES:
                            raise UnsupportedReading("Pinyin segmentation exceeded its bounded search limit")
                        bucket[used + 1] = []
                    target = bucket[used + 1]
                    for path in paths:
                        candidate = path + (syllable,)
                        if candidate not in target and len(target) < 2:
                            target.append(candidate)
        solutions = states.get(len(source), {}).get(count, [])
        if len(solutions) != 1:
            reason = "ambiguous" if solutions else "unsupported or misaligned"
            raise UnsupportedReading(f"The complete recorded pinyin is {reason}")
        return list(solutions[0]), count != len(runs)

    def align(self, text: str, pinyin: str) -> tuple[list[_Unit], bool]:
        text_spans: list[str] = [""]
        punctuation: list[str] = []
        for character in text:
            if _hanzi(character):
                text_spans[-1] += character
            elif character.isspace():
                continue
            elif character in PUNCTUATION:
                punctuation.append(PUNCTUATION[character])
                text_spans.append("")
            else:
                raise UnsupportedReading("Non-Han text requires a separate reliable pronunciation frontend")
        reading_spans: list[list[str]] = [[]]
        reading_punctuation: list[str] = []
        run = ""

        def flush() -> None:
            nonlocal run
            if run:
                reading_spans[-1].append(run)
                run = ""

        for index, character in enumerate(pinyin):
            if _letter(character):
                run += character
                continue
            between_letters = (index > 0 and index + 1 < len(pinyin)
                               and _letter(pinyin[index - 1]) and _letter(pinyin[index + 1]))
            flush()
            if character.isspace() or (character in "'’-" and between_letters):
                continue
            if character not in PUNCTUATION:
                raise UnsupportedReading("The recorded pinyin contains an unsupported character")
            reading_punctuation.append(PUNCTUATION[character])
            reading_spans.append([])
        flush()
        if reading_punctuation != punctuation:
            raise UnsupportedReading("Text and recorded pinyin punctuation do not align")
        units: list[_Unit] = []
        joined = False
        for index, (hanzi, runs) in enumerate(zip(text_spans, reading_spans, strict=True)):
            syllables, segmented = self.split_syllables(runs, len(hanzi))
            joined |= segmented
            units.extend(_Unit(character, syllable) for character, syllable in zip(hanzi, syllables, strict=True))
            if index < len(punctuation):
                units.append(_Unit(punctuation[index], None))
        if not any(unit.syllable for unit in units):
            raise UnsupportedReading("The reading has no Mandarin syllables")
        return units, joined

    def apply_sandhi(self, units: list[_Unit]) -> tuple[list[_Unit], list[str]]:
        text = "".join(unit.text for unit in units)
        warnings = ["Jieba word boundaries are inferred; sentence prosody is not linguistically verified."]

        def recorded_finals(word: str, *args, **kwargs) -> list[str]:
            if kwargs.get("style") != self.final_style:
                raise UnsupportedReading("The locked ToneSandhi requested an unsupported reading conversion")
            readings: set[tuple[str, ...]] = set()
            start = text.find(word)
            while start >= 0:
                readings.add(tuple(unit.syllable.numbered if unit.syllable else unit.text
                                   for unit in units[start:start + len(word)]))
                start = text.find(word, start + 1)
            if len(readings) != 1:
                raise UnsupportedReading("ToneSandhi cannot uniquely bind this repeated text to its recorded reading")
            return list(next(iter(readings)))

        with self.lock:
            original = self.sandhi_module.lazy_pinyin
            self.sandhi_module.lazy_pinyin = recorded_finals
            try:
                segments = [(item.word, item.flag) for item in self.posseg.lcut(text)]
                segments = self.sandhi.pre_merge_for_modify(segments)
                if "".join(word for word, _ in segments) != text:
                    raise UnsupportedReading("ToneSandhi word boundaries changed the aligned text")
                result: list[_Unit] = []
                position = 0
                for word, pos in segments:
                    span = units[position:position + len(word)]
                    position += len(word)
                    finals = [unit.syllable.numbered if unit.syllable else f"{unit.text}0" for unit in span]
                    changed = self.sandhi.modified_tone(word, pos, list(finals))
                    if len(changed) != len(span):
                        raise UnsupportedReading("ToneSandhi lost the recorded character alignment")
                    if len(word) > 4 and any(finals[index].endswith("3") and finals[index + 1].endswith("3")
                                             for index in range(len(finals) - 1)):
                        warnings.append("Long-word third-tone grouping is outside the upstream rule coverage.")
                    for unit, before, after in zip(span, finals, changed, strict=True):
                        if unit.syllable is None:
                            if after != before:
                                raise UnsupportedReading("ToneSandhi modified punctuation")
                            result.append(unit)
                            continue
                        if after[:-1] != before[:-1] or after[-1] not in "12345":
                            raise UnsupportedReading("ToneSandhi modified a recorded phoneme")
                        # Existing surface 2/4/neutral tones are not changed back
                        # to dictionary tones before the upstream rules run.
                        result.append(_Unit(unit.text, _Syllable(unit.syllable.base, int(after[-1]))))
                if any(before.syllable != after.syllable for before, after in zip(units, result, strict=True)):
                    warnings.append("Upstream 一/不/third-tone surface rules adjusted recorded citation tones.")
                return result, list(dict.fromkeys(warnings))
            finally:
                self.sandhi_module.lazy_pinyin = original

    def compile(self, text: str, pinyin: str, chunks: list[dict[str, str]] | None) -> dict:
        text = unicodedata.normalize("NFC", text)
        pinyin = unicodedata.normalize("NFC", pinyin).lower()
        if not text.strip() or len(text) > MAX_TEXT or not pinyin.strip() or len(pinyin) > MAX_PINYIN:
            raise UnsupportedReading("The reading is empty or exceeds the preview input limits")
        joined = False
        if chunks is not None:
            if not 1 <= len(chunks) <= 64 or any(not isinstance(item, dict)
                                                or not isinstance(item.get("hanzi"), str)
                                                or not isinstance(item.get("pinyin"), str) for item in chunks):
                raise UnsupportedReading("Pronunciation chunks require bounded hanzi/pinyin strings")
            if unicodedata.normalize("NFC", "".join(item["hanzi"] for item in chunks)) != text:
                raise UnsupportedReading("Pronunciation chunks do not reconstruct the complete text")

            def compact(value: str) -> str:
                return "".join(character for character in unicodedata.normalize("NFC", value).lower()
                               if not character.isspace() and character not in "'’")

            if compact(" ".join(item["pinyin"] for item in chunks)) != compact(pinyin):
                raise UnsupportedReading("Pronunciation chunks do not reconstruct the recorded pinyin")
            units = []
            for item in chunks:
                aligned, segmented = self.align(unicodedata.normalize("NFC", item["hanzi"]),
                                                unicodedata.normalize("NFC", item["pinyin"]).lower())
                units.extend(aligned)
                joined |= segmented
        else:
            units, joined = self.align(text, pinyin)
        units, warnings = self.apply_sandhi(units)
        if joined:
            warnings.append("Joined pinyin was uniquely segmented using the recorded character count.")
        phone_ids: list[int] = []
        tones: list[int] = []
        spoken: list[str] = []
        for unit in units:
            phones = self.phones[unit.syllable.base] if unit.syllable else (unit.text,)
            phone_ids.extend(self.tokens[phone] for phone in phones)
            tones.extend([unit.syllable.tone if unit.syllable else 0] * len(phones))
            spoken.append(unit.syllable.numbered if unit.syllable else unit.text)
        return {"phoneIds": phone_ids, "tones": tones, "spokenSyllables": spoken, "warnings": warnings}


@lru_cache(maxsize=1)
def _frontend() -> _Frontend:
    return _Frontend()


def compile_reading(text: str, pinyin: str, chunks: list[dict[str, str]] | None = None) -> dict:
    """Return complete phoneIds/tones/spokenSyllables/warnings or reject the reading.

    Chunks use the existing CSV example shape (hanzi, pinyin, optional gloss).
    Unknown nasal syllables, unsupported erhua, ambiguous segmentation, mixed
    non-Han text, and punctuation/alignment failures reject the WHOLE request.
    """
    return _frontend().compile(text, pinyin, chunks)
