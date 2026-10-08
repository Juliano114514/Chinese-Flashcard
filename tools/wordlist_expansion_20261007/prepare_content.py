"""Prepare draft teaching records from retained examples and scene-authored TSV.

Drafts require complete independent editorial review before freeze.py publishes them.
Retained examples are reused only at existing lexical boundaries and matching readings.
This script never writes the main CSV or changes historical frozen inputs.
"""
from __future__ import annotations

import argparse
import collections
import copy
import csv
import functools
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = Path(__file__).resolve().parent
BASE = ROOT / '.gradle/wordlist-expansion-20261007/baseline.csv'
INDEX = ROOT / '.gradle/wordlist-implementation/dictionary-index.json'
sys.path.insert(0, str(ROOT / 'tools'))
from complete_wordlist import marked_pinyin, glosses, tokens, plain_pinyin
from rebuild_wordlist import JSON_FIELDS, GRAMMAR, reading_key, short_gloss
from wordlist_difficulty import source_bytes, source_tables

BASE_SHA = '762c94d2ee6a058b2af1222094f791672d7b28bcf707b9a2cace637d7c065237'
PY = re.compile(r'[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹňḿ]+', re.I)
HAN = re.compile(r'[\u3400-\u9fff]')
NOISE = re.compile(r'(?i)\b(?:surname|particle|interjection|onomatopoeia|Japanese|Taiwan pr\.|variant of|archaic variant|old variant|abbr\. for)\b')
EXCLUDED = set('得很 家的 他用 你用 人和 中和 出清 成了 得了 对了 这位 那位 在地 罢了 好哇 嗯哼 呵呵 哈哈 嘿嘿 呵呵呵 啊哈 好嘛 好吧 是吧 对吧 好耶 呀呀 哎呀呀 什么的 之乎者也'.split())
PUNCT = {'。': '.', '，': ',', '！': '!', '？': '?', '；': ';', '：': ':', '、': ',', '“': '"', '”': '"', '（': '(', '）': ')'}
FIXED = {
    '的': ('de', 'DE (possessive or attributive marker)'), '地': ('de', 'DE (adverb marker)'),
    '得': ('de', 'DE (complement marker)'), '了': ('le', 'LE (aspect or change marker)'),
    '着': ('zhe', 'ZHE (ongoing state)'), '吗': ('ma', 'MA (question marker)'),
    '呢': ('ne', 'NE (question or topic marker)'), '吧': ('ba', 'BA (suggestion marker)'),
    '和': ('hé', 'and; with'), '也': ('yě', 'also'), '都': ('dōu', 'all'),
    '我们': ('wǒ men', 'we'), '你们': ('nǐ men', 'you (plural)'),
    '他们': ('tā men', 'they'), '她们': ('tā men', 'they'),
    '朋友': ('péng you', 'friend'), '孩子': ('hái zi', 'child'),
    '妈妈': ('mā ma', 'mom'), '爸爸': ('bà ba', 'dad'),
    '姐姐': ('jiě jie', 'older sister'), '妹妹': ('mèi mei', 'younger sister'),
    '哥哥': ('gē ge', 'older brother'), '弟弟': ('dì di', 'younger brother'),
    '奶奶': ('nǎi nai', 'grandmother'), '爷爷': ('yé ye', 'grandfather'),
    '什么': ('shén me', 'what'), '怎么': ('zěn me', 'how'),
    '这么': ('zhè me', 'so; this much'), '那么': ('nà me', 'so; that much'),
    '东西': ('dōng xi', 'thing; object'), '时候': ('shí hou', 'time; when'),
    '觉得': ('jué de', 'think; feel'), '记得': ('jì de', 'remember'),
    '认识': ('rèn shi', 'know; recognize'), '衣服': ('yī fu', 'clothes'),
    '头发': ('tóu fa', 'hair'), '眼睛': ('yǎn jing', 'eye'),
    '耳朵': ('ěr duo', 'ear'), '舒服': ('shū fu', 'comfortable'),
    '告诉': ('gào su', 'tell'), '明白': ('míng bai', 'understand'),
    '清楚': ('qīng chu', 'clear'), '意思': ('yì si', 'meaning'),
    '请': ('qǐng', 'please'), '已经': ('yǐ jīng', 'already'),
    '只有': ('zhǐ yǒu', 'only have; only'), '需要': ('xū yào', 'need'),
    '查看': ('chá kàn', 'check; look at'), '发给': ('fā gěi', 'send to'),
    '之后': ('zhī hòu', 'after'), '之前': ('zhī qián', 'before'),
}


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def hanzi(value):
    return ''.join(HAN.findall(value))


def clean_reading(value):
    return ' '.join(PY.findall(unicodedata.normalize('NFC', value).lower()))


def reading_matches(word, observed, canonical):
    left, right = clean_reading(observed).split(), clean_reading(canonical).split()
    if len(left) != len(word) or len(right) != len(word):
        return False
    for glyph, actual, expected in zip(word, left, right):
        if actual == expected:
            continue
        if glyph == '一' and expected == 'yī' and actual in {'yí', 'yì'}:
            continue
        if glyph == '不' and expected == 'bù' and actual == 'bú':
            continue
        return False
    return True


def sentence_sandhi(chunks):
    """Apply ordinary 一/不 sandhi to examples; headword readings stay lexical."""
    units = []
    for chunk in chunks:
        glyphs = HAN.findall(chunk['hanzi'])
        matches = list(PY.finditer(chunk['pinyin']))
        if len(glyphs) != len(matches):
            raise ValueError('Generated chunk reading is not aligned: ' + chunk['hanzi'])
        units.extend((glyph, chunk, match) for glyph, match in zip(glyphs, matches))
    replacements = collections.defaultdict(list)
    for i, (glyph, chunk, match) in enumerate(units[:-1]):
        next_py = units[i + 1][2].group().lower()
        fourth = any(c in next_py for c in 'àèìòùǜ')
        original = match.group().lower()
        if glyph == '不' and original == 'bù' and fourth:
            replacements[id(chunk)].append((chunk, match.start(), match.end(), 'bú'))
        elif glyph == '一' and original == 'yī':
            previous = units[i - 1][0] if i else ''
            # Ordinals, dates, weekday names and counting retain tone one.
            if previous in {'第', '周'} or previous and previous in '零一二三四五六七八九十百千' or units[i + 1][0] in '零一二三四五六七八九十月号' or ''.join(x[0] for x in units[max(0, i - 2):i]) == '星期':
                continue
            tone = 'yí' if fourth else 'yì' if any(c in next_py for c in 'āáǎēéěīíǐōóǒūúǔǖǘǚ') else 'yī'
            if tone != original:
                replacements[id(chunk)].append((chunk, match.start(), match.end(), tone))
    for edits in replacements.values():
        for chunk, start, end, value in sorted(edits, key=lambda x: x[1], reverse=True):
            chunk['pinyin'] = chunk['pinyin'][:start] + value + chunk['pinyin'][end:]


def source_data():
    raw = BASE.read_bytes()
    if hashlib.sha256(raw).hexdigest() != BASE_SHA:
        raise ValueError('The reviewed 9,223-row baseline changed')
    rows = list(csv.DictReader(raw.decode('utf-8-sig').splitlines()))
    if len(rows) != 9223:
        raise ValueError('Expected the complete frozen baseline')
    for row in rows:
        for key in JSON_FIELDS:
            row[key] = json.loads(row[key])
    return rows, load(INDEX)


class TeachingUnits:
    def __init__(self, rows, index):
        self.index = index
        self.lexical = collections.defaultdict(collections.Counter)
        self.parts = collections.defaultdict(collections.Counter)
        for row in rows:
            self.lexical[row['组词']][(row['拼音'], row['英文释义'])] += 3
            for example in row['例句JSON']:
                for chunk in example['chunks']:
                    word, reading = hanzi(chunk['hanzi']), clean_reading(chunk['pinyin'])
                    if word and len(word) == len(reading.split()):
                        self.lexical[word][(reading, chunk['gloss'])] += 1
            for part in row['部件JSON']:
                self.parts[part['hanzi']][(part['pinyin'], part['gloss'])] += 1

    @functools.lru_cache(maxsize=60000)
    def options(self, word):
        found = [(py, gloss, min(count, 50), 'retained')
                 for (py, gloss), count in self.lexical.get(word, {}).items()]
        for entry in self.index.get(word, []):
            if not entry['pinyin'].islower():
                continue
            reading = marked_pinyin(entry['pinyin'])
            if len(reading.split()) != len(word):
                continue
            for n, gloss in enumerate(glosses(entry, self.index)):
                if NOISE.search(gloss) or not gloss.strip():
                    continue
                found.append((reading, short_gloss(gloss), .5 / (n + 1), 'dictionary'))
        if word in FIXED:
            py, gloss = FIXED[word]
            found.insert(0, (py, gloss, 100, 'explicit'))
        elif word in GRAMMAR and found:
            found.insert(0, (found[0][0], GRAMMAR[word], 100, 'explicit'))
        return found

    def sentence(self, text, english, target, reading, target_gloss):
        context = tokens(english)
        target_start = text.find(target)
        if target_start < 0:
            raise ValueError('Authored example lacks its target: ' + target)
        target_end = target_start + len(target)

        @functools.lru_cache(None)
        def partition(at):
            if at == len(text):
                return 0, ()
            if not HAN.fullmatch(text[at]):
                score, rest = partition(at + 1)
                return score, ((text[at], PUNCT.get(text[at], text[at]), ''),) + rest
            best = None
            for end in range(at + 1, min(len(text), at + max(12, len(target))) + 1):
                unit = text[at:end]
                if not all(HAN.fullmatch(c) for c in unit):
                    break
                if at < target_start < end or at < target_end < end:
                    continue
                if target_start <= at < target_end and (at != target_start or end != target_end):
                    continue
                if unit != target and (unit in {'他用', '人和', '成了', '到了', '中和', '你用', '别看', '我去', '我看', '我说', '你说', '他说', '她说', '一把'} or
                        len(unit) > 1 and unit[0] in '我你他她它' and unit not in {'我们', '你们', '他们', '她们', '它们', '我俩', '你俩', '他俩', '我家', '你家', '他家', '她家', '我方', '你方'}):
                    continue
                if unit != target and (unit == '片中' and text[max(0, at - 1):at] == '图' or
                                       unit == '易读' and text[max(0, at - 1):at] == '容'):
                    continue
                if unit != target and (unit == '十分' and re.search(r'[零一二两三四五六七八九十百千]$', text[:at]) or
                                       unit == '的话' and re.search(r'(?:老师|妈妈|爸爸|医生|客户|老板|领导|同事|朋友|他|她|你|我)$', text[:at])):
                    continue
                choices = [(reading, target_gloss, 100, 'target')] if unit == target else self.options(unit)
                if not choices:
                    continue
                def preference(choice):
                    py, gloss, count, source = choice
                    penalty = 12 if re.search(r'(?i)\b(?:archaic|literary|dialect|bound form|classifier)\b', gloss) else 0
                    return len(context & tokens(gloss)) * 9 + min(count, 10) * .3 - len(gloss) * .008 - penalty + (30 if source == 'explicit' else 0)
                py, gloss, count, source = max(choices, key=preference)
                if unit != target:
                    if unit == '只' and not re.search(r'(?:这|那|几|每|一|二|两|三|四|五|六|七|八|九|十)$', text[:at]):
                        py, gloss = 'zhǐ', 'only'
                    elif unit == '为' and re.search(r'(?:设|改|定|分|选|称|视|化|升|变|转|添加|设定|定义|设置|标注|备注)$', text[:at]):
                        py, gloss = 'wéi', 'as; become'
                    elif unit == '会':
                        gloss = 'meeting' if re.search(r'(?:开|参加|散|这个|那场)$', text[:at]) else 'can; know how to' if re.search(r'(?i)\b(?:can|able|know how)\b', english) else 'will; may'
                    elif unit == '点':
                        gloss = "o'clock" if re.search(r'(?:几|一|二|两|三|四|五|六|七|八|九|十)$', text[:at]) else 'a little; some'
                    elif unit == '好' and re.search(r'(?:系|留|放|收|穿|准备|保管|写|记|拿)$', text[:at]):
                        gloss = 'properly; securely (result complement)'
                    elif unit == '送' and re.search(r'(?i)\b(?:take|taking|took|escort|bring|brought|drive|drove)\b', english):
                        gloss = 'take; escort'
                    elif unit == '扫' and re.search(r'(?i)\b(?:scan|scanned|scanning|barcode|code)\b', english):
                        gloss = 'scan'
                    elif unit == '号' and re.search(r'(?:订单|电话|手机|房间|车牌)$', text[:at]):
                        gloss = 'number'
                    elif unit in {'把', '笔', '间', '层', '件', '张', '本', '条', '位', '辆', '家', '次', '份', '只', '个', '杯', '碗', '片', '块', '双', '座', '门', '盏', '架', '台', '部', '首', '篇', '支', '根', '顶', '套', '瓶', '包'} and re.search(r'(?:这|那|几|每|一|二|两|三|四|五|六|七|八|九|十)$', text[:at]):
                        gloss = plain_pinyin(py).upper() + ' (measure word)'
                rest = partition(end)
                if rest is None:
                    continue
                score = rest[0] + len(unit) ** 1.55 + (1000 if unit == target else 0) + (.3 if source == 'explicit' else 0)
                if best is None or score > best[0]:
                    best = score, ((unit, py, gloss),) + rest[1]
            return best

        parsed = partition(0)
        if parsed is None:
            raise ValueError('Unresolved sentence: ' + target + ' / ' + text)
        chunks, leading = [], ''
        for unit, py, gloss in parsed[1]:
            if not gloss:
                if chunks:
                    chunks[-1]['hanzi'] += unit
                    chunks[-1]['pinyin'] += py
                else:
                    leading += unit
                continue
            chunks.append({'hanzi': leading + unit, 'pinyin': leading + py, 'gloss': gloss})
            leading = ''
        sentence_sandhi(chunks)
        return {'hanzi': text, 'pinyin': ' '.join(c['pinyin'] for c in chunks), 'english': english, 'chunks': chunks}

    def components(self, word, reading, english):
        # Multi-character lexical units stay whole unless an attested contextual
        # decomposition is available. Character meanings are not guessed etymology.
        options = []
        syllables = reading.split()
        for split in range(1, len(word)):
            values = []
            for unit, py in [(word[:split], ' '.join(syllables[:split])), (word[split:], ' '.join(syllables[split:]))]:
                matches = [(gloss, count) for (p, gloss), count in self.parts.get(unit, {}).items() if reading_key(p) == reading_key(py)]
                if not matches:
                    break
                gloss, count = max(matches, key=lambda x: (len(tokens(english) & tokens(x[0])), x[1]))
                values.append({'hanzi': unit, 'pinyin': py, 'gloss': gloss})
            if len(values) == 2 and all(tokens(english) & tokens(p['gloss']) for p in values):
                options.append(values)
        return max(options, key=lambda xs: max(len(x['hanzi']) for x in xs)) if options else [{'hanzi': word, 'pinyin': reading, 'gloss': english}]


def boundaries(example):
    points, offset = set(), 0
    for chunk in example['chunks']:
        text = chunk['hanzi']
        start, end = offset, offset + len(text)
        while start < end and not HAN.fullmatch(example['hanzi'][start]):
            start += 1
        while end > start and not HAN.fullmatch(example['hanzi'][end - 1]):
            end -= 1
        points.update((start, end))
        offset += len(text)
    return points


def retained_drafts():
    rows, index = source_data()
    units = TeachingUnits(rows, index)
    old = {r['组词'] for r in rows}
    glyphs = set(''.join(old))
    pool = {}
    owners = collections.defaultdict(set)
    for row in rows:
        for example in row['例句JSON']:
            key = example['hanzi']
            pool.setdefault(key, example)
            owners[key].add(row['词条ID'])
    attestations = collections.defaultdict(list)
    for text, example in pool.items():
        syllables = PY.findall(example['pinyin'])
        chars = list(HAN.finditer(text))
        if len(syllables) != len(chars):
            continue
        offsets = {match.start(): n for n, match in enumerate(chars)}
        points = boundaries(example)
        for at in sorted(points & offsets.keys()):
            for length in range(2, 7):
                word = text[at:at + length]
                if at + length not in points or len(word) != length or word in old or word in EXCLUDED or not set(word) <= glyphs or word not in index:
                    continue
                reading = ' '.join(syllables[offsets[at]:offsets[at] + length])
                entries = [e for e in index[word] if e['pinyin'].islower() and reading_matches(word, reading, marked_pinyin(e['pinyin']))]
                senses = [g for e in entries for g in glosses(e, index) if not NOISE.search(g)]
                if not senses:
                    continue
                contextual = [c['gloss'] for c in example['chunks'] if hanzi(c['hanzi']) == word]
                canonical = marked_pinyin(entries[0]['pinyin'])
                attestations[(word, reading_key(canonical))].append((example, canonical, senses, contextual, sorted(owners[text])))
    frequencies, _ = source_tables(source_bytes(False))
    result, seen = [], set()
    ordered = sorted(attestations, key=lambda k: (-len({a[0]['hanzi'] for a in attestations[k]}), -int(frequencies.get(k[0], {}).get('WCount', 0)), k))
    for key in ordered:
        word = key[0]
        examples = list({item[0]['hanzi']: item for item in attestations[key]}.values())
        if word in seen or len(examples) < 2:
            continue
        seen.add(word)
        examples.sort(key=lambda item: (not bool(item[3]), len(item[0]['hanzi']), item[0]['hanzi']))
        selected = examples[:2]
        contexts = collections.Counter(g for item in examples for g in item[3] if not NOISE.search(g))
        dictionary = selected[0][2]
        context_words = tokens(' '.join(item[0]['english'] for item in selected))
        ranked = [(g, n) for n, g in enumerate(dictionary)]
        full = max(ranked, key=lambda x: (len(tokens(x[0]) & context_words), -x[1]))[0]
        english = short_gloss(contexts.most_common(1)[0][0] if contexts else full)
        if not re.search('[A-Za-z]', english) or any(HAN.findall(english)) or len(english) > 300:
            continue
        context_tokens = tokens(english)
        compatible = [g for g in dictionary if context_tokens and context_tokens <= tokens(g)]
        pos = 'verb' if english.lower().startswith('to ') or any(g.lower().startswith('to ') for g in compatible) else 'adjective' if any(english.lower().startswith(p) for p in ['very ', 'extremely ', 'being ', 'not ', 'unable ', 'un']) else 'noun'
        # A dictionary's other sense must never replace the sense actually used
        # in the retained examples (e.g. 学会 "learn", not "learned society").
        definition = full if not contexts or tokens(full) == context_tokens else english
        result.append({'word': word, 'reading': selected[0][1], 'english': english, 'pos': pos,
                       'explanation': definition[0].upper() + definition[1:].rstrip('.') + '.',
                       'examples': [copy.deepcopy(item[0]) for item in selected],
                       'parts': units.components(word, selected[0][1], english),
                       'frequency': int(frequencies.get(word, {}).get('WCount', 0)),
                       'sourceWords': [item[4] for item in selected], 'source': 'retained',
                       'contextualGlosses': list(contexts), 'dictionarySenses': dictionary})
    save(DATA / 'retained-drafts.json', {'baseSha256': BASE_SHA, 'entries': result,
         'boundary': 'Draft selection, not new lexical or stage sign-off. Exact retained example chunk boundaries and dictionary/context readings checked.'})
    print(json.dumps({'retainedDrafts': len(result), 'sourceSentences': len(pool)}, ensure_ascii=False))


def authored_drafts():
    rows, index = source_data()
    units = TeachingUnits(rows, index)
    old = {r['组词'] for r in rows}
    glyphs = set(''.join(old))
    seen, result, authored, duplicates = {}, [], [], []
    for path in sorted(DATA.glob('*_authoring.tsv')):
        for number, values in enumerate(csv.reader(path.open(encoding='utf-8-sig', newline=''), delimiter='\t'), 1):
            if not values or len(values) >= 2 and values[0] in {'word', '组词'} and values[1] in {'reading', 'pinyin', '拼音'}:
                continue
            if len(values) != 9:
                raise ValueError(f'Expected nine authoring columns: {path.name}:{number}')
            word, reading, english, pos, explanation, h1, e1, h2, e2 = values
            if word in old or word in EXCLUDED or not set(word) <= glyphs or len(word) != len(reading.split()):
                raise ValueError(f'Invalid/old/unsupported authored word: {path.name}:{number}:{word}')
            if word in seen:
                if seen[word] == path.name:
                    raise ValueError('Duplicate word within one authoring file: ' + word)
                duplicates.append({'word': word, 'retainedAuthor': seen[word], 'duplicateAuthor': path.name, 'duplicateLine': number})
                continue
            if pos == 'particle':
                raise ValueError('No new particles: ' + word)
            seen[word] = path.name
            units.lexical[word][(reading, english)] += 100
            authored.append((path.name, number, values))
    for filename, number, values in authored:
            word, reading, english, pos, explanation, h1, e1, h2, e2 = values
            result.append({'word': word, 'reading': reading, 'english': english, 'pos': pos,
                           'explanation': explanation, 'examples': [units.sentence(h1, e1, word, reading, english), units.sentence(h2, e2, word, reading, english)],
                           'parts': units.components(word, reading, english), 'source': filename, 'sourceLine': number,
                           'dictionaryHeadword': word in index,
                           'readingReferences': [e['pinyin'] for e in index.get(word, []) if reading_matches(word, reading, marked_pinyin(e['pinyin']))]})
    save(DATA / 'authored-drafts.json', {'baseSha256': BASE_SHA, 'entries': result})
    save(DATA / 'authoring-duplicates.json', {'selectionOrder': 'activity, family, services; first authored headword retained', 'duplicates': duplicates})
    print(json.dumps({'authoredDrafts': len(result), 'removedCrossAuthorDuplicates': len(duplicates)}, ensure_ascii=False))


def combine():
    rows, index = source_data()
    stages = {}
    for path in sorted(DATA.glob('*_stages.json')):
        for word, decision in load(path).items():
            key = (path.name.replace('_stages.json', '_authoring.tsv'), word)
            if key in stages:
                raise ValueError('Overlapping authored stage decisions: ' + word)
            if decision.get('rarity') not in {'0', '1', '2', '3', '4'} or not decision.get('reason', '').strip():
                raise ValueError('Authored words need individual five-stage decisions: ' + word)
            stages[key] = decision
    source_path = DATA / 'retained-drafts.json'
    retained = load(source_path)['entries']
    by_word = {item['word']: item for item in retained}
    rejected, reviewed = set(), set()
    for path in sorted(DATA.glob('source_primary_*.json')):
        report = load(path)
        if report.get('inputSha256') != hashlib.sha256(source_path.read_bytes()).hexdigest():
            raise ValueError('Source editorial report refers to another input: ' + path.name)
        if not report.get('complete'):
            continue
        first, last = report['range']
        expected = {item['word'] for item in retained[first:last]}
        if set(report.get('coveredWords', [])) != expected or len(report.get('coveredWords', [])) != len(expected) or reviewed & expected:
            raise ValueError('Source editorial coverage differs or overlaps: ' + path.name)
        reviewed |= expected
        rejections = report.get('rejections', {})
        if not set(report.get('decisions', {})) <= expected or not set(rejections) <= expected:
            raise ValueError('Source editorial changes escape the reviewed partition: ' + path.name)
        rejected |= set(rejections)
        for word in expected - set(rejections):
            decision = report['decisions'][word]
            if decision.get('rarity') not in {'0', '1', '2', '3', '4'} or not decision.get('pos') or not decision.get('reason'):
                raise ValueError('Each retained-derived word needs an explicit stage and POS: ' + word)
            item = by_word[word]
            for key in ['reading', 'english', 'pos', 'explanation', 'parts', 'examples']:
                if key in decision:
                    item[key] = decision[key]
            item['stageDecision'] = {'rarity': decision['rarity'], 'reason': decision['reason']}
            item['editorialReport'] = path.name
    authored = load(DATA / 'authored-drafts.json')['entries']
    authored_by_word = {item['word']: item for item in authored}
    rejected_authored = set()
    initial_path = DATA / 'entries.initial.json'
    if initial_path.exists():
        initial = {row['词条ID']: row for row in load(initial_path)['entries']}
        initial_sha = hashlib.sha256(initial_path.read_bytes()).hexdigest()
        initial_groups = load(DATA / 'initial-review-snapshot.json')['sources']
        allowed_reports = {f'authored_cross_{source}.json' for source in ['activity', 'family', 'services']}
        if any(path.name not in allowed_reports for path in DATA.glob('authored_cross_*.json')):
            raise ValueError('Unknown authored editorial report; refuse an unassigned overlay')
        for source in ['activity', 'family', 'services']:
            path = DATA / f'authored_cross_{source}.json'
            if not path.exists():
                continue
            report = load(path)
            if not report.get('complete'):
                continue
            if report.get('inputSha256') != initial_sha:
                raise ValueError('Authored editorial snapshot changed: ' + path.name)
            expected = set(initial_groups[source + '_authoring.tsv'])
            if set(report.get('coveredIds', [])) != expected or len(report.get('coveredIds', [])) != len(expected):
                raise ValueError('Authored editorial coverage differs: ' + path.name)
            for key in ['corrections', 'rejections', 'decisions', 'reviewDecisions']:
                if not set(report.get(key, {})) <= expected:
                    raise ValueError('Authored editorial changes escape the assigned partition: ' + path.name)
            for word_id in report.get('rejections', {}):
                rejected_authored.add(initial[word_id]['组词'])
            mapping = {'拼音': 'reading', '英文释义': 'english', '词性': 'pos', '例句JSON': 'examples', '部件JSON': 'parts', '本义解释JSON': 'literal', '引申义解释JSON': 'figurative', '干扰词ID': 'distractors'}
            for word_id, changes in report.get('corrections', {}).items():
                word = initial[word_id]['组词']
                item = authored_by_word[word]
                for field, value in changes.items():
                    if field == '罕度':
                        stages[(item['source'], word)] = {'rarity': value, 'reason': 'Independent generated-record semantic review: ' + path.name}
                    elif field in mapping:
                        item[mapping[field]] = value
                    else:
                        raise ValueError('Protected authored editorial field: ' + field)
                item['editorialReport'] = path.name
    candidates = [item for item in authored if item['word'] not in rejected_authored] + sorted((item for item in retained if item['word'] not in rejected), key=lambda item: (-item['frequency'], item['word']))
    seen, chosen = set(), []
    for item in candidates:
        if item['word'] in seen:
            continue
        seen.add(item['word'])
        chosen.append(item)
        if len(chosen) == 4000:
            break
    entries = []
    additional = load(DATA / 'additional-sources.json') if (DATA / 'additional-sources.json').exists() else {}
    for n, item in enumerate(chosen, 9224):
        # Stage and grammatical category below are provisional. Editorial
        # decisions must explicitly cover every final row before freezing.
        sense = item['explanation'].lower()
        stage = '4' if re.search(r'\b(?:classical|literary|linguistics|philology)\b', sense) else '3' if re.search(r'\b(?:chemistry|physics|medical|medicine|anatomy|biochemistry|statistical|calculus)\b', sense) else '1'
        if item['source'] != 'retained':
            key = (item['source'], item['word'])
            if key not in stages:
                raise ValueError('Missing explicit authored stage decision: ' + item['word'])
            stage = stages[key]['rarity']
            item['stageDecision'] = stages[key]
        elif 'stageDecision' in item:
            stage = item['stageDecision']['rarity']
        sources = ('Expansion 2026-10-07: ' + ('contextual examples adapted from the frozen, AI-reviewed preset; new word boundaries and readings referenced against pinned CC-CEDICT. ' if item['source'] == 'retained' else 'two scene-specific AI-authored contextual examples; ' + ('headword and reading referenced against pinned CC-CEDICT. ' if item.get('readingReferences') else 'common compositional expression; readings referenced against constituent words and retained teaching units. ')) + 'Independent full-record AI editorial review is required before publication. CC-CEDICT CC BY-SA 4.0; word parts are memory aids, not etymology. No human linguistic certification.')
        for reference in additional.get(item['word'], []):
            sources += ' Additional sense reference: ' + reference['url'] + '.'
        entries.append({'罕度': stage, '组词': item['word'], '拼音': item['reading'], '词条ID': f'wl_{n:05d}',
            '英文释义': item['english'], '词性': item['pos'], '例句JSON': item['examples'], '部件JSON': item['parts'], '干扰词ID': item.get('distractors', []),
            '来源说明': sources,
            '本义解释JSON': item.get('literal', [item['explanation']]), '引申义解释JSON': item.get('figurative', [])})
        item['id'] = f'wl_{n:05d}'
    save(DATA / 'draft-provenance.json', {'baseSha256': BASE_SHA, 'entries': chosen, 'sourceEditorialReviewedWords': len(reviewed), 'rejectedSourceWords': sorted(rejected), 'rejectedAuthoredWords': sorted(rejected_authored)})
    save(DATA / 'entries.json', {'entries': entries})
    if not (DATA / 'corrections.json').exists():
        save(DATA / 'corrections.json', {})
    print(json.dumps({'draftEntries': len(entries), 'authored': sum(x['source'] != 'retained' for x in chosen), 'retained': sum(x['source'] == 'retained' for x in chosen)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['retained', 'authored', 'combine'])
    args = parser.parse_args()
    {'retained': retained_drafts, 'authored': authored_drafts, 'combine': combine}[args.mode]()
