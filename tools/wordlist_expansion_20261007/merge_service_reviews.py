"""Merge the two independently read service partitions without inventing decisions.

Run only after both reviewers finish their fixed initial-snapshot partitions.
--check validates the inputs without replacing the aggregate review report.
This script never changes vocabulary drafts, snapshots, corrections or the CSV.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


DATA = Path(__file__).resolve().parent
INITIAL_SHA256 = '4bdebf861724df02043412da291fe988b2746dd6641033b0294710aa6a0e8026'
MAX_JSON_BYTES = 32 * 1024 * 1024
PARTITIONS = (
    ('services-cross-prefix.json', 0, 252, 'family'),
    ('services-cross-suffix.json', 252, 492, 'activity'),
)
ALLOWED_FIELDS = {
    '罕度', '拼音', '英文释义', '词性', '例句JSON', '部件JSON',
    '本义解释JSON', '引申义解释JSON', '干扰词ID',
}
JSON_FIELDS = {'例句JSON', '部件JSON', '本义解释JSON', '引申义解释JSON', '干扰词ID'}


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('Duplicate JSON key: ' + key)
        value[key] = item
    return value


def reject_constant(value):
    raise ValueError('Nonstandard JSON numeric constant: ' + value)


def read_json(path):
    with path.open('rb') as stream:
        raw = stream.read(MAX_JSON_BYTES + 1)
    if len(raw) > MAX_JSON_BYTES:
        raise ValueError('Review input exceeds 32 MiB: ' + path.name)
    value = json.loads(raw.decode('utf-8-sig'), object_pairs_hook=unique_object,
                       parse_constant=reject_constant)
    if not isinstance(value, dict):
        raise ValueError('Expected a JSON object: ' + path.name)
    return value, hashlib.sha256(raw).hexdigest()


def decision_map(report, name, expected, filename):
    value = report.get(name, {})
    if not isinstance(value, dict) or not set(value) <= expected:
        raise ValueError('Decision keys escape the partition: ' + filename + ' / ' + name)
    return value


def check_ranges(report, first, last, filename):
    if 'range' in report:
        pair = report['range']
        if (not isinstance(pair, list) or len(pair) != 2 or
                any(type(index) is not int for index in pair) or pair != [first, last]):
            raise ValueError('Wrong partition range: ' + filename)
    ranges = report.get('reviewedRanges')
    if ranges is None:
        if report.get('range') != [first, last]:
            raise ValueError('Missing explicit reviewed range: ' + filename)
        return [[first, last]]
    if not isinstance(ranges, list) or not ranges:
        raise ValueError('Missing reviewed ranges: ' + filename)
    cursor = first
    for pair in ranges:
        if (not isinstance(pair, list) or len(pair) != 2 or
                any(type(index) is not int for index in pair) or
                pair[0] != cursor or not cursor < pair[1] <= last):
            raise ValueError('Overlapping, missing or unordered reviewed range: ' + filename)
        cursor = pair[1]
    if cursor != last:
        raise ValueError('Incomplete reviewed ranges: ' + filename)
    return ranges


def check_corrections(corrections, filename):
    for word_id, changes in corrections.items():
        if not isinstance(changes, dict) or not changes or not set(changes) <= ALLOWED_FIELDS:
            raise ValueError('Protected or empty field patch: ' + filename + ' / ' + word_id)
        for field, value in changes.items():
            if field in JSON_FIELDS:
                if not isinstance(value, list):
                    raise ValueError('Expected decoded JSON list: ' + word_id + ' / ' + field)
                if field == '例句JSON' and len(value) != 2:
                    raise ValueError('Expected two reviewed examples: ' + word_id)
            elif not isinstance(value, str) or not value.strip():
                raise ValueError('Expected nonempty teaching text: ' + word_id + ' / ' + field)
            if field == '罕度' and value not in {'0', '1', '2', '3', '4'}:
                raise ValueError('Invalid reviewed stage: ' + word_id)


def prepare_merge():
    initial, initial_sha = read_json(DATA / 'entries.initial.json')
    snapshot, snapshot_sha = read_json(DATA / 'initial-review-snapshot.json')
    if initial_sha != INITIAL_SHA256 or snapshot.get('entriesSha256') != initial_sha:
        raise ValueError('The fixed initial review snapshot changed')
    rows = initial.get('entries')
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise ValueError('Invalid initial entry records')
    if type(snapshot.get('totalRows')) is not int or snapshot['totalRows'] != len(rows):
        raise ValueError('Initial snapshot row counter differs from its entries')
    row_ids = [row.get('词条ID') for row in rows]
    if any(not isinstance(word_id, str) for word_id in row_ids) or len(set(row_ids)) != len(rows):
        raise ValueError('Initial entry IDs are missing or duplicated')
    sources = snapshot.get('sources', {})
    if not isinstance(sources, dict):
        raise ValueError('Invalid initial source groups')
    ids = sources.get('services_authoring.tsv')
    if (not isinstance(ids, list) or len(ids) != 492 or
            any(not isinstance(word_id, str) for word_id in ids) or
            len(set(ids)) != len(ids) or not set(ids) <= set(row_ids)):
        raise ValueError('Expected exactly 492 unique initial service IDs')
    indexed = {row['词条ID']: row for row in rows}
    if any(len(indexed[word_id].get('例句JSON', [])) != 2 for word_id in ids):
        raise ValueError('Each service entry must have two initial examples')

    covered, corrections, rejections, decisions, reviewers = [], {}, {}, {}, {}
    partitions, source_hashes, touched = [], {}, set()
    for filename, first, last, reviewer in PARTITIONS:
        report, report_sha = read_json(DATA / filename)
        expected_ids = ids[first:last]
        expected = set(expected_ids)
        count = last - first
        if (report.get('complete') is not True or report.get('source') != 'services' or
                report.get('inputSha256') != initial_sha or report.get('coveredIds') != expected_ids or
                type(report.get('reviewedEntries')) is not int or report['reviewedEntries'] != count or
                type(report.get('reviewedExamples')) is not int or report['reviewedExamples'] != count * 2):
            raise ValueError('Incomplete, changed or incorrectly counted service partition: ' + filename)
        if 'reviewer' in report and report['reviewer'] != reviewer:
            raise ValueError('Wrong independent reviewer: ' + filename)
        ranges = check_ranges(report, first, last, filename)
        patches = decision_map(report, 'corrections', expected, filename)
        rejected = decision_map(report, 'rejections', expected, filename)
        check_corrections(patches, filename)
        if any(not isinstance(reason, str) or not reason.strip() for reason in rejected.values()):
            raise ValueError('Every rejection requires its recorded reason: ' + filename)
        if set(patches) & set(rejected):
            raise ValueError('A rejected entry also has a field patch: ' + filename)
        for name in ['decisions', 'reviewDecisions']:
            for word_id, decision in decision_map(report, name, expected, filename).items():
                if not isinstance(decision, dict):
                    raise ValueError('Expected an explicit decision object: ' + filename + ' / ' + word_id)
                target = decisions.setdefault(word_id, {})
                for key, value in decision.items():
                    if key in target and target[key] != value:
                        raise ValueError('Conflicting recorded decisions: ' + word_id + ' / ' + key)
                    target[key] = value
        for word_id, changes in patches.items():
            target = corrections.setdefault(word_id, {})
            for field, value in changes.items():
                if (word_id, field) in touched:
                    raise ValueError('Conflicting field patches: ' + word_id + ' / ' + field)
                touched.add((word_id, field))
                target[field] = value
        for word_id, reason in rejected.items():
            if word_id in rejections:
                raise ValueError('Duplicate rejection: ' + word_id)
            rejections[word_id] = reason
        for word_id in expected_ids:
            if word_id in reviewers:
                raise ValueError('Duplicate independent coverage: ' + word_id)
            reviewers[word_id] = reviewer
        covered.extend(expected_ids)
        source_hashes[filename] = report_sha
        partitions.append({'source': filename, 'sha256': report_sha, 'inputSha256': initial_sha,
                           'reviewer': reviewer, 'range': [first, last], 'reviewedRanges': ranges,
                           'reviewedEntries': count, 'reviewedExamples': count * 2})
    if covered != ids or set(reviewers) != set(ids):
        raise ValueError('Combined service coverage has missing, duplicate or misplaced IDs')
    return {
        'inputSha256': initial_sha, 'source': 'services', 'range': [0, 492],
        'coveredIds': covered, 'corrections': corrections, 'rejections': rejections,
        'reviewDecisions': decisions, 'complete': True,
        'reviewedRanges': [[0, 252], [252, 492]], 'reviewedEntries': 492, 'reviewedExamples': 984,
        'partitions': partitions, 'sources': source_hashes,
        'initialSourceSnapshotSha256': snapshot_sha, 'independentReviewers': reviewers,
        'method': 'Strict union of family review [0,252) and activity review [252,492), both bound '
                  'to the same immutable initial service records. Optional per-ID judgments are '
                  'preserved; absent judgments and reasons are never generated by this merger.',
        'boundary': 'Both partition authors independently read complete generated service records, '
                    'including both examples and actual choices. The merger verifies hashes, '
                    'exact coverage, counters and field boundaries only; it performs no language '
                    'review and creates no new approval. Not human linguistic certification.',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Validate only; do not replace the aggregate.')
    args = parser.parse_args()
    report = prepare_merge()
    if not args.check:
        output = DATA / 'authored_cross_services.json'
        temporary = output.with_suffix('.json.tmp')
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n',
                             encoding='utf-8', newline='\n')
        temporary.replace(output)
    print(json.dumps({'status': 'VALIDATED' if args.check else 'MERGED',
                      'reviewedEntries': report['reviewedEntries'],
                      'reviewedExamples': report['reviewedExamples'],
                      'partitions': report['sources']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
