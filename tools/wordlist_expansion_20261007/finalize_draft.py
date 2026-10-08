"""Merge complete cross-review decisions and prepare the final choice/delta review.

Does not create a reviews.json approval or publish the main CSV. Missing coverage
is an error; a structural PASS is never treated as editorial approval.
"""
from __future__ import annotations

import collections
import hashlib
import json
from pathlib import Path

from prepare_content import DATA, BASE, source_data, combine
from editorial import distractors, load, save


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def finalize():
    retained = load(DATA / 'retained-drafts.json')['entries']
    source_hash = sha(DATA / 'retained-drafts.json')
    reviewed = {}
    for name, first, last in [('services', 0, 900), ('family', 900, 1800), ('activity', 1800, len(retained))]:
        report = load(DATA / f'source_primary_{name}.json')
        expected = {r['word'] for r in retained[first:last]}
        if (report.get('complete') is not True or report.get('inputSha256') != source_hash or
                report.get('range') != [first, last] or set(report.get('coveredWords', [])) != expected or
                len(report.get('coveredWords', [])) != len(expected)):
            raise ValueError('Incomplete or changed retained-record review: ' + name)
        if not set(report.get('decisions', {})) <= expected or not set(report.get('rejections', {})) <= expected:
            raise ValueError('Retained editorial decision escapes its assigned partition: ' + name)
        for word in expected - set(report.get('rejections', {})):
            if word not in report.get('decisions', {}):
                raise ValueError('No individual source editorial decision: ' + word)
            reviewed[word] = name
    initial = load(DATA / 'entries.initial.json')['entries']
    initial_by_id = {r['词条ID']: r for r in initial}
    snapshot = load(DATA / 'initial-review-snapshot.json')
    if sha(DATA / 'entries.initial.json') != snapshot['entriesSha256']:
        raise ValueError('Initial full-record review snapshot changed')
    for source, reviewer in [('activity', 'services'), ('family', 'activity'), ('services', 'family')]:
        report = load(DATA / f'authored_cross_{source}.json')
        expected = set(snapshot['sources'][source + '_authoring.tsv'])
        if (report.get('complete') is not True or report.get('inputSha256') != snapshot['entriesSha256'] or
                set(report.get('coveredIds', [])) != expected or len(report.get('coveredIds', [])) != len(expected)):
            raise ValueError('Incomplete or changed authored-record review: ' + source)
        for key in ['corrections', 'rejections', 'decisions', 'reviewDecisions']:
            if not set(report.get(key, {})) <= expected:
                raise ValueError('Authored editorial decision escapes its assigned partition: ' + source + ' ' + key)
        for word_id in expected - set(report.get('rejections', {})):
            assigned = report.get('independentReviewers', {}).get(word_id, reviewer)
            if assigned not in {'services', 'family', 'activity'}:
                raise ValueError('Unknown independent reviewer: ' + word_id)
            reviewed[initial_by_id[word_id]['组词']] = assigned
    combine()
    entries = load(DATA / 'entries.json')['entries']
    baseline, _ = source_data()
    distractors(entries, baseline)
    for row in entries:
        if row['组词'] not in reviewed:
            raise ValueError('Final candidate has not been independently read: ' + row['组词'])
        row['来源说明'] = row['来源说明'].replace(
            'Independent full-record AI editorial review is required before publication.',
            'Independent complete-record AI editorial review and exact-source integrity checks recorded for this expansion.')
    save(DATA / 'entries.json', {'entries': entries})
    save(DATA / 'entries.final-review.json', {'entries': entries})
    groups = {name: [] for name in ['services', 'family', 'activity']}
    for row in entries:
        groups[reviewed[row['组词']]].append(row['词条ID'])
    bank = {r['词条ID']: r for r in baseline}
    save(DATA / 'final-review-snapshot.json', {
        'entriesSha256': sha(DATA / 'entries.final-review.json'),
        'correctionsSha256': sha(DATA / 'corrections.json'),
        'groups': groups, 'rows': len(entries),
        'rarity': dict(collections.Counter(r['罕度'] for r in entries)),
        'choices': {r['词条ID']: [{'id': x, 'word': bank[x]['组词'], 'english': bank[x]['英文释义']} for x in r['干扰词ID']] for r in entries},
        'boundary': 'First cross-review decisions merged. Final per-word choice labels and modified records still require reviewer confirmation; no CSV publication.'})
    print(json.dumps({'preparedRows': len(entries), 'rarity': dict(collections.Counter(r['罕度'] for r in entries)),
                      'reviewerGroups': {name: len(ids) for name, ids in groups.items()},
                      'entriesSha256': sha(DATA / 'entries.final-review.json'), 'status': 'READY FOR FINAL CHOICE/DELTA REVIEW'}, ensure_ascii=False))


if __name__ == '__main__':
    finalize()
