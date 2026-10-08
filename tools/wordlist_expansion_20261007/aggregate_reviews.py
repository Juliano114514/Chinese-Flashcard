"""Bind completed editorial evidence and exact approved overlays to final entries.

This performs source, coverage and application checks, not another language pass.
It writes reviews.json only; freeze.py publishes the separate frozen overlay.
"""
from __future__ import annotations

import copy
import json

from merge_service_reviews import DATA, read_json, check_ranges, decision_map, check_corrections


PREPARED_SHA = '52d04daf55e8a22002c8a520153872ccbe172dfde6ddaaae8dfa26dadafa8397'
FINAL_SHA = '8fd3c0f7c2b12ee4122878235e22aff5e6f42a2dd9c075eabe4c2fd4bea7f66d'
INITIAL_SHA = '4bdebf861724df02043412da291fe988b2746dd6641033b0294710aa6a0e8026'
CORRECTIONS_SHA = '071907bdcd4c595788e147dd937d4ba5821685c16db06a045c53b4649e58ce2a'
OLD_IDS = {'wl_06211', 'wl_06550'}


def exact_ids(covered, expected, name):
    if covered != expected or len(set(covered)) != len(covered):
        raise ValueError('Missing, duplicated or unordered coverage: ' + name)


def aggregate():
    prepared, prepared_sha = read_json(DATA / 'entries.json')
    final, final_sha = read_json(DATA / 'entries.final-review.json')
    initial, initial_sha = read_json(DATA / 'entries.initial.json')
    corrections, corrections_sha = read_json(DATA / 'corrections.json')
    snapshot, _ = read_json(DATA / 'final-review-snapshot.json')
    first_snapshot, _ = read_json(DATA / 'initial-review-snapshot.json')
    root_proposals, root_sha = read_json(DATA / 'root-final-corrections.json')
    retained, retained_sha = read_json(DATA / 'retained-drafts.json')
    authored, _ = read_json(DATA / 'authored-drafts.json')
    if (prepared_sha != PREPARED_SHA or final_sha != FINAL_SHA or initial_sha != INITIAL_SHA or
            corrections_sha != CORRECTIONS_SHA or set(corrections) != OLD_IDS or
            snapshot['entriesSha256'] != final_sha or snapshot['correctionsSha256'] != corrections_sha or
            first_snapshot['entriesSha256'] != initial_sha or root_proposals['inputSha256'] != final_sha):
        raise ValueError('Fixed prepared/review inputs or old correction scope changed')
    rows, old_rows = prepared['entries'], final['entries']
    ids = [row['词条ID'] for row in rows]
    if len(rows) != 4000 or len(set(ids)) != 4000 or ids != [row['词条ID'] for row in old_rows]:
        raise ValueError('The prepared 4000-entry identity/order differs from the fixed shadow')
    if any(len(row['例句JSON']) != 2 or len(set(row['干扰词ID'])) != 3 for row in rows):
        raise ValueError('Expected 8000 examples and 12000 distinct-per-entry choice references')

    source_entries = retained['entries']
    if len(source_entries) != 2647 or len(authored['entries']) != 1491:
        raise ValueError('Source or author draft counts changed')
    source_covered, source_rejected = set(), set()
    for name, start, end in [('services', 0, 900), ('family', 900, 1800), ('activity', 1800, 2647)]:
        filename = f'source_primary_{name}.json'
        report, _ = read_json(DATA / filename)
        expected = [item['word'] for item in source_entries[start:end]]
        if report.get('complete') is not True or report.get('inputSha256') != retained_sha:
            raise ValueError('Incomplete or changed full source review: ' + filename)
        check_ranges(report, start, end, filename)
        exact_ids(report['coveredWords'], expected, filename)
        decisions = decision_map(report, 'decisions', set(expected), filename)
        rejected = decision_map(report, 'rejections', set(expected), filename)
        if source_covered & set(expected) or not set(expected) - set(rejected) <= set(decisions):
            raise ValueError('Overlapping or missing source decisions: ' + filename)
        for word in set(expected) - set(rejected):
            decision = decisions[word]
            if decision.get('rarity') not in {'0', '1', '2', '3', '4'} or not decision.get('pos') or not decision.get('reason'):
                raise ValueError('Missing per-word source stage/POS evidence: ' + word)
        source_covered.update(expected)
        source_rejected.update(rejected)
    author_covered = set()
    for name in ['activity', 'family', 'services']:
        filename = f'authored_cross_{name}.json'
        report, _ = read_json(DATA / filename)
        expected = first_snapshot['sources'][name + '_authoring.tsv']
        if (report.get('complete') is not True or report.get('inputSha256') != initial_sha or
                report.get('reviewedEntries') != len(expected) or report.get('reviewedExamples') != 2 * len(expected)):
            raise ValueError('Incomplete authored full-record review: ' + filename)
        exact_ids(report['coveredIds'], expected, filename)
        if author_covered & set(expected):
            raise ValueError('Duplicate authored cross-review ownership')
        for key in ['corrections', 'rejections', 'decisions', 'reviewDecisions']:
            decision_map(report, key, set(expected), filename)
        check_corrections(report.get('corrections', {}), filename)
        author_covered.update(expected)
    if len(source_covered) != 2647 or len(author_covered) != 1491:
        raise ValueError('Incomplete first full-record editorial coverage')

    indexed = {row['词条ID']: row for row in rows}
    overlays, touched, confirmed, approved_root, old_confirmed = {}, set(), set(), set(), set()
    resolved, owners, final_reports, declaration_evidence = [], {}, {}, {}
    for name in ['services', 'family', 'activity']:
        filename = f'final-confirmation-{name}.json'
        report, _ = read_json(DATA / filename)
        expected = snapshot['groups'][name]
        if (report.get('complete') is not True or report.get('inputSha256') != final_sha or
                report.get('correctionsSha256') != corrections_sha or
                report.get('reviewedEntries') != len(expected) or report.get('reviewedExamples') != len(expected) * 2):
            raise ValueError('Incomplete final changed-field/choice confirmation: ' + filename)
        stated_choices = report.get('reviewedChoices')
        choice_statement = report.get('method', '') + ' ' + report.get('boundary', '')
        if stated_choices is not None:
            if type(stated_choices) is not int or stated_choices != len(expected) * 3:
                raise ValueError('Incorrect explicit choice counter: ' + filename)
        elif 'all three choice labels' not in choice_statement and 'all three distractor labels' not in choice_statement:
            raise ValueError('Missing explicit complete choice-review statement: ' + filename)
        exact_ids(report['coveredIds'], expected, filename)
        if confirmed & set(expected):
            raise ValueError('Overlapping final confirmation groups')
        confirmed.update(expected)
        own_patches = decision_map(report, 'corrections', set(expected), filename)
        check_corrections(own_patches, filename)
        for wid, fields in own_patches.items():
            for field, value in fields.items():
                if (wid, field) in touched or indexed[wid][field] != value:
                    raise ValueError('Conflicting or unapplied approved field: ' + wid + ' / ' + field)
                touched.add((wid, field))
                overlays.setdefault(wid, {})[field] = value
        for issue in report.get('issues', []):
            wid, field = issue.get('id'), issue.get('field')
            if field not in own_patches.get(wid, {}) or indexed[wid][field] != own_patches[wid][field]:
                raise ValueError('Unresolved source finding: ' + filename)
            resolved.append({'source': filename, 'finding': issue, 'status': 'exact approved field applied'})
        stated_root_sha = report.get('approvedRootCorrectionsSha256')
        if stated_root_sha is not None and stated_root_sha != root_sha:
            raise ValueError('Changed root proposal approval: ' + filename)
        own_approved = report.get('approvedRootCorrectionIds', [])
        if len(own_approved) != len(set(own_approved)) or not set(own_approved) <= set(expected):
            raise ValueError('Root proposal approval escapes its final group')
        approved_root.update(own_approved)
        own_old = report.get('coveredCorrectionIds', [])
        if len(own_old) != len(set(own_old)) or not set(own_old) <= OLD_IDS or old_confirmed & set(own_old):
            raise ValueError('Duplicate or invalid old correction confirmation')
        old_confirmed.update(own_old)
        for wid in expected:
            owners[wid] = report.get('confirmingReviewer', {}).get(wid, name)
        final_reports[name] = filename
        declaration_evidence[name] = {
            'explicitChoiceCounter': stated_choices,
            'choiceCountFromPreciselyCoveredRecords': len(expected) * 3,
            'choiceReviewStatement': choice_statement.strip(),
            'explicitRootProposalSha256': stated_root_sha,
            'approvedRootCorrectionIds': own_approved,
            'boundary': 'Missing numeric counters or proposal-file hashes are not represented as verified declarations. Explicit review statements and approved ID sets are retained; this aggregation binds the actual reports, proposal file and exactly applied fields.'}
    root_fields = root_proposals['corrections']
    if confirmed != set(ids) or approved_root != set(root_fields) or len(root_fields) != 15 or old_confirmed != OLD_IDS:
        raise ValueError('Final groups, root15 or old2 confirmations are incomplete')
    check_corrections(root_fields, 'root-final-corrections.json')
    for wid, fields in root_fields.items():
        for field, value in fields.items():
            if (wid, field) in touched or indexed[wid][field] != value:
                raise ValueError('Conflicting or unapplied root field: ' + wid + ' / ' + field)
            touched.add((wid, field))
            overlays.setdefault(wid, {})[field] = value
    recomposed = copy.deepcopy(old_rows)
    for row in recomposed:
        row.update(overlays.get(row['词条ID'], {}))
    actual_diff = {(old['词条ID'], field) for old, new in zip(old_rows, rows)
                   for field in old if old[field] != new[field]}
    if recomposed != rows or actual_diff != touched or len(touched) != 30 or len(overlays) != 28:
        raise ValueError('The final array differs from its exact 30-field/28-record approved overlays')

    excluded = {'reviews.json', 'validation.json', 'aggregation-validation.json'}
    sources = {path.name: read_json(path)[1] if path.suffix == '.json' else file_sha(path)
               for path in sorted(DATA.iterdir()) if path.is_file() and path.suffix in {'.json', '.py', '.tsv', '.txt', '.md'}
               and path.name not in excluded}
    common = {'coveredIds': ids, 'reviewedEntries': 4000, 'reviewedExamples': 8000,
              'issues': [], 'coveredCorrectionIds': sorted(OLD_IDS)}
    review = {
        'entriesSha256': prepared_sha, 'correctionsSha256': corrections_sha,
        'sourceSha256': sources, 'baseFinalReviewSha256': final_sha,
        'primary': dict(common, reviewKind='author-input-source-and-structural-integrity',
            boundary='Author inputs, retained-source provenance, exact application of approved fields and complete structural integrity checked. The two evidenced old corrections were read by root. This is not a second independent full language review.'),
        'independent': dict(common, reviewedChoices=12000, confirmingReviewer=owners,
            boundary='Complete first independent reading of 2647 source records and 1491 authored generated records, followed by all selected 4000 final changed-field/actual-choice confirmations. Unchanged already-read fields are reused exactly; final approved overlays are precisely applied. Not human linguistic certification.'),
        'editorialEvidence': {'retainedFullyCovered': 2647, 'authoredFullyCrossReviewed': 1491,
            'rejectedSourceWords': sorted(source_rejected), 'selectedFinalEntries': 4000,
            'finalChoiceLabelsReviewed': 12000, 'rootProposalRecordsApproved': 15,
            'approvedOverlayFields': 30, 'approvedOverlayRecords': 28,
            'appliedResolvedFindings': resolved, 'finalConfirmations': final_reports,
            'actualDeclarationEvidence': declaration_evidence},
        'boundary': 'Coverage, source hashes and exact approved overlays are machine-checked. Author/source/structural checks and one complete independent editorial pass plus final deltas are distinct evidence; no additional language review or device/import/build proof is claimed.',
    }
    (DATA / 'reviews.json').write_text(json.dumps(review, ensure_ascii=False, indent=2) + '\n',
                                      encoding='utf-8', newline='\n')
    print(json.dumps({'entries': 4000, 'examples': 8000, 'choices': 12000, 'sourceRecords': 2647,
                      'authoredRecords': 1491, 'approvedOverlayFields': 30,
                      'approvedOverlayRecords': 28, 'boundSources': len(sources)}, ensure_ascii=False))


def file_sha(path):
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == '__main__':
    aggregate()
