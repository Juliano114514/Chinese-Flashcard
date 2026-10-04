"""Cache explicitly labelled machine translations of existing English senses.

No dictionary definition is invented for an unmatched word/reading. The source
English is retained alongside each draft. These drafts require editorial review;
this producer does not classify them as individually verified dictionary senses.
"""
from __future__ import annotations

import collections
import concurrent.futures
import csv
import hashlib
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'tools/wordlist_refined'
ENDPOINT = 'https://translate.googleapis.com/translate_a/single'
OUTPUT = DATA / 'machine-translations.json'


def translate(text):
    query = urllib.parse.urlencode({'client': 'gtx', 'sl': 'en', 'tl': 'zh-CN', 'dt': 't', 'q': text})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(ENDPOINT + '?' + query, timeout=30) as response:
                payload = response.read(2 * 1024 * 1024)
            result = json.loads(payload)
            output = ''.join(piece[0] for piece in result[0] if piece and isinstance(piece[0], str))
            if not output.strip():
                raise ValueError('Translation response is empty.')
            return output
        except Exception:
            if attempt:
                raise
            time.sleep(1)


def translate_batch(batch):
    english = [row['英文释义'].replace('\n', ' ').strip() for row in batch]
    output = translate('\n'.join(english)).strip().split('\n')
    if len(output) != len(batch):
        output = [translate(text) for text in english]
    return [(row['词条ID'], {'hanzi': row['组词'], 'reading': row['拼音'],
                            'sourceEnglish': text, 'chinese': translated.strip(),
                            'review': 'unreviewed-machine-translation'})
            for row, text, translated in zip(batch, english, output)]


def main():
    rows = list(csv.DictReader((DATA / 'baseline.csv').open(encoding='utf-8-sig', newline='')))
    records = json.loads((DATA / 'production-records.json').read_text(encoding='utf-8'))
    existing = json.loads(OUTPUT.read_text(encoding='utf-8')) if OUTPUT.exists() else {}
    wanted = [row for row in rows if records[row['词条ID']]['chineseSource'] == 'unmatched'
              and records[row['词条ID']]['review'] != 'editorial-confirmed'
              and row['词条ID'] not in existing]
    batches = [wanted[start:start + 25] for start in range(0, len(wanted), 25)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(translate_batch, batch) for batch in batches]
        for future in concurrent.futures.as_completed(futures):
            existing.update(dict(future.result()))
            OUTPUT.write_text(json.dumps(existing, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            print(json.dumps({'draftsCached': len(existing), 'newDraftsRequested': len(wanted)}), flush=True)
    metadata = {'producedOn': '2026-10-04', 'service': 'Google Translate consumer text translation',
                'endpoint': ENDPOINT, 'sourceLanguage': 'en', 'targetLanguage': 'zh-CN',
                'records': len(existing), 'payloadSha256': hashlib.sha256(OUTPUT.read_bytes()).hexdigest(),
                'boundary': 'Machine translation of existing contextual English glosses, not independent dictionary verification; original English remains visible.'}
    (DATA / 'machine-translation-source.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(metadata, ensure_ascii=False))


if __name__ == '__main__':
    main()
