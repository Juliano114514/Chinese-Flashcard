"""Freeze a reproducible, frequency-ranked teaching selection; no live app access."""
from __future__ import annotations

import csv
import hashlib
import json
import re
from pathlib import Path

from complete_wordlist import marked_pinyin, plain_pinyin
from wordlist_difficulty import source_bytes, source_tables

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.gradle/wordlist-refinement/mapull'
OUTPUT = ROOT / 'tools/idiom_selection.json'


def short_sense(senses):
    """Keep modern senses separate from attested literal pictures."""
    literal, modern = [], []
    for sense in senses:
        if sense.startswith(('also pr.', 'variant of ', 'CL:')):
            continue
        chunks = re.split(r';\s*(?:\(?fig\.?\)?\s*)', sense, flags=re.I)
        if re.match(r'^\(?lit\.?\)?\s', chunks[0], re.I):
            literal.append(re.sub(r'^\(?lit\.?\)?\s*', '', chunks[0], flags=re.I))
            modern.extend(chunks[1:])
        else:
            modern.extend(chunks)
    clean = lambda s: re.sub(r'\s+', ' ', re.sub(r'\((?:idiom|fig\.|fig|lit\.)\)', '', s)).strip(' ;.')
    modern = [clean(re.sub(r'^\(?fig\.?\)?\s*', '', s, flags=re.I)) for s in modern]
    literal = [clean(s) for s in literal]
    if not modern:
        raise ValueError('No attested modern sense: ' + repr(senses))
    return modern[0], '; '.join(literal)


def main():
    if OUTPUT.exists():
        print('Frozen selection already exists; stable IDs will not be reassigned.')
        return
    baseline = list(csv.DictReader((ROOT / 'wordlist.csv').open(encoding='utf-8-sig', newline='')))
    existing = {row['组词'] for row in baseline}
    index = json.loads((ROOT / '.gradle/wordlist-implementation/dictionary-index.json').read_text(encoding='utf-8'))
    idioms = json.loads((CACHE / 'idiom.json').read_text(encoding='utf-8'))
    source = json.loads((CACHE / 'source.json').read_text(encoding='utf-8'))
    words, _ = source_tables(source_bytes())
    glyphs = {item['glyph'] for item in json.loads((ROOT / 'core/data/src/main/assets/wordlist-strokes/index.json').read_text(encoding='utf-8'))['items']}
    candidates = []
    for record in idioms:
        word = record['word']
        entries = [e for e in index.get(word, []) if any('(idiom)' in s for s in e['senses'])]
        if not entries or word in existing or word not in words or not record.get('explanation') or not set(word) <= glyphs:
            continue
        entry = next((e for e in entries if plain_pinyin(marked_pinyin(e['pinyin'])) == plain_pinyin(record['pinyin'])), entries[0])
        try:
            meaning, literal = short_sense(entry['senses'])
        except ValueError:
            continue
        candidates.append((record, entry, meaning, literal))
    candidates.sort(key=lambda item: (-int(words[item[0]['word']]['W-CD']), -int(words[item[0]['word']]['WCount']), item[0]['word']))
    if len(candidates) < 1000:
        raise ValueError('Insufficient confirmed, supported candidates.')
    records = []
    for position, (record, entry, meaning, literal) in enumerate(candidates[:1000], 1):
        word = record['word']
        records.append({'id': f'cy_{position:05d}', 'hanzi': word,
            'pinyin': marked_pinyin(entry['pinyin']), 'selectedModernEnglish': meaning,
            'attestedLiteralEnglish': literal, 'cedict': entry, 'mapull': record,
            'frequency': {'contexts': int(words[word]['W-CD']), 'count': int(words[word]['WCount'])}})
    result = {'schemaVersion': 1, 'selectedCount': len(records), 'eligibleCandidates': len(candidates),
        'baselineCsvSha256': hashlib.sha256((ROOT / 'wordlist.csv').read_bytes()).hexdigest(),
        'selectionPolicy': 'CC-CEDICT idiom-marked entries intersect mapull; exclude all existing headwords; genuine bundled stroke coverage; SUBTLEX contexts/count descending, Hanzi ascending. Freeze IDs before authoring.',
        'source': source, 'items': records}
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'selected': len(records), 'eligible': len(candidates), 'lastContexts': records[-1]['frequency']['contexts']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
