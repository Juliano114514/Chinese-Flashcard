"""Scan every retained content record and produce editorial review queues.

The heuristics expose review candidates, not proven translation errors. They
never rewrite a sentence or remove a rare, literary, or regional vocabulary item.
"""
from __future__ import annotations

import collections
import csv
import hashlib
import json
import re
from pathlib import Path

from complete_wordlist import tokens
from refine_existing_wordlist import contextual_tokens

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'tools/wordlist_refined'
COMMON = {'describe', 'describ', 'someone', 'something', 'person', 'people', 'thing', 'behavior', 'state', 'situation', 'example', 'word', 'term', 'use', 'used'}
META = re.compile(r'^(?:古诗用|古文用|方言中的|诗中的|古文中的|古书中的)|(?:可以表示|常用来|比喻见识|是.*?的省称)')
ALIGNED = re.compile(r'[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹ]+', re.I)


def main():
    rows = list(csv.DictReader((DATA / 'baseline.csv').open(encoding='utf-8-sig', newline='')))
    edits = json.loads((DATA / 'overrides.json').read_text(encoding='utf-8'))
    records = json.loads((DATA / 'production-records.json').read_text(encoding='utf-8'))
    queue, counts = [], collections.Counter()
    sentence_errors = []
    for baseline in rows:
        row = dict(baseline, **edits[baseline['词条ID']])
        flags, examples = [], []
        target = contextual_tokens(row['英文释义']) - COMMON
        for position, fields in enumerate((('简单例句', '例句拼音', '例句英语翻译'), ('第二例句', '第二例句拼音', '第二例句英语翻译')), 1):
            chinese, pinyin, english = (row[key] for key in fields)
            characters = re.findall(r'[\u3400-\u9fff]', chinese)
            if row['组词'] not in chinese or len(characters) != len(ALIGNED.findall(pinyin)):
                sentence_errors.append({'id': row['词条ID'], 'position': position, 'reason': 'target or pinyin alignment'})
            if META.search(chinese) or re.search(re.escape(row['组词']) + r'(?:可以|可指|指|常用来|比喻)', chinese):
                flags.append('example-' + str(position) + ':metalinguistic-frame')
            if len(characters) < 8 and re.match(r'^(?:他|她|它|这|那|此)', chinese):
                flags.append('example-' + str(position) + ':short-context')
            translated = contextual_tokens(english) - COMMON
            if target and not target & translated:
                flags.append('example-' + str(position) + ':no-lexical-overlap')
            examples.append({'chinese': chinese, 'pinyin': pinyin, 'english': english})
        if row['简单例句'] == row['第二例句']:
            sentence_errors.append({'id': row['词条ID'], 'reason': 'repeated sentences'})
        if row['例句英语翻译'].strip().casefold() == row['第二例句英语翻译'].strip().casefold():
            flags.append('repeated-English-translation')
        if 'Literary or historical vocabulary; the example presents this older usage.' in row['使用提示']:
            flags.append('automatic-historical-label-leftover')
        for flag in flags:
            counts[flag] += 1
        if flags:
            queue.append({'id': row['词条ID'], 'hanzi': row['组词'], 'meaning': row['英文释义'],
                          'reading': row['拼音'], 'flags': flags, 'examples': examples,
                          'existingEditorialStatus': records[row['词条ID']]['review'],
                          'decision': 'review-candidate, not a confirmed error'})
    report = {'checkedOn': '2026-10-04', 'entriesScanned': len(rows), 'sentencesScanned': len(rows) * 2,
              'sentenceIdentityAndAlignmentErrors': sentence_errors, 'reviewCandidates': len(queue),
              'flags': dict(counts), 'editorialExampleEntries': sum(r['examplePolicy'] == 'editorial-replacement' for r in records.values()),
              'retainedExampleEntries': sum(r['examplePolicy'] == 'baseline-preserved' for r in records.values()),
              'overlaySha256': hashlib.sha256((DATA / 'overrides.json').read_bytes()).hexdigest(),
              'boundary': 'Every sentence was scanned for the listed structural and lexical heuristics. Lack of English token overlap may be a synonym, grammar, or paraphrase issue; flags do not prove mistranslation. This is not a claim of full manual linguistic review.'}
    (DATA / 'example-review-queue.json').write_text(json.dumps(queue, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (DATA / 'example-audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
