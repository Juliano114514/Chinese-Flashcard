"""Apply/check the explicit five-stage lexical revision without dropping stable IDs.

The input is the recorded 1.6.9 six-stage snapshot. Editorial overlays are
reviewable, separate from that historical snapshot, and never authorize arbitrary
ID reassignment. --check is offline and does not rewrite any resource.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import io
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "tools/vocabulary_v2"
INPUT_SHA256 = "71d0a021e789e1892a12f5732bba0b704662d2d3639681ed5c087558b692028b"
HEADWORDS = DATA / "headword-refinement-20261027.json"
BREAKDOWN = DATA / "breakdown-refinement-20261027.json"
COMPOUNDS = DATA / "compound-refinement-20261027.json"
SUPPLEMENT = DATA / "headword-supplement-20261027.json"
OVERLAY_PATHS = (HEADWORDS, BREAKDOWN, COMPOUNDS, SUPPLEMENT)
REVIEW = DATA / "wordlist-refinement-review.json"
ASSETS = ROOT / "core/data/src/main/assets/wordlist-sources"
JSON_FIELDS = {"例句JSON", "部件JSON", "本义解释JSON", "引申义解释JSON", "干扰词ID"}
EDITABLE_FIELDS = {"组词", "拼音", "英文释义", "词性", "例句JSON", "部件JSON", "本义解释JSON", "引申义解释JSON"}


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded(value))


def fingerprint(row):
    return hashlib.sha256(encoded(row)).hexdigest()


def overlays():
    headwords, breakdown, compounds, supplement = (read(path) for path in OVERLAY_PATHS)
    for overlay in (headwords, breakdown, compounds, supplement):
        if overlay.get("schemaVersion") != 1 or overlay.get("sourceCsvSha256") != INPUT_SHA256:
            raise ValueError("Editorial overlay refers to a different reviewed input")
    if set(headwords["rows"]) & set(supplement["rows"]):
        raise ValueError("Main and supplementary headword decisions overlap")
    headwords["rows"].update(supplement["rows"])
    headwords["retainedSingles"] = [row for row in headwords.get("retainedSingles", []) if row["id"] not in supplement["rows"]]
    return headwords, breakdown, compounds


def apply_fields(row, fields):
    if not set(fields) <= EDITABLE_FIELDS:
        raise ValueError("Unexpected editorial field: " + row["词条ID"])
    for field, value in fields.items():
        if field in JSON_FIELDS:
            decoded = json.loads(value) if isinstance(value, str) else value
            if not isinstance(decoded, list):
                raise ValueError("Editorial teaching JSON must be an array: " + row["词条ID"])
            row[field] = json.dumps(decoded, ensure_ascii=False, separators=(",", ":"))
        else:
            if not isinstance(value, str):
                raise ValueError("Editorial text must be a string: " + row["词条ID"])
            row[field] = value


def apply_refinements(rows):
    """Final pass for the locked expansion generator, before serializing CSV."""
    headwords, breakdown, compounds = overlays()
    index = {row["词条ID"]: row for row in rows}
    if any(set(overlay["rows"]) - set(index) for overlay in (headwords, breakdown, compounds)):
        raise ValueError("Editorial overlay refers to an absent stable ID")
    for row in rows:
        previous_rarity = int(row["罕度"])
        row["罕度"] = str(max(previous_rarity - 1, 0))
        word_id = row["词条ID"]
        for overlay in (breakdown, compounds):
            fix = overlay["rows"].get(word_id)
            if fix:
                if fix["headword"] != row["组词"]:
                    raise ValueError("Content refinement target changed: " + word_id)
                apply_fields(row, fix["fields"])
        replacement = headwords["rows"].get(word_id)
        if replacement:
            if row["组词"] != replacement["oldHanzi"]:
                raise ValueError("Replacement input changed: " + word_id)
            apply_fields(row, replacement["fields"])
            if row["组词"] != replacement["newHanzi"] or row["组词"] == replacement["oldHanzi"]:
                raise ValueError("Invalid explicit headword replacement: " + word_id)
            if "suggestedRarity" in replacement:
                rarity = replacement["suggestedRarity"]
                if type(rarity) is not int or not 0 <= rarity <= 4 or not replacement.get("rarityReason"):
                    raise ValueError("A replacement grade needs an explicit decision and reason: " + word_id)
                if previous_rarity == 0 and rarity != 0:
                    raise ValueError("Former Stage 0 must remain Stage 0: " + word_id)
                row["罕度"] = str(rarity)
    # Changed meanings can make an old distractor equal to its answer. Repair
    # only those affected questions; stage merging alone preserves choices.
    by_stage = collections.defaultdict(list)
    for row in rows:
        by_stage[row["罕度"]].append(row)
    changed_meanings = {word_id for overlay in (headwords, compounds)
                        for word_id, fix in overlay["rows"].items() if "英文释义" in fix["fields"]}
    def answer_tokens(value):
        return set(re.findall("[a-z]{3,}", value.lower())) - {"the", "and", "for", "with", "somebody", "something"}
    for row in rows:
        ids = json.loads(row["干扰词ID"])
        answer = answer_tokens(row["英文释义"])
        changed_question = row["词条ID"] in changed_meanings or bool(set(ids) & changed_meanings)
        used = {row["英文释义"].strip().casefold()}
        valid = []
        for candidate_id in ids:
            candidate = index[candidate_id]
            gloss = candidate["英文释义"].strip().casefold()
            if (candidate_id != row["词条ID"] and gloss not in used and
                    (not changed_question or not answer & answer_tokens(candidate["英文释义"]))):
                valid.append(candidate_id)
                used.add(gloss)
        if len(valid) == 3:
            continue
        pool = by_stage[row["罕度"]]
        start = int(hashlib.sha256(row["词条ID"].encode()).hexdigest()[:8], 16) % len(pool)
        for offset in range(len(pool)):
            candidate = pool[(start + offset) % len(pool)]
            gloss = candidate["英文释义"].strip().casefold()
            if candidate["词条ID"] == row["词条ID"] or gloss in used or answer & answer_tokens(candidate["英文释义"]):
                continue
            valid.append(candidate["词条ID"])
            used.add(gloss)
            if len(valid) == 3:
                break
        if len(valid) != 3:
            raise ValueError("Cannot repair affected choices")
        row["干扰词ID"] = json.dumps(valid, separators=(",", ":"))


def check():
    from wordlist_snapshot import verify_snapshot
    snapshot = verify_snapshot(ROOT / "wordlist.csv")
    report = read(REVIEW)
    if report["inputCsvSha256"] != INPUT_SHA256 or report["finalCsvSha256"] != snapshot["sha256"]:
        raise ValueError("Lexical refinement review does not match current CSV")
    for path in OVERLAY_PATHS:
        if hashlib.sha256(path.read_bytes()).hexdigest() != report["overlaySha256"][path.name]:
            raise ValueError("Editorial overlay changed after acceptance")
    rows = list(csv.DictReader((ROOT / "wordlist.csv").open(encoding="utf-8-sig", newline="")))
    if len(rows) != 18500 or [r["词条ID"] for r in rows] != list(report["words"]):
        raise ValueError("A lexical revision cannot drop/reorder stable IDs")
    heads, _, _ = overlays()
    for row in rows:
        record = report["words"][row["词条ID"]]
        replacement = heads["rows"].get(row["词条ID"], {})
        expected_rarity = replacement.get("suggestedRarity", max(record["previousRarity"] - 1, 0))
        if (fingerprint(row) != record["afterRowSha256"] or
                int(row["罕度"]) != expected_rarity or
                record["previousRarity"] == 0 and int(row["罕度"]) != 0):
            raise ValueError("Reviewed word content/stage changed: " + row["词条ID"])
    allowed = read(ASSETS / "headword-replacements.json")
    expected = {word_id: {"oldHanzi": record["previousHanzi"], "newHanzi": row["组词"],
                         "oldPinyin": record["previousPinyin"], "newPinyin": row["拼音"]}
                for row in rows if (word_id := row["词条ID"]) in heads["rows"]
                for record in [report["words"][word_id]]}
    if allowed != {"schemaVersion": 1, "replacements": expected}:
        raise ValueError("Bundled replacement allowlist is stale")
    if len({row["组词"] for row in rows}) != len(rows):
        raise ValueError("Refinement introduced or retained duplicate headwords")
    print(json.dumps({key: report[key] for key in ("wordsCount", "rarity", "headwordReplacements", "singleCharactersBefore", "singleCharactersAfter", "breakdownChangedRows")}, ensure_ascii=False))
    print("Five-stage lexical refinement: read-only check passed")


def write():
    source = ROOT / "wordlist.csv"
    payload = source.read_bytes()
    if hashlib.sha256(payload).hexdigest() != INPUT_SHA256:
        raise ValueError("Apply exactly once to the recorded 1.6.9 input; use --check afterward")
    before = list(csv.DictReader(io.StringIO(payload.decode("utf-8-sig"), newline="")))
    rows = [dict(row) for row in before]
    apply_refinements(rows)
    if len(rows) != 18500 or len({row["组词"] for row in rows}) != len(rows):
        raise ValueError("Refinement must retain 18,500 distinct stable entries and unique headwords")
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=list(before[0]), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    content = stream.getvalue().encode("utf-8-sig")
    if len(content) > 32 * 1024 * 1024:
        raise ValueError("Refinement exceeds existing CSV byte limit")
    heads, breakdown, compounds = overlays()
    records, replacements, provenance = {}, {}, {}
    for old, row in zip(before, rows, strict=True):
        word_id = row["词条ID"]
        assert word_id == old["词条ID"]
        records[word_id] = {"previousHanzi": old["组词"], "previousPinyin": old["拼音"],
            "previousRarity": int(old["罕度"]), "beforeRowSha256": fingerprint(old), "afterRowSha256": fingerprint(row),
            "rarity": int(row["罕度"]), "rarityReason": heads["rows"].get(word_id, {}).get("rarityReason", "Merge former 0/1; map former 2–5 down one band."),
            "changedFields": [field for field in row if row[field] != old[field]]}
        content_fix = compounds["rows"].get(word_id)
        if content_fix and "exampleProvenance" in content_fix:
            provenance[word_id] = content_fix["exampleProvenance"]
        if word_id in heads["rows"]:
            fix = heads["rows"][word_id]
            replacements[word_id] = {"oldHanzi": old["组词"], "newHanzi": row["组词"], "oldPinyin": old["拼音"], "newPinyin": row["拼音"]}
            provenance[word_id] = fix["exampleProvenance"]
        if word_id in provenance and [ex["hanzi"] for ex in provenance[word_id]] != [ex["hanzi"] for ex in json.loads(row["例句JSON"])]:
            raise ValueError("Refined example attribution differs: " + word_id)
    rarity = dict(sorted(collections.Counter(row["罕度"] for row in rows).items()))
    report = {"schemaVersion": 1, "reviewDate": "2026-10-27", "inputCsvSha256": INPUT_SHA256,
        "finalCsvSha256": hashlib.sha256(content).hexdigest(), "wordsCount": len(rows), "rarity": rarity,
        "headwordReplacements": len(replacements), "singleCharactersBefore": sum(len(r["组词"]) == 1 for r in before),
        "singleCharactersAfter": sum(len(r["组词"]) == 1 for r in rows),
        "breakdownChangedRows": sum(a["部件JSON"] != b["部件JSON"] or a["例句JSON"] != b["例句JSON"] for a, b in zip(before, rows)),
        "overlaySha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in OVERLAY_PATHS},
        "stageMapping": {str(n): max(n - 1, 0) for n in range(6)}, "deletedIds": [], "words": records,
        "replacementGradeChanges": sum(int(row["罕度"]) != max(int(old["罕度"]) - 1, 0) for old, row in zip(before, rows)),
        "reviewBoundary": "Full structural scan and targeted AI-assisted lexical editing; not human linguistic certification."}
    # Validate all teaching fields before accepting the new frozen fingerprint.
    from rebuild_wordlist import validate
    validate(content)
    source.write_bytes(content)
    dump(REVIEW, report)
    snapshot = read(ROOT / "docs/wordlist-validation.json")
    snapshot.update(rarity=rarity, bytes=len(content), sha256=report["finalCsvSha256"])
    dump(ROOT / "docs/wordlist-validation.json", snapshot)
    dump(ASSETS / "headword-replacements.json", {"schemaVersion": 1, "replacements": replacements})
    dump(DATA / "refinement-example-provenance.json", provenance)
    review_csv = io.StringIO(newline="")
    review_writer = csv.writer(review_csv, lineterminator="\n")
    review_writer.writerow(["词条ID", "原词头", "当前词头", "原六档", "当前五档", "英文教学义", "词性", "修改列", "修订依据", "分级依据"])
    for old, row in zip(before, rows, strict=True):
        word_id = row["词条ID"]
        reason = heads["rows"].get(word_id, {}).get("reason")
        if not reason:
            reasons = [*breakdown["rows"].get(word_id, {}).get("reasons", []),
                       *compounds["rows"].get(word_id, {}).get("reasons", [])]
            reason = "; ".join(reasons) or "五档映射；教学内容保留"
        review_writer.writerow([word_id, old["组词"], row["组词"], old["罕度"], row["罕度"],
            row["英文释义"], row["词性"], " | ".join(records[word_id]["changedFields"]), reason,
            records[word_id]["rarityReason"]])
    (DATA / "wordlist-refinement-full-review.csv").write_bytes(review_csv.getvalue().encode("utf-8-sig"))
    expansion = read(DATA / "expansion-report.json")
    expansion.update(sha256=report["finalCsvSha256"], bytes=len(content), rarity=rarity,
                     headwordReplacements=len(replacements), deletedIds=[],
                     refinementExampleSourceUses=dict(collections.Counter(
                         "legacy" if ex["source"].startswith(("legacy:", "legacy-context:")) else
                         "Tatoeba" if "tatoeba.org" in ex["source"] or "Tatoeba #" in ex["source"] else
                         "project-original" for examples in provenance.values() for ex in examples)))
    dump(DATA / "expansion-report.json", expansion)
    check()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--check", action="store_true")
    modes.add_argument("--write", action="store_true")
    args = parser.parse_args()
    check() if args.check else write()
