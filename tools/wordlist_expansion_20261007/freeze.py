"""Freeze reviewed additions/corrections; publishing stays in rebuild_wordlist.py.

entries.json: {"entries": [twelve-column CSV records with decoded JSON arrays]}.
corrections.json: {old_id: {"fields": {...}, "expectedSha256": {field: hash},
                           "reason": "the confirmed error and its evidence"}}.
The expected hash is SHA-256 of the old raw CSV field encoded as UTF-8.
reviews.json binds entriesSha256/correctionsSha256 and contains primary and
independent evidence, each with coveredIds, reviewedEntries, reviewedExamples,
issues: [], and coveredCorrectionIds when existing rows are corrected.
Primary records author-input/source/structural checks; independent records the
complete first editorial reading plus final changed-field/choice confirmation.
Its optional sourceSha256 maps relative authoring/review files to exact hashes.
No source CSV, historic frozen input, learner database or device is changed.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'tools'))
from rebuild_wordlist import (EXPANSION_20261007_BASE_ROWS, EXPANSION_20261007_BASE_SHA,
                              compose_expansion_20261007, expansion_20261007_reviews,
                              expansion_20261007_sources)


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n',
                    encoding='utf-8', newline='\n')


def freeze(baseline):
    original = baseline.read_bytes()
    if hashlib.sha256(original).hexdigest() != EXPANSION_20261007_BASE_SHA:
        raise ValueError('Use the frozen 9,223-word pre-expansion CSV as the baseline')
    entries = load(DATA / 'entries.json')['entries']
    corrections = load(DATA / 'corrections.json')
    review = load(DATA / 'reviews.json')
    source_hashes = {name: sha(DATA / name) for name in
                     ['freeze.py', 'entries.json', 'corrections.json', 'reviews.json']}
    directory = DATA.resolve()
    for name, digest in review.get('sourceSha256', {}).items():
        if not isinstance(name, str) or Path(name).is_absolute():
            raise ValueError('Review source paths must be relative to this expansion directory')
        path = (directory / name).resolve()
        if not path.is_relative_to(directory) or not path.is_file() or sha(path) != digest:
            raise ValueError('Missing or changed authoring/review evidence: ' + name)
        if name in source_hashes and source_hashes[name] != digest:
            raise ValueError('Review evidence conflicts with the final input: ' + name)
        source_hashes[name] = digest
    expansion_20261007_sources(source_hashes)
    expansion_20261007_reviews(review, entries, corrections, source_hashes)
    boundary = ('New teaching entries and evidenced existing-field corrections are AI-assisted; '
                'primary author-input/source/structural checks and independent complete editorial '
                'reading plus final changed-field/choice confirmations are separate evidence, '
                'bound to exact final input hashes. No second independent full language pass is claimed. '
                'All existing word IDs, headwords, stages, physical order and distractor IDs remain fixed. '
                'Structural validation and recorded AI review are not human linguistic certification '
                'or device-import evidence.')
    payload = {'date': '2026-10-07', 'baseRows': EXPANSION_20261007_BASE_ROWS,
               'baseSha256': EXPANSION_20261007_BASE_SHA, 'additionalRows': len(entries),
               'rarity': dict(collections.Counter(row['罕度'] for row in entries)),
               'sourceSha256': source_hashes, 'corrections': corrections,
               'entries': entries, 'boundary': boundary}
    combined, rows = compose_expansion_20261007(original, payload)
    digest = hashlib.sha256(combined).hexdigest()
    payload['resultSha256'] = digest
    payload['resultBytes'] = len(combined)
    save(ROOT / 'tools/wordlist_rebuild/expansion-20261007.json', payload)
    save(DATA / 'validation.json', {'baseRows': EXPANSION_20261007_BASE_ROWS,
         'baseSha256': EXPANSION_20261007_BASE_SHA, 'additionalRows': len(entries),
         'correctedExistingRows': len(corrections), 'resultRows': len(rows),
         'rarity': payload['rarity'], 'sha256': digest, 'bytes': len(combined),
         'newExamples': 2 * len(entries), 'primaryStructurallyCheckedEntries': len(entries),
         'independentlyEditorialReviewedEntries': len(entries), 'existingIdsStagesReferencesOrderUnchanged': True,
         'newHeadwordDuplicates': 0, 'completeStructuralValidator': 'PASS', 'boundary': boundary})
    print(json.dumps({'additionalRows': len(entries), 'correctedExistingRows': len(corrections),
                      'resultRows': len(rows), 'bytes': len(combined), 'sha256': digest,
                      'status': 'FROZEN; publish separately with rebuild_wordlist.py --apply'},
                     ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, default=ROOT / 'wordlist.csv')
    freeze(parser.parse_args().baseline)
