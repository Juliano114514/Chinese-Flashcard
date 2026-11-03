"""Apply/check the field-level vocabulary review without rewriting frozen inputs.

The inverse ledger restores the exact 1.10.0 input, so historical generators
remain verifiable. This tool never downloads sources or changes application code.
"""
from __future__ import annotations

import argparse
import collections
import copy
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tempfile
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "tools/vocabulary_refinement_20261103"
CSV = ROOT / "wordlist.csv"
BASE_SHA = "58d2f8090a50296c6e253f03e11a49e2b1b330a70c6cf8eccbacd287604ccf87"
BASE_COUNT = 19890
JSON_FIELDS = {"例句JSON", "部件JSON", "本义解释JSON", "引申义解释JSON", "干扰词ID"}
EDITABLE = {"罕度", "拼音", "英文释义", "词性", *JSON_FIELDS}
OVERLAYS = ("legacy_overrides.json", "new_words_overrides.json", "context_overrides.json",
            "final_qa_overrides.json", "post_review_overrides.json")
INPUTS = (*OVERLAYS, "supplement.tsv", "supplement.parts.json", "supplement.tags.json",
          "supplement.examples.json", "supplement.anchors.json", "tag_overrides.json", "tag_idiom_overrides.json")


def sha(content):
    return hashlib.sha256(content).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def rows_of(content):
    return list(csv.DictReader(io.StringIO(content.decode("utf-8-sig"), newline="")))


def csv_bytes(rows):
    text = io.StringIO(newline="")
    writer = csv.DictWriter(text, fieldnames=list(rows[0]), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return b"\xef\xbb\xbf" + text.getvalue().encode("utf-8")


def previous_payload(content):
    """Restore and verify the exact historical CSV, without changing disk state."""
    if sha(content) == BASE_SHA:
        return content
    ledger_path = DATA / "inverse-ledger.json"
    if not ledger_path.exists():
        raise ValueError("Unrecognized vocabulary input; missing inverse ledger")
    ledger = read(ledger_path)
    if sha(content) != ledger["outputSha256"] or ledger["inputSha256"] != BASE_SHA:
        raise ValueError("Vocabulary changed outside the accepted refinement")
    rows = rows_of(content)
    if len(rows) != 20000 or [r["词条ID"] for r in rows] != [f"wl_{i:05d}" for i in range(1, 20001)]:
        raise ValueError("Stable vocabulary IDs changed")
    for row in rows[:BASE_COUNT]:
        row.update(ledger["beforeFields"].get(row["词条ID"], {}))
    previous = csv_bytes(rows[:BASE_COUNT])
    if sha(previous) != BASE_SHA:
        raise ValueError("Inverse ledger does not restore the frozen 1.10.0 CSV")
    return previous


def load_overrides():
    result, reasons = {}, {}
    for name in OVERLAYS:
        value = read(DATA / name)
        for word_id, fields in value["overrides"].items():
            if set(fields) - EDITABLE:
                raise ValueError("Unexpected editorial field: " + word_id)
            target = result.setdefault(word_id, {})
            for key, item in fields.items():
                serialized = compact(item) if key in JSON_FIELDS else str(item)
                if key in target and target[key] != serialized and name not in {"context_overrides.json", "final_qa_overrides.json", "post_review_overrides.json"}:
                    raise ValueError("Conflicting field reviews: " + word_id + " / " + key)
                target[key] = serialized
            note = value.get("reasons", {}).get(word_id, [])
            reasons.setdefault(word_id, []).extend(note if isinstance(note, list) else [note])
    return result, reasons


def supplement(rows):
    parts = read(DATA / "supplement.parts.json")
    tags = read(DATA / "supplement.tags.json")
    explicit = read(DATA / "supplement.examples.json")
    anchors = read(DATA / "supplement.anchors.json")
    retained = {row["词条ID"]: row for row in rows}
    editorial = {}
    for fields in csv.reader((DATA / "supplement.tsv").open(encoding="utf-8"), delimiter="\t"):
        if len(fields) != 9:
            raise ValueError("A supplemental row must contain nine fields")
        word, reading, gloss, pos, rarity, zh1, en1, zh2, en2 = fields
        if word in editorial or word in {r["组词"] for r in rows} or rarity not in {"0", "1", "2"}:
            raise ValueError("Duplicate or out-of-scope supplemental word: " + word)
        editorial[word] = dict(reading=reading, gloss=gloss, pos=pos, rarity=rarity,
                               examples=[(zh1, en1), (zh2, en2)], tags=tags[word])
    if (len(editorial) != 110 or set(parts) != set(editorial) or set(tags) != set(editorial)
            or set(explicit) != set(editorial) or set(anchors) != set(editorial)):
        raise ValueError("Exactly 110 fully annotated supplemental words required")
    for word, anchor in anchors.items():
        previous = retained.get(anchor["id"])
        if (previous is None or previous["组词"] != anchor["word"]
                or previous["罕度"] != anchor["rarity"] or previous["罕度"] not in {"0", "1", "2"}):
            raise ValueError("Supplement must extend a retained rarity 0-2 anchor: " + word)
    for number, (word, item) in enumerate(editorial.items(), BASE_COUNT + 1):
        examples = explicit[word]
        if [(e["hanzi"], e["english"]) for e in examples] != item["examples"]:
            raise ValueError("Supplemental sentence review does not match its authored source: " + word)
        row = dict.fromkeys(rows[0], "")
        row.update({"罕度": item["rarity"], "组词": word, "拼音": item["reading"], "词条ID": f"wl_{number:05d}",
                    "英文释义": item["gloss"], "词性": item["pos"], "例句JSON": compact(examples),
                    "部件JSON": compact(parts[word]), "本义解释JSON": compact([item["gloss"]]), "引申义解释JSON": "[]",
                    "来源说明": "Project-authored semantic supplement 2026-11-03; CC-CEDICT/editorial. Sources: wordlist-sources/source-index.json."})
        rows.append(row)
    return editorial


def repair_choices(rows):
    index = {r["词条ID"]: r for r in rows}
    pools = collections.defaultdict(list)
    for row in rows:
        pools[row["罕度"]].append(row)
    for row in rows:
        selected, used = [], {unicodedata.normalize("NFC", row["英文释义"].strip()).lower()}
        for word_id in json.loads(row["干扰词ID"] or "[]"):
            other = index.get(word_id)
            if other is None or word_id == row["词条ID"] or word_id in selected:
                continue
            gloss = unicodedata.normalize("NFC", other["英文释义"].strip()).lower()
            if gloss not in used:
                selected.append(word_id)
                used.add(gloss)
        if len(selected) < 3:
            pool = pools[row["罕度"]]
            offset = int(sha(row["词条ID"].encode())[:8], 16) % len(pool)
            for step in range(len(pool)):
                other = pool[(offset + step) % len(pool)]
                word_id = other["词条ID"]
                gloss = unicodedata.normalize("NFC", other["英文释义"].strip()).lower()
                if word_id != row["词条ID"] and word_id not in selected and gloss not in used:
                    selected.append(word_id)
                    used.add(gloss)
                if len(selected) == 3:
                    break
        if len(selected) != 3:
            raise ValueError("Insufficient distinct answer choices")
        row["干扰词ID"] = compact(selected)


def field_review(rows, changes):
    """Audit every field; explicitly distinguish screening from editorial edits."""
    han = re.compile(r"[\u3400-\u9fff]")
    py = re.compile(r"[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹňḿ]+", re.I)
    def syllables(value):
        return [v.lower() for v in py.findall(unicodedata.normalize("NFC", value))]
    def segmental(value):
        return "".join(c for c in unicodedata.normalize("NFD", value.lower())
                       if c not in "\u0300\u0301\u0304\u030c")
    text = io.StringIO(newline="")
    writer = csv.writer(text, lineterminator="\n")
    writer.writerow(["词条ID", "组词", *[field + "检查" for field in rows[0]], "本轮修改字段", "语义检查边界"])
    for row in rows:
        word_id = row["词条ID"]
        parts = json.loads(row["部件JSON"])
        if syllables(" ".join(p["pinyin"] for p in parts)) != syllables(row["拼音"]):
            raise ValueError("Reviewed word-part reading mismatch: " + word_id)
        for part in parts:
            if len(han.findall(part["hanzi"])) != len(syllables(part["pinyin"])):
                raise ValueError("Reviewed word-part syllable count mismatch: " + word_id)
        for example in json.loads(row["例句JSON"]):
            positions = []
            for chunk in example["chunks"]:
                if len(han.findall(chunk["hanzi"])) != len(syllables(chunk["pinyin"])):
                    raise ValueError("Reviewed sentence-chunk syllable count mismatch: " + word_id)
                readings = iter(syllables(chunk["pinyin"]))
                positions.extend(next(readings) if han.fullmatch(char) else None for char in chunk["hanzi"])
            matches = re.finditer(re.escape(row["组词"]), example["hanzi"])
            if not any(segmental(" ".join(positions[m.start():m.end()])) == segmental(row["拼音"]) for m in matches):
                raise ValueError("Example has no occurrence with the target's segmental reading: " + word_id)
        if int(word_id[3:]) > BASE_COUNT and row["罕度"] not in {"0", "1", "2"}:
            raise ValueError("Supplement escaped the requested rarity stages")
        changed = changes.get(word_id, {}).get("fields", [])
        scope = "AI词条与词法单位审阅；结构及对齐全检；非逐句真人校订" if int(word_id[3:]) > 18500 else "全字段契约及词典辅助风险筛查；确定问题定向修订；非逐句真人校订"
        writer.writerow([word_id, row["组词"], *["PASS:契约/引用/对齐；已修订" if field in changed else "PASS:契约/引用/对齐" for field in row], " | ".join(changed), scope])
    return text.getvalue().encode("utf-8-sig")


def outputs(content):
    fingerprints = {name: sha((DATA / name).read_bytes()) for name in INPUTS}
    original = previous_payload(content)
    before = rows_of(original)
    before_index = {row["词条ID"]: row for row in before}
    rows = copy.deepcopy(before)
    overrides, reasons = load_overrides()
    index = {r["词条ID"]: r for r in rows}
    if set(overrides) - set(index):
        raise ValueError("Editorial overlay refers to absent IDs")
    for word_id, fields in overrides.items():
        index[word_id].update(fields)
    editorial = supplement(rows)
    repair_choices(rows)
    payload = csv_bytes(rows)
    from rebuild_wordlist import validate
    validate(payload)
    if len(rows) != 20000 or len({unicodedata.normalize("NFC", r["组词"]) for r in rows}) != 20000:
        raise ValueError("Refinement must leave exactly 20000 distinct headwords")
    before_fields, changes = {}, {}
    for previous, row in zip(before, rows):
        if previous["词条ID"] != row["词条ID"] or previous["组词"] != row["组词"]:
            raise ValueError("Retained headwords and stable IDs cannot change")
        keys = [key for key in row if previous[key] != row[key]]
        if keys:
            before_fields[row["词条ID"]] = {key: previous[key] for key in keys}
            changes[row["词条ID"]] = dict(word=row["组词"], fields=keys, reasons=reasons.get(row["词条ID"], ["Repair distinct answer choices after lexical edits"]))
    digest = sha(payload)
    snapshot = dict(schemaVersion=2, words=len(rows), columns=list(rows[0]),
                    rarity=dict(sorted(collections.Counter(r["罕度"] for r in rows).items())),
                    examples=sum(len(json.loads(r["例句JSON"])) for r in rows), bytes=len(payload), sha256=digest)
    tag_changes = {**read(DATA / "tag_overrides.json")["overrides"],
                   **read(DATA / "tag_idiom_overrides.json")["overrides"]}
    from build_word_tags import annotate, lexical_forms
    tag_review = {}
    for row in rows:
        word_id, word = row["词条ID"], row["组词"]
        if word_id in tag_changes:
            value = tag_changes[word_id]
            chosen = value["tags"] if isinstance(value, dict) else value
            reason = value.get("reason", "Reviewed contextual topic") if isinstance(value, dict) else "Reviewed contextual topic"
        elif word in editorial:
            chosen, reason = editorial[word]["tags"], "Semantic-family supplement; rarity restricted to stages 0-2"
        elif word_id in changes:
            # Classify the original sense once, then bind retained topic/usage
            # decisions to the corrected sense; explicit topic edits win above.
            chosen = annotate(before_index[word_id], apply_refinement=False)[0]
            reason = "Retained topic/usage reviewed with corrected fields; lexical forms follow current POS"
        else:
            continue
        chosen = [tag for tag in chosen if not tag.startswith("form_")] + lexical_forms(row["词性"])
        if "idiom" in re.split(r"[;/]", row["词性"]):
            chosen.append("form_idiom")
        tag_review[word_id] = dict(word=word, pinyin=row["拼音"], glossSha256=sha(row["英文释义"].encode()), tags=sorted(set(chosen)), reason=reason)
    ledger = dict(schemaVersion=1, inputSha256=BASE_SHA, outputSha256=digest, beforeFields=before_fields)
    lock = dict(schemaVersion=1, inputSha256=BASE_SHA, outputSha256=digest,
                editorialFiles={name: sha((DATA / name).read_bytes()) for name in INPUTS})
    for name in ("supplement_metadata.txt", "tag_editorial_decisions.json", "tag_missing_middle.json",
                 "tag_missing_tail.json", "substring_overrides.json", "pronunciation_overrides.json", "classifier_overrides.json", "extra_new_overrides.json"):
        path = DATA / name
        if path.exists():
            lock["editorialFiles"][name] = sha(path.read_bytes())
    report = dict(schemaVersion=1, date="2026-11-03", inputSha256=BASE_SHA, sha256=digest, bytes=len(payload),
                  inputWords=BASE_COUNT, totalWords=len(rows), supplementalWords=110, addedIdRange=["wl_19891", "wl_20000"],
                  fieldsPerWord=len(rows[0]), fieldRecordsChecked=len(rows) * len(rows[0]),
                  exampleChunksChecked=sum(len(e["chunks"]) for r in rows for e in json.loads(r["例句JSON"])),
                  wordPartsChecked=sum(len(json.loads(r["部件JSON"])) for r in rows),
                  supplementalRarity=dict(sorted(collections.Counter(r["罕度"] for r in rows[BASE_COUNT:]).items())),
                  changedExistingWords=len(changes), changedLegacyWords=sum(int(w[3:]) <= 18500 for w in changes),
                  changed110Words=sum(int(w[3:]) > 18500 for w in changes),
                  changedFields=dict(collections.Counter(key for item in changes.values() for key in item["fields"])),
                  stableIdsAndHeadwordsPreserved=BASE_COUNT, frozenInputByteRestoration=True, changes=changes,
                  reviewBoundary="Full CSV contract/field/alignment/tag checks, dictionary-assisted risk screening and targeted AI editorial correction; not independent human proofreading, listening or rights clearance.")
    review = field_review(rows, changes)
    if any(sha((DATA / name).read_bytes()) != digest for name, digest in fingerprints.items()):
        raise ValueError("Editorial inputs changed during generation; retry after the review is finalized")
    return {CSV: payload, ROOT / "docs/wordlist-validation.json": encoded(snapshot), DATA / "inverse-ledger.json": encoded(ledger),
            DATA / "field-review.csv": review,
            DATA / "source-lock.json": encoded(lock), DATA / "report.json": encoded(report), DATA / "tag-review.json": encoded(tag_review)}


def verify_review():
    report = read(DATA / "report.json")
    content = CSV.read_bytes()
    if sha(content) != report["sha256"] or report["totalWords"] != 20000:
        raise ValueError("Refinement report differs from current vocabulary")
    previous_payload(content)
    lock = read(DATA / "source-lock.json")
    for name, digest in lock["editorialFiles"].items():
        if Path(name).name != name or sha((DATA / name).read_bytes()) != digest:
            raise ValueError("Refinement editorial input changed: " + name)
    return report


def refined_provenance(provenance, vocabulary):
    """Preserve original source credit for retained/adapted sentence pairs."""
    previous = {r["词条ID"]: r for r in rows_of(previous_payload(CSV.read_bytes()))}
    result = dict(provenance)
    for row in vocabulary:
        word_id = row["词条ID"]
        old = previous.get(word_id)
        if old is not None and old["例句JSON"] == row["例句JSON"]:
            continue
        old_examples = {e["hanzi"]: e for e in json.loads(old["例句JSON"])} if old else {}
        old_sources = {e["hanzi"]: e for e in result.get(word_id, [])}
        records = []
        for example in json.loads(row["例句JSON"]):
            text = example["hanzi"]
            if text in old_sources:
                record = dict(old_sources[text])
            elif text in old_examples:
                record = dict(hanzi=text, source="legacy:" + word_id)
            else:
                record = dict(hanzi=text, source="Project-authored AI-assisted editorial example, 2026-11-03 semantic review")
            if text in old_examples and old_examples[text]["english"] != example["english"]:
                record.setdefault("originalEnglish", old_examples[text]["english"])
                record["modification"] = "contextual translation correction in the 2026-11-03 semantic review"
            records.append(record)
        result[word_id] = records
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--write", action="store_true")
    modes.add_argument("--check", action="store_true")
    args = parser.parse_args()
    original = CSV.read_bytes()
    generated = outputs(original)
    if args.check:
        for path, content in generated.items():
            if not path.exists() or path.read_bytes() != content:
                raise ValueError("Refinement output differs: " + str(path.relative_to(ROOT)))
    if args.write:
        staged, applied = {}, []
        previous = {path: path.read_bytes() if path.exists() else None for path in generated}
        order = [path for path in generated if path != CSV] + [CSV]

        def stage(path, content):
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".vocabulary-", suffix=".tmp", delete=False) as file:
                temporary = Path(file.name)
                try:
                    file.write(content)
                    file.flush()
                    os.fsync(file.fileno())
                except BaseException:
                    file.close()
                    temporary.unlink(missing_ok=True)
                    raise
            return temporary

        try:
            for path in order:
                staged[path] = stage(path, generated[path])
            if CSV.read_bytes() != original:
                raise ValueError("Concurrent CSV edit; refusing to overwrite")
            for path in order:
                os.replace(staged[path], path)
                applied.append(path)
        except BaseException:
            for path in reversed(applied):
                if previous[path] is None:
                    path.unlink(missing_ok=True)
                else:
                    restore = stage(path, previous[path])
                    try:
                        os.replace(restore, path)
                    finally:
                        restore.unlink(missing_ok=True)
            raise
        finally:
            for temporary in staged.values():
                temporary.unlink(missing_ok=True)
    report = json.loads(generated[DATA / "report.json"])
    print(json.dumps({key: report[key] for key in ("totalWords", "supplementalWords", "changedExistingWords", "changedFields", "sha256")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
