"""Reproduce the vocabulary expansion and explicit five-stage lexical revision.

This is source-assisted editing, not a claim of human or dictionary certification.
The previous IDs are immutable; reviewed headword replacements are explicit.
Examples come from existing teaching
contexts or attributed Tatoeba pairs, never generated placeholder templates.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import io
import json
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.gradle/vocabulary-v2'
DATA = ROOT / 'tools/vocabulary_v2'
sys.path.insert(0, str(ROOT / '.gradle/chinese-resource-source/python'))
from pypinyin import Style, lazy_pinyin  # Existing checksum-locked tooling only.
from pypinyin.contrib.tone_convert import to_tone

HAN = re.compile('[\u4e00-\u9fff]+')
PUNCT = '。，！？：；、,.!?;:（）()“”\" '
KINDERGARTEN = set('''幼儿园 我 你 他 她 它 我们 你们 他们 她们 自己 谁 什么 哪 哪儿 哪里 这 那 这里 那里
爸爸 妈妈 爷爷 奶奶 外公 外婆 哥哥 姐姐 弟弟 妹妹 宝宝 小孩 孩子 朋友 老师 人 家 家人 男 女 男孩 女孩
一 二 三 四 五 六 七 八 九 十 零 百 两 个 只 本 张 条 口 头 天 年 月 日 点 分 早上 中午 晚上 今天 明天 昨天
是 有 没有 不 没 的 了 吗 呢 吧 啊 和 也 都 很 太 还 就 在 到 从 给 跟 比 让 要 想 会 能 可以 不能
大 小 多 少 好 坏 新 旧 长 短 高 矮 热 冷 快 慢 红 黄 蓝 绿 白 黑 红色 黄色 蓝色 绿色 白色 黑色
上 下 左 右 前 后 里 外 上面 下面 里面 外面 左边 右边 前面 后面
吃 喝 睡 睡觉 起床 走 跑 跳 坐 站 看 听 说 读 写 玩 笑 哭 来 去 回家 回来 出去 进来 拿 放 打 开 关 找 买 卖
水 饭 米饭 面包 牛奶 鸡蛋 苹果 香蕉 西瓜 菜 肉 鱼 糖 盐 汤 杯子 碗 筷子 勺子 桌子 椅子 床 门 窗 窗户 书 笔 纸 书包
手 脚 头 脸 嘴 嘴巴 眼睛 耳朵 鼻子 牙齿 头发 衣服 裤子 鞋 鞋子 帽子 球 车 汽车
猫 狗 鸟 鸡 鸭 牛 羊 马 兔子 老虎 大象 熊猫 太阳 月亮 星星 天空 云 雨 下雨 雪 风 花 草 树 石头 土 山 河 海
你好 谢谢 再见 对不起 没关系 不客气 请 请问 好的 不要 喜欢 爱 知道 不知道 明白 认识 多少 几 怎么 为什么
'''.split())
PRIORITY = set('''盐 糖 醋 酱油 蚝油 料酒 花椒 八角 淀粉 面粉 食用油 冰箱 冰柜 洗衣机 空调 电风扇
电磁炉 热水壶 空气炸锅 微波炉 电饭煲 电饭锅 电吹风 吹风机 吸尘器 洗碗机 烤箱 净水器 路由器 充电器 插座 插头
垃圾袋 卫生纸 纸巾 湿巾 洗衣液 洗洁精 牙膏 牙刷 毛巾 浴巾 拖鞋 晾衣架 衣架 马桶 水龙头 花洒
地铁 公交 高铁 车票 机票 打车 乘车 扫码 付款 支付 找零 外卖 快递 收货 退货 退款 网购 下单 取件
医保 社保 挂号 预约 门诊 处方 报销 发票 收据 合同 押金 租金 房租 物业 停车位 充电宝 数据线 耳机 蓝牙
早餐 午餐 晚餐 夜宵 菜单 点菜 打包 少辣 不辣 香菜 葱姜 蒜头 洋葱 土豆 番茄 西红柿 黄瓜 胡萝卜 菠菜 白菜
面条 饺子 馒头 包子 豆腐 豆浆 酸奶 饼干 鸡肉 猪肉 牛肉 羊肉 虾 过敏 口罩 体温 血压 消毒 抗生素
幼儿园 托儿所 小区 超市 便利店 药店 卫生间 洗手间 电梯 楼梯 阳台 厨房 餐厅 客厅 卧室 书房 沙发 茶几
'''.split())
GRAMMAR = {
 '在': ('zài', 'to be at; in; at; marker of an action in progress', 'verb; preposition; adverb'),
 '的': ('de', 'possessive or attributive particle', 'particle'),
 '了': ('le', 'particle marking a completed action or a change of state', 'particle'),
 '吗': ('ma', 'particle used to form a yes-no question', 'particle'),
 '呢': ('ne', 'particle used in follow-up questions or to mark an ongoing situation', 'particle'),
 '吧': ('ba', 'particle used for a suggestion, request or tentative statement', 'particle'),
 '我': ('wǒ', 'I; me', 'pronoun'), '你': ('nǐ', 'you', 'pronoun'),
 '他': ('tā', 'he; him', 'pronoun'), '她': ('tā', 'she; her', 'pronoun'),
 '它': ('tā', 'it', 'pronoun'), '们': ('men', 'plural suffix for people', 'suffix'),
 '这': ('zhè', 'this', 'pronoun'), '那': ('nà', 'that', 'pronoun'),
 '谁': ('shéi', 'who; whom', 'pronoun'), '是': ('shì', 'to be', 'verb'),
 '不': ('bù', 'not; no', 'adverb'), '没': ('méi', 'not; have not', 'adverb'),
 '很': ('hěn', 'very; quite', 'adverb'), '都': ('dōu', 'all; both', 'adverb'),
 '也': ('yě', 'also; too', 'adverb'), '就': ('jiù', 'then; just; as early as', 'adverb'),
 '把': ('bǎ', 'marker placing an affected object before the verb', 'preposition'),
 '被': ('bèi', 'marker introducing the agent of a passive construction', 'preposition'),
 '着': ('zhe', 'particle marking a continuing action or state', 'particle'),
 '过': ('guo', 'particle marking a past experience', 'particle'),
 '得': ('de', 'particle linking a verb to a result or degree complement', 'particle'),
 '地': ('de', 'particle linking an adverbial modifier to a verb', 'particle'),
 '和': ('hé', 'and; with', 'conjunction; preposition'),
 '比': ('bǐ', 'than; compared with; to compare', 'preposition; verb'),
 '只': ('zhǐ', 'only; merely', 'adverb'),
 '个': ('gè', 'general classifier for people or things', 'classifier'),
 '一': ('yī', 'one; a single', 'numeral'),
 '二': ('èr', 'two', 'numeral'), '两': ('liǎng', 'two; both', 'numeral'),
 '三': ('sān', 'three', 'numeral'), '四': ('sì', 'four', 'numeral'),
 '五': ('wǔ', 'five', 'numeral'), '六': ('liù', 'six', 'numeral'),
 '七': ('qī', 'seven', 'numeral'), '八': ('bā', 'eight', 'numeral'),
 '九': ('jiǔ', 'nine', 'numeral'), '十': ('shí', 'ten', 'numeral'),
 '零': ('líng', 'zero', 'numeral'), '百': ('bǎi', 'hundred', 'numeral'),
 '千': ('qiān', 'thousand', 'numeral'), '万': ('wàn', 'ten thousand', 'numeral'),
 '本': ('běn', 'classifier for books and other bound volumes; root; basis', 'classifier; noun'),
 '张': ('zhāng', 'classifier for flat objects; to spread out', 'classifier; verb'),
 '条': ('tiáo', 'classifier for long thin things; strip', 'classifier; noun'),
 '口': ('kǒu', 'mouth; opening; classifier for people in a household', 'noun; classifier'),
 '啊': ('a', 'ah; oh; sentence-final particle expressing emotion', 'interjection; particle'),
 '好': ('hǎo', 'good; fine; well', 'adjective; adverb'),
 '大': ('dà', 'big; large; older', 'adjective'),
 '小': ('xiǎo', 'small; little; young', 'adjective'),
 '多': ('duō', 'many; much; more', 'adjective; adverb'),
 '少': ('shǎo', 'few; little; less', 'adjective; adverb'),
 '快': ('kuài', 'fast; quick; soon', 'adjective; adverb'),
 '慢': ('màn', 'slow; slowly', 'adjective; adverb'),
 '新': ('xīn', 'new; fresh', 'adjective'), '旧': ('jiù', 'old; used; former', 'adjective'),
 '高': ('gāo', 'high; tall', 'adjective'), '矮': ('ǎi', 'short in height; low', 'adjective'),
 '长': ('cháng', 'long; length', 'adjective; noun'), '短': ('duǎn', 'short in length or duration', 'adjective'),
 '太': ('tài', 'too; extremely', 'adverb'), '还': ('hái', 'still; also; even more', 'adverb'),
 '才': ('cái', 'only then; not until; just', 'adverb'),
 '更': ('gèng', 'more; even more', 'adverb'), '最': ('zuì', 'most; -est', 'adverb'),
 '跟': ('gēn', 'with; to follow', 'preposition; verb'),
 '从': ('cóng', 'from; since', 'preposition'), '给': ('gěi', 'to give; to; for', 'verb; preposition'),
 '为': ('wèi', 'for; for the sake of', 'preposition'),
 '向': ('xiàng', 'toward; to; facing', 'preposition'),
 '上': ('shàng', 'up; above; on; to go up', 'localizer; verb'),
 '下': ('xià', 'down; below; under; to go down', 'localizer; verb'),
 '里': ('lǐ', 'inside; in', 'localizer'), '外': ('wài', 'outside; external', 'localizer; adjective'),
 '前': ('qián', 'front; before; earlier', 'localizer'), '后': ('hòu', 'back; behind; after', 'localizer'),
 '哪': ('nǎ', 'which; what', 'pronoun'), '哪儿': ('nǎ er', 'where', 'pronoun'),
 '几': ('jǐ', 'how many; a few', 'pronoun; numeral'),
}


def dump(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(obj, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'))


def editorial_input(name, default):
    path = DATA / (name + '.json')
    if not path.exists():
        path = CACHE / (name + '.json')
    return json.loads(path.read_text(encoding='utf-8')) if path.exists() else default


def normalize_reading(value):
    return ' '.join(unicodedata.normalize('NFC', value).lower().replace('u:', 'ü').split())


def clean_definition(value):
    value = re.sub(r'\[[^\]]*\]', '', value)
    value = re.sub(r'[^\s;/()]*[\u3400-\u9fff][^\s;/()]*', '', value)
    value = re.sub(r'\b(?:sb|sb\.)\b', 'somebody', value)
    value = re.sub(r'\b(?:sth|sth\.)\b', 'something', value)
    value = re.sub(r'\s+', ' ', value).strip(' ;,')
    value = re.sub(r'\([^()]*\b(?:derived from|as in|used in|abbreviation of)\s*\)', '', value, flags=re.I)
    value = re.sub(r'\s+', ' ', value).strip(' ;,')
    return value


def read_sources():
    manifest = json.loads((DATA / 'source-lock.json').read_text(encoding='utf-8'))
    for record in manifest['sources']:
        path = CACHE / record['file']
        if path.stat().st_size != record['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != record['sha256']:
            raise ValueError('Source changed: ' + record['file'])
    baseline_path = CACHE / 'baseline.csv'
    baseline_index = json.loads((DATA / 'baseline-index.json').read_text(encoding='utf-8'))
    if not baseline_path.exists():
        blob = baseline_index.get('gitBlob', '')
        if not re.fullmatch('[a-f0-9]{40}', blob):
            raise ValueError('Provide the exact recorded baseline.csv in the source cache')
        payload = subprocess.run(['git', 'cat-file', 'blob', blob], cwd=ROOT, check=True, capture_output=True, timeout=30).stdout
        baseline_path.write_bytes(payload)
    if hashlib.sha256(baseline_path.read_bytes()).hexdigest() != baseline_index['baselineSha256']:
        raise ValueError('The baseline vocabulary changed')
    baseline = list(csv.DictReader(baseline_path.open(encoding='utf-8-sig')))
    freq = {}
    for rank, line in enumerate((CACHE / 'frequency-zh-cn-2018-50k.txt').read_text(encoding='utf-8').splitlines(), 1):
        bits = line.split()
        if len(bits) == 2:
            freq[bits[0]] = (rank, int(bits[1]))
    lex = collections.defaultdict(list)
    for line in (CACHE / 'cedict.txt').read_text(encoding='utf-8-sig').splitlines():
        m = re.match(r'^(\S+) (\S+) \[([^\]]+)\] /(.*)/$', line)
        if not m:
            continue
        trad, word, numbered, senses = m.groups()
        if not HAN.fullmatch(word) or not 1 <= len(word) <= 12:
            continue
        reading = normalize_reading(' '.join(to_tone(x.lower().replace('u:', 'v').replace('5', '')) for x in numbered.split()))
        glosses = [clean_definition(x) for x in senses.split('/') if not x.startswith(('CL:', 'variant of ', 'old variant of ', 'see ', 'also written '))]
        glosses = [x for x in glosses if re.search('[a-zA-Z]', x) and not any(t in x.lower() for t in ['surname ', 'county in ', 'district of ', 'prefecture', 'city in ', 'province', 'abbr. for ', 'taiwan pr.', 'also pr.'])]
        glosses = [x for x in glosses if not re.fullmatch(r'(?:used in|used in place names.*|\(phonetic\)|\(bound form\)|abbr\. for|variant of|see also|see)', x, re.I)]
        if glosses and len(reading.split()) == len(word) and not re.search('[0-9]', reading):
            lex[word].append({'pinyin': reading, 'senses': glosses, 'traditional': trad})
    return baseline, freq, lex


def simplified_context_converter():
    """Use unambiguous, same-length CEDICT spelling pairs; preserve target spellings."""
    forms = collections.defaultdict(set)
    for line in (CACHE / 'cedict.txt').read_text(encoding='utf-8-sig').splitlines():
        pieces = line.split(' ', 2)
        if len(pieces) == 3:
            traditional, simplified = pieces[:2]
            if len(traditional) == len(simplified) and HAN.fullmatch(traditional) and HAN.fullmatch(simplified):
                forms[traditional].add(simplified)
    mapping = {word: next(iter(values)) for word, values in forms.items() if len(values) == 1}
    def convert(text):
        output = []
        offset = 0
        while offset < len(text):
            word = next((text[offset:offset+n] for n in range(min(12, len(text)-offset), 0, -1)
                         if text[offset:offset+n] in mapping), text[offset])
            output.append(mapping.get(word, word)); offset += len(word)
        return ''.join(output)
    return convert


def infer_pos(word, gloss, old='word'):
    if word in GRAMMAR:
        return GRAMMAR[word][2]
    if re.search(r'\b(?:idiom|proverb)\b', gloss, re.I):
        return 'idiom' if 'idiom' in gloss.lower() else 'expression'
    if gloss.startswith('to '):
        return 'verb'
    if any(x in gloss.lower() for x in ['particle ', 'particle used', 'sentence-final']):
        return 'particle'
    if 'classifier for' in gloss.lower():
        return 'classifier'
    return old


def choice_pool_rarity(word, pos, freq, old=None):
    """Keep the original option pools reproducible; final teaching bands are reviewed below."""
    rank = freq.get(word, (100_000, 0))[0]
    if word in KINDERGARTEN:
        return 0
    if word in PRIORITY:
        return 2 if word in {'医保','社保','报销','合同','押金','租金','物业','处方','抗生素','血压','数据线','蓝牙'} else 1
    # Frequency is an input to pedagogical bands, never an official school certificate.
    value = 1 if rank <= 2500 else 2 if rank <= 7000 else 3 if rank <= 14000 else 4 if rank <= 28000 else 5
    if 'idiom' in pos:
        value = max(2, value)
    return value


def build(target):
    baseline, freq, lex = read_sources()
    existing = {r['组词']: r for r in baseline}
    old_chunks = collections.defaultdict(collections.Counter)
    old_contexts = collections.defaultdict(dict)
    chunks = {}
    examples = {}
    for row in baseline:
        for part in json.loads(row['部件JSON']):
            chunks.setdefault(part['hanzi'], (normalize_reading(part['pinyin']), part['gloss']))
        for ex in json.loads(row['例句JSON']):
            examples[ex['hanzi']] = ex
            for chunk in ex['chunks']:
                h = chunk['hanzi'].strip(PUNCT)
                if not HAN.fullmatch(h):
                    continue
                reading = normalize_reading(chunk['pinyin'].strip(PUNCT))
                if len(reading.split()) == len(h):
                    old_chunks[h][(reading, chunk['gloss'])] += 1
                    chunks.setdefault(h, (reading, chunk['gloss']))
                    old_contexts[h][ex['hanzi']] = (ex, 'legacy:' + row['词条ID'])
    for word, entries in lex.items():
        entries.sort(key=lambda x: (-sum(n for (p, _), n in old_chunks[word].items() if p == x['pinyin']), len(x['senses'][0])))
        chunks.setdefault(word, (entries[0]['pinyin'], entries[0]['senses'][0]))
    for word, (pinyin, gloss, _) in GRAMMAR.items():
        chunks[word] = (pinyin, gloss)

    def phrase_parts(text, force=None):
        result = []
        i = 0
        while i < len(text):
            if not HAN.fullmatch(text[i]):
                if result:
                    result[-1]['hanzi'] += text[i]
                    result[-1]['pinyin'] += { '。': '.', '，': ',', '？': '?', '！': '!', '：': ':', '；': ';', '、': ',' }.get(text[i], '')
                i += 1
                continue
            if force and text.startswith(force, i):
                word = force
            else:
                # Do not swallow the beginning of the headword inside a longer chunk.
                following = text.find(force, i + 1) if force else -1
                limit = min(8, len(text) - i, following - i if following >= 0 else 8)
                word = next((text[i:i+n] for n in range(limit, 0, -1) if text[i:i+n] in chunks and HAN.fullmatch(text[i:i+n])), text[i])
            py, gloss = chunks.get(word, (' '.join(lazy_pinyin(word, style=Style.TONE, neutral_tone_with_five=False)), 'character in this expression'))
            result.append({'hanzi': word, 'pinyin': py, 'gloss': clean_definition(gloss)[:290] or 'character in this expression'})
            i += len(word)
        if ''.join(x['hanzi'] for x in result) != text:
            return None
        return result

    # Index dictionary-verified words in natural bilingual sentences, retaining attribution.
    originals = {x['word']: x for x in editorial_input('daily-gap-original', [])}
    PRIORITY.update(originals)
    lexical_overrides = editorial_input('new-word-editorial-overrides', {})
    for filename in ['new-word-editorial-overrides-supplement', 'new-word-editorial-overrides-final']:
        for word, fields in editorial_input(filename, {}).items():
            lexical_overrides.setdefault(word, {}).update(fields)
    head_overrides = editorial_input('new-headword-corrections', {})
    exact_examples = editorial_input('new-example-corrections', {})
    for word, entry in originals.items():
        lex[word] = [{'pinyin': entry['pinyin'], 'senses': [entry['gloss']], 'traditional': word}]
        chunks[word] = (entry['pinyin'], entry['gloss'])
    candidates = {w for w in lex if w not in existing and len(w) <= 8 and (len(w) > 1 or w in freq or w in KINDERGARTEN)}
    contexts = collections.defaultdict(dict)
    for word in candidates:
        for text, pair in old_contexts[word].items():
            contexts[word][text] = pair
    for text, ex in examples.items():
        found = {text[i:j] for i in range(len(text)) for j in range(i + 1, min(i + 8, len(text)) + 1) if text[i:j] in candidates}
        for word in found:
            if len(contexts[word]) < 12:
                contexts[word][text] = (ex, 'legacy-context:' + hashlib.sha256(text.encode()).hexdigest()[:16])
    for line in (CACHE / 'cmn.txt').read_text(encoding='utf-8-sig').splitlines():
        fields = line.split('\t')
        if len(fields) < 3:
            continue
        en, zh, attribution = fields[:3]
        if not 3 <= len(zh) <= 58 or len(en) > 180 or not re.fullmatch(r'[\u4e00-\u9fff。，！？：；、,.!?;: ]+', zh):
            continue
        if not re.search('[A-Za-z]', en) or any(x in zh for x in ['自杀', '强奸', '性交']):
            continue
        found = {zh[i:j] for i in range(len(zh)) for j in range(i + 1, min(i + 8, len(zh)) + 1) if zh[i:j] in candidates}
        parts = phrase_parts(zh)
        if not parts:
            continue
        ex = {'hanzi': zh, 'pinyin': ' '.join(x['pinyin'] for x in parts), 'english': en, 'chunks': parts}
        for word in found:
            if len(contexts[word]) >= 12:
                continue
            contexts[word][zh] = (ex, attribution)
    for word, entry in originals.items():
        for ex in entry['examples']:
            parts = phrase_parts(ex['hanzi'], word)
            if parts:
                contexts[word][ex['hanzi']] = ({**ex, 'pinyin': ' '.join(x['pinyin'] for x in parts), 'chunks': parts}, entry.get('source', 'Project-original editorial example, 2026-10-27'))
    for word, exs in exact_examples.items():
        # Explicitly authored sense-disambiguating examples supersede mined contexts.
        contexts[word] = {}
        for ex in exs:
            contexts[word][ex['hanzi']] = (ex, 'Project-authored AI-assisted editorial correction, 2026-10-27')

    def chosen_reading(word):
        return head_overrides.get(word, {}).get('pinyin', lexical_overrides.get(word, {}).get('pinyin',
            GRAMMAR.get(word, (lex[word][0]['pinyin'],))[0]))

    def same_source_reading(ex, word):
        # Never change a polyphonic character's contextual reading just to fit a headword.
        text = ex['hanzi']
        chars = [i for i, c in enumerate(text) if HAN.fullmatch(c)]
        syllables = re.findall(r"[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹňḿ]+", ex['pinyin'].lower())
        if len(chars) != len(syllables):
            return False
        lookup = dict(zip(chars, syllables))
        wanted = chosen_reading(word).split()
        boundaries = []
        offset = 0
        for part in ex['chunks']:
            boundaries.append((offset, offset + len(part['hanzi'])))
            offset += len(part['hanzi'])
        for match in re.finditer(re.escape(word), text):
            if len(word) > 1 and not any(start <= match.start() and match.end() <= end for start, end in boundaries):
                continue
            actual = [lookup[i] for i in range(match.start(), match.end())]
            if actual == wanted:
                return True
            if word in {'一', '不'} and all(unicodedata.normalize('NFD', x)[0] == unicodedata.normalize('NFD', y)[0] for x, y in zip(actual, wanted)):
                return True
        return False

    contexts = {word: {text: pair for text, pair in pairs.items() if same_source_reading(pair[0], word)}
                for word, pairs in contexts.items()}
    eligible = [w for w in candidates if len(contexts.get(w, {})) >= 2]
    eligible.sort(key=lambda w: (w not in KINDERGARTEN, w not in originals, w not in PRIORITY, freq.get(w, (100_000, 0))[0], len(w), w))
    required = target - len(baseline)
    if len(eligible) < required:
        dump(CACHE / 'insufficient-candidates.json', {'eligible': len(eligible), 'required': required, 'heads': eligible})
        raise ValueError(f'Only {len(eligible)} eligible headwords with two natural examples; need {required}')
    selected = eligible[:required]
    rows = []
    edits = {}
    provenance = {}
    legacy_sources = {r['词条ID']: r['来源说明'] for r in baseline}
    corrections = editorial_input('editorial-corrections', {})
    for original in baseline:
        row = dict(original)
        correction = corrections.get(row['词条ID'], {})
        for field, value in correction.get('fields', correction.get('updates', correction)).items():
            if field in row and field not in ['词条ID', '组词']:
                row[field] = json.dumps(value, ensure_ascii=False, separators=(',', ':')) if field.endswith('JSON') and not isinstance(value, str) else str(value)
        row['罕度'] = str(choice_pool_rarity(row['组词'], row['词性'], freq, int(original['罕度'])))
        row['拼音'] = normalize_reading(row['拼音'])
        row['英文释义'] = clean_definition(row['英文释义'])
        row['来源说明'] = 'Sources: wordlist-sources/source-index.json (stable ID).'
        changed = [k for k in row if row[k] != original[k]]
        edits[row['词条ID']] = {'changed': changed, 'oldRarity': original['罕度'], 'newRarity': row['罕度'], 'frequencyRank': freq.get(row['组词'], (None, 0))[0], 'editorialCorrection': bool(correction)}
        rows.append(row)
    next_id = max(int(r['词条ID'][3:]) for r in baseline) + 1
    for index, word in enumerate(selected, next_id):
        entry = lex[word][0]
        reading = entry['pinyin']
        senses = entry['senses']
        gloss = '; '.join(dict.fromkeys(senses[:3]))[:290].rstrip(' ;')
        pos = infer_pos(word, gloss)
        if old_chunks[word]:
            (old_reading, old_gloss), count = old_chunks[word].most_common(1)[0]
            if count >= 5 and old_reading == reading and not re.search(r'\b(?:marker|form|measure word)\b', old_gloss, re.I):
                gloss = clean_definition(old_gloss)
        if word in originals:
            reading, gloss, pos = originals[word]['pinyin'], originals[word]['gloss'], originals[word]['pos']
        if word in GRAMMAR:
            reading, gloss, pos = GRAMMAR[word]
        for patch in [lexical_overrides.get(word, {}), head_overrides.get(word, {})]:
            reading = patch.get('pinyin', reading)
            gloss = patch.get('gloss', gloss)
            pos = patch.get('pos', pos)
        if word in GRAMMAR:
            reading, gloss, pos = GRAMMAR[word]
        gloss = clean_definition(gloss)
        if word == '於':
            gloss = 'Classical preposition: at; in; to'
        chunks[word] = (reading, gloss)
        # Prefer short contexts and lexical English overlap; word inclusion alone is not a sense guarantee.
        tokens = set(re.findall('[a-z]{3,}', gloss.lower())) - {'the', 'and', 'for', 'with', 'somebody', 'something'}
        pairs = sorted(contexts[word].values(), key=lambda pair: (word in originals and not pair[1].startswith(('Project', 'Chinese Flashcard', 'AI-assisted')), -len(tokens & set(re.findall('[a-z]{3,}', pair[0]['english'].lower()))), len(pair[0]['hanzi'])))[:2]
        exs = []
        for ex, attribution in pairs:
            ex = dict(ex)
            # Keep source sentence/translation; rebuild chunks with the chosen headword reading.
            parts = None if word in exact_examples and any(e['hanzi'] == ex['hanzi'] for e in exact_examples[word]) else phrase_parts(ex['hanzi'], word)
            if parts is not None:
                ex['chunks'] = parts
                ex['pinyin'] = ' '.join(x['pinyin'] for x in parts)
            exs.append(ex)
        word_id = f'wl_{index:05d}'
        parts = []
        for glyph, py in zip(word, reading.split()):
            hint = chunks.get(glyph, ('', 'character in this word'))[1]
            parts.append({'hanzi': glyph, 'pinyin': py, 'gloss': clean_definition(hint)[:290] or 'character in this word'})
        row = dict(zip(baseline[0], [''] * len(baseline[0])))
        row.update({'罕度': str(choice_pool_rarity(word, pos, freq)), '组词': word, '拼音': reading, '词条ID': word_id,
                    '英文释义': gloss, '词性': pos, '例句JSON': json.dumps(exs, ensure_ascii=False, separators=(',', ':')),
                    '部件JSON': json.dumps(parts, ensure_ascii=False, separators=(',', ':')),
                    '来源说明': 'CC-CEDICT / project editorial; contexts: wordlist-sources/source-index.json.',
                    '本义解释JSON': json.dumps([gloss], ensure_ascii=False), '引申义解释JSON': '[]'})
        provenance[word_id] = [{'hanzi': ex['hanzi'], 'source': attribution} for ex, attribution in pairs]
        rows.append(row)
    source_fixes = editorial_input('resource-editorial-corrections', {})
    simplify = simplified_context_converter()
    context_changes = collections.Counter()
    for row in rows:
        examples_for_row = json.loads(row['例句JSON'])
        for number, ex in enumerate(examples_for_row):
            original_hanzi = ex['hanzi']
            if original_hanzi in source_fixes:
                patch = source_fixes[original_hanzi]
                ex['pinyin'] = patch['pinyin']
                ex['chunks'] = patch['chunks']
                context_changes['readingOrPunctuation'] += 1
            sources = provenance.get(row['词条ID'])
            if sources and 'tatoeba.org' in sources[number]['source']:
                for part in ex['chunks']:
                    part['hanzi'] = row['组词'].join(simplify(piece) for piece in part['hanzi'].split(row['组词']))
                ex['hanzi'] = ''.join(part['hanzi'] for part in ex['chunks'])
                if ex['hanzi'] != original_hanzi:
                    sources[number]['originalHanzi'] = original_hanzi
                    sources[number]['hanzi'] = ex['hanzi']
                    context_changes['simplifiedSpellings'] += 1
        row['例句JSON'] = json.dumps(examples_for_row, ensure_ascii=False, separators=(',', ':'))
    # Rebuild each question's references against the complete ID set; avoid near-identical English choices.
    pools = collections.defaultdict(list)
    for row in rows:
        pools[int(row['罕度'])].append(row)
    for row in rows:
        pool = pools[int(row['罕度'])]
        start = int(hashlib.sha256(row['词条ID'].encode()).hexdigest()[:8], 16) % len(pool)
        answer = set(re.findall('[a-z]{3,}', row['英文释义'].lower())) - {'the', 'and', 'for', 'with', 'somebody', 'something'}
        distractors = []
        glosses = {row['英文释义'].casefold()}
        for offset in range(len(pool)):
            candidate = pool[(start + offset) % len(pool)]
            candidate_tokens = set(re.findall('[a-z]{3,}', candidate['英文释义'].lower())) - {'the', 'and', 'for', 'with', 'somebody', 'something'}
            if candidate['词条ID'] == row['词条ID'] or candidate['英文释义'].casefold() in glosses or answer & candidate_tokens:
                continue
            distractors.append(candidate['词条ID']); glosses.add(candidate['英文释义'].casefold())
            if len(distractors) == 3:
                break
        if len(distractors) != 3:
            raise ValueError('Insufficient distinct choices: ' + row['词条ID'])
        row['干扰词ID'] = json.dumps(distractors, separators=(',', ':'))
    # Regrading must not rewrite existing questions or their distractor choices.
    # Any later content/membership change needs a new explicit pedagogical review.
    from regrade_vocabulary import apply_reviewed_rarities
    apply_reviewed_rarities(rows)
    from refine_wordlist import apply_refinements, overlays
    apply_refinements(rows)
    for row in rows:
        if row['词条ID'] in edits:
            edit = edits[row['词条ID']]
            edit['newRarity'] = row['罕度']
            if row['罕度'] != edit['oldRarity'] and '罕度' not in edit['changed']:
                edit['changed'].insert(0, '罕度')
            elif row['罕度'] == edit['oldRarity'] and '罕度' in edit['changed']:
                edit['changed'].remove('罕度')
    assert len(rows) == target and len({r['词条ID'] for r in rows}) == target
    final_heads = {r['词条ID']: r['组词'] for r in rows}
    replacements = overlays()[0]['rows']
    assert all(final_heads.get(old['词条ID']) == (replacements[old['词条ID']]['newHanzi']
               if old['词条ID'] in replacements else old['组词']) for old in baseline)
    stream = io.StringIO(newline='')
    writer = csv.DictWriter(stream, fieldnames=list(baseline[0]), lineterminator='\n')
    writer.writeheader(); writer.writerows(rows)
    content = stream.getvalue().encode('utf-8-sig')
    if len(content) > 32 * 1024 * 1024:
        raise ValueError(f'Refined CSV exceeds existing 32 MiB bound: {len(content)}')
    (CACHE / 'candidate-wordlist.csv').write_bytes(content)
    dump(DATA / 'legacy-source-notes.json', legacy_sources)
    dump(DATA / 'example-provenance.json', provenance)
    dump(DATA / 'refinement-ledger.json', edits)
    report = {'schemaVersion': 1, 'baselineWords': len(baseline), 'totalWords': len(rows), 'addedWords': len(selected),
              'headwordReplacements': len(replacements),
              'deletedIds': [], 'eligibleCandidates': len(eligible), 'rarity': dict(sorted(collections.Counter(r['罕度'] for r in rows).items())),
              'contextCorrections': dict(context_changes),
              'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest(), 'newExampleSources': dict(collections.Counter('legacy' if x['source'].startswith(('legacy:', 'legacy-context:')) else 'Tatoeba' if 'tatoeba.org' in x['source'] else 'project-original' for v in provenance.values() for x in v)),
              'reviewBoundary': 'Source-assisted full structural/lexical pass; corpus translations and automatic pinyin/chunks still need linguistic review; not human certification.'}
    dump(DATA / 'expansion-report.json', report)
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', type=int, default=18500, choices=range(18000, 20001))
    arguments = parser.parse_args()
    build(arguments.target)
