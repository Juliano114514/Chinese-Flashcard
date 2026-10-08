"""Print complete bounded review packets and apply hash-bound editorial decisions.

No review claims are generated here. Reviewers must read the requested records
and write decisions/reports themselves. This script does not publish the CSV.
"""
from __future__ import annotations

import argparse
import collections
import copy
import functools
import hashlib
import json
import sys
from pathlib import Path

DATA = Path(__file__).resolve().parent
ROOT = DATA.parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from complete_wordlist import tokens, marked_pinyin, glosses
from rebuild_wordlist import reading_key

ALLOWED = {'罕度', '拼音', '英文释义', '词性', '例句JSON', '部件JSON', '本义解释JSON', '引申义解释JSON', '干扰词ID'}


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def packet(start, count):
    path = DATA / 'entries.json'
    rows = load(path)['entries']
    print('snapshotSha256=' + sha(path), 'range=', start, min(start + count, len(rows)))
    for row in rows[start:start + count]:
        print(json.dumps(row, ensure_ascii=False, separators=(',', ':')))


def authored_packet(source, start, count):
    snapshot = load(DATA / 'initial-review-snapshot.json')
    path = DATA / 'entries.initial.json'
    if sha(path) != snapshot['entriesSha256']:
        raise ValueError('Initial complete-record review snapshot changed')
    ids = snapshot['sources'][source + '_authoring.tsv']
    indexed = {row['词条ID']: row for row in load(path)['entries']}
    sys.path.insert(0, str(DATA))
    from prepare_content import source_data
    baseline, _ = source_data()
    bank = {row['词条ID']: row for row in baseline}
    print('inputSha256=' + snapshot['entriesSha256'], 'source=' + source, 'groupRows=', len(ids), 'range=', start, min(start + count, len(ids)))
    for word_id in ids[start:start + count]:
        row = indexed[word_id]
        print(json.dumps(dict(row, choicesForReview=[{'id': x, 'word': bank[x]['组词'], 'english': bank[x]['英文释义']} for x in row['干扰词ID']]), ensure_ascii=False, separators=(',', ':')))


def final_packet(reviewer, start, count):
    snapshot = load(DATA / 'final-review-snapshot.json')
    path = DATA / 'entries.final-review.json'
    if sha(path) != snapshot['entriesSha256']:
        raise ValueError('Final choice/delta snapshot changed')
    rows = {r['词条ID']: r for r in load(path)['entries']}
    initial = {r['组词']: r for r in load(DATA / 'entries.initial.json')['entries']}
    ids = snapshot['groups'][reviewer]
    print('inputSha256=' + snapshot['entriesSha256'], 'reviewer=' + reviewer,
          'groupRows=', len(ids), 'range=', start, min(start + count, len(ids)))
    for word_id in ids[start:start + count]:
        row = rows[word_id]
        old = initial.get(row['组词'])
        changed = {key: value for key, value in row.items()
                   if key not in {'来源说明', '词条ID', '干扰词ID'} and (old is None or old[key] != value)}
        print(json.dumps({'id': word_id, 'word': row['组词'], 'pinyin': row['拼音'],
            'stage': row['罕度'], 'pos': row['词性'], 'english': row['英文释义'],
            'changedFields': changed, 'choices': snapshot['choices'][word_id]}, ensure_ascii=False, separators=(',', ':')))


def distractors(rows, baseline):
    # Choice labels are deliberately semantically disjoint. A token filter is
    # only a conservative first pass; reviewers still inspect all three labels.
    bank = [r for r in baseline if r['罕度'] in {'0', '1', '2'} and len(r['英文释义']) <= 90 and len(r['组词']) >= 2]
    dictionary = load(ROOT / '.gradle/wordlist-implementation/dictionary-index.json')
    @functools.lru_cache(maxsize=20000)
    def semantic(word, reading, english):
        values = tokens(english)
        for entry in dictionary.get(word, []):
            if reading_key(marked_pinyin(entry['pinyin'])) != reading_key(reading):
                continue
            for gloss in glosses(entry, dictionary):
                values |= tokens(gloss)
        return values - {'describe', 'idiom', 'sb', 'sth', 'something', 'somebody', 'thing', 'bound', 'form'}
    def category(pos):
        return next((key for key in ['pronoun', 'adverb', 'adjective', 'noun', 'verb', 'conjunction'] if key in pos), pos)
    for row in rows:
        if row['干扰词ID']:
            continue
        seed = int(hashlib.sha256(row['组词'].encode('utf-8')).hexdigest()[:8], 16)
        preferred = [r for r in bank if category(r['词性']) == category(row['词性'])]
        choices = preferred if len(preferred) >= 20 else bank
        ordered = choices[seed % len(choices):] + choices[:seed % len(choices)]
        meanings, labels, chosen = semantic(row['组词'], row['拼音'], row['英文释义']), {row['英文释义'].casefold()}, []
        for option in ordered:
            label = option['英文释义'].casefold()
            option_semantic = semantic(option['组词'], option['拼音'], label)
            if label in labels or option_semantic & meanings:
                continue
            labels.add(label)
            meanings |= option_semantic
            chosen.append(option['词条ID'])
            if len(chosen) == 3:
                break
        if len(chosen) != 3:
            raise ValueError('Cannot select three distinct distractors: ' + row['组词'])
        row['干扰词ID'] = chosen


def apply(paths):
    input_path = DATA / 'entries.json'
    input_sha = sha(input_path)
    original = load(input_path)['entries']
    rows = copy.deepcopy(original)
    indexed = {r['词条ID']: r for r in rows}
    touched = set()
    for path in paths:
        review = load(path)
        if review.get('inputSha256') != input_sha:
            raise ValueError('Editorial decisions refer to another snapshot: ' + str(path))
        for word_id, changes in review.get('corrections', {}).items():
            if word_id not in indexed or not isinstance(changes, dict) or not set(changes) <= ALLOWED:
                raise ValueError('Unknown ID or protected editorial field: ' + word_id)
            for field, value in changes.items():
                if (word_id, field) in touched:
                    raise ValueError('Conflicting independent corrections: ' + word_id + ' ' + field)
                touched.add((word_id, field))
                indexed[word_id][field] = value
        if review.get('rejections'):
            raise ValueError('Resolve rejected candidates before applying decisions: ' + str(path))
    save(input_path, {'entries': rows})
    print(json.dumps({'inputSha256': input_sha, 'resultSha256': sha(input_path), 'correctedFields': len(touched), 'rows': len(rows)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    p = sub.add_parser('packet')
    p.add_argument('start', type=int)
    p.add_argument('count', type=int)
    g = sub.add_parser('authored-packet')
    g.add_argument('source', choices=['activity', 'family', 'services'])
    g.add_argument('start', type=int)
    g.add_argument('count', type=int)
    f = sub.add_parser('final-packet')
    f.add_argument('reviewer', choices=['activity', 'family', 'services'])
    f.add_argument('start', type=int)
    f.add_argument('count', type=int)
    a = sub.add_parser('apply')
    a.add_argument('paths', nargs='+', type=Path)
    args = parser.parse_args()
    if args.mode in {'packet', 'authored-packet', 'final-packet'}:
        if args.start < 0 or not 1 <= args.count <= 100:
            raise ValueError('Review packets use non-negative offsets and 1–100 records')
        if args.mode == 'packet':
            packet(args.start, args.count)
        elif args.mode == 'authored-packet':
            authored_packet(args.source, args.start, args.count)
        else:
            final_packet(args.reviewer, args.start, args.count)
    else:
        apply(args.paths)
