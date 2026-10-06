"""Prepare teaching rows from authored TSV and freeze reviewed additions.

Only data artifacts are written. Existing CSV content is never edited here.
The main frozen rebuild owns publication and performs the complete validator.
"""
from __future__ import annotations

import argparse
import collections
import csv
import functools
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from complete_wordlist import marked_pinyin, glosses, tokens, plain_pinyin
from rebuild_wordlist import GRAMMAR, short_gloss, reading_key
from rebuild_wordlist import serialize, validate

DATA = Path(__file__).resolve().parent
BASE = ROOT / '.gradle/wordlist-expansion-20261005/baseline.csv'
INDEX = ROOT / '.gradle/wordlist-implementation/dictionary-index.json'
PUNCTUATION = {'。': '.', '，': ',', '！': '!', '？': '?', '；': ';', '：': ':', '、': ',', '“': '"', '”': '"', '（': '(', '）': ')'}
INDIVISIBLE = {'葡萄','蝴蝶','蜻蜓','蚂蚁','咖啡','沙发','巧克力','吉他','琵琶','尴尬','垃圾','姑娘','玻璃','马虎','疙瘩','啰嗦','喇叭','胳膊','吩咐','枇杷','柠檬','苜蓿','玫瑰','牡丹','茉莉','鸳鸯','凤凰','惆怅','徘徊','踌躇','蹉跎','忐忑','吝啬','伶俐','囫囵吞枣','魑魅魍魉'}
INDIVISIBLE |= {'这么','那么','多么','什么','觉得','记得','晓得','明白','当然','原来','于是','毕竟','几乎','一直','一起','一下','有些','不少','不怎么','倒是','以为','只要','不管','别看','难道','从来','反正','左右','还是','要是','要么','否则','不过','可是','因此','然而','不仅','即使','至于','似乎','并且','或者','如果','虽然','然后','尽管','究竟','况且','难免','怪不得'}
INDIVISIBLE |= {'任何','对不起','过去','大家','以前','以后','亲爱','其实','结果','除了','之间','没关系','而已','本来','除非','不行','其中','终于','极了','随便','介意','不好意思','差不多','经常','值得','干什么','比如','不得不','后来','不然','再说','通常','一边','越来越','正好','而是','随时','只不过','接着','好几','派对','故意','许多','无聊','好好','看看','刚刚','回去','看上去','收拾','学生','先生','小姐','太太','客气','老实','舒服','厉害'}
PART_WORDS = {
    '为什么':[('为','for'),('什么','what')],
    '相信':[('相','mutually; toward someone'),('信','trust; believe')],
    '发现':[('发','bring out'),('现','appear; become visible')],
    '第二':[('第','ordinal prefix'),('二','two')],
    '想法':[('想','think'),('法','way; method')],
    '办公室':[('办公','office work'),('室','room')],
    '女士':[('女','woman; female'),('士','person (in this title)')],
    '幸运':[('幸','fortunate'),('运','luck')],
    '全部':[('全','all; whole'),('部','part; section')],
    '想起':[('想','think'),('起','up; brings something to mind here')],
    '见到':[('见','see'),('到','successful result')],
    '听到':[('听','hear'),('到','successful result')],
    '感到':[('感','feel'),('到','successful result')],
    '想到':[('想','think'),('到','successful result')],
    '遇到':[('遇','encounter'),('到','successful result')],
    '收到':[('收','receive'),('到','successful result')],
    '回到':[('回','return'),('到','reach')],
    '来到':[('来','come'),('到','reach')],
    '浪费':[('浪','reckless; unrestrained'),('费','spend; cost')],
    '通知':[('通','communicate'),('知','know')],
    '开玩笑':[('开','make; start'),('玩笑','joke')],
    '答案':[('答','answer'),('案','written record; proposal')],
    '听见':[('听','listen; hear'),('见','perceive (result complement)')],
    '服务':[('服','serve'),('务','work; duty')],
    '错过':[('错','miss'),('过','pass')],
    '顺便':[('顺','along; following'),('便','convenient')],
    '放开':[('放','release'),('开','apart; open (result complement)')],
    '停下':[('停','stop'),('下','settled result')],
    '单独':[('单','single'),('独','alone')],
    '上次':[('上','previous'),('次','occasion; time')],
    '不许':[('不','not'),('许','allow')],
    '再见':[('再','again'),('见','see; meet')],
    '晚上':[('晚','evening'),('上','time suffix')],
    '早上':[('早','early; morning'),('上','time suffix')],
}
FIXED = {
    '的':('de','DE'),'地':('de','DE (adverb marker)'),
    '得':('de','DE (complement marker)'),'着':('zhe','ZHE'),
    '了':('le','LE'),'吧':('ba','BA'),'吗':('ma','MA'),'呢':('ne','NE'),
    '和':('hé','and; with'),'也':('yě','also'),'都':('dōu','all'),
    '我们':('wǒ men','we'),'你们':('nǐ men','you (plural)'),
    '他们':('tā men','they'),'她们':('tā men','they'),
    '时候':('shí hou','time; when'),'东西':('dōng xi','thing; object'),
    '朋友':('péng you','friend'),'孩子':('hái zi','child'),
    '妈妈':('mā ma','mom'),'爸爸':('bà ba','dad'),
    '姐姐':('jiě jie','older sister'),'妹妹':('mèi mei','younger sister'),
    '哥哥':('gē ge','older brother'),'弟弟':('dì di','younger brother'),
    '奶奶':('nǎi nai','grandmother'),'爷爷':('yé ye','grandfather'),
    '学生':('xué sheng','student'),'先生':('xiān sheng','Mr.; gentleman'),
    '怎么':('zěn me','how'),'什么':('shén me','what'),
    '为什么':('wèi shén me','why'),'这么':('zhè me','so; this much'),
    '那么':('nà me','so; that much'),'明白':('míng bai','understand'),
    '觉得':('jué de','think; feel'),'记得':('jì de','remember'),
    '哪里':('nǎ lǐ','where'),'这里':('zhè lǐ','here'),'那里':('nà lǐ','there'),
    '一会儿':('yī huì ér','a little while'),'好好':('hǎo hǎo','carefully; properly'),
    '请':('qǐng','please'),'别':('bié','do not'),'看':('kàn','look; watch'),
    '点':('diǎn','a little; some'),'下':('xià','down; under'),
    '晚上':('wǎn shang','evening'),'早上':('zǎo shang','morning'),
    '意思':('yì si','meaning'),'认识':('rèn shi','know; recognize'),
    '休息':('xiū xi','rest'),'舒服':('shū fu','comfortable'),
    '清楚':('qīng chu','clear'),'告诉':('gào su','tell'),
    '眼睛':('yǎn jing','eye'),'耳朵':('ěr duo','ear'),
    '衣服':('yī fu','clothes'),'头发':('tóu fa','hair'),
    '桌子':('zhuō zi','table'),'椅子':('yǐ zi','chair'),
    '饺子':('jiǎo zi','dumpling'),'包子':('bāo zi','steamed bun'),
    '做':('zuò','do; make'),'会':('huì','can; will'),
    '去':('qù','go'),'来':('lái','come'),'好':('hǎo','good; well'),
}

def load(path):
    return json.loads(path.read_text(encoding='utf-8'))

def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n',encoding='utf-8')

def prepare(level):
    baseline=list(csv.DictReader(BASE.open(encoding='utf-8-sig',newline='')))
    index=load(INDEX)
    lexical=collections.defaultdict(collections.Counter)
    parts=collections.defaultdict(collections.Counter)
    for row in baseline:
        for example in json.loads(row['例句JSON']):
            for chunk in example['chunks']:
                word=re.sub(r'[^\u3400-\u9fff]','',chunk['hanzi'])
                reading=re.sub(r'[^a-züêāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜńňǹḿ\s]','',chunk['pinyin'].lower()).strip()
                if word and len(reading.split())==len(word):
                    lexical[word][(reading,chunk['gloss'])]+=1
        for part in json.loads(row['部件JSON']):
            parts[part['hanzi']][(part['pinyin'].lower(),part['gloss'])]+=1
        lexical[row['组词']][(row['拼音'],row['英文释义'])]+=3

    @functools.lru_cache(maxsize=60000)
    def options(word):
        found=[]
        for (reading,gloss),count in lexical.get(word,{}).items():
            found.append((reading,gloss,min(count,50),'retained contextual teaching unit'))
        for entry in index.get(word,[]):
            if any(c.isupper() for c in entry['pinyin']): continue
            reading=marked_pinyin(entry['pinyin'])
            if len(reading.split())!=len(word): continue
            for n,gloss in enumerate(glosses(entry,index)):
                if re.match(r'(?i)^(surname|variant|see |abbr\.|old name|ancient name|Japanese)',gloss):continue
                found.append((reading,short_gloss(gloss),0.5/(n+1),'CC-CEDICT lexical unit'))
        if word in FIXED:
            reading,gloss=FIXED[word];found.insert(0,(reading,gloss,100,'explicit common reading'))
        if word in GRAMMAR:
            readings=[FIXED[word][0]] if word in FIXED else [x[0] for x in found]
            if readings: found.insert(0,(readings[0],GRAMMAR[word],100,'explicit grammar unit'))
        return found

    def sentence(hanzi,english,target,reading,target_gloss):
        context=tokens(english)
        @functools.lru_cache(None)
        def partition(at):
            if at==len(hanzi): return 0,()
            if not '\u3400'<=hanzi[at]<='\u9fff':
                score,rest=partition(at+1)
                return score,((hanzi[at],PUNCTUATION.get(hanzi[at],hanzi[at]),''),)+rest
            best=None
            for end in range(at+1,min(len(hanzi),at+max(16,len(target)))+1):
                word=hanzi[at:end]
                if not all('\u3400'<=c<='\u9fff' for c in word):break
                if word!=target and word in {'他用','人和','成了','中和','你用','这次','这本','这家','那家','下摆','过得','别看','我去','我看','我说','你说','他说','她说','有人说','一个人','要看','一把'}:continue
                if word!=target and word=='个人' and re.search(r'(?:几|每|一|二|两|三|四|五|六|七|八|九|十|百|千|万)$',hanzi[:at]):continue
                if word!=target and len(word)>1 and word[0] in '我你他她它' and word not in {'我们','你们','他们','她们','它们','我俩','你俩','他俩','我家','你家','他家','她家','我方','你方'}:continue
                choices=[(reading,target_gloss,100,'authored target')] if word==target else options(word)
                if not choices:continue
                def preference(choice):
                    py,gloss,count,source=choice
                    penalty=10 if re.search(r'(?i)\b(?:archaic|literary|dialect|bound form|classifier)\b',gloss) else 0
                    return len(context & tokens(gloss))*9 + min(count,10)*0.3 - len(gloss)*0.008-penalty + (30 if source.startswith('explicit') else 0)
                py,gloss,count,source=max(choices,key=preference)
                if word!=target:
                    if word=='下' and '雨' in hanzi[:at]:gloss='fall (of rain)'
                    elif word=='点' and re.search(r'(?:几|一|二|两|三|四|五|六|七|八|九|十)$',hanzi[:at]):gloss="o'clock"
                    elif word=='点':gloss='a little; some'
                    elif word=='会':
                        gloss='meeting' if re.search(r'(?:开|参加|散|这个|那场)$',hanzi[:at]) else 'can; know how to' if re.search(r'(?i)\b(?:can|able|know how)\b',english) else 'will; may'
                    elif word=='好' and re.search(r'(?:系|留|放|收|穿|准备|保管|做好|写|记|拿)$',hanzi[:at]):gloss='properly; securely (result complement)'
                    elif word=='又' and (('又' in hanzi[:at] or '又' in hanzi[at+1:]) or '既' in hanzi[:at]):gloss='both; and'
                    elif word=='送' and re.search(r'(?i)\b(?:take|taking|took|escort|bring|brought|walked|drive|drove)\b',english):gloss='take; escort'
                    elif word=='下' and hanzi[at+1:at+2]=='雨':gloss='fall (of rain)'
                    elif word in {'件','张','本','条','位','辆','家','次','份','只','个','杯','碗','片','块','双','座'} and re.search(r'(?:这|那|几|每|一|二|两|三|四|五|六|七|八|九|十)$',hanzi[:at]):gloss=plain_pinyin(py).upper()+' (measure word)'
                rest=partition(end)
                if rest is None:continue
                score=rest[0]+len(word)**1.55+(1000 if word==target else 0)+(0.3 if source.startswith('explicit') else 0)
                candidate=(score,((word,py,gloss),)+rest[1])
                if best is None or score>best[0]:best=candidate
            return best
        parsed=partition(0)
        if parsed is None:raise ValueError('Unresolved sentence segmentation: '+hanzi)
        chunks=[]
        leading=''
        for word,py,gloss in parsed[1]:
            if not gloss:
                if chunks:
                    chunks[-1]['hanzi']+=word;chunks[-1]['pinyin']+=py
                else:leading+=word
                continue
            chunks.append({'hanzi':leading+word,'pinyin':leading+py,'gloss':gloss});leading=''
        return {'hanzi':hanzi,'pinyin':' '.join(c['pinyin'] for c in chunks),'english':english,'chunks':chunks}

    def components(word,reading,english,explanation):
        if word in PART_WORDS:
            result=[];at=0;syllables=reading.split()
            for unit,gloss in PART_WORDS[word]:
                result.append({'hanzi':unit,'pinyin':' '.join(syllables[at:at+len(unit)]),'gloss':gloss});at+=len(unit)
            return result
        if word in INDIVISIBLE:return [{'hanzi':word,'pinyin':reading,'gloss':english}]
        syllables=reading.split();context=tokens(english+' '+explanation)
        @functools.lru_cache(None)
        def split(at):
            if at==len(word):return 0,()
            best=None
            for end in range(at+1,len(word)+1):
                if at==0 and end==len(word) and len(word)>1:continue
                unit=word[at:end];py=' '.join(syllables[at:end])
                choices=[(g,c) for (p,g),c in parts.get(unit,{}).items() if reading_key(p)==reading_key(py)]
                choices += [(g,c) for p,g,c,s in options(unit) if reading_key(p)==reading_key(py)]
                if not choices:continue
                gloss,count=max(choices,key=lambda x:(len(context & tokens(x[0]))*10 + min(x[1],30)*0.05 - len(x[0])*0.01))
                after=split(end)
                if after is None:continue
                candidate=(after[0]+len(unit)**1.6,({'hanzi':unit,'pinyin':py,'gloss':gloss},)+after[1])
                if best is None or candidate[0]>best[0]:best=candidate
            return best
        result=split(0)
        return list(result[1]) if result else [{'hanzi':word,'pinyin':reading,'gloss':english}]

    review_path=DATA/f'level{level}_review.json'
    if review_path.exists():INDIVISIBLE.update(load(review_path).get('nonTransparentWords',[]))
    rows=[];seen={r['组词'] for r in baseline}
    path=DATA/f'level{level}_authoring.tsv'
    authored=list(csv.reader(path.open(encoding='utf-8-sig',newline=''),delimiter='\t'))
    authored=[r for r in authored if r and r[0] not in {'组词','word'}]
    for number,values in enumerate(authored,1):
        if len(values)!=9:raise ValueError(f'Nine TSV columns required: {path.name}:{number}')
        word,reading,english,pos,explanation,h1,e1,h2,e2=values
        if word in seen:raise ValueError('Existing/duplicate word: '+word)
        seen.add(word)
        if len(word)!=len(reading.split()):raise ValueError('Target reading alignment: '+word)
        word_id=f'wl_{7723+level*500+number:05d}'
        row={'罕度':str(level),'组词':word,'拼音':reading,'词条ID':word_id,
             '英文释义':english,'词性':pos,'例句JSON':[sentence(h1,e1,word,reading,english),sentence(h2,e2,word,reading,english)],
             '部件JSON':components(word,reading,english,explanation),'干扰词ID':[],
             '来源说明':f'Expansion 2026-10-05: AI-authored and AI-reviewed teaching meaning and two contextual examples; reading and decomposition reference pinned CC-CEDICT (CC BY-SA 4.0) and retained teaching data. Difficulty {level} follows the existing learning-expectation policy. Word-part glosses are memory aids, not etymology. No human linguistic certification.',
             '本义解释JSON':[explanation],'引申义解释JSON':[]}
        rows.append(row)
    if not 1<=len(rows)<=500:raise ValueError(f'Expected 1–500 draft rows for level {level}, got {len(rows)}')
    corrections=DATA/f'level{level}_corrections.json'
    if corrections.exists():
        patch=load(corrections)
        indexed={r['词条ID']:r for r in rows}
        for word_id,changes in patch.items():
            if word_id not in indexed:raise ValueError('Unknown correction ID: '+word_id)
            if not set(changes)<={'拼音','英文释义','词性','例句JSON','部件JSON','本义解释JSON','引申义解释JSON'}:raise ValueError('Protected correction field')
            indexed[word_id].update(changes)
    save(DATA/f'level{level}_draft.json',{'entries':rows})
    print(json.dumps({'level':level,'words':len(rows),'chunks':sum(len(e['chunks']) for r in rows for e in r['例句JSON'])}))

def freeze():
    original=BASE.read_bytes()
    baseline=validate(original)
    additions=[]
    permitted={'拼音','英文释义','词性','例句JSON','部件JSON','本义解释JSON','引申义解释JSON'}
    for level in range(3):
        draft_path=DATA/f'level{level}_draft.json'
        review=load(DATA/f'generated_review_{level}.json')
        rows=load(draft_path)['entries']
        coverage=review.get('coveredIds',review.get('coverageIDs',[]))
        if (review.get('reviewedEntries',review.get('headwordCount'))!=500 or
                review.get('reviewedExamples',review.get('exampleCount'))!=1000 or
                len(coverage)!=500 or set(coverage)!={r['词条ID'] for r in rows}):
            raise ValueError(f'Generated teaching content is not fully reviewed for level {level}')
        if review['draftSha256']!=hashlib.sha256(draft_path.read_bytes()).hexdigest():
            raise ValueError('Generated review does not match its draft snapshot')
        indexed={r['词条ID']:r for r in rows}
        path=DATA/f'level{level}_corrections.json'
        if review.get('correctionsSha256') and not path.exists():
            raise ValueError('Generated review requires its missing correction snapshot')
        if path.exists():
            if review['correctionsSha256']!=hashlib.sha256(path.read_bytes()).hexdigest():
                raise ValueError('Generated review does not match its correction snapshot')
            for word_id,changes in load(path).items():
                if word_id not in indexed or not set(changes)<=permitted:
                    raise ValueError('Invalid frozen correction: '+word_id)
                indexed[word_id].update(changes)
        for path in sorted(DATA.glob(f'cross_corrections_{level}_*.json')):
            for word_id,changes in load(path).items():
                if word_id not in indexed or not set(changes)<=permitted:
                    raise ValueError('Invalid independent correction: '+word_id)
                indexed[word_id].update(changes)
        additions.extend(rows)
    existing={r['组词'] for r in baseline}
    if (collections.Counter(r['罕度'] for r in additions)!={'0':500,'1':500,'2':500} or
            len({r['组词'] for r in additions})!=1500 or existing & {r['组词'] for r in additions}):
        raise ValueError('Expansion requires 1,500 distinct words absent from the baseline')
    references=collections.defaultdict(list)
    for word,item in load(DATA/'level1_review.json').get('readingSelections',{}).items():
        if item.get('reference'):references[word].append(item['reference'])
    for item in load(DATA/'level2_review.json').get('externalReadingReferences',[]):
        references[item['word']].append(item['url'])
    for row in additions:
        if references.get(row['组词']):
            row['来源说明'] += ' Additional reading references: '+', '.join(dict.fromkeys(references[row['组词']]))+'.'
    bank=[r for r in baseline if r['罕度'] in {'0','1'} and len(r['英文释义'])<=90 and len(r['组词'])>=2]
    index=load(INDEX)
    @functools.lru_cache(maxsize=10000)
    def semantic(word,reading,english):
        result=tokens(english)
        for entry in index.get(word,[]):
            if reading_key(marked_pinyin(entry['pinyin']))!=reading_key(reading):continue
            for sense in glosses(entry,index):result |= tokens(sense)
        return result-{'describe','idiom','sb','sth','something','somebody','thing','bound','form'}
    def category(pos):
        return next((key for key in ['pronoun','adverb','adjective','noun','verb','conjunction'] if key in pos),pos)
    for row in additions:
        if row['干扰词ID']:continue
        seed=int(hashlib.sha256(row['组词'].encode()).hexdigest()[:8],16)
        preferred=[r for r in bank if category(r['词性'])==category(row['词性']) or
                   row['罕度']=='2' and r['词性']=='idiom']
        choices=preferred if len(preferred)>=20 else bank
        ordered=choices[seed%len(choices):]+choices[:seed%len(choices)]
        used={row['英文释义'].casefold()};meaning=semantic(row['组词'],row['拼音'],row['英文释义'])
        selected=[]
        for candidate in ordered:
            text=candidate['英文释义'].casefold();option=semantic(candidate['组词'],candidate['拼音'],text)
            if text in used or meaning & option:continue
            used.add(text);meaning |= option;selected.append(candidate['词条ID'])
            if len(selected)==3:break
        if len(selected)!=3:raise ValueError('No distinct distractors: '+row['组词'])
        row['干扰词ID']=selected
    combined=serialize(baseline+additions)
    if not combined.startswith(original):
        raise ValueError('Serializing additions would alter an existing CSV byte')
    validate(combined)
    source_files=['assemble.py']+[f'level{level}_{suffix}' for level in range(3) for suffix in ['authoring.tsv','review.json','draft.json']]
    source_files += [p.name for p in sorted(DATA.glob('level*_corrections.json'))]
    source_files += [p.name for p in sorted(DATA.glob('cross_review_*.json'))]
    source_files += [p.name for p in sorted(DATA.glob('cross_corrections_*.json'))]
    source_files += [p.name for p in sorted(DATA.glob('generated_review_*.json'))]
    evidence={name:hashlib.sha256((DATA/name).read_bytes()).hexdigest() for name in source_files}
    payload={'date':'2026-10-05','baseRows':7723,'baseSha256':hashlib.sha256(original).hexdigest(),
             'additionalRows':1500,'rarity':{'0':500,'1':500,'2':500},
             'dictionarySha256':hashlib.sha256(INDEX.read_bytes()).hexdigest(),
             'sourceSha256':evidence,'entries':additions,
             'boundary':'AI-authored contextual teaching content with structural verification and recorded AI review; not human linguistic certification or device import proof.'}
    save(ROOT/'tools/wordlist_rebuild/expansion-20261005.json',payload)
    save(DATA/'validation.json',{'baseSha256':payload['baseSha256'],'additionalRows':1500,
         'rarity':payload['rarity'],'resultRows':len(baseline)+len(additions),
         'sha256':hashlib.sha256(combined).hexdigest(),'bytes':len(combined),
         'examples':3000,'chunks':sum(len(e['chunks']) for r in additions for e in r['例句JSON']),
         'parts':sum(len(r['部件JSON']) for r in additions),'duplicateAddedWords':0,
         'baselineRowsUnchanged':True,'completeStructuralValidator':'PASS',
         'genuineStrokeCoverage':'PASS: all added word glyphs in existing 6,324-glyph package',
         'boundary':payload['boundary']})
    print(json.dumps({'additionalRows':1500,'totalRows':len(baseline)+1500,'bytes':len(combined),'status':'PASS'}))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--draft',type=int,choices=[0,1,2])
    mode.add_argument('--freeze',action='store_true')
    args=parser.parse_args()
    if args.freeze:freeze()
    else:prepare(args.draft)
