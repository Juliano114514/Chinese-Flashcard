"""Freeze known legacy teaching fingerprints from the immutable reviewed catalog.

Rarity, file order and provenance are excluded because they never establish
per-word ownership. This is an asset generator, not an application test.
"""
from __future__ import annotations

import csv
import argparse
import hashlib
import io
import json
import subprocess
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVISION = '387ffb1'
CSV_HASH = '8cc63533b692ad99e580bd74cf4b8ee9bcf52ded2bb35981ccfeccdb572349af'
DEMO_HASH = '01211a1e232b6616890a9f3073dc47b781ea93e6049351a4ad7b786dcf843cea'
OUTPUT = ROOT / 'core/data/src/main/assets/default-wordlist-history.json'


def text(value):
    return unicodedata.normalize('NFC', value.strip())


def reading(value):
    return ''.join(text(value).lower().split())


def identity(hanzi, pinyin):
    return text(hanzi) + '\0' + reading(pinyin)


def fingerprint(word, meanings, examples, parts, distractors):
    tokens = ['preset-content-v1', text(word['hanzi']), reading(word['pinyin']), str(len(meanings))]
    for meaning in meanings:
        tokens.extend([text(meaning['english']), text(meaning.get('partOfSpeech', ''))])
    tokens.append(str(len(examples)))
    for example in examples:
        tokens.extend([text(example['hanzi']), reading(example['pinyin']), text(example['english'])])
    tokens.append(str(len(parts)))
    for part in parts:
        tokens.extend([text(part['hanzi']), reading(part['pinyin']), text(part['gloss'])])
    tokens.extend([text(word['note']), '', '', str(len(distractors)), *sorted(distractors)])
    digest = hashlib.sha256()
    for token in tokens:
        encoded = token.encode('utf-8')
        digest.update(str(len(encoded)).encode('ascii') + b':' + encoded)
    return digest.hexdigest()


def historical(path, expected_hash):
    content = subprocess.check_output(['git', 'show', f'{REVISION}:{path}'], cwd=ROOT)
    if hashlib.sha256(content).hexdigest() != expected_hash:
        raise ValueError('The immutable history source no longer matches its pinned hash.')
    return content


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Compare the frozen asset without rewriting it.')
    args = parser.parse_args()
    payload = historical('wordlist.csv', CSV_HASH)
    rows = list(csv.DictReader(io.StringIO(payload.decode('utf-8-sig'), newline='')))
    demo = json.loads(historical('core/data/src/main/assets/demo/catalog.json', DEMO_HASH))['words']
    identities = {row['词条ID']: identity(row['组词'], row['拼音']) for row in rows}
    by_identity = {value: key for key, value in identities.items()}
    entries = []
    for row in rows:
        word = {'hanzi': row['组词'], 'pinyin': row['拼音'], 'note': row['使用提示']}
        meanings = [{'english': row['英文释义'], 'partOfSpeech': row['词性']}]
        examples = [
            {'hanzi': row['简单例句'], 'pinyin': row['例句拼音'], 'english': row['例句英语翻译']},
            {'hanzi': row['第二例句'], 'pinyin': row['第二例句拼音'], 'english': row['第二例句英语翻译']},
        ]
        parts = json.loads(row['部件JSON'])
        distractors = [identities[item] for item in json.loads(row['干扰词ID'])]
        entries.append({'id': row['词条ID'], 'sourceId': row['词条ID'],
                        'hanzi': word['hanzi'], 'pinyin': word['pinyin'],
                        'fingerprint': fingerprint(word, meanings, examples, parts, distractors)})
    demo_meanings = {meaning['id']: word for word in demo for meaning in word['meanings']}
    for word in demo:
        source_id = by_identity.get(identity(word['hanzi'], word['pinyin']))
        if source_id is None:
            continue
        targets = [demo_meanings[item] for item in word['distractorMeaningIds']]
        distractors = [identity(target['hanzi'], target['pinyin']) for target in targets]
        entries.append({'id': word['id'], 'sourceId': source_id,
                        'hanzi': word['hanzi'], 'pinyin': word['pinyin'],
                        'fingerprint': fingerprint(word, word['meanings'], word['examples'], word['parts'], distractors)})
    if len(rows) != 6648 or len(entries) != 6663:
        raise ValueError('The pinned history no longer has the reviewed catalog and 15 demo aliases.')
    manifest = {'version': 1, 'revision': REVISION, 'csvSha256': CSV_HASH,
                'demoSha256': DEMO_HASH, 'fingerprintFormat': 'SHA-256 of UTF-8 byte-length-prefixed semantic tokens; excludes rarity, order and provenance.',
                'entries': entries}
    content = (json.dumps(manifest, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    if args.check:
        if OUTPUT.read_bytes() != content:
            raise ValueError('The bundled history differs from its immutable legacy sources.')
    else:
        OUTPUT.write_bytes(content)
    print(f'Legacy fingerprints {"verified" if args.check else "frozen"}: {len(entries)} entries, {len(content)} bytes.')


if __name__ == '__main__':
    main()
