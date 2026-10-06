"""Build the v2 teaching CSV from frozen, traceable content; --check is read-only.

--prepare uses the local pinned CC-CEDICT cache to draft aligned sentence chunks.
The resulting chunks/definitions are frozen inputs, not human-reviewed claims.
--apply assembles those inputs and uses the frozen one-time ID map thereafter.
No network, device, application build, or historical learner data is accessed.
"""
from __future__ import annotations

import argparse
import collections
import csv
import functools
import hashlib
import io
import json
import re
import unicodedata
from pathlib import Path

from complete_wordlist import glosses, marked_pinyin, plain_pinyin, tokens
from refine_existing_wordlist import contextual_tokens, clean_english

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'tools/wordlist_rebuild'
BASELINE = DATA / 'baseline.csv'
OUTPUT = ROOT / 'wordlist.csv'
DICTIONARY = ROOT / '.gradle/wordlist-implementation/dictionary-index.json'
REVIEW = ROOT / 'tools/content_review_20261005'
HEADERS = ['罕度', '组词', '拼音', '词条ID', '英文释义', '词性', '例句JSON',
           '部件JSON', '干扰词ID', '来源说明', '本义解释JSON', '引申义解释JSON']
JSON_FIELDS = ['例句JSON', '部件JSON', '干扰词ID', '本义解释JSON', '引申义解释JSON']
HANZI = re.compile(r'[\u3400-\u9fff\U00020000-\U000323af]')
PINYIN = re.compile(r'[a-züêāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜńňǹḿ]+', re.I)
SENSE_MARKER = 'Other attested senses with this reading:'
GRAMMAR = {
    '我': 'I', '我们': 'we', '你': 'you', '你们': 'you', '他': 'he', '她': 'she',
    '它': 'it', '他们': 'they', '她们': 'they', '它们': 'they', '您': 'you (polite)',
    '这': 'this', '那': 'that', '这些': 'these', '那些': 'those', '这个': 'this one',
    '那个': 'that one', '自己': 'oneself', '大家': 'everyone', '有人': 'someone',
    '每个': 'each', '什么': 'what', '谁': 'who', '哪里': 'where', '怎么': 'how',
    '为什么': 'why', '多少': 'how many; how much', '几个': 'how many',
    '了': 'LE (completed action or change of state)', '着': 'ZHE (continuing state)',
    '过': 'GUO (past experience)', '的': 'DE (modifier marker)',
    '地': 'DE (adverb marker)', '得': 'DE (complement marker)',
    '吗': 'MA (question marker)', '呢': 'NE (question or continuing-state particle)',
    '吧': 'BA (suggestion or uncertainty)', '啊': 'AH (exclamation)', '呀': 'YA (exclamation)',
    '把': 'BA (marks the affected object)', '被': 'BEI (passive marker)',
    '个': 'GE (general measure word)', '们': 'MEN (plural suffix)',
    '和': 'and; with', '而': 'and; whereas', '也': 'also', '都': 'all',
    '很': 'very', '更': 'more', '最': 'most', '太': 'too; very', '还': 'still; also',
    '只': 'only', '就': 'then; simply', '才': 'only then', '又': 'again',
    '不': 'not', '没': 'have not', '没有': 'do not have; have not',
    '是': 'is; am; are', '不是': 'is not', '有': 'have; there is',
    '在': 'at; be doing', '从': 'from', '到': 'to', '向': 'toward', '往': 'toward',
    '对': 'toward; regarding', '给': 'to; for', '跟': 'with', '比': 'than',
    '为了': 'in order to', '因为': 'because', '所以': 'so', '但是': 'but',
    '如果': 'if', '虽然': 'although', '然后': 'then', '一起': 'together',
    '已经': 'already', '正在': 'be doing', '还是': 'still; or', '或者': 'or',
    '可以': 'can; may', '应该': 'should', '必须': 'must', '不能': 'cannot',
    '不要': 'do not', '想': 'want to; think', '要': 'want to; need to',
    '先': 'first', '再': 'then; again', '终于': 'finally', '突然': 'suddenly',
    '时候': 'time; when', '以后': 'after', '以前': 'before', '之后': 'after',
    '之前': 'before', '现在': 'now', '今天': 'today', '昨天': 'yesterday',
    '明天': 'tomorrow', '每天': 'every day', '一下': 'briefly; once',
}
GRAMMAR.update({'了':'LE','着':'ZHE','过':'GUO','的':'DE','地':'DE','得':'DE',
                '吗':'MA','呢':'NE','吧':'BA','啊':'AH','呀':'YA',
                '把':'BA (object marker)','被':'BEI (passive)','个':'GE (measure word)',
                '们':'MEN (plural)'})


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def baseline():
    with BASELINE.open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream))


def reading_key(value):
    return unicodedata.normalize('NFC', value).lower().replace(' ', '')


def sentence_key(hanzi, pinyin, english):
    return hashlib.sha256(json.dumps([hanzi, pinyin, english], ensure_ascii=False).encode()).hexdigest()


def make_english_cleaner(rows, index):
    readings = {r['组词']: r['拼音'] for r in rows}
    def clean(value):
        def replace(match):
            word = match.group()
            reading = readings.get(word)
            if not reading and index.get(word):
                reading = marked_pinyin(index[word][0]['pinyin'])
            if not reading:
                parts = []
                for glyph in word:
                    if not index.get(glyph):
                        raise ValueError('Unresolved Chinese reference in English: ' + word)
                    parts.append(marked_pinyin(index[glyph][0]['pinyin']))
                reading = ' '.join(parts)
            return "'" + reading + "'"
        value = re.sub(r'[\u3400-\u9fff]+', replace, value)
        value = value.replace('English:', '').replace('Modern use:', '').strip()
        return re.sub(r'\s+', ' ', value).strip()
    return clean


def english_senses(row, field, index, clean):
    """Retain actual English lines and recover structured attested sense boundaries."""
    text = row[field]
    ordinary, extra = [], ''
    for line in text.splitlines():
        line = line.strip()
        if line.startswith(SENSE_MARKER):
            extra = line[len(SENSE_MARKER):].strip()
        elif line.startswith('English:'):
            ordinary.append(clean(line[len('English:'):]))
        elif line and (not HANZI.search(line) or re.match(r'[A-Za-z]', line)):
            ordinary.append(clean(line))
    if field == '本义解释' and not ordinary:
        raise ValueError('No retained English explanation: ' + row['组词'])
    if extra:
        structured = [clean_english(g) for e in index.get(row['组词'], [])
                      if reading_key(marked_pinyin(e['pinyin'])) == reading_key(row['拼音'])
                      for g in glosses(e, index)]
        # These are source list entries, not arbitrary semicolons in prose.
        while extra:
            matches = [g for g in structured if extra.casefold().startswith(g.casefold())
                       and (len(extra) == len(g) or extra[len(g):].startswith('; '))]
            if not matches:
                ordinary.append(clean(extra))
                break
            longest = max(matches, key=len)
            ordinary.append(clean(longest))
            extra = extra[len(longest):].lstrip('; ')
    # Standalone numeric/trigram symbols repeat the preceding English sense;
    # they are representations, not another numbered English explanation.
    return list(dict.fromkeys(x for x in ordinary if x and re.search('[A-Za-z]', x)))


def short_gloss(value):
    value = re.sub(r'\s*\(CL:[^)]*\)', '', value)
    value = re.sub(r'\s*\(idiom\)', '', value)
    value = re.sub(r'^\((?:bound form|coll\.|literary|archaic|idiom|fig\.)\)\s*', '', value)
    value = re.sub(r'^to\s+', '', value)
    value = re.sub(r'^Describes\s+', '', value)
    # Select one readable lexical gloss. Full attested senses remain in explanations.
    value = value.split(';')[0].strip()
    return value[:300]


def prepare():
    rows, index = baseline(), load(DICTIONARY)
    clean = make_english_cleaner(rows, index)
    definitions = {r['词条ID']: {
        'english': clean(r['英文释义']),
        'literal': english_senses(r, '本义解释', index, clean),
        'figurative': english_senses(r, '引申义解释', index, clean),
        'parts': [dict(part, gloss=clean(part['gloss'].split('|')[-1].splitlines()[-1].strip()))
                  for part in json.loads(r['部件JSON'])],
    } for r in rows}
    save(DATA / 'definitions.json', definitions)
    lexical = collections.defaultdict(list)
    named_lexical = collections.defaultdict(list)
    for word, entries in index.items():
        if HANZI.fullmatch(word[0]) and HANZI.fullmatch(word[-1]):
            for entry in entries:
                values = glosses(entry, index)
                for value in values:
                    gloss = clean(value)
                    if not re.search('[A-Za-z]', short_gloss(gloss)):
                        continue
                    bank = named_lexical if re.search('[A-Z]', entry['pinyin']) else lexical
                    bank[word].append((marked_pinyin(entry['pinyin']), gloss, 'CC-CEDICT'))
    for r in rows:
        lexical[r['组词']].insert(0, (r['拼音'], definitions[r['词条ID']]['english'], 'retained teaching gloss'))
        for part in json.loads(r['部件JSON']):
            gloss = part['gloss'].split('|')[-1].splitlines()[-1].strip()
            if gloss and not HANZI.search(gloss):
                lexical[part['hanzi']].append((part['pinyin'], gloss, 'retained contextual component'))
    for word, reading, gloss in [('阿嫲','ā má','grandmother (regional term)'),
                                 ('桔柣门','jié dié mén','Jie Die Gate (a historical gate name)'),
                                 ('垃圾','lè sè','garbage (regional reading)'),
                                 ('农书','nóng shū','farming book'),
                                 ('高渐离','gāo jiàn lí','Gao Jianli (a historical person)'),
                                 ('召公','shào gōng','Duke Shao'),
                                 ('吴淞江','wú sōng jiāng','Wusong River'),
                                 ('离情','lí qíng','feelings of parting')]:
        lexical[word].insert(0,(reading,gloss,'editorial regional/proper-name unit'))
    overrides = load(DATA/'gloss-overrides.json') if (DATA/'gloss-overrides.json').exists() else {}
    corrections = load(DATA/'sentence-corrections.json') if (DATA/'sentence-corrections.json').exists() else {}
    unknown, ambiguity, counter = {}, {}, collections.Counter()

    @functools.lru_cache(maxsize=50000)
    def choices(word, pinyin, english):
        options = list(lexical.get(word, []))
        # Capitalized source readings identify names. A matching sound alone does
        # not turn "a small bridge" into the historical person Xiao Qiao.
        for option in named_lexical.get(word, []):
            name = re.match(r'([A-Z][a-z]+(?: [A-Z][a-z]+)*)', option[1])
            if name and re.search(r'\b'+re.escape(name.group())+r'\b', english):
                options.append(option)
        exact = [x for x in options if reading_key(x[0]) == reading_key(pinyin)]
        if exact:
            return exact
        if len(word) > 1:
            return []
        plain = [x for x in options if plain_pinyin(x[0]).replace(' ', '') == plain_pinyin(pinyin).replace(' ', '')]
        return plain

    def chunks(hanzi, pinyin, english, target):
        hs, ps = list(HANZI.finditer(hanzi)), list(PINYIN.finditer(pinyin))
        if len(hs) != len(ps):
            raise ValueError('Sentence alignment changed: ' + hanzi)
        chars = ''.join(x.group() for x in hs)
        syllables = [x.group().lower() for x in ps]
        context = tokens(english)
        @functools.lru_cache(None)
        def partition(start):
            if start == len(hs):
                return 0, ()
            best = None
            for end in range(start + 1, min(start + 16, len(hs)) + 1):
                if any(hanzi[hs[k].end():hs[k + 1].start()] for k in range(start, end - 1)):
                    break
                word, reading = chars[start:end], ' '.join(syllables[start:end])
                if word in {'他用','成了','中和','人和','你用','过得'} and word != target:
                    continue
                opts = choices(word, reading, english)
                # These ordinary phrases share boundaries with real dictionary
                # words; the surrounding characters disambiguate their use.
                prefix = chars[:start]
                suffix = chars[end:]
                if ((word == '下摆' and prefix.endswith(('树', '桌', '架')))
                    or (word == '个人' and prefix.endswith(('几', '一', '两', '三', '四', '五', '六', '七', '八', '九', '十', '每')))
                    or (word == '语言学' and suffix.startswith('习'))
                    or (word == '经受' and prefix.endswith('已'))
                    or (word == '别人家' and prefix.endswith('进'))):
                    continue
                if not opts and word not in GRAMMAR and end != start + 1:
                    continue
                # Prefer lexical units over isolated characters; preserve the studied word.
                # A character studied inside a compound still needs the whole
                # compound's contextual gloss (e.g. jasmine or a plant name).
                own = 300 if word == target and len(word) > 1 else 0
                score = (end - start) ** 1.65 + own + (0 if opts or word in GRAMMAR else -20)
                rest, parts = partition(end)
                candidate = score + rest, ((start, end, word, reading),) + parts
                if best is None or candidate[0] > best[0]:
                    best = candidate
            return best
        parts = partition(0)[1]
        result = []
        for start, end, word, reading in parts:
            opts = choices(word, reading, english)
            override = overrides.get(word+'\0'+reading,overrides.get(word))
            neutral_particles = {'了':'le','着':'zhe','的':'de','地':'de','得':'de','吗':'ma','呢':'ne','吧':'ba'}
            grammar_reading = word not in neutral_particles or reading == neutral_particles[word]
            if override:
                gloss,evidence = override,'AI contextual editorial gloss'
            elif word in GRAMMAR and word != target and grammar_reading:
                gloss, evidence = GRAMMAR[word], 'explicit grammar/pronoun gloss'
            elif opts:
                def score(option):
                    value = option[1]
                    overlap = len(context & tokens(value))
                    penalty = 3 if re.match(r'(?i)(surname |variant |see |abbr\.|CL:)', value) else 0
                    return overlap * 8 - penalty - len(value)*.015 + (1 if option[2] == 'retained teaching gloss' else 0)
                selected = max(opts, key=score)
                gloss, evidence = short_gloss(selected[1]), selected[2]
                distinct = list(dict.fromkeys(short_gloss(x[1]) for x in opts))
                if len(distinct) > 1:
                    ambiguity.setdefault(word + '\0' + reading, {'word': word, 'pinyin': reading,
                        'selected': gloss, 'choices': distinct, 'example': hanzi, 'english': english})
            else:
                unknown.setdefault(word + '\0' + reading, {'word': word, 'pinyin': reading,
                    'example': hanzi, 'english': english})
                gloss, evidence = '', 'unresolved'
            # Context/reading-dependent function words must not be forced into one dictionary sense.
            prefix, suffix = chars[:start], chars[end:]
            if word in {'座','位','张','家','场','份','幅','条','块','批','本'} and re.search(r'(?:这|那|几|每|一|二|两|三|四|五|六|七|八|九|十|百|千|万)$',prefix):
                gloss = plain_pinyin(reading).upper()+' (measure word)'
            elif word == '为' and reading == 'wèi':
                gloss = 'for'
            elif word == '为' and reading == 'wéi':
                gloss = 'as; be'
            elif word == '出' and reading == 'chū':
                gloss = 'out'
            elif word == '门' and reading == 'mén':
                gloss = 'door; gate'
            elif word == '使' and reading == 'shǐ' and not re.search(r'\b(?:envoy|messenger)\b',english,re.I):
                gloss = 'make; cause'
            elif word == '装' and reading == 'zhuāng' and not re.search(r'\b(?:dress|pretend|install)\w*\b',english,re.I):
                gloss = 'put in; contain'
            elif word == '端' and reading == 'duān' and re.search(r'\b(?:carry|carries|carried|serve|serves|served)\b',english,re.I):
                gloss = 'carry; serve'
            elif word == '热' and reading == 'rè':
                gloss = 'hot'
            elif word == '间' and reading == 'jiān':
                gloss = 'between; during'
            elif word == '中' and reading == 'zhōng':
                gloss = 'in; among'
            elif word == '里' and plain_pinyin(reading) == 'li':
                gloss = 'li (traditional distance unit)' if re.search(r'\b(?:mile|miles|kilomet|distance)\w*',english,re.I) else 'in; inside'
            elif word == '书' and reading == 'shū':
                gloss = 'letter' if '赍书' in hanzi else 'book; written text'
            elif word == '让' and reading == 'ràng':
                gloss = 'yield; give way' if re.search(r'\b(?:yield|give way|gave way)',english,re.I) else 'let; make'
            elif word == '时' and reading == 'shí':
                gloss = "o'clock" if "o'clock" in english else 'when; time'
            elif word == '上' and reading == 'shàng':
                gloss = 'on; up'
            elif word == '下' and reading == 'xià':
                gloss = 'under; down'
            elif word == '后' and reading == 'hòu':
                gloss = 'after; behind'
            elif word == '指' and reading == 'zhǐ':
                gloss = 'finger' if 'finger' in english.lower() else 'refer to; point to'
            elif word == '次' and reading == 'cì':
                gloss = 'time; occurrence'
            elif word == '番' and reading == 'fān':
                gloss = 'foreign' if 'foreign' in english.lower() else 'FAN (measure word for actions or speech)'
            elif word == '篇' and reading == 'piān':
                gloss = 'PIAN (measure word for written texts)'
            elif word == '只' and reading == 'zhī':
                gloss = 'ZHI (measure word for animals or one of a pair)'
            elif word == '过':
                before, after = chars[:start], chars[end:]
                if after.startswith('着') or re.match(r'.{0,8}生活',after) or re.search(r'\b(?:live\w*|spend\w*|spent|celebrat\w*)\b',english,re.I):
                    gloss = 'live; spend time'
                elif before.endswith(('躲','熬','渡','度','盖','越','避')):
                    gloss = 'get through; get past'
                elif before.endswith(('走','跑','游','驶','飞','跳','跨','穿','流','路','划','飘','滚','爬','骑','行','淌','掠','飙','漫')) or reading == 'guò':
                    gloss = 'pass; cross'
                else:
                    gloss = 'GUO (past experience)'
            elif word == '会' and reading == 'huì':
                if re.search(r'\b(?:meeting|gathering|association)\b',english,re.I) and not suffix.startswith(('在','有','把','被','给','向','做','说')):
                    gloss = 'meeting; gathering'
                else:
                    gloss = 'can; know how to' if re.search(r'\b(?:can|able|know how)\b',english,re.I) else 'will; be likely to'
            elif word == '又':
                before = re.split(r'[，。；！？,.;!?]',hanzi[:hs[start].start()])[-1]
                after = re.split(r'[，。；！？,.;!?]',hanzi[hs[end-1].end():])[0]
                if '又' in before or '既' in before:
                    gloss = 'and (in both ... and ...)'
                elif '又' in after:
                    gloss = 'both (in both ... and ...)'
            hstart = 0 if start == 0 else hs[start].start()
            hend = len(hanzi) if end == len(hs) else hs[end].start()
            pstart = 0 if start == 0 else ps[start].start()
            pend = len(pinyin) if end == len(ps) else ps[end].start()
            result.append({'hanzi': hanzi[hstart:hend], 'pinyin': pinyin[pstart:pend].strip(), 'gloss': gloss})
            counter[evidence] += 1
        return result

    examples = {}
    for r in rows:
        for h, p, e in [('简单例句', '例句拼音', '例句英语翻译'), ('第二例句', '第二例句拼音', '第二例句英语翻译')]:
            key = sentence_key(r[h], r[p], r[e])
            if key not in examples:
                english = clean(r[e])
                correction = corrections.get(key,{})
                corrected_pinyin = correction.get('pinyin',r[p])
                if 'chunks' in correction:
                    sentence_chunks = correction['chunks']
                    counter['AI sentence-specific editorial chunks'] += len(sentence_chunks)
                else:
                    sentence_chunks = chunks(r[h], corrected_pinyin, english, r['组词'])
                examples[key] = {'hanzi': r[h], 'pinyin': corrected_pinyin, 'english': english,
                                 'chunks': sentence_chunks}
    save(DATA / 'examples.json', examples)
    save(DATA / 'draft-audit.json', {'baselineSha256': hashlib.sha256(BASELINE.read_bytes()).hexdigest(),
        'dictionarySha256': hashlib.sha256(DICTIONARY.read_bytes()).hexdigest(),
        'uniqueExamples': len(examples), 'glossSources': dict(counter),
        'unresolved': list(unknown.values()), 'polysemousChunks': list(ambiguity.values()),
        'boundary': 'Context-ranked dictionary/retained glosses and explicit grammar labels are AI-assisted drafts, not human sign-off.'})
    print(json.dumps({'examples': len(examples), 'unresolvedChunks': len(unknown), 'glossSources': dict(counter)}, ensure_ascii=False))


def pinyin_sort_key(value):
    marks = {'\u0304': 1, '\u0301': 2, '\u030c': 3, '\u0300': 4}
    result = []
    for syllable in re.split(r"[\s'’\-]+", value.strip().lower()):
        tone = 5
        letters = []
        for char in unicodedata.normalize('NFD', syllable):
            if char in marks:
                tone = marks[char]
            elif char == '\u0308':
                if not letters or letters[-1] != 'u':
                    raise ValueError('Invalid umlaut: ' + syllable)
                letters[-1] = 'ü'
            elif char == '\u0302':
                if not letters or letters[-1] != 'e':
                    raise ValueError('Invalid circumflex: ' + syllable)
                letters[-1] = 'ê'
            else:
                letters.append(char)
        weights = tuple(ord(c) if c not in {'ü','ê'} else ord('u' if c == 'ü' else 'e') + 0.5 for c in letters)
        result.append((weights, tone))
    return tuple(result)


def serialize(rows):
    stream = io.StringIO(newline='')
    writer = csv.DictWriter(stream, fieldnames=HEADERS, lineterminator='\r\n')
    writer.writeheader()
    for row in rows:
        writer.writerow({k: json.dumps(row[k], ensure_ascii=False, separators=(',', ':'))
                         if k in JSON_FIELDS else row[k] for k in HEADERS})
    return b'\xef\xbb\xbf' + stream.getvalue().encode('utf-8')


def validate(content):
    if len(content) > 32 * 1024 * 1024:
        raise ValueError('CSV exceeds existing 32 MiB limit')

    def whitespace(character):
        # Kotlin Char.isWhitespace excludes NEXT LINE (U+0085), which Python
        # str.isspace/strip include.
        return character.isspace() and character != '\u0085'

    def trimmed(value):
        start, end = 0, len(value)
        while start < end and whitespace(value[start]):
            start += 1
        while end > start and whitespace(value[end - 1]):
            end -= 1
        return value[start:end]

    def text(value, maximum, context, trim=False):
        if not isinstance(value, str):
            raise ValueError('A teaching text must be a string: ' + context)
        if trim:
            value = trimmed(value)
        # Kotlin String.length counts UTF-16 code units, not Python code points.
        length = len(value.encode('utf-16-le', errors='surrogatepass')) // 2
        if not trimmed(value) or length > maximum or '\0' in value:
            raise ValueError('Blank, oversized or NUL-containing text: ' + context)
        return value

    def is_word_hanzi(character):
        point = ord(character)
        return (0x3400 <= point <= 0x4DBF or 0x4E00 <= point <= 0x9FFF or
                0xF900 <= point <= 0xFAFF or 0x20000 <= point <= 0x2FFFF or
                0x30000 <= point <= 0x323AF)

    def english(value, maximum, context):
        value = text(value, maximum, context)
        # UnicodeScript.HAN also includes radicals and ideographic marks that
        # are outside the word-only Han ranges accepted by CsvDecoder.
        han_ranges = ((0x2E80, 0x2E99), (0x2E9B, 0x2EF3), (0x2F00, 0x2FD5),
                      (0x3021, 0x3029), (0x3038, 0x303B), (0x16FE2, 0x16FE3),
                      (0x16FF0, 0x16FF1))
        if (not re.search('[A-Za-z]', value) or any(is_word_hanzi(c) or
                ord(c) in (0x3005, 0x3007) or
                any(start <= ord(c) <= end for start, end in han_ranges) for c in value)):
            raise ValueError('Teaching English needs Latin letters and no Han script: ' + context)
        return value

    pinyin_letter = re.compile(r'[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹňḿ]', re.I)
    sentence_punctuation = '.,!?;:，。！？；：、…—–-\"\'“”‘’()（）[]'

    def pinyin(value, maximum, context, sentence=False):
        value = unicodedata.normalize('NFC', text(value, maximum, context))
        punctuation = sentence_punctuation if sentence else "'-’"
        if not pinyin_letter.search(value) or any(not whitespace(c) and
                not pinyin_letter.fullmatch(c) and c not in punctuation for c in value):
            raise ValueError('Invalid pinyin characters: ' + context)
        return value

    def normalized_example_pinyin(value):
        return ''.join(c for c in unicodedata.normalize('NFC', value).lower()
                       if not whitespace(c) and c not in "'’")

    def exact_keys(value, names, context):
        if not isinstance(value, dict) or set(value) != set(names):
            raise ValueError('Unexpected or missing teaching JSON keys: ' + context)

    def json_array(value, maximum, context):
        value = text(value, maximum, context, trim=True)
        depth, quoted, escaped = 0, False, False
        for character in value:
            if quoted:
                if escaped:
                    escaped = False
                elif character == '\\':
                    escaped = True
                elif character == '"':
                    quoted = False
            elif character == '"':
                quoted = True
            elif character in '[{':
                depth += 1
                if depth > 4:
                    raise ValueError('Teaching JSON nesting exceeds four levels: ' + context)
            elif character in ']}':
                depth -= 1
                if depth < 0:
                    raise ValueError('Unbalanced teaching JSON: ' + context)
            elif character not in ' \t\r\n:,':
                raise ValueError('Teaching JSON values must be quoted strings, arrays or objects: ' + context)
        if depth or quoted:
            raise ValueError('Unclosed teaching JSON: ' + context)
        value = json.loads(value)
        if not isinstance(value, list):
            raise ValueError('Teaching JSON field must contain an array: ' + context)
        return value

    decoded = content.decode('utf-8-sig')
    # csv.reader(strict=True) still accepts quotes inside unquoted fields;
    # CsvParser rejects them and any text after a closing quote.
    state = 0
    for character in decoded:
        if character in '\r\n':
            if state != 2:
                state = 0
        elif state == 0:
            if character == '"':
                state = 2
            elif character != ',':
                state = 1
        elif state == 1:
            if character == '"':
                raise ValueError('A CSV quote must start a quoted field')
            if character == ',':
                state = 0
        elif state == 2:
            if character == '"':
                state = 3
        elif character == '"':
            state = 2
        elif character == ',':
            state = 0
        else:
            raise ValueError('Only a comma or newline may follow a closing CSV quote')
    if state == 2:
        raise ValueError('A CSV quoted field is not closed')
    # Count the actual RFC 4180 record, including doubled quotes and UTF-8,
    # rather than a JSON representation whose byte count differs from CSV.
    physical_lines = re.findall(r'[^\r\n]*(?:\r\n|\r|\n|$)', decoded)
    reader = csv.reader(io.StringIO(decoded, newline=''), strict=True)
    names = next(reader, None)
    if names is None or [trimmed(name) for name in names] != HEADERS:
        raise ValueError('CSV headers/count invalid')
    previous_line = reader.line_num
    if len(''.join(physical_lines[:previous_line]).encode('utf-8')) > 32 * 1024:
        raise ValueError('Header exceeds existing 32 KiB record limit')
    rows = []
    for fields in reader:
        raw_record = ''.join(physical_lines[previous_line:reader.line_num])
        previous_line = reader.line_num
        if len(raw_record.encode('utf-8')) > 32 * 1024:
            raise ValueError('Record exceeds existing 32 KiB limit')
        if not fields or len(fields) == 1 and not trimmed(fields[0]):
            continue
        if len(fields) != len(HEADERS):
            raise ValueError('CSV row width differs from header')
        # CsvParser normalizes both CRLF and bare CR inside quoted fields.
        row = dict(zip(HEADERS, (re.sub(r'\r\n?', '\n', value) for value in fields)))
        for key, maximum in [('罕度', 1), ('组词', 64), ('拼音', 128),
                             ('词条ID', 92), ('英文释义', 300), ('词性', 80), ('来源说明', 2000)]:
            row[key] = text(row[key], maximum, key, trim=True)
        for key in JSON_FIELDS:
            row[key] = json_array(row[key], 400 if key == '干扰词ID' else 16000, key)
        rows.append(row)
        if len(rows) > 10000:
            raise ValueError('CSV headers/count invalid')
    if not rows:
        raise ValueError('CSV headers/count invalid')
    ids = {r['词条ID']: r for r in rows}
    if len(ids) != len(rows):
        raise ValueError('Duplicate IDs')
    stroke_index = load(ROOT / 'core/data/src/main/assets/wordlist-strokes/index.json')
    glyphs = {item['glyph'] for item in stroke_index['items']}
    for r in rows:
        if r['罕度'] not in {'0','1','2','3','4'} or not re.fullmatch(r'wl_[0-9]{5}', r['词条ID']):
            raise ValueError('Invalid ID or rarity: ' + r['组词'])
        word = unicodedata.normalize('NFC', r['组词'])
        if not 1 <= len(word) <= 32 or not all(is_word_hanzi(c) for c in word):
            raise ValueError('A word must contain 1–32 Han characters: ' + r['组词'])
        reading = pinyin(r['拼音'], 128, r['组词'] + ' word pinyin')
        if len([s for s in re.split(r"[\s'’\-]+", trimmed(reading), flags=re.ASCII) if s]) != len(word):
            raise ValueError('Word pinyin alignment: ' + r['组词'])
        english(r['英文释义'], 300, r['组词'] + ' short English')
        for glyph in word:
            if glyph not in glyphs:
                raise ValueError('Missing genuine stroke: ' + glyph)
        for key in ['本义解释JSON', '引申义解释JSON']:
            values = r[key]
            if not isinstance(values, list) or len(values) > 16 or (key == '本义解释JSON' and not values):
                raise ValueError('Invalid explanation array: ' + r['组词'])
            for value in values:
                context = r['组词'] + ' ' + key
                text(value, 2000, context)
                english(trimmed(value), 2000, context)
        examples = r['例句JSON']
        if len(examples) != 2:
            raise ValueError('Two examples required: ' + r['组词'])
        for ex in examples:
            exact_keys(ex, ('hanzi', 'pinyin', 'english', 'chunks'), r['组词'] + ' example')
            text(ex['hanzi'], 1000, r['组词'] + ' example Hanzi')
            pinyin(ex['pinyin'], 2000, r['组词'] + ' example pinyin', sentence=True)
            english(ex['english'], 2000, r['组词'] + ' natural English')
            if word not in ex['hanzi']:
                raise ValueError('Invalid example: ' + r['组词'])
            chunks = ex['chunks']
            if not isinstance(chunks, list) or not 1 <= len(chunks) <= 64:
                raise ValueError('Invalid chunk shape: ' + r['组词'])
            for chunk in chunks:
                exact_keys(chunk, ('hanzi', 'pinyin', 'gloss'), r['组词'] + ' chunk')
                text(chunk['hanzi'], 1000, r['组词'] + ' chunk Hanzi')
                pinyin(chunk['pinyin'], 2000, r['组词'] + ' chunk pinyin', sentence=True)
                english(chunk['gloss'], 2000, r['组词'] + ' literal gloss')
            if ''.join(c['hanzi'] for c in chunks) != ex['hanzi']:
                raise ValueError('Chunk Hanzi reconstruction: ' + r['组词'])
            if normalized_example_pinyin(' '.join(c['pinyin'] for c in chunks)) != normalized_example_pinyin(ex['pinyin']):
                raise ValueError('Chunk pinyin reconstruction: ' + r['组词'])
        if examples[0]['hanzi'] == examples[1]['hanzi']:
            raise ValueError('Two distinct examples required: ' + r['组词'])
        parts = r['部件JSON']
        if not 1 <= len(parts) <= 32:
            raise ValueError('Provide 1–32 word parts: ' + r['组词'])
        for part in parts:
            exact_keys(part, ('hanzi', 'pinyin', 'gloss'), r['组词'] + ' part')
            text(part['hanzi'], 64, r['组词'] + ' part Hanzi')
            pinyin(part['pinyin'], 128, r['组词'] + ' part pinyin')
            english(part['gloss'], 300, r['组词'] + ' part gloss')
        if ''.join(p['hanzi'] for p in parts) != word:
            raise ValueError('Word parts must reconstruct the complete word: ' + r['组词'])
        distractors = r['干扰词ID']
        if (len(distractors) != 3 or any(not isinstance(x, str) or
                not re.fullmatch('[A-Za-z0-9_]{1,92}', x) for x in distractors) or
                len(set(distractors)) != 3 or r['词条ID'] in distractors or any(x not in ids for x in distractors)):
            raise ValueError('Invalid distractors: ' + r['组词'])
        if len({unicodedata.normalize('NFC', trimmed(ids[x]['英文释义'])).lower()
                for x in [r['词条ID'],*distractors]}) != 4:
            raise ValueError('Repeated choice text: ' + r['组词'])
    return rows


def apply():
    manifest_path = DATA / 'build-manifest.json'
    if manifest_path.exists():
        published = load(manifest_path)
        required = set(published['inputSha256'])
        if 'vocabularyExpansion' in published:
            required.add('expansion-20261005.json')
        if 'stageGrading' in published:
            required.add('stage-grading-20261005.json')
        missing = sorted(name for name in required if not (DATA / name).is_file())
        if missing:
            raise ValueError('Published frozen inputs are missing; refuse to roll back the wordlist: '
                             + ', '.join(missing))
    retained, definitions, examples = baseline(), load(DATA/'definitions.json'), load(DATA/'examples.json')
    extras = load(DATA/'additional-idioms.json')['entries']
    if len(retained) != 7648 or len(extras) != 75:
        raise ValueError('Frozen rebuild must contain 7,648 retained and 75 new entries')
    rows = []
    for old in retained:
        record = {k: old[k] for k in ['罕度','组词','拼音','词条ID','词性','来源说明']}
        record.update({'英文释义':definitions[old['词条ID']]['english'],
            '本义解释JSON':definitions[old['词条ID']]['literal'],
            '引申义解释JSON':definitions[old['词条ID']]['figurative'],
            '部件JSON':definitions[old['词条ID']]['parts'], '干扰词ID':json.loads(old['干扰词ID']),
            '例句JSON':[examples[sentence_key(old[h],old[p],old[e])] for h,p,e in
                         [('简单例句','例句拼音','例句英语翻译'),('第二例句','第二例句拼音','第二例句英语翻译')]]})
        record['来源说明'] = (record['来源说明'] + ' Rebuild 2026-10-05: English explanations retained; sentence chunks use context-ranked CC-CEDICT/retained glosses and AI grammar/editorial corrections; not human-reviewed.')[:2000]
        rows.append(record)
    rows.extend(extras)
    if len({(r['组词'],r['拼音']) for r in rows}) != len(rows):
        raise ValueError('Duplicate word/reading identity in rebuild')
    identities = [(r['词条ID'], r['组词'], r['拼音']) for r in rows]
    map_path = DATA/'id-map.json'
    mapping = {r['词条ID']:f'wl_{n:05}' for n,r in enumerate(rows,1)}
    frozen = {'baselineSha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(), 'identities':identities, 'oldToNew':mapping}
    if map_path.exists():
        if load(map_path) != json.loads(json.dumps(frozen)):
            raise ValueError('One-time ID mapping is frozen; refuse automatic renumbering')
    else:
        save(map_path,frozen)
    bank = [r for r in rows if r['词性']=='idiom' and r['词条ID'] not in {x['词条ID'] for x in extras}]
    def semantic(value):
        return contextual_tokens(value)-{'describe','idiom','sb','sth','something','somebody','thing'}
    for row in rows:
        if not row['干扰词ID']:
            target = semantic(row['英文释义'])
            choices, labels = [], {row['英文释义'].casefold()}
            selected_semantics = set()
            for option in bank:
                label = option['英文释义'].casefold()
                meaning = semantic(option['英文释义'])
                if label in labels or target & meaning or selected_semantics & meaning:
                    continue
                choices.append(option['词条ID']); labels.add(label)
                selected_semantics |= meaning
                if len(choices)==3:
                    break
            if len(choices)!=3:
                raise ValueError('No three distinct idiom distractors: '+row['组词'])
            row['干扰词ID']=choices
    # All reference selection uses original IDs; remapping is a separate pass.
    for row in rows:
        row['干扰词ID']=[mapping[x] for x in row['干扰词ID']]
        row['词条ID']=mapping[row['词条ID']]
    content = serialize(rows)
    review_path = REVIEW / 'review-manifest.json'
    if review_path.exists():
        review = load(review_path)
        if hashlib.sha256(content).hexdigest() != review['sourceSha256']:
            raise ValueError('Editorial overlay does not match the frozen rebuild')
        indexed = {r['词条ID']: r for r in rows}
        coverage = set()
        for partition in review['partitions']:
            first, last = partition['range']
            report_data = load(REVIEW / partition['report'])
            completed = report_data.get('coveredRows', report_data.get('semanticComplete'))
            source_digest = report_data.get('sourceSha256', report_data.get('inputSha256'))
            if (completed != last - first + 1 or report_data['range'] != [first, last]
                    or source_digest != review['sourceSha256']):
                raise ValueError('Editorial partition is not fully reviewed')
            assigned = set(range(first, last + 1))
            if coverage & assigned:
                raise ValueError('Overlapping editorial partitions')
            coverage |= assigned
        if coverage != set(range(len(rows))):
            raise ValueError('Editorial review must cover every row')
        allowed = {'英文释义', '词性', '例句JSON', '部件JSON', '本义解释JSON', '引申义解释JSON'}
        for filename in review['overlays']:
            changes = load(REVIEW / filename)
            permitted = allowed | ({'拼音'} if filename.startswith('primary_corrections_') else set())
            for word_id, fields in changes.items():
                if word_id not in indexed or not set(fields) <= permitted:
                    raise ValueError('Editorial overlay changes a protected field or unknown ID')
                for field, value in fields.items():
                    if not isinstance(value, str):
                        raise ValueError('Editorial CSV field must be a string')
                    indexed[word_id][field] = json.loads(value) if field in JSON_FIELDS else value
        if len({(r['组词'], reading_key(r['拼音'])) for r in rows}) != len(rows):
            raise ValueError('Editorial corrections introduce duplicate word/reading identities')
        content = serialize(rows)
    expansion_path = DATA / 'expansion-20261005.json'
    if expansion_path.exists():
        expansion = load(expansion_path)
        for name, digest in expansion['sourceSha256'].items():
            if hashlib.sha256((ROOT / 'tools/wordlist_expansion_20261005' / name).read_bytes()).hexdigest() != digest:
                raise ValueError('Vocabulary expansion source differs before publishing: ' + name)
        if len(rows) != expansion['baseRows'] or hashlib.sha256(content).hexdigest() != expansion['baseSha256']:
            raise ValueError('Vocabulary expansion does not match the reviewed frozen base')
        additions = expansion['entries']
        if (len(additions) != expansion['additionalRows'] or
                dict(collections.Counter(row['罕度'] for row in additions)) != expansion['rarity']):
            raise ValueError('Vocabulary expansion count or learning levels changed')
        rows.extend(additions)
        if len({(row['组词'], reading_key(row['拼音'])) for row in rows}) != len(rows):
            raise ValueError('Vocabulary expansion introduces duplicate identities')
        content = serialize(rows)
    stage_path = DATA / 'stage-grading-20261005.json'
    if stage_path.exists():
        grading = load(stage_path)
        for name, digest in grading['sourceSha256'].items():
            if hashlib.sha256((ROOT / 'tools/wordlist_stages_20261005' / name).read_bytes()).hexdigest() != digest:
                raise ValueError('Stage grading source differs before publishing: ' + name)
        if len(rows) != grading['rows'] or hashlib.sha256(content).hexdigest() != grading['baseSha256']:
            raise ValueError('Stage grading does not match its frozen complete wordlist')
        decisions = grading['rarityById']
        if set(decisions) != {row['词条ID'] for row in rows} or any(
                level not in {'0','1','2','3','4'} for level in decisions.values()):
            raise ValueError('Stage grading must cover every word with a valid level')
        before = content.splitlines(keepends=True)
        for row in rows:
            row['罕度'] = decisions[row['词条ID']]
        content = serialize(rows)
        after = content.splitlines(keepends=True)
        if (len(before) != len(rows) + 1 or len(after) != len(before) or before[0] != after[0]
                or any(old[1:] != new[1:] for old, new in zip(before[1:], after[1:]))):
            raise ValueError('Stage grading changes a byte outside the rarity column')
    validate(content)
    if OUTPUT.exists():
        permitted = {hashlib.sha256(BASELINE.read_bytes()).hexdigest(), hashlib.sha256(content).hexdigest()}
        manifest = DATA/'build-manifest.json'
        if manifest.exists():
            permitted.add(load(manifest)['sha256'])
        if hashlib.sha256(OUTPUT.read_bytes()).hexdigest() not in permitted:
            raise ValueError('Final CSV has edits outside the frozen rebuild; refuse to overwrite')
    OUTPUT.write_bytes(content)
    report(content, rows)


def report(content, rows):
    ordered = sorted(rows,key=lambda r:(int(r['罕度']),pinyin_sort_key(r['拼音']),r['词条ID']))
    corrections = load(DATA/'sentence-corrections.json')
    summary={'schemaVersion':2,'words':len(rows),'retainedWords':7648,'newRarity0Idioms':75,
        'editorialCorrections':{'glossOverrides':len(load(DATA/'gloss-overrides.json')),
            'sentenceReadings':sum('pinyin' in value for value in corrections.values()),
            'sentenceChunks':sum('chunks' in value for value in corrections.values())},
        'inputSha256':{name:hashlib.sha256((DATA/name).read_bytes()).hexdigest() for name in
            ['baseline.csv','definitions.json','examples.json','additional-idioms.json','id-map.json',
             'gloss-overrides.json','sentence-corrections.json','draft-audit.json']},
        'rarity':dict(collections.Counter(r['罕度'] for r in rows)),
        'examples':sum(len(r['例句JSON']) for r in rows),'bytes':len(content),
        'sha256':hashlib.sha256(content).hexdigest(),'csvPhysicalOrder':'retained order, then frozen 75-idiom appendix',
        'learningOrder':'rarity, syllable letters then tone 1/2/3/4/5, next syllable, stable ID',
        'firstLearningWords':[{k:r[k] for k in ['词条ID','组词','拼音']} for r in ordered[:20]],
        'verification':'Complete structural/content-shape/reference/stroke-coverage checks. AI-assisted lexical gloss selection is not human linguistic sign-off.'}
    review_path = REVIEW / 'review-manifest.json'
    if review_path.exists():
        review = load(review_path)
        names = list(dict.fromkeys(review['overlays'] + [p['report'] for p in review['partitions']] + review.get('evidence', [])))
        summary['contentReview'] = {
            'reviewedRows': sum(p['range'][1]-p['range'][0]+1 for p in review['partitions']), 'date': '2026-10-05',
            'manifestSha256': hashlib.sha256(review_path.read_bytes()).hexdigest(),
            'inputSha256': {name: hashlib.sha256((REVIEW/name).read_bytes()).hexdigest() for name in names},
            'boundary': 'Complete AI editorial reading with contextual refinements and independent structural validation; not human linguistic certification.'}
    expansion_path = DATA / 'expansion-20261005.json'
    if expansion_path.exists():
        expansion = load(expansion_path)
        summary['inputSha256'][expansion_path.name] = hashlib.sha256(expansion_path.read_bytes()).hexdigest()
        summary['vocabularyExpansion'] = {key: expansion[key] for key in
            ['date','baseRows','baseSha256','additionalRows','rarity','sourceSha256','boundary']}
        summary['csvPhysicalOrder'] += ', then frozen 1,500-word expansion (500 each in levels 0/1/2)'
    stage_path = DATA / 'stage-grading-20261005.json'
    if stage_path.exists():
        grading = load(stage_path)
        summary['inputSha256'][stage_path.name] = hashlib.sha256(stage_path.read_bytes()).hexdigest()
        summary['stageGrading'] = {key: grading[key] for key in
            ['date','rows','baseSha256','reviewedRows','levels','sourceSha256','boundary']}
        summary['csvPhysicalOrder'] += '; five-stage grading changes only rarity cells'
    save(ROOT/'docs/wordlist-validation.json',summary)
    save(DATA/'build-manifest.json',summary)
    print(json.dumps(summary,ensure_ascii=False))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--prepare',action='store_true')
    mode.add_argument('--apply',action='store_true')
    mode.add_argument('--check',action='store_true')
    args=parser.parse_args()
    if args.prepare:
        prepare()
    elif args.apply:
        apply()
    else:
        content=OUTPUT.read_bytes();rows=validate(content)
        expected=load(DATA/'build-manifest.json')
        for name, digest in expected['inputSha256'].items():
            if hashlib.sha256((DATA/name).read_bytes()).hexdigest()!=digest:
                raise ValueError('Frozen rebuild input differs from manifest: '+name)
        if 'vocabularyExpansion' in expected:
            expansion = expected['vocabularyExpansion']
            for name, digest in expansion['sourceSha256'].items():
                if hashlib.sha256((ROOT/'tools/wordlist_expansion_20261005'/name).read_bytes()).hexdigest() != digest:
                    raise ValueError('Vocabulary expansion source differs: '+name)
        if 'stageGrading' in expected:
            for name, digest in expected['stageGrading']['sourceSha256'].items():
                if hashlib.sha256((ROOT/'tools/wordlist_stages_20261005'/name).read_bytes()).hexdigest() != digest:
                    raise ValueError('Stage grading source differs: '+name)
        if 'contentReview' in expected:
            review = expected['contentReview']
            if hashlib.sha256((REVIEW/'review-manifest.json').read_bytes()).hexdigest() != review['manifestSha256']:
                raise ValueError('Editorial review manifest differs')
            for name, digest in review['inputSha256'].items():
                if hashlib.sha256((REVIEW/name).read_bytes()).hexdigest() != digest:
                    raise ValueError('Editorial input differs from manifest: '+name)
        if hashlib.sha256(content).hexdigest()!=expected['sha256']:
            raise ValueError('Final CSV hash differs from rebuild manifest')
        print(json.dumps({'words':len(rows),'bytes':len(content),'sha256':expected['sha256'],'status':'PASS'},ensure_ascii=False))


if __name__=='__main__':
    main()
