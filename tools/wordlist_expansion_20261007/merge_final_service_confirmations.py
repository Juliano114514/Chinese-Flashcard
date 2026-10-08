"""Strictly merge the three completed final service confirmation partitions.

Reuse the bounded JSON, range and teaching-field guards from the cross-review
merger. --check is read-only; neither mode changes fixed snapshots or the CSV.
"""
from __future__ import annotations

import argparse
import json

from merge_service_reviews import DATA, read_json, check_ranges, decision_map, check_corrections


FINAL_SHA256 = '8fd3c0f7c2b12ee4122878235e22aff5e6f42a2dd9c075eabe4c2fd4bea7f66d'
CORRECTIONS_SHA256 = '071907bdcd4c595788e147dd937d4ba5821685c16db06a045c53b4649e58ce2a'
OLD_CORRECTION_IDS = ['wl_06211', 'wl_06550']
PARTITIONS = (
    ('final-services-prefix.json', 0, 600, 'services'),
    ('final-services-middle.json', 600, 1000, 'family'),
    ('final-services-suffix.json', 1000, 1373, 'activity'),
)


def unique_ids(value, allowed, name):
    if (not isinstance(value, list) or any(not isinstance(item, str) for item in value) or
            len(set(value)) != len(value) or not set(value) <= allowed):
        raise ValueError('Invalid, duplicate or out-of-scope IDs: ' + name)
    return value


def prepare_merge():
    final, final_sha = read_json(DATA / 'entries.final-review.json')
    old_corrections, corrections_sha = read_json(DATA / 'corrections.json')
    snapshot, snapshot_sha = read_json(DATA / 'final-review-snapshot.json')
    root_proposals, root_sha = read_json(DATA / 'root-final-corrections.json')
    if (final_sha != FINAL_SHA256 or corrections_sha != CORRECTIONS_SHA256 or
            snapshot.get('entriesSha256') != final_sha or
            snapshot.get('correctionsSha256') != corrections_sha or
            root_proposals.get('inputSha256') != final_sha or
            set(old_corrections) != set(OLD_CORRECTION_IDS)):
        raise ValueError('A fixed final snapshot or its bound corrections changed')
    rows = final.get('entries')
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise ValueError('Invalid final entry records')
    row_ids = [row.get('词条ID') for row in rows]
    unique_ids(row_ids, set(item for item in row_ids if isinstance(item, str)), 'final records')
    groups = snapshot.get('groups', {})
    if not isinstance(groups, dict):
        raise ValueError('Invalid final reviewer groups')
    ids = unique_ids(groups.get('services'), set(row_ids), 'services reviewer group')
    if len(ids) != 1373:
        raise ValueError('Expected exactly 1373 final service IDs')
    proposed = root_proposals.get('corrections', {})
    if not isinstance(proposed, dict):
        raise ValueError('Invalid root proposal corrections')
    allowed_root = set(proposed) & set(ids)
    check_corrections({wid: proposed[wid] for wid in allowed_root}, 'root-final-corrections.json')

    covered, patches, confirming, sources, partitions = [], {}, {}, {}, []
    approved, deltas, resolved = set(), set(), {}
    for filename, first, last, reviewer in PARTITIONS:
        report, report_sha = read_json(DATA / filename)
        expected_ids, count = ids[first:last], last - first
        expected = set(expected_ids)
        if (report.get('complete') is not True or report.get('inputSha256') != final_sha or
                report.get('correctionsSha256') != corrections_sha or
                report.get('coveredIds') != expected_ids or report.get('issues') != [] or
                type(report.get('reviewedEntries')) is not int or report['reviewedEntries'] != count or
                type(report.get('reviewedExamples')) is not int or report['reviewedExamples'] != count * 2):
            raise ValueError('Incomplete, changed or unresolved final confirmation: ' + filename)
        if 'reviewer' in report and report['reviewer'] != reviewer:
            raise ValueError('Incorrect confirming reviewer: ' + filename)
        ranges = check_ranges(report, first, last, filename)
        if report.get('coveredCorrectionIds') != (OLD_CORRECTION_IDS if first == 0 else []):
            raise ValueError('Old correction confirmation belongs only to the prefix: ' + filename)
        own_root = unique_ids(report.get('approvedRootCorrectionIds', []), allowed_root,
                              filename + ' / approvedRootCorrectionIds')
        if own_root and report.get('approvedRootCorrectionsSha256') != root_sha:
            raise ValueError('Root proposal approval refers to another file: ' + filename)
        approved.update(own_root)
        own_delta = unique_ids(report.get('deltaReviewedIds', []), expected | set(own_root),
                               filename + ' / deltaReviewedIds')
        deltas.update(own_delta)
        changes = decision_map(report, 'corrections', expected, filename)
        check_corrections(changes, filename)
        for wid, fields in changes.items():
            if wid in patches:
                raise ValueError('Overlapping or conflicting final field patches: ' + wid)
            patches[wid] = fields
        for key in ['decisions', 'reviewDecisions', 'rejections']:
            decision_map(report, key, expected, filename)
        if report.get('rejections'):
            raise ValueError('Final confirmations cannot silently reject selected entries: ' + filename)
        for wid in expected_ids:
            if wid in confirming:
                raise ValueError('Duplicate final coverage: ' + wid)
            confirming[wid] = reviewer
        covered.extend(expected_ids)
        sources[filename] = report_sha
        notes = ['resolvedIssues', 'resolvedChoiceNotes', 'resolvedWithProposedCorrections']
        if any(report.get(key) for key in notes):
            resolved[filename] = {key: report[key] for key in notes if key in report}
        partitions.append({'source': filename, 'sha256': report_sha, 'reviewer': reviewer,
                           'range': [first, last], 'reviewedRanges': ranges,
                           'reviewedEntries': count, 'reviewedExamples': count * 2})
    if covered != ids or set(confirming) != set(ids) or approved != allowed_root:
        raise ValueError('Combined final coverage or root proposal confirmations are incomplete')
    for wid in approved & set(patches):
        if set(proposed[wid]) & set(patches[wid]):
            raise ValueError('Root and partition corrections target the same field: ' + wid)
    return {
        'inputSha256': final_sha, 'correctionsSha256': corrections_sha, 'source': 'services',
        'range': [0, 1373], 'coveredIds': covered, 'coveredCorrectionIds': OLD_CORRECTION_IDS,
        'reviewedEntries': 1373, 'reviewedExamples': 2746, 'reviewedChoices': 4119,
        'reviewedRanges': [[0, 600], [600, 1000], [1000, 1373]],
        'complete': True, 'issues': [], 'corrections': patches,
        'approvedRootCorrectionIds': sorted(approved), 'approvedRootCorrectionsSha256': root_sha,
        'deltaReviewedIds': sorted(deltas), 'confirmingReviewer': confirming,
        'sources': sources, 'partitions': partitions, 'resolvedFindings': resolved,
        'finalSourceSnapshotSha256': snapshot_sha,
        'boundary': 'Prior independent full-record review plus exact final changed-field and '
                    'actual choice-label confirmation in three recorded partitions. Counters '
                    'cover prior records and final deltas; unchanged teaching fields are not '
                    'reread. This merger verifies provenance and coverage and performs no new '
                    'language review. Not human linguistic certification.',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Validate only; do not write the aggregate.')
    args = parser.parse_args()
    report = prepare_merge()
    if not args.check:
        output = DATA / 'final-confirmation-services.json'
        temporary = output.with_suffix('.json.tmp')
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n',
                             encoding='utf-8', newline='\n')
        temporary.replace(output)
    print(json.dumps({'status': 'VALIDATED' if args.check else 'MERGED',
                      'reviewedEntries': report['reviewedEntries'],
                      'reviewedExamples': report['reviewedExamples'],
                      'correctedEntries': len(report['corrections']), 'partitions': report['sources']}))


if __name__ == '__main__':
    main()
