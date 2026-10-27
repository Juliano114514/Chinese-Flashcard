"""Read-only validation of the sole current wordlist; historical rebuild inputs retired."""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import io
import json
import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'wordlist.csv'

HEADERS = ['罕度', '组词', '拼音', '词条ID', '英文释义', '词性', '例句JSON',
           '部件JSON', '干扰词ID', '来源说明', '本义解释JSON', '引申义解释JSON']
JSON_FIELDS = ['例句JSON', '部件JSON', '干扰词ID', '本义解释JSON', '引申义解释JSON']


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def validate(content):
    if len(content) > 32 * 1024 * 1024:
        raise ValueError('CSV exceeds existing 32 MiB limit')

    def whitespace(character):
        # Kotlin Char.isWhitespace excludes NEXT LINE (U+0085), which Python
        # str.isspace/strip include.
        return character.isspace() and character != '\u0085'

    def trimmed(value):
        start, end = 0, len(value)
        while start < end and whitespace(value[start]):
            start += 1
        while end > start and whitespace(value[end - 1]):
            end -= 1
        return value[start:end]

    def text(value, maximum, context, trim=False):
        if not isinstance(value, str):
            raise ValueError('A teaching text must be a string: ' + context)
        if trim:
            value = trimmed(value)
        # Kotlin String.length counts UTF-16 code units, not Python code points.
        length = len(value.encode('utf-16-le', errors='surrogatepass')) // 2
        if not trimmed(value) or length > maximum or '\0' in value:
            raise ValueError('Blank, oversized or NUL-containing text: ' + context)
        return value

    def is_word_hanzi(character):
        point = ord(character)
        return (0x3400 <= point <= 0x4DBF or 0x4E00 <= point <= 0x9FFF or
                0xF900 <= point <= 0xFAFF or 0x20000 <= point <= 0x2FFFF or
                0x30000 <= point <= 0x323AF)

    def english(value, maximum, context):
        value = text(value, maximum, context)
        # UnicodeScript.HAN also includes radicals and ideographic marks that
        # are outside the word-only Han ranges accepted by CsvDecoder.
        han_ranges = ((0x2E80, 0x2E99), (0x2E9B, 0x2EF3), (0x2F00, 0x2FD5),
                      (0x3021, 0x3029), (0x3038, 0x303B), (0x16FE2, 0x16FE3),
                      (0x16FF0, 0x16FF1))
        if (not re.search('[A-Za-z]', value) or any(is_word_hanzi(c) or
                ord(c) in (0x3005, 0x3007) or
                any(start <= ord(c) <= end for start, end in han_ranges) for c in value)):
            raise ValueError('Teaching English needs Latin letters and no Han script: ' + context)
        return value

    pinyin_letter = re.compile(r'[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹňḿ]', re.I)
    sentence_punctuation = '.,!?;:，。！？；：、…—–-\"\'“”‘’()（）[]'

    def pinyin(value, maximum, context, sentence=False):
        value = unicodedata.normalize('NFC', text(value, maximum, context))
        punctuation = sentence_punctuation if sentence else "'-’"
        if not pinyin_letter.search(value) or any(not whitespace(c) and
                not pinyin_letter.fullmatch(c) and c not in punctuation for c in value):
            raise ValueError('Invalid pinyin characters: ' + context)
        return value

    def normalized_example_pinyin(value):
        return ''.join(c for c in unicodedata.normalize('NFC', value).lower()
                       if not whitespace(c) and c not in "'’")

    def exact_keys(value, names, context):
        if not isinstance(value, dict) or set(value) != set(names):
            raise ValueError('Unexpected or missing teaching JSON keys: ' + context)

    def json_array(value, maximum, context):
        value = text(value, maximum, context, trim=True)
        depth, quoted, escaped = 0, False, False
        for character in value:
            if quoted:
                if escaped:
                    escaped = False
                elif character == '\\':
                    escaped = True
                elif character == '"':
                    quoted = False
            elif character == '"':
                quoted = True
            elif character in '[{':
                depth += 1
                if depth > 4:
                    raise ValueError('Teaching JSON nesting exceeds four levels: ' + context)
            elif character in ']}':
                depth -= 1
                if depth < 0:
                    raise ValueError('Unbalanced teaching JSON: ' + context)
            elif character not in ' \t\r\n:,':
                raise ValueError('Teaching JSON values must be quoted strings, arrays or objects: ' + context)
        if depth or quoted:
            raise ValueError('Unclosed teaching JSON: ' + context)
        value = json.loads(value)
        if not isinstance(value, list):
            raise ValueError('Teaching JSON field must contain an array: ' + context)
        return value

    decoded = content.decode('utf-8-sig')
    # csv.reader(strict=True) still accepts quotes inside unquoted fields;
    # CsvParser rejects them and any text after a closing quote.
    state = 0
    for character in decoded:
        if character in '\r\n':
            if state != 2:
                state = 0
        elif state == 0:
            if character == '"':
                state = 2
            elif character != ',':
                state = 1
        elif state == 1:
            if character == '"':
                raise ValueError('A CSV quote must start a quoted field')
            if character == ',':
                state = 0
        elif state == 2:
            if character == '"':
                state = 3
        elif character == '"':
            state = 2
        elif character == ',':
            state = 0
        else:
            raise ValueError('Only a comma or newline may follow a closing CSV quote')
    if state == 2:
        raise ValueError('A CSV quoted field is not closed')
    # Count the actual RFC 4180 record, including doubled quotes and UTF-8,
    # rather than a JSON representation whose byte count differs from CSV.
    physical_lines = re.findall(r'[^\r\n]*(?:\r\n|\r|\n|$)', decoded)
    reader = csv.reader(io.StringIO(decoded, newline=''), strict=True)
    names = next(reader, None)
    if names is None or [trimmed(name) for name in names] != HEADERS:
        raise ValueError('CSV headers/count invalid')
    previous_line = reader.line_num
    if len(''.join(physical_lines[:previous_line]).encode('utf-8')) > 32 * 1024:
        raise ValueError('Header exceeds existing 32 KiB record limit')
    rows = []
    for fields in reader:
        raw_record = ''.join(physical_lines[previous_line:reader.line_num])
        previous_line = reader.line_num
        if len(raw_record.encode('utf-8')) > 32 * 1024:
            raise ValueError('Record exceeds existing 32 KiB limit')
        if not fields or len(fields) == 1 and not trimmed(fields[0]):
            continue
        if len(fields) != len(HEADERS):
            raise ValueError('CSV row width differs from header')
        # CsvParser normalizes both CRLF and bare CR inside quoted fields.
        row = dict(zip(HEADERS, (re.sub(r'\r\n?', '\n', value) for value in fields)))
        for key, maximum in [('罕度', 1), ('组词', 64), ('拼音', 128),
                             ('词条ID', 92), ('英文释义', 300), ('词性', 80), ('来源说明', 2000)]:
            row[key] = text(row[key], maximum, key, trim=True)
        for key in JSON_FIELDS:
            row[key] = json_array(row[key], 400 if key == '干扰词ID' else 16000, key)
        rows.append(row)
        if len(rows) > 20000:
            raise ValueError('CSV headers/count invalid')
    if not rows:
        raise ValueError('CSV headers/count invalid')
    ids = {r['词条ID']: r for r in rows}
    if len(ids) != len(rows):
        raise ValueError('Duplicate IDs')
    stroke_index = load(ROOT / 'core/data/src/main/assets/wordlist-strokes/index.json')
    glyphs = {item['glyph'] for item in stroke_index['items']}
    for r in rows:
        if r['罕度'] not in {'0','1','2','3','4'} or not re.fullmatch(r'wl_[0-9]{5}', r['词条ID']):
            raise ValueError('Invalid ID or rarity: ' + r['组词'])
        word = unicodedata.normalize('NFC', r['组词'])
        if not 1 <= len(word) <= 32 or not all(is_word_hanzi(c) for c in word):
            raise ValueError('A word must contain 1–32 Han characters: ' + r['组词'])
        reading = pinyin(r['拼音'], 128, r['组词'] + ' word pinyin')
        if len([s for s in re.split(r"[\s'’\-]+", trimmed(reading), flags=re.ASCII) if s]) != len(word):
            raise ValueError('Word pinyin alignment: ' + r['组词'])
        english(r['英文释义'], 300, r['组词'] + ' short English')
        for glyph in word:
            if glyph not in glyphs:
                raise ValueError('Missing genuine stroke: ' + glyph)
        for key in ['本义解释JSON', '引申义解释JSON']:
            values = r[key]
            if not isinstance(values, list) or len(values) > 16 or (key == '本义解释JSON' and not values):
                raise ValueError('Invalid explanation array: ' + r['组词'])
            for value in values:
                context = r['组词'] + ' ' + key
                text(value, 2000, context)
                english(trimmed(value), 2000, context)
        examples = r['例句JSON']
        if len(examples) != 2:
            raise ValueError('Two examples required: ' + r['组词'])
        for ex in examples:
            exact_keys(ex, ('hanzi', 'pinyin', 'english', 'chunks'), r['组词'] + ' example')
            text(ex['hanzi'], 1000, r['组词'] + ' example Hanzi')
            pinyin(ex['pinyin'], 2000, r['组词'] + ' example pinyin', sentence=True)
            english(ex['english'], 2000, r['组词'] + ' natural English')
            if word not in ex['hanzi']:
                raise ValueError('Invalid example: ' + r['组词'])
            chunks = ex['chunks']
            if not isinstance(chunks, list) or not 1 <= len(chunks) <= 64:
                raise ValueError('Invalid chunk shape: ' + r['组词'])
            for chunk in chunks:
                exact_keys(chunk, ('hanzi', 'pinyin', 'gloss'), r['组词'] + ' chunk')
                text(chunk['hanzi'], 1000, r['组词'] + ' chunk Hanzi')
                pinyin(chunk['pinyin'], 2000, r['组词'] + ' chunk pinyin', sentence=True)
                english(chunk['gloss'], 2000, r['组词'] + ' literal gloss')
            if ''.join(c['hanzi'] for c in chunks) != ex['hanzi']:
                raise ValueError('Chunk Hanzi reconstruction: ' + r['组词'])
            if normalized_example_pinyin(' '.join(c['pinyin'] for c in chunks)) != normalized_example_pinyin(ex['pinyin']):
                raise ValueError('Chunk pinyin reconstruction: ' + r['组词'])
        if examples[0]['hanzi'] == examples[1]['hanzi']:
            raise ValueError('Two distinct examples required: ' + r['组词'])
        parts = r['部件JSON']
        if not 1 <= len(parts) <= 32:
            raise ValueError('Provide 1–32 word parts: ' + r['组词'])
        for part in parts:
            exact_keys(part, ('hanzi', 'pinyin', 'gloss'), r['组词'] + ' part')
            text(part['hanzi'], 64, r['组词'] + ' part Hanzi')
            pinyin(part['pinyin'], 128, r['组词'] + ' part pinyin')
            english(part['gloss'], 300, r['组词'] + ' part gloss')
        if ''.join(p['hanzi'] for p in parts) != word:
            raise ValueError('Word parts must reconstruct the complete word: ' + r['组词'])
        distractors = r['干扰词ID']
        if (len(distractors) != 3 or any(not isinstance(x, str) or
                not re.fullmatch('[A-Za-z0-9_]{1,92}', x) for x in distractors) or
                len(set(distractors)) != 3 or r['词条ID'] in distractors or any(x not in ids for x in distractors)):
            raise ValueError('Invalid distractors: ' + r['组词'])
        if len({unicodedata.normalize('NFC', trimmed(ids[x]['英文释义'])).lower()
                for x in [r['词条ID'],*distractors]}) != 4:
            raise ValueError('Repeated choice text: ' + r['组词'])
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', required=True,
                        help='Validate current CSV and snapshot manifest without writing')
    parser.parse_args()
    content = OUTPUT.read_bytes()
    rows = validate(content)
    baseline_path = ROOT / 'tools/vocabulary_v2/baseline-index.json'
    if baseline_path.exists():
        baseline = load(baseline_path)
        current = {row['词条ID']: row['组词'] for row in rows}
        retained = baseline['words']
        replacements_path = ROOT / 'core/data/src/main/assets/wordlist-sources/headword-replacements.json'
        replacements = load(replacements_path)['replacements'] if replacements_path.exists() else {}
        for word_id, record in retained.items():
            if current.get(word_id) == record['hanzi']:
                continue
            replacement = replacements.get(word_id)
            if (not replacement or replacement['oldHanzi'] != record['hanzi'] or
                    replacement['newHanzi'] != current.get(word_id)):
                raise ValueError('Missing legacy ID or unreviewed headword replacement: ' + word_id)
    actual = {
        'schemaVersion': 2,
        'words': len(rows),
        'columns': HEADERS,
        'rarity': dict(sorted(collections.Counter(row['罕度'] for row in rows).items())),
        'examples': sum(len(row['例句JSON']) for row in rows),
        'bytes': len(content),
        'sha256': hashlib.sha256(content).hexdigest(),
    }
    if actual != load(ROOT / 'docs/wordlist-validation.json'):
        raise ValueError('Current CSV differs from its snapshot manifest')
    print(json.dumps(dict(actual, status='PASS'), ensure_ascii=False))


if __name__ == '__main__':
    main()
