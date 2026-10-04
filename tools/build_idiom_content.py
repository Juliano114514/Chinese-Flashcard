"""Assemble source-backed idiom teaching content with explicit draft provenance.

Only public dictionary text and newly authored teaching sentences are sent to
the optional translation endpoint. This tool never edits wordlist.csv or app
sources. Authored sentences live in idiom_curated/modern_*.tsv; translations
are cached so an offline --build is deterministic after --translate.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import csv
import hashlib
import json
import re
import time
import unicodedata
from pathlib import Path

import requests

from complete_wordlist import marked_pinyin, plain_pinyin, replace_target_pinyin, spaced_pinyin, tokens

ROOT = Path(__file__).resolve().parents[1]
SELECTION = ROOT / 'tools/idiom_selection.json'
CURATED = ROOT / 'tools/idiom_curated'
CACHE = ROOT / '.gradle/wordlist-refinement/idiom-translations'
ENDPOINT = 'https://translate.googleapis.com/translate_a/single'
DICT = ROOT / '.gradle/wordlist-implementation/dictionary-index.json'
MODERN_MARKER = re.compile(r'(?:原|也|又|亦|现(?:在)?(?:多|常)?|后(?:来)?(?:多|常)?)?(?:也|又|亦)?(?:可|多|常)?(?:用(?:来|以))?比喻|现(?:在)?(?:多|常)?(?:也|又|亦|泛)?(?:用(?:来|以))?(?:指|形容)|后(?:来)?(?:多|常)?(?:也|又|亦|泛)?(?:用(?:来|以))?(?:指|形容)|引申(?:为|指)')


def read_authored():
    result = {}
    for path in sorted(CURATED.glob('modern_*.tsv')):
        for line, row in enumerate(csv.reader(path.open(encoding='utf-8-sig'), delimiter='\t'), 1):
            if not row or row[0].startswith('#'):
                continue
            if len(row) not in (2, 3) or not re.fullmatch(r'cy_\d{5}', row[0]):
                raise ValueError(f'Invalid authored sentence row: {path.name}:{line}')
            if row[0] in result:
                raise ValueError('Repeated authored ID: ' + row[0])
            result[row[0]] = {'sentences': row[1:], 'file': path.name, 'line': line}
    return result


def clean_english(value):
    value = re.sub(r'[\u3400-\u9fff]+\|([\u3400-\u9fff]+)\[[^]]+\]', r'\1', value)
    value = re.sub(r'([\u3400-\u9fff]+)\[[^]]+\]', r'\1', value)
    value = re.sub(r'\((?:abbr\. for|Note:|typically preceded by)[^)]*\)', '', value, flags=re.I)
    value = re.sub(r'\((?:idiom|fig\.?|lit\.?)\)', '', value, flags=re.I)
    value = re.sub(r'\bsth\b', 'something', value)
    value = re.sub(r'\bsb\b', 'someone', value)
    return re.sub(r'\s+', ' ', value).strip(' ;.')


def explanations(item):
    original = re.sub(r'\s+', '', item['mapull']['explanation']).strip()
    # Retain attested text, rather than manufacture an origin from glyph glosses.
    marker = MODERN_MARKER.search(original)
    literal = original[:marker.start()].strip('。；，：') + '。' if marker and marker.start() else ''
    modern = original[marker.start():] if marker else original
    if item.get('attestedLiteralEnglish'):
        literal = ''  # Prefer a complete, explicitly attested CEDICT image over term fragments.
    elif literal:
        literal = '词典中的原义或字面说明：' + literal
    else:
        literal = '词典未单列独立的历史本义；按整体词义理解：' + original
    modern = '现代用法：' + modern
    return literal, modern


def part_drafts(item, english, dictionary, literal_english='', custom=None):
    if custom:
        if ''.join(part['hanzi'] for part in custom) != item['hanzi']:
            raise ValueError('Contextual parts do not reconstruct ' + item['id'])
        return custom
    result = []
    for glyph, reading in zip(item['hanzi'], item['pinyin'].split()):
        entries = [entry for entry in dictionary.get(glyph, [])
            if not entry['pinyin'][0].isupper()
            and (unicodedata.normalize('NFC', marked_pinyin(entry['pinyin'])) == unicodedata.normalize('NFC', reading)
                or (reading == plain_pinyin(reading) and plain_pinyin(marked_pinyin(entry['pinyin'])) == reading))]
        possible = []
        for entry in entries:
            for sense in entry['senses']:
                if re.search(r'surname|Kangxi|radical|^see |variant of|^CL:|^also pr\.|^old pr\.|^Taiwan pr\.|^abbr\. for|^used in', sense, re.I):
                    continue
                cleaned = clean_english(re.sub(r'\((?:bound form|literary|archaic|coll\.|colloquial)\)', '', sense)).strip(' ,;')
                if (cleaned and len(cleaned) <= 100 and not cleaned[0].isupper()
                        and not re.search(r'[\u3400-\u9fff\[\]|]|-[a-z]{2,}\b|^classifier|^measure word', cleaned)):
                    possible.append(cleaned)
        if not possible:
            # CEDICT represents some characters only by a bound-word/reference
            # entry. Resolve that public reference without inventing a standalone
            # meaning or selecting a personal name.
            for entry in entries:
                for sense in entry['senses']:
                    if not re.match(r'(?:used in|see|variant of) ', sense):
                        continue
                    reference = re.search(r'[\u3400-\u9fff]+(?:\|([\u3400-\u9fff]+))?\[([^]]+)\]', sense)
                    if not reference:
                        continue
                    head = reference[1] or reference[0].split('[')[0]
                    reference_reading = plain_pinyin(marked_pinyin(reference[2]))
                    choices = [s for e in dictionary.get(head, [])
                        if plain_pinyin(marked_pinyin(e['pinyin'])) == reference_reading
                        for s in e['senses'] if not re.search(r'surname|^see |variant of|^CL:|^also pr\.|^used in', s, re.I)]
                    if choices:
                        meaning = clean_english(re.sub(r'\([^)]*\)', '', choices[0]))
                        if re.search(r'[\u3400-\u9fff\[\]|]', meaning):
                            continue
                        possible.append(('bound character in ' + head + ': ' + meaning)
                            if sense.startswith('used in ') else meaning)
                        break
                if possible:
                    break
        if not possible:
            # Some contextual/neutral readings lack an independent CEDICT
            # character sense. Preserve the attested whole idiom in that case.
            return [{'hanzi': item['hanzi'], 'pinyin': item['pinyin'], 'english': english,
                'glossZh': '整体词义：' + item['mapull']['explanation'] + '（此条不提供未经词典确认的逐字拆释。）',
                'fallbackReason': 'No reliable non-name character sense for ' + glyph + ' at this reading'}]
        context = tokens(literal_english)
        modern_context = tokens(english)
        scored = [(3 * len(context & tokens(value)) + len(modern_context & tokens(value)), index, value)
            for index, value in enumerate(possible)]
        if len(possible) >= 4 and not max(score for score, _, _ in scored):
            return [{'hanzi': item['hanzi'], 'pinyin': item['pinyin'], 'english': english,
                'glossZh': '整体学习：' + item['mapull']['explanation'] + '（此条不把多义字的孤立义项当作成语内义。）',
                'fallbackReason': 'Polysemous character lacks a reliable contextual sense match: ' + glyph}]
        selected = max(enumerate(possible), key=lambda pair: (3 * len(context & tokens(pair[1])) + len(modern_context & tokens(pair[1])), -pair[0]))[1]
        query = f'In the Chinese expression {item["hanzi"]}, the character {glyph} means {selected}.'
        result.append({'hanzi': glyph, 'pinyin': reading, 'english': selected, 'translationQuery': query})
    if len(result) != len(item['hanzi']):
        raise ValueError('Part reading positions differ: ' + item['id'])
    return result


def prepare_items(selection, authored):
    pending = []
    drafts = []
    overrides_path = CURATED / 'overrides.json'
    overrides = json.loads(overrides_path.read_text(encoding='utf-8')) if overrides_path.exists() else {}
    semantic_path = CURATED / 'semantic-review.json'
    semantic = json.loads(semantic_path.read_text(encoding='utf-8')) if semantic_path.exists() else {}
    example_review_path = CURATED / 'example_english_review.json'
    example_review = json.loads(example_review_path.read_text(encoding='utf-8')) if example_review_path.exists() else {}
    reviewed_parts = {}
    reviewed_part_sources = {}
    for part_path in sorted(CURATED.glob('breakdown-reviewed-*.json')):
        for identity, parts in json.loads(part_path.read_text(encoding='utf-8')).items():
            if identity in reviewed_parts:
                raise ValueError('Repeated semantic breakdown identity: ' + identity)
            reviewed_parts[identity] = parts
            reviewed_part_sources[identity] = part_path.name
    literal_review = {}
    literal_path = CURATED / 'literal_context_review.tsv'
    if literal_path.exists():
        literal_review = {row[0]: row[1] for row in csv.reader(literal_path.open(encoding='utf-8'), delimiter='\t')
            if row and not row[0].startswith('#')}
    dictionary = json.loads(DICT.read_text(encoding='utf-8'))
    for item in selection['items']:
        custom = {**semantic.get(item['id'], {}), **example_review.get(item['id'], {}), **overrides.get(item['id'], {})}
        sentences = authored.get(item['id'], {}).get('sentences', [])
        if len(sentences) != 2:
            pending.append(item['id'])
            continue
        for sentence in sentences:
            if item['hanzi'] not in sentence or len(sentence) > 120 or '\x00' in sentence:
                raise ValueError('Invalid authored example for ' + item['id'])
        if sentences[0] == sentences[1]:
            raise ValueError('Repeated example for ' + item['id'])
        literal, modern = explanations(item)
        literal = custom.get('literalZh', literal)
        modern = custom.get('figurativeZh', modern)
        explicit_parts = reviewed_parts.get(item['id'], custom.get('parts'))
        if explicit_parts:
            joined_reading = ' '.join(part['pinyin'].strip() for part in explicit_parts)
            if unicodedata.normalize('NFC', joined_reading) != unicodedata.normalize('NFC', item['pinyin']):
                raise ValueError('Contextual part pinyin does not reconstruct ' + item['id'])
            for part in explicit_parts:
                if not all(isinstance(part.get(key), str) and part[key].strip() for key in ['hanzi', 'pinyin']):
                    raise ValueError('Invalid contextual part strings: ' + item['id'])
                if part.get('gloss') and ('\x00' in part['gloss'] or len(part['gloss']) > 300):
                    raise ValueError('Invalid contextual part gloss: ' + item['id'])
        english = clean_english(custom.get('english', item['selectedModernEnglish']))
        if english.startswith('see '):
            reference = re.search(r'[\u3400-\u9fff]+', english)
            matching = json.loads(DICT.read_text(encoding='utf-8')).get(reference[0], []) if reference else []
            senses = [s for e in matching for s in e['senses'] if not s.startswith(('see ', 'variant of '))]
            if not senses:
                raise ValueError('Unresolved dictionary reference: ' + item['id'])
            english = clean_english(senses[0])
        marker = MODERN_MARKER.search(item['mapull']['explanation'])
        short_zh = ''
        if marker and 'english' not in custom:
            short_zh = item['mapull']['explanation'][marker.end():].split('。')[0].strip('，；： ') + '。'
        risks = []
        if '[' in item['selectedModernEnglish'] or '|' in item['selectedModernEnglish']:
            risks.append('dictionary annotation or reference in original selected sense')
        if clean_english(item['selectedModernEnglish']) != item['selectedModernEnglish']:
            risks.append('short-form lexical cleanup required')
        if marker:
            risks.append('explicit source modern sense checked instead of trusting the first English image')
        if re.search(r'^(?:to )?(?:throw|swallow|hold|carry|turn|ride)|sky|earth|fish|bird|sword|cleaver|horse|tiger|dragon|water|fire|flower|bone', item['selectedModernEnglish'], re.I):
            risks.append('possible literal imagery: checked against attested source modern sense')
        if re.search(r'也|又|原|现|后|同[“「]?', item['mapull']['explanation']):
            risks.append('source contains polysemy, historical usage or a cross-reference')
        drafts.append({'input': item, 'sentences': sentences, 'literalZh': literal,
            'figurativeZh': modern, 'literalEn': clean_english(custom.get('literalEn', literal_review.get(item['id'], item.get('attestedLiteralEnglish', '')))),
            'figurativeEn': custom.get('figurativeEn', ''),
            'exampleEnglishOverrides': [custom.get('firstEn', ''), custom.get('secondEn', '')],
            'english': english, 'shortZh': short_zh, 'riskFlags': risks,
            'parts': part_drafts(item, english, dictionary,
                custom.get('literalEn', literal_review.get(item['id'], item.get('attestedLiteralEnglish', ''))), explicit_parts),
            'authored': authored[item['id']], 'override': bool(custom),
            'englishOverride': 'english' in custom, 'contextualLiteralReview': item['id'] in literal_review,
            'semanticReview': semantic.get(item['id'], {})})
        drafts[-1]['partReviewSource'] = reviewed_part_sources.get(item['id'], '')
    if pending:
        raise ValueError(f'{len(pending)} idioms still need two contextual examples: ' + ', '.join(pending[:20]))
    return drafts


def translation_key(text, direction='zh-en'):
    return hashlib.sha256((direction + '\0' + text).encode('utf-8')).hexdigest()


def cached_translation(text, direction='zh-en'):
    path = CACHE / (translation_key(text, direction) + '.json')
    return json.loads(path.read_text(encoding='utf-8')) if path.exists() else None


def translate_batch(job):
    direction, texts = job
    sl, tl = ('zh-CN', 'en') if direction == 'zh-en' else ('en', 'zh-CN')
    query = '\n'.join(texts)
    if len(query) > 1800 or any('\n' in text for text in texts):
        raise ValueError('Translation batches require bounded single-line texts.')
    last_error = None
    for attempt in range(3):
        try:
            response = requests.get(ENDPOINT, params={'client': 'gtx', 'sl': sl, 'tl': tl,
                'dt': ['t', 'rm'], 'q': query}, timeout=(5, 20), allow_redirects=False)
            response.raise_for_status()
            if len(response.content) > 1024 * 1024:
                raise ValueError('Translation response is too large.')
            payload = response.json()
            translated = ''.join(row[0] for row in payload[0] if row[0]).strip().splitlines()
            romanization = ''.join(row[3] for row in payload[0] if len(row) > 3 and row[3]).strip().splitlines()
            if len(translated) != len(texts) or (direction == 'zh-en' and len(romanization) != len(texts)):
                if len(texts) > 1:
                    return sum((translate_batch((direction, [text])) for text in texts), [])
                raise ValueError('Translation response did not preserve text boundaries.')
            entries = []
            for n, text in enumerate(texts):
                entry = {'sourceText': text, 'translation': translated[n],
                    'pinyinDraft': romanization[n] if direction == 'zh-en' else '',
                    'direction': direction, 'provider': 'Google public translation endpoint',
                    'status': 'machine draft; not human-verified', 'retrievedDate': '2026-10-04'}
                path = CACHE / (translation_key(text, direction) + '.json')
                path.write_text(json.dumps(entry, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
                entries.append(entry)
            return entries
        except (requests.RequestException, ValueError, KeyError, IndexError, TypeError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(1.0 * (2 ** attempt))
    raise RuntimeError('Public teaching-text translation failed after three attempts.') from last_error


def translate_missing(drafts):
    CACHE.mkdir(parents=True, exist_ok=True)
    requests_by_direction = {'zh-en': set(), 'en-zh': set()}
    for draft in drafts:
        requests_by_direction['zh-en'].update(draft['sentences'])
        if draft['literalZh'] and not draft['literalEn'] and not draft['literalZh'].startswith('词典未单列'):
            requests_by_direction['zh-en'].add(draft['literalZh'])
        if draft['shortZh']:
            requests_by_direction['zh-en'].add(draft['shortZh'])
        if not draft['literalZh'] and draft['literalEn']:
            requests_by_direction['en-zh'].add(draft['literalEn'])
        requests_by_direction['en-zh'].update(part['translationQuery'] for part in draft['parts'] if not part.get('glossZh') and not part.get('gloss'))
    jobs = []
    for direction, texts in requests_by_direction.items():
        batch = []
        length = 0
        for text in sorted(texts):
            if cached_translation(text, direction):
                continue
            if batch and (len(batch) >= 12 or length + len(text) + 1 > 1800):
                jobs.append((direction, batch)); batch = []; length = 0
            batch.append(text); length += len(text) + 1
        if batch:
            jobs.append((direction, batch))
    completed = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for entries in pool.map(translate_batch, jobs):
            completed += len(entries)
            if completed % 120 < len(entries):
                print(json.dumps({'translatedTexts': completed, 'batches': len(jobs)}), flush=True)
    return completed


def translation(text, direction='zh-en'):
    result = cached_translation(text, direction)
    if not result:
        raise ValueError('Missing cached public-text translation; run --translate first.')
    return result


def calibrate_particles(sentence, pinyin, target):
    """Correct common structural particles while protecting dictionary idioms.

    These explicit constructions distinguish de/di, de/dei and le/liao.
    This is a bounded contextual correction, not a general pronunciation model.
    """
    characters = list(re.finditer(r'[\u3400-\u9fff]', sentence))
    syllables = list(re.finditer(r'[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹňḿ]+', pinyin))
    if len(characters) != len(syllables):
        raise ValueError('Cannot align structural particle corrections.')
    protected = [(match.start(), match.end()) for match in re.finditer(re.escape(target), sentence)]
    di_words = '地上 地面 地铁 地下 地球 地板 地点 地步 地区 地方 地域 地理 地形 地震 地狱 地势 地盘 地图 地位 地址 目的地 土地 场地 基地 当地 实地 扫地 落地 耕地 园地 占地 遍地 就地 山地 平地 境地 大地 阵地 天地 胜地 外地 盐碱地 草地 绿地 湿地 田地 工地 营地 墓地 余地 腹地 领地 属地 之地'.split()
    de_verb_words = '得到 获得 取得 赢得 心得 得意 得当 得力 得罪 得出 得失 得救 得知 得以 得病 得分 得奖 得利 得逞 得胜 值得 难得 不得已 不得不 不得了 只得 得了'.split()
    de_particle_words = '觉得 记得 免得 显得 懂得 认得 舍得 舍不得 来得及 巴不得 恨不得'.split()
    liao_words = '了解 了结 了却 了不起 知了 了然 了无 了断 不了了之'.split()
    def contains_word(position, words):
        return any(any(match.start() <= position < match.end() for match in re.finditer(re.escape(word), sentence)) for word in words)
    corrections = []
    for index, (character, syllable) in enumerate(zip(characters, syllables)):
        position = character.start()
        if any(start <= position < end for start, end in protected):
            continue
        glyph = character[0]
        reading = syllable[0]
        corrected = reading
        rule = ''
        if glyph == '的':
            if contains_word(position, ['目的']):
                corrected, rule = 'dì', 'lexical di in purpose'
            elif not contains_word(position, ['的确', '的士', '的卢']):
                corrected, rule = 'de', 'structural or possessive de'
        elif glyph == '地':
            if contains_word(position, di_words):
                corrected, rule = 'dì', 'noun meaning ground/place'
            else:
                corrected, rule = 'de', 'adverbial structural de'
        elif glyph == '得':
            if contains_word(position, de_particle_words):
                corrected, rule = 'de', 'fixed expression or structural complement de'
            elif contains_word(position, de_verb_words):
                corrected, rule = 'dé', 'verb meaning obtain or deserve'
            elif contains_word(position, ['就得']):
                corrected, rule = 'děi', 'modal meaning must'
            elif reading != 'děi':
                corrected, rule = 'de', 'structural complement de'
        elif glyph == '了':
            if contains_word(position, ['不了', '不得了']):
                corrected, rule = 'liǎo', 'potential complement liao'
            elif not contains_word(position, liao_words):
                corrected, rule = 'le', 'aspect or sentence-final le'
        if corrected != reading:
            corrections.append({'character': glyph, 'position': position, 'from': reading, 'to': corrected, 'rule': rule})
    for correction in reversed(corrections):
        index = next(i for i, match in enumerate(characters) if match.start() == correction['position'])
        span = syllables[index]
        pinyin = pinyin[:span.start()] + correction['to'] + pinyin[span.end():]
    return pinyin, corrections


def build(drafts, selection):
    dictionary = json.loads(DICT.read_text(encoding='utf-8'))
    items = []
    romanization_issues = []
    grammar_path = CURATED / 'short_grammar_review.json'
    grammar = json.loads(grammar_path.read_text(encoding='utf-8')) if grammar_path.exists() else {}
    for draft in drafts:
        item = draft['input']
        examples = []
        particle_corrections = []
        for example_index, sentence in enumerate(draft['sentences']):
            translated = translation(sentence)
            pinyin = unicodedata.normalize('NFC', translated['pinyinDraft'].lower())
            try:
                pinyin = spaced_pinyin(sentence, pinyin, dictionary)
                pinyin = replace_target_pinyin(sentence, pinyin, item['hanzi'], item['pinyin'])
                pinyin, corrections = calibrate_particles(sentence, pinyin, item['hanzi'])
                particle_corrections.append(corrections)
                glyphs = len(re.findall(r'[\u3400-\u9fff]', sentence))
                syllables = len(re.findall(r'[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹňḿ]+', pinyin))
                if glyphs != syllables:
                    raise ValueError('Hanzi and pinyin position counts differ.')
            except ValueError as error:
                romanization_issues.append({'id': item['id'], 'sentence': sentence, 'draft': pinyin,
                    'issue': str(error)})
                continue
            examples.append([sentence, pinyin, draft['exampleEnglishOverrides'][example_index] or translated['translation']])
        if len(examples) != 2:
            continue
        short = translation(draft['shortZh'])['translation'] if draft['shortZh'] else draft['english']
        if item['id'] in grammar and not draft['englishOverride']:
            reviewed = grammar[item['id']]
            reviewed_source = reviewed.get('sourceEnglish', '').removeprefix('Describes: ').removeprefix('Describes ')
            if not reviewed_source or clean_english(reviewed_source) == clean_english(short):
                short = reviewed['english']
        short = clean_english(short).rstrip('.')
        if not short or len(short) > 288 or re.search(r'[\u3400-\u9fff\[\]|]', short):
            raise ValueError('Short meaning needs a contextual override: ' + item['id'] + ' ' + short)
        if draft['literalZh']:
            if draft['literalEn']:
                literal_english = draft['literalEn']
            elif draft['literalZh'].startswith('词典未单列'):
                literal_english = 'The dictionary gives the whole-expression meaning, without a separate historical literal sense: ' + short + '.'
            else:
                literal_english = translation(draft['literalZh'])['translation']
            if literal_english and literal_english[-1] not in '.!?':
                literal_english += '.'
            literal = draft['literalZh'] + '\n' + literal_english
        else:
            literal = translation(draft['literalEn'], 'en-zh')['translation'] + '\n' + draft['literalEn'].rstrip('.') + '.'
        figurative = draft['figurativeZh'] + '\n' + (draft['figurativeEn'] or 'Modern use: ' + short + '.')
        if max(len(literal), len(figurative)) > 2000:
            raise ValueError('Explanation exceeds the app limit: ' + item['id'])
        if len(draft['parts']) == 1 and draft['parts'][0].get('fallbackReason'):
            draft['parts'][0]['glossZh'] = '整体学习：' + draft['figurativeZh'] + '（未提供未经词典与语境确认的逐字拆释。）'
            draft['parts'][0]['english'] = short
        parts = [{'hanzi': part['hanzi'], 'pinyin': part['pinyin'],
            'gloss': part['gloss'] if part.get('gloss') else (part.get('glossZh') or translation(part['translationQuery'], 'en-zh')['translation']) + '\n' + part['english']}
            for part in draft['parts']]
        if any(len(part['gloss']) > 300 for part in parts):
            raise ValueError('Bilingual memory gloss is too long: ' + item['id'])
        items.append({'id': item['id'], 'hanzi': item['hanzi'], 'pinyin': item['pinyin'],
            'english': 'Describes ' + short, 'pos': 'idiom', 'parts': parts,
            'first': examples[0], 'second': examples[1],
            'literalExplanation': literal, 'figurativeExplanation': figurative,
            'note': '现代语境例句；英文翻译与例句拼音为机器辅助稿，目标成语读音已按词典校准。\n'
                'Modern contextual examples. English translations and sentence pinyin are machine-assisted drafts; '
                'the target idiom reading is aligned to the dictionary.\n'
                '结合本义与现代用法理解语义分组。\n'
                'Read the semantic groups together with the literal and modern meaning.\n'
                '语义分组依据词典与语境由AI校订，不等同历史词源。\n'
                'Semantic groups were revised by AI against dictionary definitions and context; they are not historical etymologies.',
            'source': f"mapull/chinese-dictionary MIT, pinned {selection['source']['commit']}, idiom/idiom.json entry {item['hanzi']}; "
                'CC-CEDICT CC BY-SA 4.0 selected modern sense; newly authored teaching examples; '
                'Google machine-assisted translation/pinyin (2026-10-04) with targeted AI corrections; '
                'AI-authored contextual semantic groups, not historical etymology; no claim of human review.',
            'evidence': {'dictionaryRecord': item['mapull'], 'cedict': item['cedict'],
                'frequency': item['frequency'], 'authoredFile': draft['authored']['file'],
                'authoredLine': draft['authored']['line'], 'shortSenseOverride': draft['override'],
                'shortSenseSource': 'attested mapull modern meaning, machine-translated' if draft['shortZh'] else 'context-selected CEDICT sense or documented override',
                'originalSelectedEnglish': item['selectedModernEnglish'], 'shortRiskFlags': draft['riskFlags'],
                'semanticReview': draft['semanticReview'],
                'literalReview': 'AI contextual correction grounded in the frozen dictionary record' if draft['contextualLiteralReview'] else 'explicit CEDICT literal sense or source dictionary explanation',
                'partSenseReview': [part.get('fallbackReason', 'AI-authored contextual semantic group based on the source dictionary')
                    if draft['partReviewSource'] or part.get('glossZh') else
                    'exact-tone, non-name dictionary sense selected in literal/modern context; translation is a contextual machine draft' for part in draft['parts']],
                'semanticBreakdownSource': draft['partReviewSource'],
                'exampleAuthoring': 'AI-authored; contextual review by author; not human review',
                'exampleEnglishCorrections': [bool(value) for value in draft['exampleEnglishOverrides']],
                'sentencePinyinCorrections': particle_corrections,
                'translationReview': 'machine-assisted draft; structural validation and targeted AI contextual corrections; not human sign-off'}})
    (CURATED / 'pinyin-issues.json').write_text(json.dumps(romanization_issues, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if romanization_issues:
        raise ValueError(f'{len(romanization_issues)} sentence romanizations need correction; see pinyin-issues.json.')
    result = {'schemaVersion': 1, 'selectionSha256': hashlib.sha256(SELECTION.read_bytes()).hexdigest(),
        'count': len(items), 'reviewBoundary': 'AI-authored modern examples; source-backed explanations; '
            'Google machine translation and contextual pinyin drafts; structural validation does not prove complete semantic review.',
        'quality': {'reviewedBreakdowns': sum(bool(draft['partReviewSource']) for draft in drafts),
            'semanticGroups': sum(len(item['parts']) for item in items),
            'exampleEnglishCorrections': sum(sum(item['evidence']['exampleEnglishCorrections']) for item in items),
            'sentenceParticleCorrections': sum(sum(len(corrections) for corrections in item['evidence']['sentencePinyinCorrections']) for item in items),
            'breakdownReviewMethod': 'AI source/context comparison, not human sign-off or historical etymology'},
        'items': items}
    (CURATED / 'catalog.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    rows = []
    for item in items:
        # Difficulty and all three distractors are set by the root assembly.
        rows.append({'罕度': '0', '组词': item['hanzi'], '拼音': item['pinyin'],
            '简单例句': item['first'][0], '例句拼音': item['first'][1], '例句英语翻译': item['first'][2],
            '词条ID': item['id'], '英文释义': item['english'], '词性': item['pos'],
            '第二例句': item['second'][0], '第二例句拼音': item['second'][1], '第二例句英语翻译': item['second'][2],
            '部件JSON': json.dumps(item['parts'], ensure_ascii=False, separators=(',', ':')),
            '使用提示': item['note'], '干扰词ID': '[]', '来源说明': item['source'],
            '本义解释': item['literalExplanation'], '引申义解释': item['figurativeExplanation']})
    (CURATED / 'rows_001_1000.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    audit = [{'id': item['id'], 'hanzi': item['hanzi'], 'short': item['english'],
        'original': item['evidence']['originalSelectedEnglish'], 'source': item['evidence']['shortSenseSource'],
        'riskFlags': item['evidence']['shortRiskFlags'], 'reviewBoundary': 'source comparison and targeted AI corrections; not a human sign-off'} for item in items]
    (CURATED / 'short-sense-audit.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'idioms': len(items), 'authoredModernExamples': 2 * len(items),
        'explicitCedictLiteralSenses': sum(bool(d['input'].get('attestedLiteralEnglish')) for d in drafts),
        'contextualLiteralEnglishCorrections': sum(d['contextualLiteralReview'] for d in drafts),
        'translationReview': 'machine drafts; no claim of human review'}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--translate', action='store_true', help='Translate only public dictionary/teaching text into the local cache.')
    parser.add_argument('--build', action='store_true', help='Build curated catalog from authored sentences and cached translations.')
    args = parser.parse_args()
    if not args.translate and not args.build:
        parser.error('Specify --translate and/or --build.')
    CURATED.mkdir(parents=True, exist_ok=True)
    selection = json.loads(SELECTION.read_text(encoding='utf-8'))
    drafts = prepare_items(selection, read_authored())
    if args.translate:
        translate_missing(drafts)
    if args.build:
        build(drafts, selection)


if __name__ == '__main__':
    main()
