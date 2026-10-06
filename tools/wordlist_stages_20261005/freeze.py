"""Freeze a complete semantic stage review; publish using rebuild_wordlist.py.

Reads the final pre-grading CSV and the three fully reviewed decision shards.
The output is a rarity-only overlay, not a rewrite of teaching content.
"""
from __future__ import annotations

import collections
import csv
import hashlib
import io
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'tools'))
from rebuild_wordlist import validate, serialize

BASE_SHA = '82f6084f63cff8c4a945608b3fe185ba1b2cce9d9e6e797a1026b603ae814a3c'
LEVELS = {
    '0': {'grade': 'Primary school, Grade 1', 'label': 'Primary school'},
    '1': {'grade': 'Middle school, Grade 7', 'label': 'Middle school'},
    '2': {'grade': 'High school, Grade 10', 'label': 'High school'},
    '3': {'grade': 'University, Year 1', 'label': 'University'},
    '4': {'grade': 'Chinese language/literature major, Year 1', 'label': 'Specialist'},
}
REASONS = {'BASIC_OBJECT', 'BASIC_ACTION', 'BASIC_GRAMMAR', 'GENERAL_LIFE',
           'SCHOOL_GENERAL', 'COMMON_IDIOM', 'FORMAL_ABSTRACT', 'SCHOOL_SCIENCE',
           'LITERARY_COMMON', 'UNIVERSITY_ABSTRACT', 'TECHNICAL_NONLANGUAGE',
           'CLASSICAL_LEXICON', 'LITERARY_SPECIALIST', 'LINGUISTIC_SPECIALIST'}


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def freeze():
    baseline = ROOT / '.gradle/wordlist-stages-20261005/baseline.csv'
    original = baseline.read_bytes()
    if hashlib.sha256(original).hexdigest() != BASE_SHA:
        raise ValueError('Grading input must be the reviewed 9,223-word snapshot')
    rows = validate(original)
    if len(rows) != 9223 or serialize(rows) != original:
        raise ValueError('Baseline count or original CSV bytes changed')
    indexed = {row['词条ID']: row for row in rows}
    assignments = {}
    sources = ['freeze.py', 'policy.md', 'sources.json']
    for part, count in enumerate([3074, 3074, 3075]):
        input_path = DATA / f'input{part}.tsv'
        decision_path = DATA / f'decisions{part}.tsv'
        review_path = DATA / f'review{part}.json'
        with input_path.open(encoding='utf-8-sig', newline='') as file:
            supplied = list(csv.DictReader(file, delimiter='\t'))
        with decision_path.open(encoding='utf-8-sig', newline='') as file:
            decisions = list(csv.DictReader(file, delimiter='\t'))
        review = load(review_path)
        expected_ids = {row['id'] for row in supplied}
        if (len(supplied) != count or len(decisions) != count or
                {row['id'] for row in decisions} != expected_ids or
                len(review['coveredIds']) != count or set(review['coveredIds']) != expected_ids or
                review['reviewedRows'] != count):
            raise ValueError(f'Stage shard {part} does not have complete review coverage')
        for supplied_row in supplied:
            original_row = indexed.get(supplied_row['id'])
            if original_row is None or any(supplied_row[key] != original_row[field] for key, field in
                    [('word', '组词'), ('pinyin', '拼音'), ('meaning', '英文释义'), ('pos', '词性')]):
                raise ValueError('Stage reviewer input differs from the complete wordlist')
        if (review['inputSha256'] != sha(input_path) or
                review['decisionsSha256'] != sha(decision_path) or
                review['policySha256'] != sha(DATA / 'policy.md')):
            raise ValueError(f'Stage review {part} no longer matches its input or decisions')
        for decision in decisions:
            word_id = decision['id']
            if (word_id in assignments or word_id not in indexed or
                    decision['word'] != indexed[word_id]['组词'] or
                    decision['rarity'] not in LEVELS or decision['reason'] not in REASONS):
                raise ValueError('Invalid or overlapping semantic decision: ' + word_id)
            assignments[word_id] = decision['rarity']
        sources += [input_path.name, decision_path.name, review_path.name]
    corrections_path = DATA / 'cross_corrections.json'
    if corrections_path.exists():
        for word_id, decision in load(corrections_path).items():
            if (word_id not in indexed or decision['word'] != indexed[word_id]['组词'] or
                    decision['rarity'] not in LEVELS or decision['reason'] not in REASONS):
                raise ValueError('Invalid independent stage correction: ' + word_id)
            assignments[word_id] = decision['rarity']
        sources.append(corrections_path.name)
    for name in ['cross_review.json', 'cross4a.json', 'cross4b.json']:
        if (DATA / name).exists():
            sources.append(name)
    if set(assignments) != set(indexed):
        raise ValueError('The full 9,223-word list must have a semantic decision')
    for row in rows:
        row['罕度'] = assignments[row['词条ID']]
    result = serialize(rows)
    before, after = original.splitlines(keepends=True), result.splitlines(keepends=True)
    if (len(before) != len(rows) + 1 or len(after) != len(before) or before[0] != after[0] or
            any(old[1:] != new[1:] for old, new in zip(before[1:], after[1:]))):
        raise ValueError('A byte outside the first rarity cell would change')
    validate(result)
    counts = dict(sorted(collections.Counter(assignments.values()).items()))
    if set(counts) != set(LEVELS):
        raise ValueError('All five education stages must be represented')
    boundary = ('All entries were individually AI-reviewed under the requested educational bands; '
                'these are inferred learning stages, not an official national per-grade word list '
                'or human linguistic certification. Only rarity cells change.')
    payload = {'date': '2026-10-05', 'rows': len(rows), 'reviewedRows': len(assignments),
               'baseSha256': BASE_SHA, 'levels': LEVELS, 'rarityById': assignments,
               'rarity': counts, 'sourceSha256': {name: sha(DATA / name) for name in sources},
               'boundary': boundary}
    save(ROOT / 'tools/wordlist_rebuild/stage-grading-20261005.json', payload)
    save(DATA / 'validation.json', {'words': len(rows), 'reviewedRows': len(assignments),
         'rarity': counts, 'changedRarityCells': sum(old[0:1] != new[0:1] for old, new in zip(before[1:], after[1:])),
         'nonRarityBytesUnchanged': True, 'idsAndPhysicalOrderUnchanged': True,
         'sha256': hashlib.sha256(result).hexdigest(), 'bytes': len(result),
         'completeStructuralValidator': 'PASS', 'boundary': boundary})
    print(json.dumps({'words': len(rows), 'rarity': counts, 'onlyRarityChanges': True, 'status': 'PASS'}))


if __name__ == '__main__':
    freeze()
