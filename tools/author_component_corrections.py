"""Persist explicit contextual corrections for previously misselected components."""
from __future__ import annotations

import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / 'tools/wordlist_refined/baseline.csv'
OUTPUT = ROOT / 'tools/wordlist_refined/component_editorial.json'

# Each group is a bounded, inspected list of compounds, rather than a global
# character rewrite. Units, titles and genuinely named people are kept distinct.
GROUPS = [
    ('民', '人民、人群 | people', '民族 渔民 难民'),
    ('山', '山、山地 | mountain', '山谷 山羊 山顶 山洞 山崖 山坡'),
    ('米', '这里是谷物、颗粒的构词成分；玉米整体指 corn | grain; part of maize', '玉米'),
    ('米', '长度单位米；厘米是百分之一米 | meter, a unit of length', '厘米'),
    ('庞', '庞：大，庞大整体指规模很大 | large or extensive', '庞大'),
    ('施', '施行、实行 | to carry out', '实施 措施'),
    ('企', '与业组成企业，指从事经营的组织 | bound element in enterprise', '企业'),
    ('劳', '劳作、用力 | labor or effort', '疲劳 勤劳'),
    ('密', '不公开、隐藏 | concealed or private', '秘密'),
    ('密', '细致而严密 | close and careful', '缜密'),
    ('安', '使人安心、安稳 | to put at ease', '安慰 安顿'),
    ('安', '安稳、平静 | safe or peaceful', '安全 安静 安宁 惴惴不安 安逸 乂安'),
    ('关', '与某事有关、相关联 | to concern or relate to', '关于 关心 攸关'),
    ('渠', '输送水的通道 | a water channel', '渠道'),
    ('辜', '辜负整体指没有达到别人的信任或期望 | bound element of letting someone down', '辜负'),
    ('中', '中间、居中 | middle', '中午'),
    ('中', '里面 | inside', '瓮中捉鳖'),
    ('伍', '行列、队列 | a rank or group', '队伍'),
    ('意', '心意、意愿或意念 | intention or thought', '注意 满意 愿意 恣意 肆意 惬意 慊意'),
    ('意', '表达的意思 | intended meaning', '言简意赅'),
    ('机', '机械装置 | a mechanism', '机械 扳机'),
    ('国', '国家 | country', '国际 国王 国籍'),
    ('居', '居住 | to live or reside', '邻居'),
    ('郁', '郁闷整体表示心情不舒畅 | bound element of feeling depressed', '郁闷'),
    ('郁', '气味浓厚 | rich or strong in aroma', '浓郁'),
    ('滑', '与稽组成滑稽，指引人发笑 | part of comical', '滑稽'),
    ('稽', '与滑组成滑稽，指引人发笑 | part of comical', '滑稽'),
    ('稽', '考查、根据 | evidence or verification', '无稽之谈'),
    ('万', '很大数量；千万在劝告中也可表示务必 | ten thousand; emphatic in advice', '千万'),
    ('门', '门、入口 | door or entrance', '门槛 车马阗门 门枨'),
    ('门', '身体部位的开口或通道 | an anatomical opening', '肛门 贲门 囟门'),
    ('门', '与专组成专门，指特定用途或特意 | part of specially or specialized', '专门'),
    ('门', '与抠组成抠门，整体指吝啬 | bound part of stingy', '抠门'),
    ('平', '平稳、均衡或平常 | level, balanced or ordinary', '平安 平衡 平庸'),
    ('平', '平坦 | flat or level', '夷为平地'),
    ('刀', '切割用的刀或刀刃 | a blade or cutting tool', '剪刀 镰刀 铡刀 锉刀 镘刀'),
    ('堡', '防御性建筑、堡垒 | a fortified building', '城堡 碉堡'),
    ('冒', '与犯组成冒犯，指言行失礼或侵犯 | part of offending', '冒犯'),
    ('祝', '表达祝愿 | to express good wishes', '祝贺 庆祝 祝福'),
    ('鱼', '名称或复合词里的鱼字；不能据此判断整个动物的生物分类 | fish-name element; not a biological classification of the whole animal', '鲨鱼 鲸鱼 鱿鱼 鲤鱼 鲑鱼 鳟鱼 鳗鱼 鲈鱼 鳕鱼 鲶鱼 鲱鱼 鳅鱼 鲇鱼 鲫鱼 鲷鱼 鲮鱼 鳝鱼 鲟鱼 鲣鱼 鲻鱼 鳀鱼 鲔鱼 鱼鳔 鲳鱼 鲂鱼 鲢鱼 鳊鱼 鱼鹗 鳙鱼 鱼簖 鲽鱼 鲋鱼 鲎鱼 鳇鱼 鲚鱼 鳐鱼 鱼罾'),
    ('舍', '房屋、住宿处 | lodging', '宿舍'),
    ('舍', '放弃、舍弃 | to give up', '锲而不舍 舍生取义'),
    ('也', '与许组成也许，表示可能 | part of perhaps', '也许'),
    ('保', '保留或保护 | to keep or protect', '保守 保姆 保镖'),
    ('森', '树木众多 | densely wooded', '森林'),
    ('储', '存放、积存 | to store or save', '储蓄'),
    ('卜', '预测吉凶的占卜行为 | divination', '占卜'),
    ('从', '与容组成从容，表示镇定、不慌张 | part of calm and unhurried', '从容'),
    ('巩', '牢固；巩固表示使之更稳固 | firm; to consolidate', '巩固'),
    ('元', '与素组成元素，指化学基本物质 | part of chemical element', '镉元素 钴元素 铍元素 铋元素 镓元素 钋元素 钍元素 钚元素 碲元素 钒元素 锗元素 铷元素 铟元素 铌元素 钽元素 钫元素 锕元素 铪元素 砹元素'),
    ('元', '主要、首要 | chief or principal', '元帅'),
    ('元', '与宵组成元宵，指节日或相关食物 | part of the Lantern Festival expression', '元宵'),
    ('帅', '军队统帅 | military commander', '元帅'),
    ('农', '种植、农业 | farming', '农夫 佃农'),
    ('和', '温和、柔和 | mild or gentle', '和蔼 和煦'),
    ('木', '树木、木材 | tree or wood', '木柴 木炭 木板 檀木 木樨'),
    ('倪', '端倪整体指迹象、线索 | part of clue or indication', '端倪'),
    ('凯', '凯旋整体指胜利归来 | part of returning in triumph', '凯旋'),
    ('臣', '君主的臣属、官员 | a minister or subject', '大臣'),
    ('兀', '突兀整体指突然、显得不协调或高耸 | bound part of abrupt or jutting', '突兀'),
    ('佛', '佛陀、佛教信仰 | the Buddha or Buddhist faith', '佛教 佛龛 赕佛'),
    ('戈', '古代长柄兵器 | an ancient dagger-axe', '干戈 枕戈待旦'),
    ('桑', '桑树；在沧桑中与海陆变化的画面相关 | mulberry tree', '沧桑 桑梓'),
    ('坊', '街坊整体指邻居、邻里 | part of neighbor or neighborhood', '街坊'),
    ('荆', '有刺的灌木 | a thorny shrub', '荆棘'),
    ('赫', '明显、盛大或显著 | prominent or impressive', '显赫 煊赫 烜赫'),
    ('园', '种植或游憩的园地 | garden', '园丁 菜园'),
    ('籍', '登记所属的身份或资格 | registered affiliation', '国籍'),
    ('硕', '硕士整体指研究生学位或取得该学位的人 | part of master\'s degree', '硕士'),
    ('赵', '古代赵国，故事中的归还对象 | the ancient state of Zhao', '完璧归赵'),
    ('郑', '郑重整体指严肃认真，不是姓氏 | part of solemn and serious', '郑重'),
    ('僧', '出家修行的佛教人士 | Buddhist monk', '僧人'),
    ('冉', '与另一冉组成冉冉，表示缓慢渐进 | gradually or slowly, in reduplicated form', '冉冉'),
    ('麦', '麦类或相关谷物名称 | a grain-name element', '荞麦'),
    ('鞠', '鞠躬：弯身行礼；这里不是姓氏 | to bow', '鞠躬尽瘁'),
    ('恪', '谨慎认真、严格 | carefully and strictly', '恪守'),
    ('韦', '编连竹简的皮绳 | leather straps binding bamboo slips', '韦编三绝'),
    ('吾', '我、我们 | I or we', '吾辈'),
    ('虞', '欺骗、欺诈 | to deceive', '尔虞我诈'),
    ('济', '济济：众多、盛多的样子 | numerous, in the reduplicated form', '人才济济'),
    ('尹', '古代官职中的称呼 | an ancient official title', '令尹'),
    ('林', '与翰组成翰林，指古代文职学术机构或官员 | part of the Hanlin institution', '翰林'),
    ('蕃', '生长茂盛 | flourishing', '蕃盛'),
    ('扁', '形容船小 | small, in the expression for a small boat', '一叶扁舟'),
    ('韶', '美好；韶华指美好青春时光 | beautiful; part of youthful years', '韶华'),
    ('柯', '树枝 | a branch', '枝柯'),
    ('靖', '安定、平定 | to pacify', '绥靖'),
    ('丑', '十二地支之一；丑时是传统时辰 | the second Earthly Branch', '丑时'),
    ('侯', '古代爵位或诸侯统治者 | an ancient noble rank', '诸侯'),
    ('苟', '苟且整体表示敷衍或暂且将就 | part of making do or acting carelessly', '苟且'),
    ('窦', '疑窦整体指疑点、使人疑惑之处 | part of a point of doubt', '疑窦'),
    ('甘', '甘美、合意；甘霖指及时的好雨 | welcome or beneficial', '甘霖'),
    ('塔', '塔形建筑 | a tower or pagoda', '宝塔'),
    ('皮', '外皮、外层 | outer covering', '麸皮'),
    ('匡', '扶助、纠正 | to support or rectify', '匡扶'),
    ('矍', '矍铄整体指年老而精神健旺 | part of vigorous in old age', '矍铄'),
    ('兰', '兰草；兰心借指美好品性 | orchid or orchid-related image', '蕙质兰心 芄兰'),
    ('劭', '美好、高尚 | fine or admirable', '年高德劭'),
    ('妁', '媒妁整体指说媒的人 | part of matchmaker', '媒妁'),
    ('云', '云璈是乐器的名称，应整体理解 | part of the instrument name yun\'ao', '云璈'),
    ('颉', '颉颃整体指上下飞翔或相互抗衡 | part of flying up and down or rivaling', '颉颃'),
    ('秦', '秦艽整体是植物和药材名称 | part of the plant name qinjiao', '秦艽'),
    ('黥', '在面部刺字的古代刑罚 | tattooing the face as an ancient punishment', '黥刑'),
    ('蘧', '蘧然或蘧蘧中的构词成分，依整词语境理解 | a bound element in these literary expressions', '蘧然 蘧蘧'),
    ('庾', '储藏粮食的场所 | a granary or grain store', '仓庾'),
]


def main():
    path = BASELINE if BASELINE.exists() else ROOT / 'wordlist.csv'
    rows = list(csv.DictReader(path.open(encoding='utf-8-sig', newline='')))
    present = {row['组词']: row for row in rows}
    result = {}
    for character, gloss, names in GROUPS:
        for word in names.split():
            if word not in present or character not in word:
                raise ValueError('An inspected correction no longer matches its word: ' + word)
            value = result.setdefault(word, {'glossByCharacter': {}, 'evidence':
                'Contextual component correction, authored after inspecting the retained word, reading, selected meaning and example. Does not assert etymology.'})
            value['glossByCharacter'][character] = gloss
    for word in ('咖啡', '巴士'):
        row = present[word]
        result[word] = {'parts': [{'hanzi': word, 'pinyin': row['拼音'], 'gloss': row['英文释义']}],
            'noteAppend': '这是音译词，按整体学习，不把每个字独立拼成词源。 This is a transliterated lexical unit; individual character meanings do not explain its origin.',
            'evidence': 'Explicit transliteration correction.'}
    for row in rows:
        if '姓' in row['组词']:
            result.setdefault(row['组词'], {'glossByCharacter': {}, 'evidence': 'A surname context is explicit in the headword.'})['preserveSurname'] = True
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Explicit contextual correction groups: {len(result)} words.')


if __name__ == '__main__':
    main()
