"""Publish the selected vocabulary attribution indexes; never bundle raw corpora."""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'tools/vocabulary_v2'
ASSETS = ROOT / 'core/data/src/main/assets/wordlist-sources'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')


def id_ranges(values):
    numbers = sorted(int(value[3:]) for value in set(values))
    ranges = []
    for number in numbers:
        if ranges and number == ranges[-1][-1] + 1:
            ranges[-1].append(number)
        else:
            ranges.append([number])
    return ', '.join(f'wl_{group[0]:05d}' if len(group) == 1 else
                     f'wl_{group[0]:05d}–wl_{group[-1]:05d}' for group in ranges)


def publish(check=False):
    legacy = read(DATA / 'legacy-source-notes.json')
    provenance = read(DATA / 'example-provenance.json')
    refinements_path = DATA / 'refinement-example-provenance.json'
    refined_provenance = read(refinements_path) if refinements_path.exists() else {}
    report = read(DATA / 'expansion-report.json')
    lock = read(DATA / 'source-lock.json')
    refinement_lock = DATA / 'source-lock-refinement.json'
    if refinement_lock.exists():
        lock = {**lock, 'sources': [*lock['sources'], *read(refinement_lock)['sources']]}
    vocabulary_path = ROOT / 'wordlist.csv'
    if hashlib.sha256(vocabulary_path.read_bytes()).hexdigest() != report['sha256']:
        raise ValueError('Publish source attribution only after the formal wordlist matches its report')
    vocabulary = list(csv.DictReader(vocabulary_path.open(encoding='utf-8-sig')))
    example_english = {row['词条ID']: {ex['hanzi']: ex['english'] for ex in json.loads(row['例句JSON'])}
                       for row in vocabulary}
    vocabulary_ids = {row['词条ID'] for row in vocabulary}
    if len(vocabulary) != report['totalWords'] or not set(legacy) <= vocabulary_ids:
        raise ValueError('Vocabulary source index would omit retained entries')
    if set(provenance) != vocabulary_ids - set(legacy):
        raise ValueError('Every added entry must have an example attribution record')
    if set(refined_provenance) - vocabulary_ids:
        raise ValueError('Refinement attribution refers to an absent word ID')
    provenance = {**provenance, **refined_provenance}
    source_counts = dict(collections.Counter(
        'legacy' if example['source'].startswith(('legacy:', 'legacy-context:')) else
        'Tatoeba' if 'tatoeba.org' in example['source'] or 'Tatoeba #' in example['source'] else
        'project-original' for examples in provenance.values() for example in examples))
    for row in vocabulary:
        if row['词条ID'] in provenance:
            actual = collections.Counter(example['hanzi'] for example in json.loads(row['例句JSON']))
            attributed = collections.Counter(example['hanzi'] for example in provenance[row['词条ID']])
            if actual != attributed:
                raise ValueError('Example attribution differs from the selected sentence: ' + row['词条ID'])
    notes = list(dict.fromkeys(legacy.values()))
    note_indexes = {note: index for index, note in enumerate(notes)}
    legacy_index = {word_id: note_indexes[note] for word_id, note in legacy.items()}
    baseline = ROOT / '.gradle/vocabulary-v2/baseline.csv'
    origins_path = DATA / 'legacy-context-origins.json'
    saved_origins = read(origins_path) if origins_path.exists() else {}
    old_contexts = collections.defaultdict(set)
    for text, ids in saved_origins.items():
        old_contexts[text].update(ids)
    if baseline.exists():
        for row in csv.DictReader(baseline.open(encoding='utf-8-sig')):
            if row['词条ID'] in legacy:
                for example in json.loads(row['例句JSON']):
                    old_contexts[example['hanzi']].add(row['词条ID'])
    contexts = {}
    for word_id, examples in provenance.items():
        for example in examples:
            text, source = example['hanzi'], example['source']
            original_text = example.get('originalHanzi')
            original_english = example.get('originalEnglish')
            english = example_english[word_id][text]
            modification = example.get('modification', 'editorial translation correction' if original_english else
                                       'script normalization' if original_text else None)
            key = (text, source, english)
            if key not in contexts:
                previous = []
                if source.startswith('legacy:'):
                    previous = [source.split(':', 1)[1]]
                elif source.startswith('legacy-context:'):
                    previous = sorted(old_contexts[text])
                    if not previous:
                        raise ValueError('Unresolved retained context: ' + word_id)
                elif source.startswith('CC-BY 2.0 (France) Attribution:'):
                    if 'tatoeba.org #' not in source or ' & #' not in source:
                        raise ValueError('Missing Tatoeba sentence attribution: ' + word_id)
                elif source.startswith('CC-BY 2.0 France: Tatoeba #'):
                    import re
                    if not re.fullmatch(r'CC-BY 2\.0 France: Tatoeba #[0-9]+ \([^\r\n]+\) / #[0-9]+ \([^\r\n]+\)', source):
                        raise ValueError('Missing Tatoeba sentence IDs/authors: ' + word_id)
                elif 'Project-authored' not in source and not source.startswith('original:'):
                    raise ValueError('Unrecognized example attribution: ' + word_id)
                if any(value not in legacy_index for value in previous):
                    raise ValueError('Unknown retained vocabulary attribution: ' + word_id)
                contexts[key] = {'hanzi': text, 'english': english, 'source': source, 'wordIds': [], 'legacyWordIds': previous}
                if original_text is not None:
                    if not isinstance(original_text, str) or not original_text or original_text == text:
                        raise ValueError('Invalid original sentence script: ' + word_id)
                    contexts[key]['originalHanzi'] = original_text
                    contexts[key]['modification'] = modification
                if original_english is not None:
                    if not isinstance(original_english, str) or not original_english or original_english == english:
                        raise ValueError('Invalid original translation: ' + word_id)
                    contexts[key]['originalEnglish'] = original_english
                    contexts[key]['modification'] = modification
            elif (contexts[key].get('originalHanzi') != original_text or
                  contexts[key].get('originalEnglish') != original_english):
                raise ValueError('Inconsistent original script for the same attributed context: ' + word_id)
            contexts[key]['wordIds'].append(word_id)
    index = {'schemaVersion': 1, 'modifiedOn': '2026-10-27', 'wordlistSha256': report['sha256'],
             'totalWords': report['totalWords'], 'legacyNotes': notes,
             'legacyEntries': legacy_index, 'exampleContexts': list(contexts.values()),
             'selectedExampleSourceUses': source_counts,
             'scriptNormalizedExampleUses': sum('originalHanzi' in example and not example.get('modification') and 'originalEnglish' not in example
                                               for examples in provenance.values() for example in examples),
             'editoriallyModifiedExampleUses': sum(('originalHanzi' in example and bool(example.get('modification'))) or 'originalEnglish' in example
                                                  for examples in provenance.values() for example in examples)}
    selected_origins = {context['hanzi']: sorted(old_contexts[context['hanzi']]) for context in contexts.values()
                        if context['source'].startswith('legacy-context:')}
    origin_bytes = encoded(selected_origins)
    if check:
        if not origins_path.exists() or origins_path.read_bytes() != origin_bytes:
            raise ValueError('Retained example origins differ from selected provenance')
    else:
        origins_path.write_bytes(origin_bytes)
    header = '''CHINESE FLASHCARD — VOCABULARY SOURCE UPDATE, 2026-10-27

The vocabulary expansion and five-stage revision retain every prior stable word
ID. Explicit headword replacements are recorded in headword-replacements.json;
the current examples below supersede the original context for those entries.
Data editing, frequency-based ranking, generated pinyin/chunks and source-assisted
review are not human linguistic certification or a clearance of legacy rights.

CC-CEDICT: CC-CEDICT contributors; CEDICT originally started by Paul Denisowski.
Publisher: MDBG. Selected release: 2026-10-26T02:33:39Z.
https://www.mdbg.net/chinese/dictionary?page=cc-cedict
CC BY-SA 4.0: https://creativecommons.org/licenses/by-sa/4.0/
Changes: selection, contextual gloss editing, tone marks, word boundaries,
learning-stage classification, teaching chunks, example selection and tags.
The earlier CC-CEDICT release and supplied-example notices remain below.

FrequencyWords / hermitdave: OpenSubtitles2018 Mandarin frequency list.
https://github.com/hermitdave/FrequencyWords
Content: CC BY-SA 4.0. Code: MIT. Frequencies guide editorial prioritization;
they are not a school syllabus or an educational-level certification.

Tatoeba contributors, compiled by Charles Kelly at ManyThings.org (2026-02-13).
https://www.manythings.org/anki/ and https://tatoeba.org/
CC BY 2.0 France: https://creativecommons.org/licenses/by/2.0/fr/
The selected sentences and translations retain their sentence IDs and authors
below. Pinyin, segmentation and lexical notes were added for this project.
Selected Chinese sentence forms were converted to simplified characters outside
protected headwords; special characters in the target headword were retained.
Later vocabulary revisions also make explicit contextual edits. The source text
appears as "Original Chinese" with the recorded change below whenever modified.
The English translation, sentence IDs and author attribution were retained.
The corpus may contain language errors; native-speaker metadata is not a claim
that every reused pair has received independent professional review.

Additional bilingual contexts use Tatoeba's official direct links and detailed
Mandarin/English exports of 2026-10-24, with sentence IDs and owners retained.
https://tatoeba.org/en/downloads
Exact downloaded file hashes are included in the bundled source lock.

HSK list transcription: krmanik/HSK-3.0, New HSK (2025).
https://github.com/krmanik/HSK-3.0
Coverage reference only. The repository declares CC BY-SA 4.0 for its HSK word
lists and separate licenses for its other inputs; no blanket MIT grant is made.

Project-authored AI-assisted daily-life contexts (2026-10-27): corresponding
project contributions are offered under CC BY-SA 4.0 to the extent rights are
held. This statement does not change the rights of any underlying reference.

The legacy source notes below are preserved verbatim and grouped by word ID.
Full machine-readable attribution and exact-source hashes are included in
wordlist-sources/source-index.json and wordlist-sources/source-lock.json.
No upstream publisher endorses this application or these modifications.
The unresolved legacy redistribution boundaries in NOTICE and
docs/OPEN_SOURCE_COMPLIANCE.md remain in force.
'''
    paragraphs = [header, 'Selected wordlist: ' + str(report['totalWords']) + ' words; ' +
                  str(report['baselineWords']) + ' retained IDs; ' + str(report['addedWords']) + ' added IDs.\n' +
                  'Wordlist SHA-256: ' + report['sha256'] + '\n' +
                  'Selected-example source uses (added IDs and explicit later revisions): ' + json.dumps(source_counts, ensure_ascii=False)]
    grouped = collections.defaultdict(list)
    for word_id, note in legacy.items():
        grouped[note].append(word_id)
    for note, ids in grouped.items():
        paragraphs.append('Retained source — ' + id_ranges(ids) + '\n' + note)
    for context in contexts.values():
        paragraph = 'Example — ' + id_ranges(context['wordIds']) + '\n' + context['hanzi'] + '\n' + context['source']
        if context.get('originalHanzi'):
            paragraph += '\nOriginal Chinese: ' + context['originalHanzi']
        if context.get('originalEnglish'):
            paragraph += '\nOriginal English: ' + context['originalEnglish']
        if context.get('modification'):
            paragraph += '\nChanges: ' + context['modification']
            paragraph += '\nModification: simplified-character conversion outside protected target characters.'
        if context['legacyWordIds']:
            paragraph += '\nRetained source notes: ' + id_ranges(context['legacyWordIds'])
        paragraphs.append(paragraph)
    outputs = {'source-index.json': encoded(index), 'source-lock.json': encoded(lock),
               'ATTRIBUTIONS.txt': ('\n\n'.join(paragraphs) + '\n').encode('utf-8')}
    ASSETS.mkdir(exist_ok=True)
    for name, content in outputs.items():
        path = ASSETS / name
        if check:
            if not path.exists() or path.read_bytes() != content:
                raise ValueError('Bundled vocabulary attribution differs: ' + name)
        else:
            path.write_bytes(content)
    print(json.dumps({'legacyWords': len(legacy), 'legacyNotes': len(notes),
                      'addedWords': len(vocabulary_ids) - len(legacy),
                      'attributedWordRecords': len(provenance), 'contexts': len(contexts),
                      'totalWords': report['totalWords'], 'check': check,
                      'assets': {name: {'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()}
                                 for name, content in outputs.items()}}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    arguments = parser.parse_args()
    publish(arguments.check)
