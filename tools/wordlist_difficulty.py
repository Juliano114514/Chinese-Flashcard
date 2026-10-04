"""Reproducible four-level grading; only the CSV rarity cell and row order change.

SUBTLEX-CH is a subtitle corpus, not a proficiency syllabus. Missing whole-word
observations are not proof of rarity: reviewed everyday combinations override
the conservative unknown-word floor. The pinned raw corpus stays in .gradle.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import io
import json
import math
import re
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CSV_PATH = ROOT / 'wordlist.csv'
MANIFEST = ROOT / 'tools/wordlist_difficulty.json'
CACHE = ROOT / '.gradle/wordlist-difficulty'
SOURCE_PATH = CACHE / 'subtlex-ch-2010.zip'
SOURCE_URL = 'https://journals.plos.org/plosone/article/file?type=supplementary&id=10.1371/journal.pone.0010729.s002'
SOURCE_SHA256 = 'cced9cb382914b93956a24fd06c10de709d351ff4501ac94c9ab136004421f07'
SOURCE_CITATION = 'Cai Q, Brysbaert M (2010). SUBTLEX-CH: Chinese Word and Character Frequencies Based on Film Subtitles. PLoS ONE 5(6):e10729. doi:10.1371/journal.pone.0010729.'
TARGET_PER_LEVEL = 1912
STRONG_MEANING = re.compile(r'\b(?:archaic|obsolete|classical|old times|old name|ancient name|former name|dialect(?:al)?)\b', re.I)

# Levels refer to this reviewed word/reading/sense, not every reading of a glyph.
# Keep names in the rule so an ID accidentally reassigned to another word fails.
OVERRIDES = {
    'wl_01671': (0, '很好', 'hěn hǎo', 'Everyday evaluation; corpus segmentation omits the combination.'),
    'wl_02722': (0, '好吧', 'hǎo ba', 'Everyday acknowledgement.'),
    'wl_03231': (0, '一只', 'yī zhī', 'Basic numeral and classifier combination.'),
    'wl_03095': (0, '打人', 'dǎ rén', 'Everyday verb-object combination.'),
    'wl_00558': (0, '你呢', 'nǐ ne', 'Basic conversational question.'),
    'wl_04020': (0, '天哪', 'tiān na', 'Common conversational exclamation.'),
    'wl_04000': (0, '好啊', 'hǎo a', 'Common conversational agreement.'),
    'wl_02987': (0, '两个', 'liǎng gè', 'Basic numeral and classifier combination.'),
    'wl_04102': (0, '拿来', 'ná lái', 'Everyday directional verb combination.'),
    'wl_00747': (0, '几个', 'jǐ ge', 'Basic quantity question.'),
    'wl_01901': (0, '去玩', 'qù wán', 'Everyday activity combination.'),
    'wl_03677': (0, '三个', 'sān gè', 'Basic numeral and classifier combination.'),
    'wl_01089': (0, '数一数', 'shǔ yī shǔ', 'Ordinary counting sense, not the noun reading shù.'),
    'wl_01192': (0, '喝水', 'hē shuǐ', 'Basic everyday activity; missing corpus compound is a segmentation issue.'),
    'wl_03965': (0, '嗯嗯', 'en en', 'Everyday conversational acknowledgement; retained supplied reading.'),
    'wl_03624': (0, '十个', 'shí gè', 'Basic numeral and classifier combination.'),
    'wl_03940': (0, '搞乱', 'gǎo luàn', 'Everyday resultative verb combination.'),
    'wl_04051': (0, '我俩', 'wǒ liǎ', 'Basic conversational pronoun combination.'),
    'wl_04011': (0, '饭团', 'fàn tuán', 'Ordinary food vocabulary.'),
    'wl_03999': (0, '好啦', 'hǎo la', 'Everyday conversational acknowledgement.'),
    'wl_01368': (0, '系鞋带', 'jì xié dài', 'Everyday activity; retain the tying reading jì.'),
    'wl_00286': (1, '扛起', 'káng qǐ', 'Modern shoulder-carrying meaning, not literary gāng.'),
    'wl_04045': (3, '嘿', 'mò', 'Classical silent sense; frequent hēi interjection is a different reading.'),
    'wl_01067': (3, '夫', 'fú', 'Classical opening particle; husband/man fū is a different reading.'),
    'wl_02662': (3, '跛', 'bì', 'Uncommon sideways-leaning reading, not ordinary bǒ.'),
    'wl_02827': (3, '行行', 'hàng hàng', 'Classical strong-and-resolute expression.'),
    'wl_00625': (3, '于思', 'yú sāi', 'Classical thick-bearded expression.'),
    'wl_01980': (3, '远小人', 'yuǎn xiǎo rén', 'Classical admonition, not an ordinary modern compound.'),
    'wl_00660': (3, '期年', 'jī nián', 'Classical full-year expression with the reading jī.'),
    'wl_01794': (3, '单于', 'chán yú', 'Ancient Xiongnu title with a special reading.'),
    'wl_00655': (3, '台小子', 'yí xiǎo zǐ', 'Early classical royal self-reference.'),
    'wl_00933': (3, '恶乎', 'wū hū', 'Classical interrogative reading wū.'),
    'wl_00416': (3, '齐衰', 'zī cuī', 'Ancient mourning category with special readings.'),
    'wl_00495': (3, '赠遗', 'zèng wèi', 'Classical giving sense with the reading wèi.'),
    'wl_00870': (3, '不以语人', 'bù yǐ yù rén', 'Classical syntax and telling reading yù.'),
    'wl_02268': (3, '发见', 'fā jiàn', 'Older spelling; modern everyday form is 发现.'),
    'wl_05338': (3, '焘', 'dào', 'Literary covering verb; surname/name frequency does not transfer.'),
    'wl_05372': (3, '挝', 'wō', 'Non-independent element of 老挝, not a standalone modern word.'),
    'wl_05373': (3, '挝', 'zhuā', 'Older striking verb; Laos-name frequency does not transfer.'),
    'wl_05959': (3, '悝', 'kuī', 'Rare literary ridicule sense.'),
    'wl_05960': (3, '悝', 'lǐ', 'Rare literary worried sense.'),
}


def content_hash(rows):
    """Order-independent hash of stable IDs and all fifteen non-rarity cells."""
    values = [{key: value for key, value in row.items() if key != '罕度'}
              for row in sorted(rows, key=lambda row: row['词条ID'])]
    return hashlib.sha256(json.dumps(values, ensure_ascii=False, sort_keys=True,
        separators=(',', ':')).encode('utf-8')).hexdigest()


def source_bytes(download=False):
    if not SOURCE_PATH.exists():
        if not download:
            raise FileNotFoundError('Pinned SUBTLEX source is not cached; run wordlist_difficulty.py --apply once.')
        with urllib.request.urlopen(SOURCE_URL, timeout=30) as response:
            payload = response.read(4 * 1024 * 1024 + 1)
        if len(payload) > 4 * 1024 * 1024:
            raise ValueError('SUBTLEX download exceeds its bounded size.')
        if hashlib.sha256(payload).hexdigest() != SOURCE_SHA256:
            raise ValueError('SUBTLEX source differs from the pinned publication attachment.')
        CACHE.mkdir(parents=True, exist_ok=True)
        SOURCE_PATH.write_bytes(payload)
    payload = SOURCE_PATH.read_bytes()
    if hashlib.sha256(payload).hexdigest() != SOURCE_SHA256:
        raise ValueError('Cached SUBTLEX source hash does not match the pinned publication attachment.')
    return payload


def source_tables(payload):
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        if sum(item.file_size for item in archive.infolist()) > 16 * 1024 * 1024:
            raise ValueError('SUBTLEX expanded data exceeds its bounded size.')
        def table(name, key):
            entry = archive.getinfo(name)
            if entry.file_size > 8 * 1024 * 1024:
                raise ValueError('SUBTLEX table exceeds its bounded size.')
            text = archive.read(name).decode('gb18030').splitlines()[2:]
            return {row[key]: row for row in csv.DictReader(io.StringIO('\n'.join(text)), delimiter='\t')}
        words = table('SUBTLEX-CH-WF', 'Word')
        characters = table('SUBTLEX-CH-CHR', 'Character')
    if len(words) != 99121 or len(characters) != 5936:
        raise ValueError('Unexpected pinned SUBTLEX table counts.')
    return words, characters


def features_for(rows, words, characters):
    strokes = {}
    for path in sorted((ROOT / 'core/data/src/main/assets/wordlist-strokes').glob('shard_*.json')):
        for item in json.loads(path.read_text(encoding='utf-8'))['items']:
            strokes[item['glyph']] = len(item['paths'])
    features = {}
    for row in rows:
        word = row['组词']
        entry = words.get(word)
        contexts = [int(characters.get(glyph, {}).get('CHR-CD', 0)) for glyph in word]
        features[row['词条ID']] = [int(entry is not None), int(entry['W-CD']) if entry else 0,
            int(entry['WCount']) if entry else 0, min(contexts), sum(contexts), len(contexts),
            sum(strokes[glyph] for glyph in word)]
    return features


def minimum_level(row, feature):
    if row['词性'].startswith('bound ') or STRONG_MEANING.search(row['英文释义']):
        return 3
    if row['词性'] == 'proper noun' or not feature[0]:
        return 2
    # Frequency ranks candidates within equal teaching groups. A fixed context
    # threshold cannot fill the larger 1,912-word groups without misclassifying
    # missing observations or genuinely historical senses.
    return 0


def classify(rows, features):
    by_id = {row['词条ID']: row for row in rows}
    if set(by_id) != set(features) or len(by_id) != len(rows):
        raise ValueError('Difficulty features must cover every unique stable word ID.')
    ratings = {}
    for word_id, (level, word, reading, _) in OVERRIDES.items():
        row = by_id.get(word_id)
        if row is None or (row['组词'], row['拼音']) != (word, reading):
            raise ValueError('Reviewed difficulty override identity changed: ' + word_id)
        ratings[word_id] = level
    def order(row):
        matched, contexts, count, minimum, total, length, strokes = features[row['词条ID']]
        # Missing observations use a deliberately discounted character estimate.
        score = math.log10(1 + contexts) if matched else math.log10(1 + minimum) - 1
        return (-score, -count, -total / length, strokes, row['词条ID'])
    ordered = sorted(rows, key=order)
    for level in (0, 1, 2):
        remaining = max(0, TARGET_PER_LEVEL - sum(value == level for value in ratings.values()))
        eligible = [row for row in ordered if row['词条ID'] not in ratings
                    and minimum_level(row, features[row['词条ID']]) <= level]
        for row in eligible[:remaining]:
            ratings[row['词条ID']] = level
    for row in ordered:
        ratings.setdefault(row['词条ID'], 3)
    if collections.Counter(ratings.values()) != {level: TARGET_PER_LEVEL for level in range(4)}:
        raise ValueError('Semantic constraints cannot fill four equal 1,912-word groups; review the actual content rather than weakening a sense constraint.')
    return ratings


def read_manifest():
    manifest = json.loads(MANIFEST.read_text(encoding='utf-8'))
    if manifest['schemaVersion'] != 2 or manifest['source']['sha256'] != SOURCE_SHA256:
        raise ValueError('Difficulty manifest source/version is not the pinned reviewed dataset.')
    policy = manifest['policy']
    if (policy['targetPerLevel'], policy['frequencyAssignment'],
            policy['unknownWordMinimumLevel'], policy['strongSenseMinimumLevel'], policy['properNounMinimumLevel']) != (
            TARGET_PER_LEVEL, 'ranked equal groups', 2, 3, 2):
        raise ValueError('Difficulty manifest policy does not match the reviewed classification rules.')
    if manifest['reviewedOverrides'] != {word_id: {'level': value[0], 'hanzi': value[1],
            'pinyin': value[2], 'reason': value[3]} for word_id, value in OVERRIDES.items()}:
        raise ValueError('Difficulty manifest overrides do not match the reviewed word/reading rules.')
    return manifest


def apply_ratings(rows):
    manifest = read_manifest()
    if content_hash(rows) != manifest['baseline']['nonRarityContentSha256']:
        raise ValueError('Non-rarity content changed; review and regenerate difficulty evidence before publishing.')
    for row in rows:
        row['罕度'] = str(manifest['ratings'][row['词条ID']])
    return rows


def validate_difficulty(rows):
    manifest = read_manifest()
    if len(rows) != manifest['baseline']['rows'] or content_hash(rows) != manifest['baseline']['nonRarityContentSha256']:
        raise ValueError('Difficulty update changed stable IDs or another CSV content column.')
    expected = classify(rows, manifest['features'])
    if manifest['ratings'] != expected or any(int(row['罕度']) != expected[row['词条ID']] for row in rows):
        raise ValueError('CSV difficulty does not match the reproducible reviewed assignments.')
    if collections.Counter(expected.values()) != {level: TARGET_PER_LEVEL for level in range(4)}:
        raise ValueError('The reviewed default wordlist must contain 1,912 words in each of four levels.')
    source_status = 'pinned source recorded; derived features verified offline'
    if SOURCE_PATH.exists():
        words, characters = source_tables(source_bytes())
        if features_for(rows, words, characters) != manifest['features']:
            raise ValueError('Recorded difficulty features differ from the pinned source and stroke data.')
        source_status = 'pinned ZIP hash and derived features verified'
    return {'sourceSha256': SOURCE_SHA256, 'sourceVerification': source_status,
        'wordFrequencyMatched': sum(value[0] for value in manifest['features'].values()),
        'wordFrequencyMissing': sum(not value[0] for value in manifest['features'].values()),
        'nonRarityContentSha256': content_hash(rows),
        'reviewedOverrides': len(OVERRIDES),
        'rarity': dict(sorted(collections.Counter(str(value) for value in expected.values()).items()))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Obtain pinned source and rewrite only CSV rarity, retaining the frozen original order and idiom appendix.')
    parser.add_argument('--check', action='store_true', help='Validate current assignments without rewriting the CSV.')
    args = parser.parse_args()
    payload = CSV_PATH.read_bytes()
    rows = list(csv.DictReader(io.StringIO(payload.decode('utf-8-sig'), newline='')))
    if args.check:
        print(json.dumps(validate_difficulty(rows), ensure_ascii=False))
        return
    if not args.apply:
        parser.error('Specify --apply or --check.')
    words, characters = source_tables(source_bytes(download=True))
    features = features_for(rows, words, characters)
    ratings = classify(rows, features)
    before_hash = hashlib.sha256(payload).hexdigest()
    if MANIFEST.exists():
        previous = json.loads(MANIFEST.read_text(encoding='utf-8'))
        if previous.get('schemaVersion') == 2 and previous['baseline']['nonRarityContentSha256'] == content_hash(rows):
            # Reapplying the same classification is byte-for-byte idempotent.
            before_hash = previous['baseline']['csvSha256BeforeGrading']
    manifest = {'schemaVersion': 2,
        'source': {'url': SOURCE_URL, 'sha256': SOURCE_SHA256, 'citation': SOURCE_CITATION,
            'licenseEvidence': 'https://journals.plos.org/plosone/s/licenses-and-copyright',
            'wordCount': len(words), 'characterCount': len(characters)},
        'baseline': {'rows': len(rows), 'csvSha256BeforeGrading': before_hash,
            'nonRarityContentSha256': content_hash(rows)},
        'policy': {'targetPerLevel': TARGET_PER_LEVEL, 'frequencyAssignment': 'ranked equal groups',
            'physicalOrder': 'frozen original word order, then stable idiom ID appendix', 'unknownWordMinimumLevel': 2,
            'strongSenseMinimumLevel': 3, 'properNounMinimumLevel': 2,
            'missingWordScore': 'log10(1 + minimum character contexts) - 1',
            'tieBreak': ['wordCount descending', 'mean character contexts descending', 'stroke count ascending', 'stable ID ascending'],
            'meaning': 'Relative learning difficulty within this wordlist, not HSK or a clinical language norm.'},
        'reviewedOverrides': {word_id: {'level': value[0], 'hanzi': value[1], 'pinyin': value[2], 'reason': value[3]}
            for word_id, value in OVERRIDES.items()},
        'featureColumns': ['wholeWordMatched', 'wordContexts', 'wordCount', 'minimumCharacterContexts',
            'sumCharacterContexts', 'characterCount', 'strokeCount'],
        'features': dict(sorted(features.items())), 'ratings': dict(sorted(ratings.items()))}
    CACHE.mkdir(parents=True, exist_ok=True)
    backup = CACHE / 'wordlist.before-grading.csv'
    if not backup.exists():
        backup.write_bytes(payload)
    for row in rows:
        row['罕度'] = str(ratings[row['词条ID']])
    if content_hash(rows) != manifest['baseline']['nonRarityContentSha256']:
        raise ValueError('Grading unexpectedly changed another CSV column.')
    buffer = io.StringIO(newline='')
    writer = csv.DictWriter(buffer, fieldnames=list(rows[0]), lineterminator='\r\n')
    writer.writeheader()
    writer.writerows(rows)
    if CSV_PATH.read_bytes() != payload:
        raise ValueError('CSV changed during grading; no result was written.')
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
    CSV_PATH.write_bytes(b'\xef\xbb\xbf' + buffer.getvalue().encode('utf-8'))
    print(json.dumps(validate_difficulty(rows), ensure_ascii=False))


if __name__ == '__main__':
    main()
