"""Append the reviewed supplied vocabulary without rewriting a single legacy byte.

The supplied file establishes membership only. The three editorial TSVs establish
teaching senses, readings, five-stage rarity and two original bilingual contexts.
Existing pinned dictionaries provide lexical chunk glosses; no model is downloaded.
--check reproduces the append and metadata without writing any outputs.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import io
import json
import os
import re
import sys
import tempfile
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "tools/vocabulary_additions_20261103"
CSV = ROOT / "wordlist.csv"
BASE_BYTES = 32_356_588
BASE_SHA256 = "6de1a9877f79ea7a809fd40d41383c15a86ce93580cb2954cf1344515d15639a"
INPUT_SHA256 = "5713ad31338c953e9cca645290f61111f2827097124aaba6417546aec7e58861"
HAN = re.compile(r"[\u3400-\u9fff]+")
PY_LETTERS = re.compile(r"[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹňḿ]+", re.I)
PUNCT = {"。": ".", "，": ",", "？": "?", "！": "!", "；": ";", "：": ":",
         "、": ",", "“": '"', "”": '"', "‘": "'", "’": "'", "（": "(", "）": ")",
         "《": '"', "》": '"', "…": "…", "—": "—"}


def sha(value):
    return hashlib.sha256(value).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def normalize_py(value):
    return " ".join(unicodedata.normalize("NFC", value).lower().split())


def read_inputs(base_rows):
    source = (DATA / "words.txt").read_bytes()
    if sha(source) != INPUT_SHA256:
        raise ValueError("The supplied membership file changed; preserve its exact bytes")
    inputs, page = [], None
    for line, raw in enumerate(source.decode("utf-8-sig").splitlines(), 1):
        value = unicodedata.normalize("NFC", raw.strip())
        if not value:
            continue
        header = re.fullmatch(r"# 第(\d+)页", value)
        if header:
            page = int(header[1])
            continue
        if not HAN.fullmatch(value):
            raise ValueError(f"Unexpected membership data at line {line}")
        inputs.append(dict(word=value, page=page, line=line))
    present = {r["组词"] for r in base_rows}
    required = list(dict.fromkeys(r["word"] for r in inputs if r["word"] not in present))
    editorial = {}
    for batch in range(1, 4):
        path = DATA / f"batch-{batch}.tsv"
        for fields in csv.reader(path.open(encoding="utf-8", newline=""), delimiter="\t"):
            if len(fields) != 9 or any(not v.strip() for v in fields):
                raise ValueError(f"Expected nine nonempty editorial fields in {path.name}")
            word, reading, gloss, pos, rarity, zh1, en1, zh2, en2 = fields
            if word in editorial or word not in required:
                raise ValueError("Unexpected or duplicate editorial headword: " + word)
            reading = normalize_py(reading)
            if (len(reading.split()) != len(word) or
                    any(not PY_LETTERS.fullmatch(s) for s in reading.split())):
                raise ValueError("Unaligned editorial headword pinyin: " + word)
            if rarity not in {"0", "1", "2", "3", "4"} or pos in {"word", "unknown"}:
                raise ValueError("Missing reviewed rarity or POS: " + word)
            if zh1 == zh2 or any(word not in text for text in (zh1, zh2)):
                raise ValueError("Two distinct target-containing contexts required: " + word)
            editorial[word] = dict(reading=reading, gloss=gloss, pos=pos, rarity=rarity,
                                   examples=[(zh1, en1), (zh2, en2)])
    if set(editorial) != set(required):
        raise ValueError("Incomplete editorial coverage: " + str(set(required) - set(editorial)))
    if len(inputs) != 2649 or len(required) != 1390:
        raise ValueError("Membership differs from the reviewed source")
    from build_word_tags import catalog
    known_tags = {tag["id"] for tag in catalog()["tags"]}
    tag_review = {}
    for batch in range(1, 4):
        reviewed = json.loads((DATA / f"batch-{batch}.tags.json").read_text(encoding="utf-8"))
        for word, tags in reviewed.items():
            if (word in tag_review or word not in editorial or not isinstance(tags, list)
                    or not tags or len(tags) != len(set(tags)) or not set(tags) <= known_tags
                    or not any(t.startswith("topic_") and t != "topic_others" for t in tags)
                    or any(not t.startswith(("topic_", "usage_")) or t == "topic_others" for t in tags)):
                raise ValueError("Invalid or duplicate semantic tag review: " + word)
            tag_review[word] = tags
    if set(tag_review) != set(required):
        raise ValueError("Every addition needs a reviewed semantic topic")
    for word in required:
        editorial[word]["tags"] = tag_review[word]
    pronunciation = json.loads((DATA / "sentence-readings.json").read_text(encoding="utf-8"))
    if not set(pronunciation) <= {text for item in editorial.values() for text, _ in item["examples"]}:
        raise ValueError("A sentence pronunciation review no longer matches its example")
    return source, inputs, required, editorial


def make_chunks(base_rows, editorial):
    lock = json.loads((ROOT / "tools/vocabulary_v2/source-lock.json").read_text(encoding="utf-8"))
    cedict = next(source for source in lock["sources"] if source["id"] == "cc-cedict-2026-10-26")
    dictionary = (ROOT / ".gradle/vocabulary-v2" / cedict["file"]).read_bytes()
    if len(dictionary) != cedict["bytes"] or sha(dictionary) != cedict["sha256"]:
        raise ValueError("Pinned CC-CEDICT source changed")
    from refine_word_breakdown import make_lexicon, GRAMMAR, acceptable, normalized_py
    lexicon = make_lexicon(base_rows)
    observed = collections.defaultdict(collections.Counter)
    for row in base_rows:
        units = json.loads(row["部件JSON"])
        units += [c for ex in json.loads(row["例句JSON"]) for c in ex["chunks"]]
        for part in units:
            word = part["hanzi"].strip("。，！？；：、,.!?;: \"“”‘’（）()《》")
            reading = normalized_py(part["pinyin"].strip(".,!?;: \"'()[]…—"))
            if HAN.fullmatch(word) and acceptable(part["gloss"]):
                observed[(word, reading)][part["gloss"]] += 1
    for key, counts in observed.items():
        if key not in lexicon:
            lexicon[key] = [counts.most_common(1)[0][0]]
    for word, item in editorial.items():
        lexicon[(word, item["reading"])] = [item["gloss"]]
    grammar = dict(GRAMMAR)
    grammar.update({("了", "le"): "LE (completed action or change of state)",
                    ("着", "zhe"): "ZHE (ongoing state)",
                    ("的", "de"): "DE (modifier or possessive marker)",
                    ("地", "de"): "DE (adverbial marker)",
                    ("得", "de"): "DE (complement marker)",
                    ("儿", "er"): "noun suffix (er)",
                    ("小明", "xiǎo míng"): "Xiaoming (a person's name)",
                    ("小红", "xiǎo hóng"): "Xiaohong (a person's name)"})
    lexicon.update({key: [value] for key, value in grammar.items()})
    chunk_review = json.loads((DATA / "chunk-review.json").read_text(encoding="utf-8"))
    if chunk_review.get("schemaVersion") != 1:
        raise ValueError("Unsupported contextual chunk review")
    excluded_units = set(chunk_review["excludedUnits"])
    if any(not HAN.fullmatch(unit) or unit in editorial for unit in excluded_units):
        raise ValueError("Invalid excluded sentence chunk")
    for fix in chunk_review["lexicalSenses"]:
        unit, reading, gloss = fix["hanzi"], fix["pinyin"], fix["gloss"]
        if (not HAN.fullmatch(unit) or len(unit) != len(reading.split())
                or not acceptable(gloss) or unit in editorial):
            raise ValueError("Invalid contextual sentence sense: " + unit)
        lexicon[(unit, reading)] = [gloss]
    by_word = collections.defaultdict(list)
    for unit, reading in lexicon:
        by_word[unit].append(reading)

    def plain(syllable):
        return "".join(c for c in unicodedata.normalize("NFD", syllable) if not unicodedata.combining(c))

    def lexical_reading(unit, reading):
        if unit in excluded_units:
            return None
        if (unit, reading) in lexicon:
            return reading
        # Context dictionaries sometimes give a neutral final a full tone inside
        # a larger compound (葡萄架). Only neutralization variants may substitute;
        # never guess a different consonant, vowel or lexical full-tone reading.
        actual = reading.split()
        if len(unit) > 1:
            for candidate in by_word.get(unit, []):
                expected = candidate.split()
                if len(expected) == len(actual) and all(a == b or
                        plain(a) == plain(b) and (a == plain(a) or b == plain(b))
                        for a, b in zip(actual, expected)):
                    return candidate
        return None
    sys.path.insert(0, str(ROOT / ".gradle/chinese-resource-source/python"))
    from pypinyin import Style, lazy_pinyin, load_phrases_dict
    load_phrases_dict({word: [[syllable] for syllable in item["reading"].split()]
                       for word, item in editorial.items()})
    pronunciation = json.loads((DATA / "sentence-readings.json").read_text(encoding="utf-8"))

    def chunks(text, target):
        if any(not HAN.fullmatch(c) and c not in PUNCT and c not in ".,!?;: ()\"'" for c in text):
            raise ValueError("Unsupported example character: " + target + " / " + text)
        # Read a complete sentence for context, then lock only the reviewed target.
        py = lazy_pinyin(text, style=Style.TONE, neutral_tone_with_five=False,
                         errors=lambda value: list(value))
        if len(py) != len(text):
            raise ValueError("Sentence pinyin does not align: " + text)
        for match in re.finditer(re.escape(target), text):
            py[match.start():match.end()] = editorial[target]["reading"].split()
        seen_offsets = set()
        contextual_glosses = {}
        for fix in pronunciation.get(text, []):
            offset, char, reading = fix["offset"], fix["hanzi"], fix["pinyin"]
            if (type(offset) is not int or not 0 <= offset < len(text) or text[offset] != char
                    or len(char) != 1 or not HAN.fullmatch(char) or not PY_LETTERS.fullmatch(reading)
                    or offset in seen_offsets):
                raise ValueError("Invalid contextual pronunciation review: " + text)
            seen_offsets.add(offset)
            py[offset] = reading
            if "gloss" in fix:
                contextual_glosses[offset] = fix["gloss"]
        result, offset = [], 0
        while offset < len(text):
            if not HAN.fullmatch(text[offset]):
                if not result:
                    raise ValueError("Example must start with a lexical unit: " + text)
                result[-1]["hanzi"] += text[offset]
                result[-1]["pinyin"] += PUNCT.get(text[offset], text[offset])
                offset += 1
                continue
            following = text.find(target, offset + 1)
            maximum = min(12, len(text) - offset, following - offset if following >= 0 else 12)
            sizes = [len(target)] if text.startswith(target, offset) else range(maximum, 0, -1)
            chosen = None
            for size in sizes:
                unit = text[offset:offset + size]
                reading = normalize_py(" ".join(py[offset:offset + size]))
                matched = lexical_reading(unit, reading) if HAN.fullmatch(unit) else None
                if matched is not None:
                    gloss = contextual_glosses.get(offset, lexicon[(unit, matched)][0]) if size == 1 else lexicon[(unit, matched)][0]
                    chosen = dict(hanzi=unit, pinyin=matched, gloss=gloss[:300])
                    break
            if chosen is None:
                raise ValueError(f"No reading-specific lexical gloss: {target} / {text[offset:offset+8]} / {py[offset]}")
            result.append(chosen)
            offset += len(chosen["hanzi"])
        return result
    return chunks


def reviewed_parts(editorial):
    review = json.loads((DATA / "breakdown-review.json").read_text(encoding="utf-8"))
    if review.get("schemaVersion") != 1 or set(review["words"]) != set(editorial):
        raise ValueError("Incomplete explicit word-breakdown review")
    result = {}
    for word, item in editorial.items():
        record = review["words"][word]
        if (record["word"] != word or record["pinyin"] != item["reading"]
                or record["glossSha256"] != sha(item["gloss"].encode("utf-8"))):
            raise ValueError("Word-breakdown sense changed; review required: " + word)
        parts = record["parts"]
        if (not 1 <= len(parts) <= 32 or any(set(p) != {"hanzi", "pinyin", "gloss"} for p in parts)
                or "".join(p["hanzi"] for p in parts) != word
                or normalize_py(" ".join(p["pinyin"] for p in parts)) != item["reading"]
                or record["reason"] not in {"reviewed_semantic_units", "opaque_lexical_unit"}
                or (len(parts) == 1) != (record["reason"] == "opaque_lexical_unit")):
            raise ValueError("Invalid reviewed semantic units: " + word)
        for part in parts:
            if (not HAN.fullmatch(part["hanzi"]) or len(part["pinyin"].split()) != len(part["hanzi"])
                    or not part["gloss"].strip() or len(part["gloss"]) > 300
                    or not re.search(r"[A-Za-z]", part["gloss"]) or HAN.search(part["gloss"])
                    or any(not PY_LETTERS.fullmatch(s) for s in part["pinyin"].split())):
                raise ValueError("Invalid reviewed word-part field: " + word)
        result[word] = parts
    return result


def build():
    current = CSV.read_bytes()
    from refine_vocabulary_20261103 import previous_payload
    current = previous_payload(current)
    base = current[:BASE_BYTES]
    if sha(base) != BASE_SHA256 or not base.endswith(b"\n"):
        raise ValueError("Legacy CSV bytes changed; refusing to rewrite or renumber old content")
    base_rows = list(csv.DictReader(io.StringIO(base.decode("utf-8-sig"))))
    if len(base_rows) != 18500:
        raise ValueError("Unexpected baseline vocabulary count")
    source, inputs, required, editorial = read_inputs(base_rows)
    chunker = make_chunks(base_rows, editorial)
    part_review = reviewed_parts(editorial)
    rows, provenance, tag_review = [], {}, {}
    for index, word in enumerate(required, 18501):
        item = editorial[word]
        word_id = f"wl_{index:05d}"
        examples = []
        for hanzi, english in item["examples"]:
            chunks = chunker(hanzi, word)
            examples.append(dict(hanzi=hanzi, pinyin=" ".join(c["pinyin"] for c in chunks),
                                 english=english, chunks=chunks))
        # Use explicit lexical constituents; opaque expressions remain whole.
        parts = part_review[word]
        row = dict(zip(base_rows[0], [""] * len(base_rows[0])))
        row.update({"罕度": item["rarity"], "组词": word, "拼音": item["reading"], "词条ID": word_id,
                    "英文释义": item["gloss"], "词性": item["pos"], "例句JSON": compact(examples),
                    "部件JSON": compact(parts), "来源说明": "Supplied vocabulary 2026-11-03; CC-CEDICT/project editorial. Sources: wordlist-sources/source-index.json.",
                    "本义解释JSON": compact([item["gloss"]]), "引申义解释JSON": "[]"})
        provenance[word_id] = [dict(hanzi=ex["hanzi"], source="Project-authored AI-assisted editorial example, 2026-11-03")
                               for ex in examples]
        tag_review[word_id] = dict(word=word, pinyin=item["reading"], glossSha256=sha(item["gloss"].encode("utf-8")),
                                   tags=sorted(item["tags"]),
                                   reason="按所选教学义逐词审校主题与语体；AI辅助编辑，未作真人认证。")
        rows.append(row)
    pools = collections.defaultdict(list)
    for row in [*base_rows, *rows]:
        pools[row["罕度"]].append(row)
    for row in rows:
        pool = pools[row["罕度"]]
        offset = int(sha(row["词条ID"].encode())[:8], 16) % len(pool)
        chosen, glosses = [], {row["英文释义"].strip().lower()}
        for step in range(len(pool)):
            other = pool[(offset + step) % len(pool)]
            gloss = other["英文释义"].strip().lower()
            if other["词条ID"] == row["词条ID"] or gloss in glosses:
                continue
            chosen.append(other["词条ID"])
            glosses.add(gloss)
            if len(chosen) == 3:
                break
        if len(chosen) != 3:
            raise ValueError("Insufficient distinct answer choices: " + row["组词"])
        row["干扰词ID"] = compact(chosen)
    text = io.StringIO(newline="")
    writer = csv.DictWriter(text, fieldnames=list(base_rows[0]), lineterminator="\n")
    writer.writerows(rows)
    payload = base + text.getvalue().encode("utf-8")
    if len(current) != BASE_BYTES and current != payload:
        previous_path = DATA / "report.json"
        previous = json.loads(previous_path.read_text(encoding="utf-8")) if previous_path.exists() else {}
        if (previous.get("baselineSha256") != BASE_SHA256 or previous.get("sha256") != sha(current)
                or previous.get("bytes") != len(current)):
            raise ValueError("Existing appended CSV differs; refusing to discard unrelated edits")
    from rebuild_wordlist import validate
    validate(payload)  # Enforce the runtime-aligned teaching contract before writing.
    all_rows = [*base_rows, *rows]
    fingerprint = dict(schemaVersion=2, words=len(all_rows), columns=list(base_rows[0]),
                       rarity=dict(sorted(collections.Counter(r["罕度"] for r in all_rows).items())),
                       examples=2 * len(all_rows), bytes=len(payload), sha256=sha(payload))
    mapping = collections.defaultdict(list)
    for row in all_rows:
        mapping[row["组词"]].append(row["词条ID"])
    counts = collections.Counter(r["word"] for r in inputs)
    report = dict(schemaVersion=1, date="2026-11-03", baselineBytes=BASE_BYTES, baselineSha256=BASE_SHA256,
                  baselineWords=18500, suppliedLines=len(inputs), uniqueSuppliedWords=len(counts),
                  duplicateInputWords={w: n for w, n in counts.items() if n > 1},
                  existingSuppliedWords=len(counts) - len(rows), addedWords=len(rows), totalWords=len(all_rows),
                  sha256=fingerprint["sha256"], bytes=len(payload),
                  addedRarity=dict(sorted(collections.Counter(r["罕度"] for r in rows).items())),
                  addedIdRange=[rows[0]["词条ID"], rows[-1]["词条ID"]],
                  legacyBytesPreserved=True, inputSha256=sha(source),
                  wordBreakdown=dict(reviewedWords=len(rows),
                      splitWords=sum(len(part_review[word]) > 1 for word in required),
                      retainedWhole=[word for word in required if len(part_review[word]) == 1]),
                  inputCoverage=[dict(**record, ids=mapping[record["word"]], added=record["word"] in editorial)
                                 for record in inputs],
                  reviewBoundary="AI-assisted editorial senses, examples and rarity; reading-specific dictionary chunks. Not human proofreading, speech listening or copyright clearance.")
    lock = dict(schemaVersion=1, sources=[dict(id="supplied-vocabulary-20261103", file="tools/vocabulary_additions_20261103/words.txt",
                    bytes=len(source), sha256=sha(source), role="Supplied headword membership only; definitions and examples are separately edited.",
                    license="User-supplied word list; no additional redistribution clearance asserted.")],
                editorialFiles={name: sha((DATA / name).read_bytes())
                                for i in range(1, 4) for name in (f"batch-{i}.tsv", f"batch-{i}.tags.json")})
    lock["editorialFiles"]["editorial-evidence.json"] = sha((DATA / "editorial-evidence.json").read_bytes())
    lock["editorialFiles"]["sentence-readings.json"] = sha((DATA / "sentence-readings.json").read_bytes())
    lock["editorialFiles"]["breakdown-review.json"] = sha((DATA / "breakdown-review.json").read_bytes())
    lock["editorialFiles"]["chunk-review.json"] = sha((DATA / "chunk-review.json").read_bytes())
    return {CSV: payload, ROOT / "docs/wordlist-validation.json": encoded(fingerprint),
            DATA / "report.json": encoded(report), DATA / "example-provenance.json": encoded(provenance),
            DATA / "tag-review.json": encoded(tag_review),
            DATA / "source-lock.json": encoded(lock)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args()
    original_csv = CSV.read_bytes()
    from refine_vocabulary_20261103 import previous_payload, verify_review
    previous = previous_payload(original_csv)
    refined = previous != original_csv
    if refined and args.write:
        raise ValueError("Use refine_vocabulary_20261103.py --write for the current reviewed vocabulary")
    if refined:
        verify_review()
    outputs = build()
    for path, content in outputs.items():
        if args.check:
            if refined and path == CSV:
                if content != previous:
                    raise ValueError("Historical addition recipe no longer restores the frozen input")
                continue
            if refined and path == ROOT / "docs/wordlist-validation.json":
                continue  # Current manifest is verified by the current refinement check.
            if not path.exists() or path.read_bytes() != content:
                raise ValueError("Addition output differs: " + str(path.relative_to(ROOT)))
        else:
            if path == CSV:
                # A failed write must not truncate the only frozen CSV. Replace
                # a completely written sibling, and reject a concurrent edit.
                temporary = None
                try:
                    with tempfile.NamedTemporaryFile(dir=CSV.parent, prefix=".wordlist-", suffix=".tmp", delete=False) as file:
                        temporary = Path(file.name)
                        file.write(content)
                        file.flush()
                        os.fsync(file.fileno())
                    if CSV.read_bytes() != original_csv:
                        raise ValueError("CSV changed during generation; refusing to overwrite a concurrent edit")
                    os.replace(temporary, CSV)
                finally:
                    if temporary is not None and temporary.exists():
                        temporary.unlink()
            else:
                path.write_bytes(content)
    print(json.dumps(dict(status="PASS", check=args.check, addedWords=1390, historicalTotalWords=19890,
                          currentSemanticRefinement=refined,
                          sha256=sha(outputs[CSV]), legacyBytesPreserved=True)))


if __name__ == "__main__":
    main()
