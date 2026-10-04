"""Build bounded, genuine stroke assets for the CSV vocabulary and existing demo.

Only the pinned Make Me a Hanzi and AnimCJK simplified-Chinese data are used.
The upstream graphics coordinates already match the app; no Y transform is applied.
--check reads sources and compares outputs without downloading or writing files.
--allow-missing is a development option, never a complete-coverage acceptance check.
"""
import argparse
import csv
import hashlib
import json
import math
import os
import re
import tempfile
import unicodedata
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MM_REVISION = "bddc96d41bef78427ed0e034e9f7e31d71fd1b92"
ANIM_REVISION = "ec5e17cca76c87587790bcbce5ea0b4d4fb753d6"
MM_SHA256 = "a28c478b5178e98f67f510b2d52fde08a69dc664654ef43498253b9b764d46ee"
ANIM_SHA256 = "5a5c157fddd0fd9bfaf5580b25c20a1ba2b0cabc0b7142e09f6149cbd5547798"
# All 70 simplified-Chinese supplements verified for the original CSV are kept
# as independent resources, including 琫 after a partner glyph lacks stroke data.
CONFIRMED_ANIM_GLYPHS = frozenset("俶匜嗞嵎嵖巉惇愔抔挦揳旻晞杻楯殣沚沨浥湜湲滪焜牁牂玕玙玠玥玦珣珰琤琫琯瑀璈璘璟璠璪瓘盉眊秾罽羑翚芼藠豨赟跐蹽郿铻锜镠镵闿靸靺靿鞨颙馃骎鹐齁齉")
MM_CACHE = ROOT / ".gradle/stroke-source"
ANIM_CACHE = MM_CACHE / "animcjk" / ANIM_REVISION
OUTPUT = ROOT / "core/data/src/main/assets/wordlist-strokes"
MODIFIED_ON = "2026-10-04"
RESOURCE_NOTICE = ("Selected genuine stroke glyphs extracted from pinned Make Me a Hanzi and AnimCJK simplified Chinese data; "
                   "strokes renamed to paths and median point pairs represented as x/y objects; no coordinate changes. "
                   "Derived stroke resources remain under the Arphic Public License.")
MAX_DOWNLOAD_BYTES = 40 * 1024 * 1024
MAX_SHARD_BYTES = 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_INDEX_BYTES = 4 * 1024 * 1024
MAX_SHARD_GLYPHS = 128
PATH_TOKEN = re.compile(r"[MLQCZ]|[-+]?(?:[0-9]*\.)?[0-9]+(?:[eE][-+]?[0-9]+)?")
SHARD_NAME = re.compile(r"shard_[0-9]{3}\.json")
ANIM_BASE = f"https://raw.githubusercontent.com/parsimonhi/animCJK/{ANIM_REVISION}/"
MM_BASE = f"https://raw.githubusercontent.com/skishore/makemeahanzi/{MM_REVISION}/"


def digest(content):
    return hashlib.sha256(content).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def atomic_write(target, content):
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, prefix=target.name + ".", delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(content)
        os.replace(temporary, target)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def cached_source(target, url, check, expected_sha=None, limit=MAX_DOWNLOAD_BYTES):
    if target.exists():
        if target.stat().st_size > limit:
            raise ValueError(f"Source exceeds its size limit: {target.name}")
        content = target.read_bytes()
    else:
        if check:
            raise ValueError(f"Required cached source is missing: {target}")
        # URLs are fixed official HTTPS locations at pinned revisions, not CSV inputs.
        request = urllib.request.Request(url, headers={"User-Agent": "Chinese-Flashcard-resource-builder"})
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(request, timeout=30) as response:
            declared_size = response.headers.get("Content-Length")
            if declared_size and int(declared_size) > limit:
                raise ValueError(f"Source exceeds its download limit: {target.name}")
            chunks, size = [], 0
            while True:
                chunk = response.read(64 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > limit:
                    raise ValueError(f"Source exceeds its download limit: {target.name}")
                chunks.append(chunk)
        content = b"".join(chunks)
        if expected_sha and digest(content) != expected_sha:
            raise ValueError(f"Pinned source hash mismatch: {target.name}")
        atomic_write(target, content)
    if not content or (expected_sha and digest(content) != expected_sha):
        raise ValueError(f"Pinned source is empty or has an unexpected hash: {target.name}")
    return content


def read_sources(check):
    if (MM_CACHE / "revision.txt").read_text(encoding="utf-8").strip() != MM_REVISION:
        raise ValueError("Unexpected Make Me a Hanzi revision; do not replace existing source data.")
    mm_graphics = cached_source(MM_CACHE / "graphics.txt", MM_BASE + "graphics.txt", check, MM_SHA256)
    mm_copying = cached_source(MM_CACHE / "COPYING", MM_BASE + "COPYING", check, limit=128 * 1024)
    mm_apl = cached_source(MM_CACHE / "ARPHICPL.TXT", MM_BASE + "APL/english/ARPHICPL.TXT", check, limit=128 * 1024)
    anim_graphics = cached_source(ANIM_CACHE / "graphicsZhHans.txt", ANIM_BASE + "graphicsZhHans.txt", check, ANIM_SHA256)
    anim_copying = cached_source(ANIM_CACHE / "COPYING.txt", ANIM_BASE + "licenses/COPYING.txt", check, limit=128 * 1024)
    anim_apl = cached_source(ANIM_CACHE / "ARPHICPL.TXT", ANIM_BASE + "licenses/APL/english/ARPHICPL.TXT", check, limit=128 * 1024)
    licenses = {
        "licenses/ARPHICPL.TXT": mm_apl,
        "licenses/ANIMCJK_ARPHICPL.TXT": anim_apl,
        "licenses/MAKE_ME_A_HANZI_COPYING": mm_copying,
        "licenses/ANIMCJK_COPYING.txt": anim_copying,
    }
    descriptors = [
        dict(id="makemeahanzi", repository="https://github.com/skishore/makemeahanzi", revision=MM_REVISION,
             file="graphics.txt", url=MM_BASE + "graphics.txt", sha256=digest(mm_graphics), bytes=len(mm_graphics),
             license="Arphic Public License", licenseAsset="wordlist-strokes/licenses/ARPHICPL.TXT",
             copyingAsset="wordlist-strokes/licenses/MAKE_ME_A_HANZI_COPYING"),
        dict(id="animcjk_zh_hans", repository="https://github.com/parsimonhi/animCJK", revision=ANIM_REVISION,
             file="graphicsZhHans.txt", url=ANIM_BASE + "graphicsZhHans.txt", sha256=digest(anim_graphics), bytes=len(anim_graphics),
             license="Arphic Public License", licenseAsset="wordlist-strokes/licenses/ANIMCJK_ARPHICPL.TXT",
             copyingAsset="wordlist-strokes/licenses/ANIMCJK_COPYING.txt"),
    ]
    parsed = []
    for content, descriptor in zip((mm_graphics, anim_graphics), descriptors):
        records = {}
        for line in content.decode("utf-8").splitlines():
            item = json.loads(line)
            glyph = item["character"]
            if glyph in records:
                raise ValueError(f"Duplicate glyph in {descriptor['id']}: {glyph}")
            records[glyph] = item
        descriptor["sourceGlyphCount"] = len(records)
        parsed.append(records)
    return parsed, descriptors, licenses


def validate_path(path):
    if not isinstance(path, str) or not 1 <= len(path) <= 16384 or not path.startswith("M"):
        raise ValueError("Invalid stroke path length or origin")
    if any(not (char.isspace() or char == ",") for char in PATH_TOKEN.sub("", path)):
        raise ValueError("Unsupported stroke path syntax")
    tokens, index = PATH_TOKEN.findall(path), 0
    arities = {"M": 2, "L": 2, "Q": 4, "C": 6, "Z": 0}
    while index < len(tokens):
        command = tokens[index]
        index += 1
        if command not in arities:
            raise ValueError("Stroke path command expected")
        count = arities[command]
        if count == 0:
            continue
        numbers = 0
        while index < len(tokens) and tokens[index] not in arities:
            value = float(tokens[index])
            if not math.isfinite(value) or not -8192 <= value <= 8192:
                raise ValueError("Stroke path coordinate exceeds app bounds")
            index += 1
            numbers += 1
        if numbers < count or numbers % count:
            raise ValueError("Stroke path command has an invalid number of coordinates")


def tracing_item(record, source_id):
    glyph, paths, medians = record["character"], record["strokes"], record["medians"]
    if len(glyph) != 1 or not (1 <= len(paths) <= 64 and len(paths) == len(medians)):
        raise ValueError(f"Invalid genuine stroke count: {glyph}")
    for path in paths:
        validate_path(path)
    for median in medians:
        if not 2 <= len(median) <= 512:
            raise ValueError(f"Invalid median point count: {glyph}")
        for point in median:
            if not isinstance(point, list) or len(point) != 2 or any(
                isinstance(value, bool) or not isinstance(value, (int, float)) or
                not math.isfinite(value) or not -512 <= value <= 1536 for value in point
            ):
                raise ValueError(f"Invalid median coordinate: {glyph}")
    # Preserve the existing demo's item revision and attribution for MM glyphs.
    serialized = json.dumps(record, ensure_ascii=False, separators=(",", ":"))
    if source_id == "makemeahanzi":
        attribution = f"Make Me a Hanzi ({MM_REVISION}); Arphic Public License; required glyphs extracted for Chinese Flashcard."
    else:
        attribution = (f"AnimCJK simplified Chinese ({ANIM_REVISION}); Copyright 2016-2026 FM&SH; "
                       f"Arphic Public License; extracted and converted to app JSON on {MODIFIED_ON}; no coordinate changes.")
    return dict(id=f"glyph_U{ord(glyph):04X}", glyph=glyph, paths=paths,
                medians=[[dict(x=x, y=y) for x, y in median] for median in medians],
                revision=digest(serialized.encode("utf-8")), attribution=attribution)


def read_csv_words(csv_path):
    if csv_path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("CSV exceeds the 16 MiB resource limit")
    words = []
    with csv_path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if not reader.fieldnames or "组词" not in reader.fieldnames:
            raise ValueError("CSV requires the existing 组词 column")
        for row in reader:
            hanzi = row.get("组词", "").strip()
            if not hanzi or len(hanzi) > 32 or any(not unicodedata.name(char, "").startswith(
                ("CJK UNIFIED IDEOGRAPH", "CJK COMPATIBILITY IDEOGRAPH")) for char in hanzi):
                raise ValueError(f"Invalid Hanzi in CSV ending at line {reader.line_num}")
            words.append(dict(csvLine=reader.line_num, hanzi=hanzi))
            if len(words) > 10000:
                raise ValueError("CSV exceeds the 10,000-word resource limit")
    if not words:
        raise ValueError("CSV contains no vocabulary")
    return words


def build(csv_path, check, allow_missing):
    words = read_csv_words(csv_path)
    demo_path = ROOT / "core/data/src/main/assets/demo/strokes.json"
    demo_items = json.loads(demo_path.read_text(encoding="utf-8"))["items"]
    csv_glyphs = {char for word in words for char in word["hanzi"]}
    required = csv_glyphs | {item["glyph"] for item in demo_items} | CONFIRMED_ANIM_GLYPHS
    if len(required) > 10000:
        raise ValueError("Stroke index exceeds the 10,000-glyph limit")
    records, descriptors, licenses = read_sources(check)
    if len(CONFIRMED_ANIM_GLYPHS) != 70 or not CONFIRMED_ANIM_GLYPHS <= records[1].keys() or CONFIRMED_ANIM_GLYPHS & records[0].keys():
        raise ValueError("The 70 verified simplified-Chinese supplements differ from their pinned sources")
    selected, counts = {}, [0, 0]
    for glyph in sorted(required, key=ord):
        for source_index, source in enumerate(records):
            if glyph in source:
                selected[glyph] = tracing_item(source[glyph], descriptors[source_index]["id"])
                counts[source_index] += 1
                break
    missing = required - selected.keys()
    report = dict(version=1, complete=not missing, csvRows=len(words), requiredGlyphCount=len(required),
                  missingGlyphCount=len(missing), missingGlyphs=sorted(missing, key=ord),
                  affectedWords=[dict(**word, missingGlyphs=sorted(set(word["hanzi"]) & missing, key=ord))
                                 for word in words if set(word["hanzi"]) & missing])
    if missing and not allow_missing:
        print(json.dumps(report, ensure_ascii=False))
        raise ValueError("Missing true stroke data; complete coverage is required. Use --allow-missing only for a development report.")
    for demo_item in demo_items:
        if selected.get(demo_item["glyph"]) != demo_item:
            raise ValueError(f"Existing demo tracing would change: {demo_item['glyph']}")
    files = dict(licenses)
    index, shard_manifest, shard_items, shard_size = [], [], [], 0
    metadata = dict(version=1, modifiedOn=MODIFIED_ON, modifications=RESOURCE_NOTICE)
    prefix = json_bytes(metadata).rstrip(b"\n")[:-1] + b',"items":['
    suffix = b']}\n'

    def flush():
        nonlocal shard_items, shard_size
        if not shard_items:
            return
        name = f"shard_{len(shard_manifest):03d}.json"
        content = prefix + b",".join(json_bytes(item).rstrip(b"\n") for item in shard_items) + suffix
        if len(content) > MAX_SHARD_BYTES or len(shard_items) > MAX_SHARD_GLYPHS:
            raise ValueError("Generated stroke shard exceeds its bounds")
        files[name] = content
        asset = "wordlist-strokes/" + name
        index.extend(dict(id=item["id"], glyph=item["glyph"], asset=asset) for item in shard_items)
        shard_manifest.append(dict(asset=asset, glyphCount=len(shard_items), bytes=len(content), sha256=digest(content)))
        shard_items, shard_size = [], 0

    for item in selected.values():
        item_size = len(json_bytes(item)) - 1
        if item_size + len(prefix) + len(suffix) > MAX_SHARD_BYTES:
            raise ValueError(f"Individual stroke item exceeds its resource limit: {item['glyph']}")
        next_size = len(prefix) + len(suffix) + shard_size + item_size + len(shard_items)
        if shard_items and (len(shard_items) == MAX_SHARD_GLYPHS or next_size > MAX_SHARD_BYTES):
            flush()
        shard_items.append(item)
        shard_size += item_size
    flush()
    files["index.json"] = json_bytes(dict(**metadata, items=index))
    if len(files["index.json"]) > MAX_INDEX_BYTES:
        raise ValueError("Generated stroke index exceeds its resource limit")
    files["missing.json"] = json_bytes(report)
    for descriptor, count in zip(descriptors, counts):
        descriptor["selectedGlyphCount"] = count
    notice = (f"Selected genuine character outlines and stroke medians were extracted and converted to app JSON on {MODIFIED_ON}. "
              "No coordinate transforms or synthetic stroke data were applied. Make Me a Hanzi data takes precedence; "
              "AnimCJK data is used only from its simplified Chinese graphicsZhHans.txt file. "
              "The derived stroke resources are distributed under the Arphic Public License; independent app code is a separate work.")
    files["sources.json"] = json_bytes(dict(version=1, modifiedOn=MODIFIED_ON, modifications=notice,
        complete=not missing, csvRows=len(words), csvSha256=digest(csv_path.read_bytes()),
        csvGlyphCount=len(csv_glyphs), demoGlyphCount=len(demo_items), requiredGlyphCount=len(required),
        confirmedSupplementGlyphs=sorted(CONFIRMED_ANIM_GLYPHS, key=ord),
        retainedUnusedSupplementGlyphs=sorted(CONFIRMED_ANIM_GLYPHS - csv_glyphs, key=ord),
        includedGlyphCount=len(selected), missingGlyphCount=len(missing), sources=descriptors,
        coordinateSystem=dict(size=1024, topY=900, bottomY=-124, yAxis="up"),
        index=dict(asset="wordlist-strokes/index.json", bytes=len(files["index.json"]), sha256=digest(files["index.json"])),
        shards=shard_manifest,
        licenses=[dict(asset="wordlist-strokes/" + name, bytes=len(content), sha256=digest(content))
                  for name, content in licenses.items()]))
    introduction = ("CHINESE FLASHCARD — STROKE DATA SOURCES AND LICENSES\n\n"
        f"Make Me a Hanzi revision: {MM_REVISION}\nhttps://github.com/skishore/makemeahanzi\n"
        f"AnimCJK simplified Chinese revision: {ANIM_REVISION}\nhttps://github.com/parsimonhi/animCJK\n\n"
        + notice + "\n\n")
    combined = introduction.encode("utf-8")
    for name, content in licenses.items():
        combined += ("===== " + name + " =====\n").encode("utf-8") + content + b"\n\n"
    files["LICENSES.txt"] = combined
    if sum(len(content) for content in files.values()) > MAX_TOTAL_BYTES:
        raise ValueError("Generated stroke resources exceed the 64 MiB total limit")
    return files, report, shard_manifest, counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, default=ROOT / "wordlist.csv")
    parser.add_argument("--check", action="store_true", help="Read and compare only; do not download or rewrite")
    parser.add_argument("--allow-missing", action="store_true", help="Development only: write/check an explicit incomplete-coverage report")
    args = parser.parse_args()
    files, report, shards, counts = build(args.csv.resolve(strict=True), args.check, args.allow_missing)
    existing_shards = {path.name for path in OUTPUT.glob("shard_*.json") if SHARD_NAME.fullmatch(path.name)}
    expected_shards = {name for name in files if SHARD_NAME.fullmatch(name)}
    if args.check:
        if existing_shards != expected_shards:
            raise ValueError("Generated stroke shard file set differs")
        for name, content in files.items():
            target = OUTPUT / name
            if not target.is_file() or target.read_bytes() != content:
                raise ValueError("Generated resource differs: " + name)
    else:
        for name, content in files.items():
            atomic_write(OUTPUT / name, content)
        # Remove only this generator's obsolete shard names within its exact output directory.
        for name in existing_shards - expected_shards:
            target = (OUTPUT / name).resolve(strict=True)
            if target.parent != OUTPUT.resolve(strict=True):
                raise ValueError("Refusing to remove a shard outside the output directory")
            target.unlink()
    status = "COMPLETE" if report["complete"] else "PARTIAL DEVELOPMENT OUTPUT"
    verb = "checked" if args.check else "generated"
    print(f"{status}: {report['csvRows']} CSV rows; {sum(counts)} genuine glyphs; "
          f"{counts[0]} Make Me a Hanzi + {counts[1]} AnimCJK; {len(shards)} shards {verb}; "
          f"{report['missingGlyphCount']} missing glyphs affecting {len(report['affectedWords'])} rows.")


if __name__ == "__main__":
    main()
