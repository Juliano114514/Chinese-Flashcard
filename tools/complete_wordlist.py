"""Assemble the reviewed, offline CSV; never invent a missing review.

The original CSV and CC-CEDICT cache are inputs. Authored reviews live in
tools/wordlist_curated/*.json. --prepare creates work queues only; --check
checks the assembled CSV without rewriting it. No application/build is run.
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
from wordlist_difficulty import apply_ratings, validate_difficulty

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.gradle/wordlist-implementation'
CURATED = ROOT / 'tools/wordlist_curated'
ORIGINAL = ROOT / 'wordlist.original.csv'
OUTPUT = ROOT / 'wordlist.csv'
LEGACY_HEADERS = ['罕度', '组词', '拼音', '简单例句', '例句拼音', '例句英语翻译',
           '词条ID', '英文释义', '词性', '第二例句', '第二例句拼音', '第二例句英语翻译',
           '部件JSON', '使用提示', '干扰词ID', '来源说明']
HEADERS = LEGACY_HEADERS + ['本义解释', '引申义解释']
REFINED = ROOT / 'tools/wordlist_refined'
IDIOMS = ROOT / 'tools/idiom_curated'
STOP = set('a an the of to in on at by is are was were be been being and or for with this that it its his her their he she they i you we my our your from as into very more some one has have had not can will all'.split())
TONES = {'a':'āáǎà', 'e':'ēéěè', 'i':'īíǐì', 'o':'ōóǒò', 'u':'ūúǔù', 'ü':'ǖǘǚǜ'}
# This deletion decision belongs to the source CSV, not a regenerated asset report.
# Verified against pinned mainland-Chinese resources; user approved these removals.
UNAVAILABLE_GLYPHS = frozenset('㧐劻勷叆叇吔唶堄嫲徬捽杴柣灨牤珌珪睄磡粿腘觍踒迺雱颎髽龢')

def tokens(text):
    return {w.rstrip('s') for w in re.findall(r'[a-z]+', text.lower()) if w not in STOP and len(w) > 2}

def plain_pinyin(text):
    text = text.lower().replace('u:', 'ü').replace('v', 'ü')
    # Remove tone marks while retaining ü, which distinguishes lü from lu.
    return unicodedata.normalize('NFC', ''.join(c for c in unicodedata.normalize('NFD', text)
        if c not in '\u0300\u0301\u0304\u030c' and (c.isalpha() or c == '\u0308')))

def marked_pinyin(text):
    result = []
    for syllable in text.split():
        m = re.fullmatch(r'([A-Za-züÜ:]+)([0-5])', syllable)
        if not m:
            result.append(syllable.lower())
            continue
        stem, tone = m[1].lower().replace('u:', 'ü').replace('v', 'ü'), int(m[2])
        if tone not in (0, 5):
            vowels = [i for i,c in enumerate(stem) if c in TONES]
            if vowels:
                position = stem.index('a') if 'a' in stem else stem.index('e') if 'e' in stem else stem.index('o') if 'ou' in stem else vowels[-1]
                stem = stem[:position] + TONES[stem[position]][tone-1] + stem[position+1:]
        result.append(stem)
    return ' '.join(result)

def identity(hanzi, pinyin):
    return hanzi + '\0' + ''.join(unicodedata.normalize('NFC', pinyin).casefold().split())

_SYLLABLES = None
def spaced_pinyin(sentence, pinyin, index):
    """Separate authored word spellings without changing any supplied tone or letter."""
    global _SYLLABLES
    characters=re.findall(r'[\u3400-\u9fff]',sentence)
    matches=list(re.finditer(r'[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹḿ]+',pinyin,re.I))
    if len(matches)==len(characters): return pinyin
    if _SYLLABLES is None:
        _SYLLABLES={plain_pinyin(re.sub(r'[0-5]$','',s)) for entries in index.values() for entry in entries for s in entry['pinyin'].split()}
        _SYLLABLES|={'m','n','ng','hm','hng'}
    readings=[{plain_pinyin(marked_pinyin(e['pinyin'])) for e in index.get(c,[]) if len(e['pinyin'].split())==1} for c in characters]
    @functools.lru_cache(None)
    def split(character, token, offset):
        if token==len(matches): return (0,()) if character==len(characters) else None
        if character==len(characters): return None
        text=matches[token].group()
        best=None
        for end in range(offset+1,min(len(text),offset+8)+1):
            piece=text[offset:end];plain=plain_pinyin(piece)
            if plain not in _SYLLABLES: continue
            following=split(character+1,token+1,0) if end==len(text) else split(character+1,token,end)
            if following is None: continue
            score=following[0]+(10 if plain in readings[character] else 0)
            candidate=(score,((token,piece),)+following[1])
            if best is None or candidate[0]>best[0]: best=candidate
        return best
    result=split(0,0,0)
    if result is None: raise ValueError('Cannot align authored pinyin: '+sentence+' / '+pinyin)
    pieces=collections.defaultdict(list)
    for token,piece in result[1]: pieces[token].append(piece)
    output=[];position=0
    for n,match in enumerate(matches):
        output.extend([pinyin[position:match.start()],' '.join(pieces[n])]);position=match.end()
    output.append(pinyin[position:])
    return ''.join(output)

def read_inputs():
    rows = list(csv.DictReader(ORIGINAL.open(encoding='utf-8-sig', newline='')))
    index = json.loads((CACHE/'dictionary-index.json').read_text(encoding='utf-8'))
    return rows, index

def glosses(entry, index, seen=None):
    seen = set() if seen is None else seen
    values = []
    for raw in entry['senses']:
        if raw.startswith(('CL:', 'Taiwan pr.', 'also pr.', 'old pr.')):
            continue
        ref = re.match(r'(?:(?:old |archaic )?variant of |see |abbr\. for )([^\[ ]+)', raw)
        if ref:
            name = ref[1].split('|')[-1]
            if name not in seen:
                for target in index.get(name, [])[:2]:
                    values.extend(glosses(target, index, seen | {name}))
            continue
        clean = re.sub(r'\[[^\]]*\]', '', raw)
        clean = re.sub(r'[\u3400-\u9fff]+(?:\|[\u3400-\u9fff]+)?', '', clean)
        clean = re.sub(r'\s+', ' ', clean).strip(' ;,')
        if clean and len(clean) <= 300:
            values.append(clean)
    return list(dict.fromkeys(values))

def choose_entry(row, index):
    options = index.get(row['组词'], [])
    if not options:
        return None
    p = plain_pinyin(row['拼音'])
    readings = [e for e in options if plain_pinyin(marked_pinyin(e['pinyin'])) == p]
    candidates = readings or options
    context = tokens(row['例句英语翻译'])
    ranked = []
    for entry in candidates:
        for n,gloss in enumerate(glosses(entry, index)):
            overlap = len(context & tokens(gloss))
            score = overlap * 10 - n * .15
            if gloss.startswith('surname ') and not any(x in row['简单例句'] for x in ['姓', '先生', '女士']): score -= 5
            if re.search(r'\b(mountain|county|province|city|dynasty|surname)\b', gloss, re.I) and not re.search(r'山|县|省|城|姓|朝|史', row['简单例句']): score -= 2
            ranked.append((score, entry, gloss))
    if not ranked:
        return None
    _, entry, gloss = max(ranked, key=lambda x:x[0])
    return dict(english=gloss, pinyin=marked_pinyin(entry['pinyin']), dictionarySenses=glosses(entry,index), rawEntry=entry)

def replace_target_pinyin(hanzi, pinyin, word, reading):
    units = re.findall(r'[A-Za-züÜāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜńňǹḿ]+', pinyin)
    chars = [c for c in hanzi if '\u3400' <= c <= '\u9fff']
    start = hanzi.find(word)
    if start < 0 or len(chars) != len(units) or len(reading.split()) != len(word):
        raise ValueError('Cannot align pronunciation correction: '+word)
    at = sum('\u3400' <= c <= '\u9fff' for c in hanzi[:start])
    spans = list(re.finditer(r'[A-Za-züÜāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜńňǹḿ]+', pinyin))
    replacements = list(reading.split())
    for i in range(len(word)-1, -1, -1):
        span = spans[at+i]
        pinyin = pinyin[:span.start()] + replacements[i] + pinyin[span.end():]
    return pinyin

def prepare():
    rows,index = read_inputs()
    missing = UNAVAILABLE_GLYPHS
    sentence_map = {}
    for r in rows:
        if r['例句拼音']:
            sentence_map.setdefault(r['简单例句'], [r['简单例句'],r['例句拼音'],r['例句英语翻译']])
    jobs, drafts = [], {}
    for n,row in enumerate(rows, 2):
        if not row['拼音'] or set(row['组词']) & missing:
            continue
        draft = choose_entry(row,index)
        if not draft:
            continue
        # An alternative must retain this word's reading and the selected sense.
        alternatives = []
        for s in sentence_map.values():
            if row['组词'] not in s[0] or s[0] == row['简单例句']:
                continue
            try:
                corrected = replace_target_pinyin(s[0],s[1],row['组词'],draft['pinyin'])
            except ValueError:
                continue
            if corrected.casefold() != s[1].casefold():
                continue
            if not (tokens(draft['english']) & tokens(s[2])):
                continue
            alternatives.append(s)
        drafts[str(n)] = dict(draft, second=min(alternatives,key=lambda x:len(x[0])) if alternatives else None)
        if not alternatives:
            jobs.append(dict(line=n, **row, proposedEnglish=draft['english'], proposedPinyin=draft['pinyin'], dictionarySenses=draft['dictionarySenses']))
    folder=CACHE/'authoring'; folder.mkdir(exist_ok=True)
    for n in range(0,len(jobs),250):
        (folder/f'lex_{n//250:02}.json').write_text(json.dumps(jobs[n:n+250],ensure_ascii=False,indent=2),encoding='utf-8')
    (CACHE/'lexical-drafts.json').write_text(json.dumps(drafts,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'dictionaryDrafts':len(drafts),'usableExistingSecond':sum(bool(v['second']) for v in drafts.values()),'needAuthoredSecond':len(jobs),'batches':(len(jobs)+249)//250}))

def reviewed_content():
    reviews = {}
    if CURATED.exists():
        for path in sorted(CURATED.glob('*.json')):
            for line,value in json.loads(path.read_text(encoding='utf-8')).items():
                if line in reviews:
                    raise ValueError('Two authored reviews for original line '+line)
                reviews[line] = value
    return reviews

def infer_pos(gloss):
    if gloss.startswith('to '): return 'verb'
    if re.search(r'\b(surname|province|county|city|dynasty)\b',gloss,re.I): return 'proper noun'
    if re.search(r'\b(onomatopoeia|sound of)\b',gloss,re.I): return 'onomatopoeia'
    if re.search(r'\b(classifier|measure word)\b',gloss,re.I): return 'classifier'
    if gloss.lower() in {'today','tomorrow','yesterday','often','always','sometimes','already','again','perhaps','probably','together','quickly','slowly'}: return 'adverb'
    if gloss.lower() in {'big','small','large','little','long','short','good','bad','new','old','cold','hot','warm','cool','fast','slow','happy','sad','bright','dark','beautiful','ugly','red','green','blue','white','black','yellow','clean','dirty','empty','full','deep','shallow','high','low','strong','weak','soft','hard','kind','angry','tired','hungry','thirsty','quiet','noisy','expensive','cheap','difficult','easy','heavy','light','wet','dry','sweet','bitter','sour','salty','fresh','stale','healthy','sick','alive','dead','young','ancient','modern','clear','cloudy','comfortable','useful','important','possible','impossible','common','rare','polite','rude','careful','clever','smart','foolish','brave','afraid','safe','dangerous','wide','narrow','round','square'}: return 'adjective'
    # Do not invent a grammatical category for dictionaries without POS annotations.
    return 'expression'

def word_parts(word, pinyin, english, index, pos):
    syllables=pinyin.split()
    if len(syllables)!=len(word) or pos in {'proper noun','onomatopoeia','bound morpheme'}:
        return [dict(hanzi=word,pinyin=pinyin,gloss=english)]
    parts=[]
    for character,reading in zip(word,syllables):
        options=[e for e in index.get(character,[]) if identity(character,marked_pinyin(e['pinyin']))==identity(character,reading)]
        possible=[g for e in options for g in glosses(e,index) if len(g)<=300 and g.lower() not in {'used','used in','see'} and not g.endswith('e.g.')]
        if not possible:
            # An attested complete-word gloss is more honest than a missing-character placeholder.
            return [dict(hanzi=word,pinyin=pinyin,gloss=english)]
        context=tokens(english)
        gloss=max(possible,key=lambda x:(len(context&tokens(x)),-len(x)))
        parts.append(dict(hanzi=character,pinyin=reading,gloss=gloss))
    return parts

def note_for(row, english, pos, authored):
    pieces=[]
    if authored: pieces.append(authored)
    if any(x in row['简单例句'] for x in ['古文','古书','古代','古诗','古人','古称']) and not any(x in authored.lower() for x in ['literary','classical','historical']):
        pieces.append('Literary or historical vocabulary; the example presents this older usage.')
    if any(x in row['简单例句'] for x in ['方言','粤语']):
        pieces.append('Regional vocabulary. The pinyin gives Mandarin character readings, not Cantonese or another dialect pronunciation.')
    if not pieces:
        pieces.append('In these examples, '+row['组词']+' means '+english.rstrip('.')+'.')
    pieces.append('Character glosses are memory aids, not a claim about the word\'s origin; learn its complete contextual meaning.')
    return ' '.join(pieces)

def assemble_refined(regrade=True):
    """Keep the retained baseline's order and append persistent idiom content."""
    words = list(csv.DictReader((REFINED/'baseline.csv').open(encoding='utf-8-sig', newline='')))
    overrides = json.loads((REFINED/'overrides.json').read_text(encoding='utf-8'))
    if set(overrides) != {word['词条ID'] for word in words}:
        raise ValueError('Every retained stable ID requires a refinement production record.')
    seen = {identity(word['组词'], word['拼音']) for word in words}
    for word in words:
        change = overrides[word['词条ID']]
        if any(change.get(key, word[key]) != word[key] for key in ['词条ID', '组词', '拼音']):
            raise ValueError('Refinement cannot silently change retained identity: '+word['词条ID'])
        if set(change)-set(HEADERS):
            raise ValueError('Unknown refinement field: '+word['词条ID'])
        word.update(change)
        for key in HEADERS:
            word.setdefault(key, '')
    additions = []
    if IDIOMS.exists():
        for path in sorted(IDIOMS.glob('rows_*.json')):
            payload = json.loads(path.read_text(encoding='utf-8'))
            if not isinstance(payload, list):
                raise ValueError('Idiom content must be a list of CSV rows: '+path.name)
            for word in payload:
                if set(word) != set(HEADERS) or not re.fullmatch(r'cy_[0-9]{5}',word['词条ID']):
                    raise ValueError('Invalid persistent idiom record: '+path.name)
                ident = identity(word['组词'], word['拼音'])
                if ident in seen:
                    raise ValueError('New idiom duplicates a retained identity: '+word['组词'])
                seen.add(ident)
                additions.append(word)
    words.extend(sorted(additions, key=lambda word: word['词条ID']))
    if len(additions) != 1000 or {word['词条ID'] for word in additions} != {f'cy_{n:05}' for n in range(1, 1001)}:
        raise ValueError('The frozen idiom appendix requires exactly cy_00001 through cy_01000.')
    assign_idiom_distractors(words)
    removed = list(csv.DictReader((ROOT/'wordlist-removals.csv').open(encoding='utf-8-sig', newline='')))
    if regrade:
        apply_ratings(words)
    return words, removed


def assign_idiom_distractors(words):
    """Use equally formatted, semantically distinct idiom answers for idiom cards.

    Mixing descriptive idiom answers with single-noun answers reveals the correct
    answer through typography and grammar. These reviewed anchors cover distinct
    domains; conservative English/Chinese exclusions avoid a target's near senses.
    The application still grades the existing meaning IDs, not answer text.
    """
    idioms = [word for word in words if word['词性'] == 'idiom']
    by_word = {word['组词']: word for word in idioms}
    anchors = [
        ('言简意赅', 'concise brief expression words speech writing complete clarity lucid clear', '简明|简洁|说话|言语|表达|言辞|清楚|清晰|明白'),
        ('千钧一发', 'danger dangerous risk critical imminent emergency peril confrontation erupting tension inauspicious threat', '危急|危险|风险|险境|一触即发|紧张|凶险'),
        ('彬彬有礼', 'courteous courtesy polite refined gentle manners behavior', '礼貌|礼仪|温雅|儒雅|斯文'),
        ('无稽之谈', 'falsehood false factual fabricated baseless unfounded rumor lie deception', '无根据|虚构|凭空|捏造|谣言|欺骗'),
        ('鞠躬尽瘁', 'dedication devoted diligent duty effort strength hardworking tireless conscientious responsible assiduous industrious enduring endurance determination patiently repeated trouble', '勤奋|勤恳|勤勉|认真|尽责|尽力|劳苦|努力|奉献|尽职|坚持|竭力|忍住|耐心|不嫌麻烦'),
        ('深孚众望', 'trust confidence respected respect reputation popular approval', '信任|声望|威望|众望|信赖'),
        ('潸然泪下', 'sadness sad grief tears crying emotion sorrow unhappy distressed touching moving moved poignant', '悲伤|悲哀|眼泪|流泪|伤心|悲痛|感动|感人|触动'),
        ('穷兵黩武', 'military force war warfare battle fighting aggression', '战争|军队|用兵|武力|好战'),
        ('绿草如茵', 'grass green dense soft vegetation plants scenery lush', '草地|青草|茂盛|草木|绿草'),
        ('人才济济', 'talent talented skilled ability capable people excellence', '人才|才能|才华|能人'),
        ('敝帚自珍', 'treasure attachment cherish possession own imperfect value love like fond', '珍爱|珍惜|珍视|喜爱|喜欢|爱好|自珍|敝帚'),
        ('姗姗来迟', 'late arriving arrival delay delayed overdue', '来迟|迟到|晚到|延迟'),
        ('釜底抽薪', 'root cause problem solve solving fundamental solution essential point key core', '根本|根源|根除|解决问题|要害|关键|实质|症结'),
        ('良莠不齐', 'quality mixed good bad uneven variable inferior', '好坏|良莠|混杂|参差|质量'),
        ('噤若寒蝉', 'silent silence quiet afraid fear scared hesitant', '害怕|恐惧|沉默|不敢说|不敢发声'),
        ('一蹶不振', 'discouraged discouragement setback recover failure defeated hopeless spirits', '挫折|消沉|振作|失败|丧气'),
        ('睚眦必报', 'revenge vindictive resentment slight retaliation grudge', '报复|报仇|怨恨|记仇'),
        ('津津有味', 'enjoy enjoyment enjoyable interesting pleasure delicious appetite taste like love fond enthralled engrossed absorbed captivated fascination', '兴趣|兴味|喜欢|喜爱|爱好|滋味|津津|美味|入迷|沉迷|陶醉|如痴如醉'),
        ('一望无垠', 'landscape horizon vast endless limitless distance extensive broad', '广阔|辽阔|边际|无垠|无际'),
        ('大惊小怪', 'fuss exaggerated surprise reaction overreacting trivial', '惊怪|大惊|惊讶|小事|过分惊'),
        ('小心翼翼', 'careful cautious caution meticulous attentive prudent', '小心|谨慎|仔细|慎重'),
        ('井井有条', 'order orderly organization organized neat systematic tidy', '条理|整齐|有序|秩序'),
        ('同舟共济', 'unity together cooperation cooperative mutual help joint difficulties common cause ideals aims', '团结|互助|合作|共同|齐心|同心|志趣|共同目标|意见一致'),
        ('诚心诚意', 'sincere sincerity earnest genuine honest truthful integrity', '诚恳|诚心|真诚|诚意|真心'),
        ('循序渐进', 'gradual step incremental steady progress sequence methodical improvement improving improve better practice', '逐步|循序|渐进|一步步|按步骤|逐渐|渐渐|进步|好转|练习|熟练'),
    ]
    generic = {'describe', 'describ', 'someone', 'something', 'person', 'people', 'thing', 'behavior', 'state', 'situation'}
    available = [(by_word[name], tokens(domain)-generic, re.compile(chinese))
                 for name, domain, chinese in anchors if name in by_word]
    if len(available) < 20:
        raise ValueError('Reviewed idiom distractor anchors are missing.')
    for n, word in enumerate(idioms):
        meaning = re.sub(r'^Describes:\s*', 'Describes ', word['英文释义'])
        if not meaning.startswith('Describes '):
            raise ValueError('Idiom answer must describe its modern sense: '+word['组词'])
        word['英文释义'] = meaning
        target = tokens(meaning)-generic
        chinese = word.get('引申义解释', '').split('\n')[0]
        candidates = [(anchor, domain) for anchor, domain, pattern in available
                      if anchor['词条ID'] != word['词条ID'] and not target & domain and not pattern.search(chinese)]
        offset = n % len(candidates) if candidates else 0
        candidates = candidates[offset:] + candidates[:offset]
        selected = []
        labels = {meaning.strip().casefold()}
        domains = set()
        for anchor, domain in candidates:
            label = re.sub(r'^Describes:\s*', 'Describes ', anchor['英文释义']).strip().casefold()
            if label in labels or domains & domain:
                continue
            selected.append(anchor['词条ID'])
            labels.add(label)
            domains.update(domain)
            if len(selected) == 3:
                break
        if len(selected) != 3:
            raise ValueError('No three distinct idiom distractors for '+word['组词'])
        word['干扰词ID'] = json.dumps(selected, separators=(',', ':'))


def assemble(regrade=True):
    if (REFINED/'baseline.csv').exists():
        return assemble_refined(regrade)
    rows,index=read_inputs()
    drafts=json.loads((CACHE/'lexical-drafts.json').read_text(encoding='utf-8'))
    reviews=reviewed_content()
    missing=UNAVAILABLE_GLYPHS
    # Several original cards share one sentence. Propagate verified pronunciation
    # corrections to those copies, rather than leaving a different card with an old error.
    first_by_sentence={r['简单例句']:[r['简单例句'],r['例句拼音'],r['例句英语翻译']] for r in rows}
    for line,original in enumerate(rows,2):
        authored=reviews.get(str(line),{})
        if authored.get('drop'): continue
        revised=authored.get('first')
        if revised:
            first_by_sentence[original['简单例句']]=list(revised)
            first_by_sentence[original['简单例句']][1]=spaced_pinyin(revised[0],revised[1],index)
        if authored.get('pinyin') and original['拼音'] and identity(original['组词'],authored['pinyin'])!=identity(original['组词'],original['拼音']):
            sentence=first_by_sentence[original['简单例句']]
            if original['组词'] in sentence[0]:
                sentence[1]=replace_target_pinyin(sentence[0],sentence[1],original['组词'],authored['pinyin'])
    output,removed,unfinished=[],[],[]
    for line,row in enumerate(rows,2):
        review=reviews.get(str(line),{})
        reason=review.get('drop')
        if set(row['组词'])&missing:
            reason='No verified Mandarin stroke geometry: '+''.join(sorted(set(row['组词'])&missing))
        elif not row['拼音'] and not review:
            reason='Original verification placeholder; no reliable word meaning, pronunciation and use established.'
        if reason:
            removed.append({'原始行':line,'词条ID':f'wl_{line-1:05}','组词':row['组词'],'原拼音':row['拼音'],'原因':reason})
            continue
        draft=drafts.get(str(line),{})
        english=review.get('english',draft.get('english'))
        # A dictionary candidate is evidence for meaning, not authorization to
        # replace a reviewed polyphonic/neutral reading in the supplied card.
        reading=review.get('pinyin',row['拼音'])
        second=review.get('second',draft.get('second'))
        if not english or not second:
            unfinished.append({'line':line,'hanzi':row['组词'],'missingEnglish':not english,'missingSecond':not second})
            continue
        if not review.get('second'):
            revised_second=first_by_sentence.get(second[0])
            if revised_second and row['组词'] in revised_second[0]: second=revised_second
        second=list(second)
        shared_first=first_by_sentence[row['简单例句']]
        if row['组词'] not in shared_first[0]: shared_first=[row['简单例句'],row['例句拼音'],row['例句英语翻译']]
        first=review.get('first',shared_first)
        first=list(first)
        reading=spaced_pinyin(row['组词'],reading,index)
        first[1]=spaced_pinyin(first[0],first[1],index)
        second[1]=spaced_pinyin(second[0],second[1],index)
        if identity(row['组词'],reading)!=identity(row['组词'],row['拼音']):
            first[1]=replace_target_pinyin(first[0],first[1],row['组词'],reading)
            if not review.get('second'):
                second[1]=replace_target_pinyin(second[0],second[1],row['组词'],reading)
        pos=review.get('pos',infer_pos(english))
        parts=review.get('parts',word_parts(row['组词'],reading,english,index,pos))
        source=review.get('source','CC-CEDICT (MDBG, CC BY-SA 4.0); selected contextual sense; original supplied example and translation.')
        if not review.get('second'):
            source+=' Second example: reused supplied sentence in a different context.'
        record=dict(zip(LEGACY_HEADERS, [row['罕度'],row['组词'],reading,*first,f'wl_{line-1:05}',english,pos,*second,
          json.dumps(parts,ensure_ascii=False,separators=(',',':')),note_for(row,english,pos,review.get('note','')),'',source]))
        record.update({'本义解释':'','引申义解释':''})
        output.append(record)
    if unfinished:
        (CACHE/'unfinished-content.json').write_text(json.dumps(unfinished,ensure_ascii=False,indent=2),encoding='utf-8')
        raise ValueError(f'{len(unfinished)} retained words still need authored content; original CSV is not overwritten.')
    output.sort(key=lambda r:(int(r['罕度']),r['词条ID']))
    # Fixed, audited semantic anchors. Avoid matching synonyms and overlapping domains.
    bank_words=['电话','天气','老师','朋友','今天','明天','学校','时间','农业','学习','吃饭','喝水','商店','工作','喜欢',
                '火车','苹果','桌子','猫','狗','牛奶','护照','音乐','红色','太阳','月亮','睡觉','游泳','银行','雨伞','钥匙','飞机']
    bank=[r for w in bank_words for r in output if r['组词']==w][:40]
    groups={
      '电话':'phone telephone call contact ring communication', '天气':'weather rain snow cloud wind sky storm climate atmosphere',
      '老师':'teacher tutor instructor educator school student learn study class education', '朋友':'friend companion colleague friendship pal ally',
      '今天':'today day date present now current', '明天':'tomorrow day future date next', '学校':'school education academy classroom campus institution',
      '时间':'time hour minute moment period duration age year date', '农业':'agriculture farm farming crop cultivation husbandry',
      '学习':'learn study learning education read practice', '吃饭':'eat eating food meal dine dinner lunch breakfast',
      '喝水':'drink drinking water thirst beverage liquid', '商店':'shop store commerce business buy trade market merchant',
      '工作':'work job labor employment occupation task career', '喜欢':'like love enjoy affection fond prefer desire',
      '火车':'train rail railway transport vehicle travel', '苹果':'apple fruit food plant tree', '桌子':'table desk furniture',
      '猫':'cat feline kitten animal mammal creature', '狗':'dog canine puppy animal mammal creature', '牛奶':'milk dairy food drink liquid',
      '护照':'passport document travel identity paper', '音乐':'music song sound melody tune', '红色':'red color crimson scarlet',
      '太阳':'sun sunlight solar star light sky', '月亮':'moon lunar sky light', '睡觉':'sleep sleeping rest dream',
      '游泳':'swim swimming water sport', '银行':'bank banking finance money business', '雨伞':'umbrella rain shelter',
      '钥匙':'key lock unlock', '飞机':'airplane aircraft plane fly flying transport vehicle'
    }
    demo=json.loads((ROOT/'core/data/src/main/assets/demo/catalog.json').read_text(encoding='utf-8'))['words']
    retained_meanings={identity(w['hanzi'],w['pinyin']):w['meanings'][0]['english'] for w in demo}
    def actual_label(record):
        return retained_meanings.get(identity(record['组词'],record['拼音']),record['英文释义']).strip().casefold()
    for n,row in enumerate(output):
        target=tokens(row['英文释义'])|tokens(actual_label(row))|tokens(groups.get(row['组词'],''))
        candidates=[r for r in bank if r['词条ID']!=row['词条ID'] and not target & (tokens(r['英文释义'])|tokens(groups.get(r['组词'],'')))]
        candidates=candidates[n%max(1,len(candidates)):]+candidates[:n%max(1,len(candidates))]
        selected=[];labels={actual_label(row)};selected_domains=set()
        for r in candidates:
            label=actual_label(r)
            domain=tokens(groups.get(r['组词'],''))|tokens(label)
            if label in labels or domain & selected_domains: continue
            selected.append(r['词条ID']);labels.add(label);selected_domains|=domain
            if len(selected)==3: break
        if len(selected)!=3: raise ValueError('No three unambiguous distractor anchors for '+row['组词'])
        row['干扰词ID']=json.dumps(selected,separators=(',',':'))
    if regrade: apply_ratings(output)
    output.sort(key=lambda r:(int(r['罕度']),r['词条ID']))
    return output,removed

def serialize_csv(rows,headers):
    buffer=io.StringIO(newline='');writer=csv.DictWriter(buffer,fieldnames=headers,lineterminator='\r\n')
    writer.writeheader();writer.writerows(rows)
    return b'\xef\xbb\xbf'+buffer.getvalue().encode('utf-8')

def validate_csv(path):
    return validate_csv_bytes(path.read_bytes())

def validate_csv_bytes(content, verify_difficulty=True):
    if len(content)>32*1024*1024: raise ValueError('CSV exceeds byte limit')
    table=list(csv.DictReader(io.StringIO(content.decode('utf-8-sig'),newline='')))
    if not table or list(table[0]) not in [HEADERS,LEGACY_HEADERS] or len(table)>10000: raise ValueError('CSV headers/count invalid')
    ids={r['词条ID']:r for r in table}
    if len(ids)!=len(table): raise ValueError('Duplicate word IDs')
    seen=set()
    demo=json.loads((ROOT/'core/data/src/main/assets/demo/catalog.json').read_text(encoding='utf-8'))['words']
    demo_by_identity={identity(w['hanzi'],w['pinyin']):w for w in demo}
    aliases={r['词条ID']:demo_by_identity.get(identity(r['组词'],r['拼音']),{}).get('id',r['词条ID']) for r in table}
    actual_english={aliases[r['词条ID']]:demo_by_identity.get(identity(r['组词'],r['拼音']),{}).get('meanings',[{'english':r['英文释义']}])[0]['english'] for r in table}
    pinyin_letters=set('abcdefghijklmnopqrstuvwxyzüāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹňḿ')
    sentence_punctuation=set('.,!?;:，。！？；：、…—–-\"\'“”‘’()（）[]')
    def check_pinyin(value,sentence=False):
        normalized=unicodedata.normalize('NFC',value).lower()
        punctuation=sentence_punctuation if sentence else set("'-’")
        return any(c in pinyin_letters for c in normalized) and all(c in pinyin_letters or c.isspace() or c in punctuation for c in normalized)
    if list(table[0])==HEADERS and (REFINED/'baseline.csv').exists():
        baseline=list(csv.DictReader((REFINED/'baseline.csv').open(encoding='utf-8-sig',newline='')))
        original_ids=[r['词条ID'] for r in baseline]
        if [r['词条ID'] for r in table[:len(baseline)]]!=original_ids:
            raise ValueError('Retained baseline order changed')
        tail=[r['词条ID'] for r in table[len(baseline):]]
        if tail!=sorted(tail) or any(not re.fullmatch(r'cy_[0-9]{5}',x) for x in tail):
            raise ValueError('New idioms must follow all retained words in stable ID order')
    elif table!=sorted(table,key=lambda r:(int(r['罕度']),r['词条ID'])):
        raise ValueError('Rarity/original-order sort invalid')
    for r in table:
        if any('\x00' in value for value in r.values()):
            raise ValueError('Null character in teaching content: '+r['词条ID'])
        encoded_record = serialize_csv([r], list(r)).split(b'\r\n', 1)[1]
        if len(encoded_record) > 32 * 1024:
            raise ValueError('CSV record exceeds the app parser limit: '+r['词条ID'])
        if any(not r[k].strip() for k in LEGACY_HEADERS): raise ValueError('Blank field: '+r['组词'])
        ident=identity(r['组词'],r['拼音'])
        if ident in seen: raise ValueError('Duplicate word and reading: '+r['组词'])
        seen.add(ident)
        if r['罕度'] not in {'0','1','2','3'} or not re.fullmatch(r'(?:wl|cy)_[0-9]{5}',r['词条ID']): raise ValueError('Invalid rarity/stable ID')
        if r['组词'] not in r['简单例句'] or r['组词'] not in r['第二例句'] or r['简单例句']==r['第二例句']: raise ValueError('Invalid examples: '+r['组词'])
        for text_key,pinyin_key in [('组词','拼音'),('简单例句','例句拼音'),('第二例句','第二例句拼音')]:
            if not check_pinyin(r[pinyin_key],text_key!='组词'): raise ValueError('Invalid pinyin characters: '+r['词条ID']+' '+pinyin_key)
            count=len(re.findall(r'[\u3400-\u9fff]',r[text_key]))
            syllables=len(re.findall(r'[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹ]+',r[pinyin_key].lower()))
            if count!=syllables: raise ValueError(f'Hanzi/pinyin position mismatch: {r["词条ID"]} {r["组词"]} {pinyin_key}: {count}/{syllables}')
        if any(len(r[k])>limit for k,limit in [('组词',64),('拼音',128),('英文释义',300),('词性',80),('使用提示',2000),('来源说明',2000),('简单例句',1000),('例句拼音',2000),('例句英语翻译',2000),('第二例句',1000),('第二例句拼音',2000),('第二例句英语翻译',2000)]): raise ValueError('Field length exceeded: '+r['组词'])
        if any(len(r.get(k,''))>2000 for k in ['本义解释','引申义解释']): raise ValueError('Explanation field length exceeded: '+r['组词'])
        parts=json.loads(r['部件JSON'])
        if not 1<=len(parts)<=32 or ''.join(p['hanzi'] for p in parts)!=r['组词'] or any(not all(isinstance(p.get(k),str) and p[k].strip() for k in ['hanzi','pinyin','gloss']) for p in parts): raise ValueError('Invalid parts: '+r['组词'])
        if len(r['部件JSON'])>16000 or any(len(p['hanzi'])>64 or len(p['pinyin'])>128 or len(p['gloss'])>300 or not check_pinyin(p['pinyin']) for p in parts): raise ValueError('Invalid part limits or pinyin: '+r['组词'])
        distractors=json.loads(r['干扰词ID'])
        if len(distractors)!=3 or len(set(distractors))!=3 or r['词条ID'] in distractors or any(x not in ids for x in distractors): raise ValueError('Invalid distractor IDs: '+r['组词'])
        labels=[r['英文释义']]+[ids[x]['英文释义'] for x in distractors]
        if len({x.strip().casefold() for x in labels})!=4: raise ValueError('Repeated answer labels: '+r['组词'])
        if list(table[0]) == HEADERS and r['词性'] == 'idiom':
            if any(ids[x]['词性'] != 'idiom' for x in distractors) or any(not x.startswith('Describes ') for x in labels):
                raise ValueError('Idiom options reveal the answer format: '+r['组词'])
            if not all(r.get(key, '').strip() for key in ['本义解释', '引申义解释']):
                raise ValueError('Idiom explanation is incomplete: '+r['组词'])
        canonical=[aliases[r['词条ID']]]+[aliases[x] for x in distractors]
        if len(set(canonical))!=4 or len({actual_english[x].strip().casefold() for x in canonical})!=4: raise ValueError('Invalid retained-demo options: '+r['组词'])
    overlap=sum(identity(r['组词'],r['拼音']) in demo_by_identity for r in table)
    grading = validate_difficulty(table) if verify_difficulty else {'status':'ungraded content assembly; review and regenerate the difficulty manifest before publishing'}
    return {'words':len(table),'characters':len(set(''.join(r['组词'] for r in table))), 'rarity':dict(collections.Counter(r['罕度'] for r in table)), 'bytes':len(content),'demoDuplicates':overlap,'firstImportNewWords':len(table)-overlap,'resultingWordbookCount':len(table)-overlap+len(demo),'sha256':hashlib.sha256(content).hexdigest(),'difficulty':grading}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--prepare',action='store_true')
    parser.add_argument('--check',action='store_true')
    parser.add_argument('--assemble-ungraded',action='store_true',help='Write assembled content before refreshing difficulty evidence; all structural checks still apply.')
    args=parser.parse_args()
    if args.prepare: prepare()
    elif args.check: print(json.dumps(validate_csv(OUTPUT),ensure_ascii=False))
    else:
        words,removed=assemble(regrade=not args.assemble_ungraded)
        content=serialize_csv(words,HEADERS)
        result=validate_csv_bytes(content,verify_difficulty=not args.assemble_ungraded)
        OUTPUT.write_bytes(content)
        (ROOT/'wordlist-removals.csv').write_bytes(serialize_csv(removed,['原始行','词条ID','组词','原拼音','原因']))
        print(json.dumps(result,ensure_ascii=False))

if __name__ == '__main__': main()
