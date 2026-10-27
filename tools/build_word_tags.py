"""Build/check definition-based editorial tags without rewriting the wordlist.

Rules inspect the teaching gloss/POS, never example sentences or Stage as a proxy
for register. Every entry is inspected; uncertain semantic topics retain an explicit review reason.
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
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "wordlist.csv"
ASSETS = ROOT / "core/data/src/main/assets/word-tags"
MANIFEST = ROOT / "docs/wordlist-validation.json"
VERSION = "2026-10-27.3"

# Stable IDs are persisted by the app. Display labels may be edited independently.
TOPICS = [
    ("family", "Family & relatives", "亲属与家庭", "Kinship, marriage and family relationships.", "亲属称谓、婚姻与家庭关系。"),
    ("people", "People & identity", "人物与身份", "People, age groups, names and social identities.", "人物、年龄群体、姓名与身份。"),
    ("body_health", "Body & health", "身体与健康", "Body parts, symptoms, treatment and health.", "身体部位、症状、治疗与健康。"),
    ("emotions", "Feelings & character", "情绪与性格", "Feelings, moods and personal character.", "情感、心情与性格特征。"),
    ("home", "Home & buildings", "居家与建筑", "Dwellings, buildings, rooms and household activities.", "住房、建筑、房间与家务生活。"),
    ("furniture", "Furniture", "家具", "Furniture used in living and working spaces.", "生活与工作空间中的家具。"),
    ("appliances", "Household appliances", "电器", "Recognizable household electrical appliances.", "常见家用电器；不因带有电字就归入。"),
    ("food", "Food & drink", "饮食", "Food, drinks, meals, cooking and dining.", "食物、饮品、用餐、烹饪与餐饮。"),
    ("clothing", "Clothing & appearance", "衣着与外观", "Clothes, footwear, accessories and grooming.", "衣服、鞋帽、饰物与仪容。"),
    ("study", "School & study", "学校与学习", "Learning, teaching, school and examinations.", "学习、教学、学校与考试。"),
    ("work", "Work & occupations", "工作与职业", "Employment, professions, workplace and business operations.", "就业、职业、工作场所与经营活动。"),
    ("money", "Money & shopping", "金钱与购物", "Buying, selling, prices, money and finance.", "买卖、价格、货币与金融。"),
    ("travel_transport", "Travel & transport", "出行与交通", "Travel, vehicles, routes and transport facilities.", "旅行、交通工具、路线与交通设施。"),
    ("time_number", "Time & quantity", "时间与数量", "Calendar/time expressions, numbers and quantities.", "日历、时间表达、数词与数量。"),
    ("space", "Space & position", "空间与位置", "Direction, location, distance and spatial relations.", "方向、位置、距离与空间关系。"),
    ("nature_weather", "Nature & weather", "自然与天气", "Natural landforms, water, weather and the environment.", "地形、水体、天气与自然环境。"),
    ("animals", "Animals", "动物", "Animals and explicitly zoological concepts.", "动物与明确的动物学概念；成语中的比喻不据此归类。"),
    ("plants", "Plants & growing", "植物与种植", "Plants, plant parts and cultivation.", "植物、植物部位与种植。"),
    ("sport_leisure", "Sport & leisure", "运动与休闲", "Sport, exercise, games and leisure activities.", "运动、锻炼、游戏与休闲活动。"),
    ("communication", "Language & communication", "语言与交流", "Speaking, reading, writing and interpersonal communication.", "语言、说读写与人际交流。"),
    ("thinking", "Thinking & knowledge", "思考与认知", "Thinking, memory, reasoning and knowledge.", "思考、记忆、推理与知识。"),
    ("society", "Society & public life", "社会与公共生活", "Communities, government, public affairs and social relations.", "群体、政府、公共事务与社会关系。"),
    ("law", "Law & justice", "法律与司法", "Legal concepts, courts, rights and judicial procedures.", "法律概念、法院、权利与司法程序。"),
    ("science_technology", "Science & technology", "科学与技术", "Scientific concepts, engineering, machinery and computing.", "科学概念、工程、机械与计算机技术。"),
    ("history", "History & old institutions", "历史", "Historical people, objects, offices and institutions; not automatically Classical usage.", "历史人物、器物、官职与制度；不自动表示文言用法。"),
    ("culture", "Arts & culture", "文化与艺术", "Literature, music, visual art, cultural practices and heritage.", "文学、音乐、美术、文化习俗与文化遗产。"),
    ("religion", "Religion & mythology", "宗教与神话", "Religious concepts, practices and mythical beings.", "宗教概念、宗教活动与神话形象。"),
    ("geography", "Places & geography", "地点与地理", "Named places and geographical/administrative areas.", "地名与地理、行政区域。"),
    ("daily_actions", "Everyday actions", "日常动作", "Basic physical actions with a clear literal daily sense.", "具有明确日常本义的基础动作。"),
    ("tools", "Tools & materials", "工具与材料", "Physical hand tools, construction materials and their literal properties.", "实体手工具、建筑材料及其本义属性。"),
    ("properties", "Qualities & states", "性质与状态", "Physical or abstract qualities, degree and states without a more specific semantic topic.", "没有更具体语义主题的物理或抽象属性、程度和状态。"),
    ("actions_processes", "Actions & changes", "动作与变化", "General actions, operations and changes without a more specific semantic topic.", "没有更具体主题的一般动作、操作和变化；不因例句出现动作而标注。"),
    ("abstract_relations", "Ideas & relations", "概念与关系", "Abstract entities, causes, conditions, purposes, outcomes, categories and relationships.", "抽象事物、原因、条件、目的、结果、类别与关系；不单凭名词词性推定。"),
    ("grammar", "Grammar & function words", "语法与功能词", "Pronouns, determiners, conjunctions, particles, classifiers, auxiliaries and grammatical constructions.", "代词、限定词、连词、助词、量词、助动表达与明确语法结构；不表示难度或语体。"),
    ("objects", "Objects & materials", "物品与材料", "Physical objects, containers, components and substances without a more specific topic.", "没有更具体主题的实体物品、容器、构件和物质；抽象事物不能据名词词性归入。"),
    ("others", "Others", "其他", "Entries with no specific topic, usage or form annotation yet; removed once a specific tag is assigned.", "尚无主题、用途或词语类型标注的词条；补充正式标签后移除此标记。"),
]
USAGES = [
    ("everyday", "Everyday spoken Chinese", "日常口语", "Useful in ordinary daily conversation, including neutral everyday words; not a frequency score.", "适合普通日常交流，包括中性日用词；不是词频或难度认证。"),
    ("written", "Written expression", "书面表达", "Marked literary/formal wording and selected general written expressions; may overlap everyday use.", "有明确书面、正式或文学色彩的词及选定书面表达；可与日常口语重叠。"),
    ("classical", "Classical usage", "文言", "An explicitly old/Classical sense or a reviewed Classical expression, not merely an old referent.", "明确的古义、文言功能词或经复核的文言表达；不以所指事物古老为依据。"),
]
FORMS = [
    ("noun", "Nouns", "名词", "Entries explicitly labelled as nouns, including named entities and nominal phrases.", "词性明确为名词、专名或名词性词组；多词性词条允许多个类型标签。"),
    ("verb", "Verbs", "动词", "Entries explicitly labelled as verbs or verbal phrases.", "词性明确为动词或动词性词组；不表示日常口语或书面语。"),
    ("adjective", "Adjectives", "形容词", "Entries explicitly labelled as adjectives or adjectival phrases.", "词性明确为形容词或形容词性词组。"),
    ("adverb", "Adverbs", "副词", "Entries explicitly labelled as adverbs or adverbial phrases.", "词性明确为副词或状语词组。"),
    ("number_measure", "Numbers & classifiers", "数词与量词", "Numerals, ordinals, classifiers, measure words and explicitly quantified expressions.", "数词、序数词、量词与有明确数量结构的表达。"),
    ("pronoun", "Pronouns", "代词", "Personal, demonstrative and interrogative pronouns and pronominal phrases.", "人称、指示、疑问代词及代词性词组。"),
    ("function", "Function words", "虚词", "Prepositions, conjunctions, particles, determiners, auxiliaries and grammatical complements.", "介词、连词、助词、限定词、助动词与明确语法补语。"),
    ("sound_interjection", "Sounds & interjections", "拟声与感叹", "Entries explicitly labelled as onomatopoeia, interjections or exclamations.", "词性明确为拟声词、感叹词或感叹表达。"),
    ("phrase", "Phrases & patterns", "词组与句式", "Entries explicitly labelled as phrases, clauses, formulae or multiword grammatical constructions.", "词性明确为词组、分句、套语或多词语法结构；不自动视为成语或惯用语。"),
    ("morpheme", "Bound forms", "语素与构词", "Bound forms, bound nouns/verbs/adjectives, affixes and other explicitly bound morphemes.", "黏着语素、非独立名动形语素与词缀；不把普通单字一律标为语素。"),
    ("abbreviation", "Abbreviations", "缩略语", "Explicit abbreviations and shortened lexical forms, rather than all short words.", "明确的缩略形式；不把字数少的词一律当缩略语。"),
    ("symbol", "Symbols & signs", "符号与标记", "Entries explicitly classified as symbols, signs or letter names.", "词性明确为符号、标记或字母名称；不由罕度推定。"),
    ("idiom", "Chengyu", "成语", "Entries already labelled idiom in the frozen lexical POS, plus explicit reviewed corrections; not all four-character words.", "沿用冻结词库中 idiom 词性及逐词复核补充；四字词不自动视为成语。"),
    ("fixed_expression", "Fixed expressions", "惯用语与谚语", "Reviewed conventional sayings and fixed phrases; ordinary multiword entries are not automatically included.", "经逐词复核的惯用语、谚语和固定说法；多字词不自动归入。"),
]

# Whole English words/phrases in the entry's teaching gloss. These are deliberately
# explicit; do not match isolated Hanzi inside a compound or inspect its examples.
PATTERNS = {
    "family": r"\b(?:father|mother|parent|grandfather|grandmother|grandparent|uncle|aunt|cousin|nephew|niece|sibling|brother|sister|husband|wife|spouse|widow|widower|married couple|marriage|marry|kinship|family member|relative|son-in-law|daughter-in-law)\b",
    "people": r"\b(?:person|people|child|children|adult|elder|elderly|teenager|infant|baby|surname|given name|personal name|courtesy name|man|woman|boy|girl|human being|identity)\b",
    "body_health": r"\b(?:body|head|face|forehead|eye|eyes|eyelid|eyebrow|ear|ears|nose|mouth|lip|lips|tooth|teeth|tongue|throat|neck|shoulder|elbow|wrist|finger|fingers|chest|abdomen|stomach|intestine|liver|kidney|lung|lungs|bone|bones|skin|muscle|blood|disease|illness|sick|ill|symptom|fever|cough|headache|dizziness|vertigo|asthma|eczema|herpes|ulcer|cancer|pain|painful|sore|medical|medicine|medicinal|doctor|nurse|hospital|patient|surgery|anatomy|physician|health|healthy|treatment|diagnosis|infection|pregnant|pregnancy|dementia|acupuncture|morphine|pneumonia|dysentery|malaria|hemorrhoids|radius|tibia|fibula|palpitations|malformation|urinate|defecate)\b",
    "emotions": r"\b(?:happy|happiness|sad|sadness|sorrow|sorrowful|joy|joyful|angry|anger|fear|fearful|afraid|anxious|anxiety|worry|worried|relaxed|lonely|loneliness|jealous|jealousy|envy|envy|grief|shame|ashamed|embarrassed|emotion|feeling|mood|brave|courageous|cowardly|honest|dishonest|loyal|loyalty|kindhearted|generous|selfish|arrogant|conceited|humble|modest|clever|intelligent|stubborn|temperament|personality|cheerful|optimistic|pessimistic|perplexed|alarmed)\b",
    "home": r"\b(?:home|household|dwelling|house|housing|building|bedroom|bathroom|kitchen|living room|dining room|roof|courtyard|housework|laundry|sweep|mop|tidy|cleaning|candle|curtain|curtains|blanket|pillow|mattress)\b",
    "furniture": r"\b(?:furniture|chair|chairs|table|desk|sofa|couch|armchair|stool|bench|bed|bunk bed|cupboard|wardrobe|bookcase|bookshelf|cabinet|chest of drawers)\b",
    "appliances": r"\b(?:refrigerator|fridge|freezer|washing machine|dishwasher|microwave|rice cooker|electric cooker|electric kettle|electric fan|air conditioner|air conditioning|vacuum cleaner|toaster|television|TV set|hair dryer|water heater|household appliance|electrical appliance)\b",
    "food": r"\b(?:food|meal|breakfast|lunch|dinner|supper|rice|noodle|noodles|bread|bun|buns|dumpling|dumplings|cake|biscuit|cookie|chocolate|candy|beef|pork|mutton|lamb|chicken meat|meat|seafood|egg|eggs|milk|cheese|butter|cream|yogurt|apple|banana|lemon|orange|grape|pear|peach|watermelon|fruit|vegetable|cabbage|carrot|potato|tomato|tofu|bean curd|corn|maize|soup|tea|coffee|juice|wine|beer|drink|drinking|eat|eating|cook|cooking|restaurant|dining|flavor|flavour|taste|tasty|delicious|hungry|thirsty|salt|sugar|vinegar|soy sauce|chopsticks|spoon|fork|bowl|plate|tableware|banquet|feast|cream)\b",
    "clothing": r"\b(?:clothes|clothing|garment|garments|shirt|T-shirt|jacket|coat|overcoat|trousers|pants|skirt|dress|sweater|scarf|glove|gloves|sock|socks|shoe|shoes|footwear|boot|boots|hat|cap|necklace|bracelet|earring|jewelry|jewellery|hair ornament|hairstyle|cosmetic|cosmetics|makeup|rouge|face powder|belt|wear clothes|wearing clothes)\b",
    "study": r"\b(?:school|student|pupil|teacher|teach|teaching|learn|learning|study|studying|education|educational|lesson|homework|classroom|examination|exam|examiner|textbook|university|college|kindergarten|scholar|scholarship|curriculum|tuition|tutor|academic)\b",
    "work": r"\b(?:work|worker|working|job|employment|employer|employee|occupation|profession|professional|workplace|office|company|business|factory|labor|labour|salary|wages|manager|director|engineer|carpenter|plumber|electrician|farmer|merchant|broker|commission payment|clerk|interview|resign|resignation|recruit|recruitment)\b",
    "money": r"\b(?:money|buy|buying|purchase|sell|selling|sale|price|cost|payment|pay|paid|income|expense|cash|currency|coin|coins|banknote|bank|finance|financial|loan|debt|interest rate|investment|invest|stock market|tax|taxation|wealth|wealthy|rich person|poverty|poor|shopping|shop|store|market|supermarket|discount|profit|budget|economy|economic|economic system|wages|salary|commission payment)\b",
    "travel_transport": r"\b(?:travel|trip|tourism|tourist|journey|vehicle|car|bus|train|railway|railroad|subway|metro|bicycle|bike|motorcycle|airplane|aeroplane|aircraft|airport|flight|taxi|truck|lorry|boat|ship|barge|ferry|transport|traffic|drive|driving|driver|road|street|alley|highway|avenue|bridge|station|ticket|passport|luggage|suitcase|hotel|carriage|wheel|pedestrian)\b",
    "time_number": r"\b(?:time|hour|minute|second|morning|afternoon|evening|night|midnight|noon|day|days|week|month|year|spring|summer|autumn|winter|today|tomorrow|yesterday|calendar|date|birthday|weekend|number|numeral|quantity|amount|count|counting|hundred|thousand|million|billion|dozen|half|quarter|percent|percentage|duration|anniversary|daily|monthly|yearly|formerly|gradually|finally|always|again|repeatedly|future|past)\b|^(?:one|two|three|four|five|six|seven|eight|nine|ten|zero|first|last|forty)(?:[ ,;(]|$)",
    "space": r"\b(?:east|west|north|south|eastern|western|northern|southern|direction|location|position|distance|distant|remote|nearby|far away|middle|center|centre|corner|inside|outside|upper|lower|above|below|front|behind|left side|right side|vertical|horizontal|spatial|surrounding)\b",
    "nature_weather": r"\b(?:nature|natural environment|weather|sky|cloud|clouds|rain|rainwater|snow|snowfall|wind|storm|thunder|lightning|rainbow|sunlight|sunshine|sunrise|sunset|moonlight|frost|ice|fog|mist|mountain|hill|valley|river|stream|lake|sea|ocean|coast|shore|island|desert|sand|soil|rock|stone|waterfall|flood|drought|earthquake|volcano|climate|environment|ecology|mound|runoff|mirage|earth|forest|jungle)\b",
    "animals": r"\b(?:animal|zoology|mammal|bird|birds|fish|insect|insects|bee|bees|butterfly|mosquito|ant|spider|worm|earthworm|tapeworm|dog|cat|horse|cow|cattle|ox|bull|sheep|goat|pig|swine|hen|chicken|rooster|duck|goose|eagle|falcon|owl|osprey|sparrow|tiger|lion|elephant|giraffe|zebra|panda|monkey|ape|rabbit|mouse|rat|bat|wolf|fox|bear|deer|frog|toad|snake|turtle|shark|whale|dolphin|salmon|trout|carp|lizard|crab|shrimp|lobster|octopus|snail|clam|mussel|oyster|venomous creatures)\b",
    "plants": r"\b(?:plant|plants|botany|tree|trees|grass|flower|flowers|flowering|leaf|leaves|stem|stems|root|roots|seed|seeds|sapling|sprout|bamboo|willow|pine|oak|maple|lotus|orchid|chrysanthemum|rose|blossom|blossoms|bloom|cultivate|cultivation|planting|farmland|harvest|granary|farming|crop|crops|opium poppy|lush|vegetation)\b",
    "sport_leisure": r"\b(?:sport|sports|exercise|physical exercise|athlete|athletics|football|soccer|basketball|volleyball|tennis|badminton|swim|swimming|run|running|jog|jogging|gymnastics|gym|hike|hiking|climb|climbing|play|playing|game|games|chess|entertainment|leisure|amusement|hobby|camp|camping|tent|picnic|dance|dancing)\b",
    "communication": r"\b(?:language|word|words|sentence|phrase|speech|speak|speaking|talk|talks|talking|conversation|communicate|communication|say|saying|tell|told|ask|asking|question|answer|reply|explain|explanation|pronounce|pronunciation|pinyin|read|reading|write|writing|written text|text message|letter|telephone|phone|call|greet|greeting|handshake|shake hands|name|invite|invitation|request|proclamation|announcement|declaration|note|transcript|record|translation|translate|vocabulary|grammar|tone|particle|proverb|slang)\b",
    "thinking": r"\b(?:think|thinking|thought|idea|ideas|mind|memory|remember|forget|knowledge|know|understand|understanding|reason|reasoning|logic|logical|paradox|ethics|concept|theory|theoretical|imagine|imagination|consider|consideration|analyze|analysis|judge|judgment|judgement|decide|decision|plan|planning|expect|forecast|doubt|belief|opinion|recognize|recognition|awareness|consciousness|possibility|method|principle)\b",
    "society": r"\b(?:society|social|public|crowd|community|population|nation|national|ethnic group|government|govern|governing|politics|political|minister|president|mayor|official|administration|administrative|citizen|citizens|state affairs|reform|policy|democracy|election|vote|voting|military|army|soldier|war|battle|peace|rights|reputation|justice|righteousness|servant)\b",
    "law": r"\b(?:law|legal|lawyer|court|lawsuit|litigation|arbitration|judicial|judiciary|justice|verdict|trial|prosecute|prosecution|crime|criminal|prison|imprisonment|punishment|penalty|police|arrest|detention|detain|impeach|impeachment|constitution|statute|regulation|contract|copyright|patent|testimony|witness|sentence to|death sentence)\b",
    "science_technology": r"\b(?:science|scientific|technology|technical|engineering|machine|machinery|mechanical|bearing|computer|software|hardware|internet|network|database|programming|algorithm|physics|chemistry|chemical|mathematics|mathematical|geometry|geometrical|biology|biological|atom|atomic|molecule|molecular|electron|electricity|electrical|voltage|current|circuit|battery|engine|motor|gear|axis|axle|laser|satellite|telescope|microscope|laboratory|experiment|experimental|sensor|data|digital|optical|acoustic)\b",
    "history": r"\b(?:history|historical|ancient|dynasty|dynastic|emperor|empress|imperial|feudal|old government|old imperial|court official|Confucius|Confucian|Book of Songs|Book of Changes|archaeology|archaeological|traditional gift|funeral rite|funeral mourning|traditional unit|traditional measure|courtesy name|inscription)\b",
    "culture": r"\b(?:culture|cultural|art|arts|artist|artistic|paint|painting|poetry|poem|poems|poet|prose|literature|novel|novelist|essay|story|stories|music|musical|song|sing|singing|opera|theater|theatre|drama|dramatic|lute|flute|piano|violin|guitar|calligraphy|calligraphic|haiku|prosody|rubbing|inscription|costume|custom|customs|festival|heritage|Book of Songs|Book of Changes)\b",
    "religion": r"\b(?:religion|religious|Buddhist|Buddhism|Buddha|Taoist|Taoism|Daoist|Daoism|Christian|Christianity|Muslim|Islam|Islamic|temple|monastery|monk|nun|deity|deities|god|goddess|myth|mythical|mythology|phoenix|prayer|pray|worship|sacred|holy|chanting|ritual|divination|trigrams|reincarnation|heavenly being|ghost|demon|scripture|sutra)\b",
    "geography": r"\b(?:geography|geographical|province|provincial|county|district|city|town|village|country|continent|capital city|place name|Gansu|China|Chinese place|Beijing|Shanghai|Taiwan|Hong Kong|Tibet|Xinjiang|Sichuan|Guangdong|Mount Tai|River|Mountain)\b",
    "tools": r"\b(?:axe|ax|hammer|saw|pliers|wrench|spanner|screwdriver|chisel|trowel|plastering float|shovel|spade|hoe|scissors|needle|broom|brush|rope|nail|screw|cement|plaster|brick|mortar|timber|lumber|sandpaper|wooden board)\b",
}

# Finite, reviewed everyday vocabulary. These are lexical forms, not substrings.
EVERYDAY = set("""
东方 儿童 喉咙 聪明 中间 工人 天空 彩虹 红色 帐篷 蜜蜂 柠檬 动物 总是 角落 叔叔 牛肉 房屋 蝙蝠 衣服 冬天 轻松 重新 容易 种子 勇敢 绿色 蜡烛 玉米 项链 小巷 桌子 学习 学校 握手 家人 家庭 爸爸 妈妈 父亲 母亲 哥哥 弟弟 姐姐 妹妹 奶奶 爷爷 外婆 外公 阿姨 舅舅 伯伯 姑姑 丈夫 妻子 结婚 孩子 儿子 女儿 朋友 同学 老师 学生 医生 护士 司机 工程师 农民 商人 工作 公司 办公室 上班 下班 工资 赚钱 买 卖 买卖 购买 价格 便宜 贵 昂贵 钱 现金 银行 商店 商场 超市 购物 水 果汁 牛奶 咖啡 茶 饮料 米饭 面条 面包 包子 饺子 馒头 早饭 午饭 晚饭 早餐 午餐 晚餐 饭店 餐厅 食堂 厨房 鸡蛋 鸡肉 猪肉 羊肉 鱼肉 土豆 西红柿 番茄 白菜 黄瓜 胡萝卜 蔬菜 水果 苹果 香蕉 橘子 橙子 葡萄 西瓜 桃子 梨子 草莓 冰箱 冷冻 洗衣机 电视 电视机 空调 电风扇 电饭锅 微波炉 吹风机 电吹风 热水器 家具 沙发 椅子 凳子 衣柜 书柜 橱柜 书架 床 单人床 双人床 枕头 被子 床单 毯子 毛巾 牙刷 牙膏 肥皂 洗发水 卫生间 浴室 卧室 客厅 阳台 楼梯 电梯 窗户 大门 房间 房子 住房 家务 洗衣服 洗澡 洗脸 刷牙 洗手 打扫 扫地 擦 吃饭 喝水 做饭 烧饭 炒菜 做菜 睡觉 起床 上床 休息 走路 散步 跑步 游泳 骑车 坐车 开车 停车 乘车 上车 下车 汽车 火车 飞机 自行车 公交车 公共汽车 出租车 摩托车 轮船 地铁 车站 火车站 机场 机票 车票 护照 行李 箱子 旅行 旅游 出发 到达 回来 回家 回去 出去 进去 进来 离开 进入 开始 结束 完成 等待 迟到 准时 早上 上午 中午 下午 晚上 今天 昨天 明天 前天 后天 每天 今年 去年 明年 星期 周末 周一 星期一 周二 星期二 周三 星期三 周四 星期四 周五 星期五 周六 星期六 周日 星期日 时间 小时 分钟 秒钟 一会儿 一起 一直 已经 现在 以前 以后 将来 过去 最近 最后 首先 然后 以后 刚才 突然 终于 还是 或者 因为 所以 但是 可是 如果 虽然 当然 也许 可能 应该 必须 需要 想要 希望 喜欢 讨厌 爱好 高兴 开心 快乐 难过 伤心 生气 害怕 担心 放心 紧张 舒服 难受 疼痛 疲劳 累 生病 感冒 发烧 咳嗽 医院 药店 健康 身体 脑袋 头发 眼睛 眼镜 耳朵 鼻子 嘴巴 牙齿 舌头 手指 指头 肚子 肩膀 胳膊 大腿 膝盖 皮肤 脚趾 血液 心脏 肺部 左边 右边 前面 后面 上面 下面 里面 外面 附近 旁边 对面 远处 北方 南方 西方 方向 地方 哪里 那里 这里 什么 怎么 为什么 多少 几个 这个 那个 这些 那些 我们 你们 他们 她们 自己 别人 大家 每个 全部 所有 一些 一点 一半 许多 很多 更多 最多 最少 比较 非常 特别 真正 十分 十个 一个 两个 三个 四个 五个 六个 七个 八个 九个 百 千 万 零 第一 第二 第三 颜色 白色 黑色 黄色 蓝色 紫色 灰色 棕色 粉色 深色 浅色 大小 长短 高低 多少 干净 漂亮 美丽 好看 难看 新鲜 安静 热闹 热情 有趣 无聊 简单 复杂 认真 努力 小心 注意 帮忙 帮助 谢谢 不客气 对不起 没关系 再见 你好 您好 欢迎 请问 请坐 请进 不用 不要 没有 不会 可以 能够 知道 明白 了解 理解 记住 忘记 认识 认为 觉得 感觉 看见 听见 看书 读书 说话 聊天 告诉 问题 回答 解释 说法 电话 手机 打电话 上网 网络 电脑 计算机 电子邮件 短信 地址 名字 姓名 出生 生日 节日 过年 春节 中秋节 国庆节 礼物 谢谢 音乐 电影 电视节目 运动 篮球 足球 乒乓球 羽毛球 跳舞 唱歌 绘画 游戏 玩耍 公园 花园 动物园 植物园 门票 天气 下雨 下雪 刮风 晴天 阴天 春天 夏天 秋天 下班 寒冷 炎热 温暖 凉快 晾晒 太阳 月亮 星星 云朵 白云 雨水 大雨 小雨 大雪 大风 河流 山谷 大海 沙滩 海边 河边 湖泊 森林 树木 树叶 树枝 花朵 草地 小狗 小猫 兔子 老虎 狮子 大象 熊猫 猴子 小鸟 蝴蝶 蚊子 蚂蚁 蜘蛛 狗熊 马匹 绵羊 山羊 公鸡 母鸡 鸭子 鹅 猪 牛 羊 马 猫 狗 鸟 鱼 鸡 鸭 书包 课本 书本 笔记 笔记本 铅笔 钢笔 圆珠笔 橡皮 尺子 教室 考试 作业 练习 复习 预习 上课 下课 课堂 小学 中学 大学 专业 成绩 分数 汉语 中文 英语 口语 书面语 语法 单词 句子 拼音 声调 读音 翻译 发音 意思 文章 故事 新闻 报纸 杂志 图片 照片 照相 拍照 相机
""".split())
WRITTEN = set("""
实施 履行 约束 奉献 贡献 确凿 综合 正义 欲望 局面 利益 物质 思想 名誉 预计 导致 纯粹 所谓 委托 剥夺 庇护 诉讼 赎罪 耻辱 奢侈 空虚 堕落 讽刺 隆重 热衷 忠心 群众 最终 功劳 丰富 逐渐 教育 民族 建筑 角色 严肃 独立 幸福 记录 文字 散文 注疏 属文 亟请 告谕 睽违 俊彦 祯祥 伉俪 囹圄 莞尔 仓廪 莘莘 倜傥 公告 通告 通牒 申报 签署 文稿 文笔 文采 评语 笔录 协议书 会谈 根据 依据 相关 有关 关于 对于 至于 此外 因此 然而 并且 以及 尤其 甚至 仍然 仍旧 尚且 尚未 倘若 尽管 由于 从而 以便 以免 以致 乃至 亦即 既然 无论 否则 何况 总之 综上 首先 其次 最后 例如 比如 即将 目前 当今 迄今 未来 以往 昔日 当年 随后 此后 此前 此时 同时 暂时 逐步 及时 确保 保证 维护 改善 提升 促进 推动 开展 组织 实现 取得 获得 形成 体现 表明 反映 证明 证实 说明 指出 提出 表示 认为 强调 阐述 分析 研究 探讨 总结 概括 评价 评估 判断 考虑 制定 规定 要求 申请 批准 审批 许可 承担 履约 责任 义务 权利 权益 制度 政策 方案 措施 原则 标准 条件 因素 程度 范围 领域 方面 内容 形式 结构 概念 理论 逻辑 推理 结论 观点 意见 建议 信息 资料 数据 文件 文献 参考 引用 统计 比例 趋势 现象 本质 性质 状态 情况 环境 社会 经济 政治 文化 历史 科学 技术 文明 发展 进步 改革 创新 建设 管理 治理 法律 法规 法院 宪法 刑法 合同 仲裁 弹劾 佣金 董事 悖论 伦理
""".split())
CLASSICAL = set("""
暨今 恭贺新厘 亟请 足恭 告谕 杀哀 泄泄 缀足 睽违 般乐 晏起 敖游 挑达 吟哦 摒挡 旁魄 飨宴 伺视 祯祥 一傅众咻 噢咻 丰稔 奄有 在兹 俾使 丕变 耆老 狎昵 琛 辅弼 忒甚 弋射 娣 乖剌 宝璐 予 欤 兮 佥 畀予 俟候 瞽者 莘莘 闿 溲 猗 殂落 缧绁 畎亩 向日
""".split())
FIXED = set("""
没关系 不客气 对不起 请多关照 不好意思 好久不见 一路平安 一路顺风 慢慢来 有什么关系 说实话 总而言之 俗话说 不管三七二十一 三天打鱼两天晒网 百闻不如一见 眼不见为净 远水救不了近火 说曹操曹操到 活到老学到老 磨刀不误砍柴工 有志者事竟成
""".split())
DAILY_ACTIONS = set("""
吃饭 喝水 做饭 烧饭 炒菜 做菜 睡觉 起床 上床 休息 走路 散步 跑步 游泳 骑车 坐车 开车 停车 乘车 上车 下车 回来 回家 回去 出去 进去 进来 离开 进入 出发 到达 等待 打扫 扫地 洗澡 洗脸 刷牙 洗手 洗衣服 看书 读书 说话 聊天 告诉 看见 听见 拿出 拿走 放下 放开 放进 打开 关上 关闭 站立 坐下 蹲下 跳跃 推 拉 抬 搬 提 拿 放 走 跑 跳 坐 站 看 听 说 读 写 吃 喝 买 卖 笑 哭 摸 拍 抱 握手 点头 摇头
""".split())

# Semantic exceptions found during the 200-entry pilot, keyed by stable source ID.
# replacement arrays are intentional: no broad rule may overrule these decisions.
OVERRIDES = {
    "wl_00003": ["topic_study", "usage_everyday"],  # pass an examination, not travel/pass through
    "wl_00039": ["topic_space", "topic_home", "usage_everyday"],
    "wl_00040": ["topic_culture", "usage_everyday"],  # role in a story, not personal character
    "wl_00043": ["topic_science_technology"],  # speed/rate/music tempo is not daily running
    "wl_00115": ["topic_study", "usage_everyday"],
    "wl_00511": ["topic_culture", "topic_religion", "topic_history"],
    "wl_01060": ["topic_family", "topic_people", "usage_everyday"],
    "wl_02399": ["topic_communication", "topic_culture"],  # the word 'proverb' is not itself a proverb
    "wl_03223": ["topic_clothing", "usage_written"],  # pearl: jewelry sense; no automatic Classical label
    "wl_03236": ["topic_communication"],  # the word 'slang' does not assert its own colloquial register
    "wl_03282": ["topic_communication", "topic_culture"],
    "wl_03364": ["topic_family", "topic_people", "usage_written"],
    "wl_03434": ["topic_religion", "topic_culture"],
    "wl_03510": ["topic_religion", "topic_culture"],
    "wl_03559": ["topic_history", "topic_plants", "usage_classical"],
    "wl_04025": ["topic_time_number", "topic_money", "usage_written"],
    "wl_03841": ["topic_food", "topic_money", "usage_classical"],  # 鲍鱼之肆: teaching sense is a salted-fish shop
    "wl_04463": ["topic_nature_weather", "usage_written"],
    "wl_04508": ["topic_home", "usage_written"],
    "wl_04614": ["topic_travel_transport", "usage_everyday"],
    "wl_05047": ["topic_communication", "usage_classical"],  # 夫 as discourse particle, not husband
    "wl_05085": ["topic_time_number", "topic_money", "usage_written"],
    "wl_05409": ["topic_money", "usage_written"],  # 青蚨 taught as money, not the insect
    "wl_07482": ["form_idiom"],  # figurative meaning, not animals/art
    "wl_08133": ["topic_home", "topic_appliances", "usage_everyday"],
    "wl_09321": ["topic_communication", "usage_everyday"],
    "wl_11317": ["topic_home", "topic_furniture", "usage_everyday"],
    "wl_12697": ["topic_communication", "usage_everyday"],
    "wl_12810": ["topic_culture", "topic_communication"],  # the subject Classical Chinese, not Classical register itself
    "wl_12952": ["topic_culture"],
    "wl_00157": ["usage_everyday"],
    "wl_02008": ["topic_tools", "usage_everyday"],
    "wl_02960": ["topic_food", "usage_everyday"],
    "wl_04217": ["topic_daily_actions", "usage_everyday"],
    "wl_04227": ["topic_daily_actions", "usage_everyday"],
    "wl_09473": ["topic_communication", "usage_everyday"],
    "wl_09746": ["topic_science_technology", "usage_everyday"],
    "wl_10754": ["topic_daily_actions", "usage_everyday"],
    "wl_11552": ["usage_everyday"],
    "wl_12142": ["topic_space", "usage_everyday"],
    "wl_12261": ["topic_time_number", "usage_everyday"],
    "wl_12316": ["topic_daily_actions", "usage_everyday"],
    "wl_12938": ["topic_thinking", "usage_everyday"],
    "wl_13113": ["topic_travel_transport", "usage_everyday"],
    "wl_00005": ["usage_everyday", "usage_written"],
    "wl_00009": ["usage_everyday", "usage_written"],
    "wl_00017": ["topic_society", "usage_everyday"],
    "wl_00027": ["topic_thinking", "topic_emotions", "usage_everyday"],
    "wl_00029": ["topic_money", "usage_everyday"],
    "wl_00044": ["topic_emotions", "usage_everyday", "usage_written"],
    "wl_00045": ["topic_home", "usage_everyday"],
    "wl_00048": ["usage_everyday"],
    "wl_00052": ["topic_emotions", "topic_daily_actions", "usage_everyday"],
    "wl_00055": ["topic_body_health", "usage_everyday"],
    "wl_00057": ["topic_emotions", "usage_everyday", "usage_written"],
    "wl_00058": ["topic_people", "topic_work"],
    "wl_01987": ["topic_daily_actions"],
    "wl_02322": ["topic_space"],
    "wl_02944": ["topic_body_health", "usage_everyday"],
    "wl_03447": ["topic_emotions", "usage_everyday"],
    "wl_07890": ["usage_everyday"],
    "wl_11018": ["topic_communication", "topic_work", "usage_written"],
    "wl_11444": ["topic_thinking", "usage_everyday"],
    "wl_11624": ["topic_time_number", "usage_written"],
    "wl_11912": ["topic_people", "topic_society", "usage_everyday"],
    "wl_12895": ["topic_culture", "topic_communication", "topic_history"],
    "wl_00069": ["topic_emotions", "usage_written"],
    "wl_00095": ["topic_emotions", "usage_written"],
    "wl_00099": ["topic_emotions", "topic_thinking", "usage_written"],
    "wl_00209": ["topic_society", "usage_written"],
    "wl_00228": ["topic_science_technology", "usage_written"],
    "wl_00510": ["topic_money", "usage_written"],
    "wl_00850": ["topic_communication", "topic_society", "usage_written"],
    "wl_03277": ["usage_written"],
    "wl_04627": ["topic_time_number", "topic_nature_weather", "usage_written"],
    "wl_04868": ["usage_written"],
    "wl_05558": ["topic_communication", "usage_written"],
    "wl_08706": ["usage_written"],
    "wl_08718": ["topic_society", "usage_written"],
    "wl_10638": ["topic_money", "usage_written"],
    "wl_01984": ["topic_people", "topic_body_health"],
    "wl_02381": ["topic_culture"],
    "wl_03367": ["topic_animals"],
    "wl_04447": ["topic_animals"],
    "wl_04797": ["topic_science_technology"],
    "wl_04802": ["topic_science_technology"],
    "wl_05474": ["topic_law", "usage_written"],
    "wl_06112": ["usage_written"],
    "wl_06118": ["topic_tools"],
    "wl_08323": ["topic_science_technology", "usage_written"],
    "wl_08398": ["topic_money", "topic_work", "usage_written"],
    "wl_08516": ["topic_science_technology", "topic_work", "usage_written"],
    "wl_03145": ["topic_food", "usage_classical"],
    "wl_03250": ["topic_home", "topic_history", "usage_classical"],
    "wl_03266": ["topic_space", "usage_written", "usage_classical"],
    "wl_03298": ["topic_family", "topic_people", "topic_culture", "usage_written"],
    "wl_03365": ["topic_emotions", "usage_written"],
    "wl_03379": ["topic_communication", "topic_culture", "usage_written", "usage_classical"],
    "wl_03448": ["topic_time_number", "usage_classical"],
    "wl_03462": ["topic_culture", "topic_religion"],
    "wl_03492": ["topic_culture", "topic_communication", "topic_study", "usage_written"],
    "wl_03526": ["topic_clothing", "topic_money"],
    "wl_03530": ["topic_nature_weather", "usage_written"],
    "wl_03562": ["topic_people", "topic_communication", "usage_written"],
    "wl_03564": ["topic_emotions", "usage_written", "usage_classical"],
    "wl_03569": ["topic_clothing", "usage_written"],
    "wl_03586": ["topic_history", "topic_culture", "usage_classical"],
    "wl_03594": ["topic_history", "topic_culture", "usage_classical"],
    "wl_03720": ["topic_daily_actions", "usage_classical"],
    "wl_03899": ["topic_culture", "topic_communication", "usage_written", "usage_classical"],
    "wl_03939": ["topic_people", "topic_work", "topic_society", "topic_history"],
    "wl_04046": ["topic_daily_actions", "usage_written", "usage_classical"],
    "wl_04384": ["topic_society", "topic_work", "usage_written", "usage_classical"],
    "wl_04759": ["usage_written"],
    "wl_05779": ["topic_communication", "topic_study", "usage_written"],
    "wl_05933": ["topic_clothing", "usage_written"],
    "wl_05943": ["topic_animals", "topic_nature_weather", "usage_written"],
    "wl_06100": ["topic_culture", "topic_history"],
    "wl_06132": ["topic_emotions", "usage_written"],
    "wl_01311": ["topic_family", "usage_everyday"],  # a relative: kinship, not comparison
    "wl_02358": ["topic_nature_weather", "usage_everyday"],  # spring water, not the season
    "wl_02613": ["topic_tools", "topic_science_technology"],  # a coiled elastic part, not the season
    "wl_02622": ["usage_written"],  # broad body of people is a degree, not anatomy/identity
    "wl_04022": ["topic_history", "topic_people", "topic_society"],
    "wl_04175": ["topic_food", "usage_everyday"],  # table salt is not furniture
    "wl_05842": ["topic_body_health", "usage_written"],  # anatomical temple, not a religious building
    "wl_05882": ["topic_geography", "topic_nature_weather"],
    "wl_07628": ["form_fixed_expression"],  # 敲门砖 is a conventional metaphor, not a chengyu
    "wl_07644": ["form_fixed_expression"],  # a proverb
    "wl_07722": ["form_fixed_expression"],  # 露马脚 is a conventional expression
    "wl_08315": ["topic_communication", "topic_study", "usage_everyday"],
    "wl_10026": ["topic_family", "topic_people"],
    "wl_10141": ["topic_food", "usage_everyday"],  # spring roll is a food
    "wl_10310": ["topic_communication", "usage_everyday", "usage_written"],
    "wl_10643": ["topic_people", "topic_family", "topic_home"],
    "wl_12708": ["topic_science_technology"],  # periodic table is not furniture
}

REVIEW_NOTES = {
    "通过": "教学义是通过考试，不能按pass误归交通。",
    "角色": "教学义是故事中的角色，不按character误归性格。",
    "鲍鱼之肆": "本词条教的是咸鱼店本义，未按现代比喻义归类，也不自动改成成语。",
    "驱赶": "drive away在本条是赶走，不是驾驶交通工具。",
    "蜿蜒": "wind在本条是弯曲，不是风。",
    "鲷鱼": "sea bream是鱼类名称，不能拆sea归天气。",
    "青蚨": "本条教的是钱的文学借代，不按昆虫本义归动物。",
    "傅粉施朱": "本条是涂脂抹粉，face powder属于仪容；现有词性为verb，不据四字强判成语。",
    "缀足": "所指为古代丧葬仪式，不因英文person泛词归人物身份。",
    "台甫": "礼貌询问名字的书面称谓，courtesy name不构成历史主题的充分依据。",
    "仆射": "所指为古官职：标历史、身份与工作，不仅因所指古老就标文言。",
    "珈": "所指为古代首饰，只标历史与衣着，不自动标文言。",
    "古籍": "古代文本属于历史/文化主题，词本身不是文言表达。",
    "书面语": "这个词表示一种语言形式，不表示它自身只能用于书面。",
    "口语": "这个词表示说话形式，不把spoken language直接当语体标签触发词。",
    "珰珥": "ear ornaments指耳饰而非耳部疾病；归衣着外观。",
    "冰箱": "普通日用名词也属于日常口语，同时归居家和电器。",
    "家人": "家庭成员属于亲属与人物；普通中性词也可标日常口语。",
    "危险": "普通会话常用，但没有足够具体主题依据，不强塞到健康或社会。",
}


# Reviewed anchors plus stable hash-stratified picks make 40 entries per Stage.
ANCHORS = {
    0: "东方 儿童 喉咙 聪明 中间 蜜蜂 柠檬 叔叔 牛肉 房屋 衣服 冬天 轻松 容易 勇敢 蜡烛 桌子 学习 学校 握手 家人 冰箱 家具",
    1: "冷冻 通过 共同 热衷 忠心 群众 最终 功劳 公平 梦想 贫穷 角色 民族 速度 严肃 建筑 成熟 哭泣 教育 腹部 幸福 仆人 书面语 口语",
    2: "隆重 玩弄 讽刺 确凿 宗教 综合 放纵 诉讼 奉献 约束 赎罪 耻辱 欲望 局面 剥夺 实施 堕落 委托 正义 履行 利益 纯粹 庇护 导致 物质 思想 所谓 名誉 预计 画蛇添足",
    3: "解剖 八卦 吗啡 董事 仲裁 轴承 佣金 驳船 药剂 畸形 糜烂 痢疾 心悸 侏儒 弹劾 鲑鱼 溃疡 悖论 伦理 湿疹 鳟鱼 渲染 眩晕 疖子 绦虫 哮喘 桡骨",
    4: "啐 玑 珈 珂 俳句 槃 泮 逖 傧相 呒 从兄弟 怔忪 属文 幡幢 神荼郁垒 暨今 琪花瑶草 注疏 神荼 估衣 山坞 台甫 足恭 傅粉施朱 杀哀 缀足 晏起 鲍鱼之肆 吟哦 仆射 予 欤 夫 兮 青蚨",
}


@functools.lru_cache(maxsize=1)
def override_headwords():
    # Source IDs survive editorial replacements. An exception for the old
    # teaching word (e.g. a Classical particle) must not tag its replacement.
    reviewed = json.loads((ROOT / "tools/vocabulary_v2/rarity-review.json").read_text(encoding="utf-8"))
    return {word_id: record["hanzi"] for word_id, record in reviewed["words"].items()}


def has_override(row):
    return row["词条ID"] in OVERRIDES and override_headwords().get(row["词条ID"]) == row["组词"]


@functools.lru_cache(maxsize=1)
def reviewed_daily_words():
    path = ROOT / "tools/vocabulary_v2/headword-refinement-20261027.json"
    return set(json.loads(path.read_text(encoding="utf-8")).get("authoredDailyWords", [])) if path.exists() else set()


def catalog():
    tags = []
    for group, prefix, entries in [("topic", "topic_", TOPICS), ("usage", "usage_", USAGES), ("form", "form_", FORMS)]:
        for key, en, zh, den, dzh in entries:
            tags.append(dict(id=prefix + key, group=group, labelEn=en, labelZh=zh,
                             descriptionEn=den, descriptionZh=dzh))
    return dict(schemaVersion=1, annotationVersion=VERSION, tags=tags)


def _annotate_legacy(row):
    word, gloss, pos = row["组词"], row["英文释义"], row["词性"]
    identifier = row["词条ID"]
    if has_override(row):
        note = REVIEW_NOTES.get(word, "按本条教学义“" + gloss + "”逐词复核标签；没有具体主题依据时只保留可确定的用途／类型。")
        return OVERRIDES[identifier], note, "AI编辑逐词复核；未作真人或权威词典认证。"
    tags, reasons = [], []
    idiom = "idiom" in [part.strip() for part in pos.split(";")]
    for key, pattern in PATTERNS.items():
        # An idiom's physical image does not establish its meaning's topic.
        if idiom and key not in {"emotions", "thinking", "communication", "society", "law"}:
            continue
        inspected = gloss
        # English homographs and phrase components are not topical evidence.
        if key == "family":
            inspected = re.sub(r"\brelative(?:ly)?\b(?!\s+(?:of|by marriage|by blood))", "", inspected, flags=re.I)
            if gloss.lower().strip() == "relative":
                inspected = gloss if pos == "noun" else ""
        elif key == "furniture":
            inspected = re.sub(r"\b(?:table of contents|periodic table|multiplication table|table of figures|table of data|table of numbers|table of values|a data table|data table|table tennis|tableland|river bed|riverbed|seabed|sea bed|flower bed|bed of|hotbed|oyster bed)\b", "", inspected, flags=re.I)
        elif key == "body_health":
            inspected = re.sub(r"\b(?:face powder|ear ornaments|head of|head for|head toward|head towards|face up to|face a|face the|facing|body of|corporate body|governing body|leg of a journey|arm of an organization)\b", "", inspected, flags=re.I)
        elif key == "travel_transport":
            inspected = re.sub(r"\bdrive (?:away|out|off|someone|somebody|a person|one crazy)\b", "", inspected, flags=re.I)
        elif key == "nature_weather":
            inspected = re.sub(r"\b(?:to wind|sea bream|sea cucumber|sea urchin|sea lion|sea horse|sea snake|sea turtle|sea gull|sea anemone)\b", "", inspected, flags=re.I)
        elif key == "time_number":
            inspected = re.sub(r"\b(?:spring mechanism|spring of a machine|coil spring|metal spring|spring-loaded|a spring|second-hand|second hand|secondly)\b", "", inspected, flags=re.I)
        match = re.search(pattern, inspected, re.I)
        if match:
            tags.append("topic_" + key)
            reasons.append("主题依据：" + key + "←“" + match.group(0) + "”。")
    if "topic_furniture" in tags or "topic_appliances" in tags:
        tags.append("topic_home")
    if word in DAILY_ACTIONS:
        tags.append("topic_daily_actions")
    if word in EVERYDAY or word in reviewed_daily_words() or re.search(r"\bcolloquial (?:usage|terminology)\b", gloss, re.I):
        # A colloquial technical diagnosis is still a technical diagnosis.
        if "colloquial terminology" not in gloss.lower() or word in EVERYDAY:
            tags.append("usage_everyday")
    if word in WRITTEN or re.search(r"\bliterary (?:usage|name|allusion|compounds)\b|\(literary\)|\bformal (?:usage|financial)\b", gloss, re.I):
        tags.append("usage_written")
    if word in CLASSICAL or re.search(r"\b(?:old usage|ancient usage|archaic|classical usage|classical question particle|classical discourse-opening particle|particle in classical Chinese|classical particle|classical term)\b", gloss, re.I):
        tags.append("usage_classical")
    if idiom:
        tags.append("form_idiom")
    if word in FIXED:
        tags.append("form_fixed_expression")
    if "usage_everyday" in tags:
        reasons.append("日常口语来自明确日用词表／口语用法标记，不按Stage推定。")
    if "usage_written" in tags:
        reasons.append("书面表达依据明确语体标记或复核的书面词表。")
    if "usage_classical" in tags:
        reasons.append("文言依据古义用法标记或复核词表，不以历史题材推定。")
    if idiom:
        reasons.append("成语沿用本条已有idiom词性，不按字数推定。")
    ambiguity = ""
    if not tags:
        ambiguity = "Others：当前规则无充分依据，保留待补；仍可在In total及Others学习。"
    elif len([tag for tag in tags if tag.startswith("topic_")]) > 3:
        ambiguity = "义项涉及多个主题，需在后续逐词审核中检查是否过宽。"
    elif re.search(r"\b(?:ancient|historical|literary|classical|old usage)\b", gloss, re.I):
        ambiguity = "区分所指事物的年代与词本身语体；标注仍属AI编辑判断。"
    return sorted(set(tags)), " ".join(reasons), ambiguity


# Additional whole-word evidence for lexical meanings missed by the first pilot.
# These never inspect examples, single Hanzi components, Stage or frequency.
EXTRA_PATTERNS = {
    "family": r"\b(?:ancestor|ancestry|descendant|lineage|kin|kinsman|maternal|paternal|bride|bridegroom|betrothal|divorce|son|daughter|offspring)\b",
    "people": r"\b(?:hero|heroine|lady|gentleman|fellow|individual|humanity|humankind|youngster|toddler|orphan|prostitute|hostage|visitor|guest|beggar|neighbor|neighbour|companion|colleague|partner|ally|rival|acquaintance|leader|follower|benefactor|bystander|audience|spectator|bachelor|villain|scoundrel|rascal|idiot|fool|talented person)\b",
    "body_health": r"\b(?:virus|viral|bacteria|bacterial|microbe|pathogen|vaccine|vaccination|immune|immunity|antibody|antibiotic|epidemic|pandemic|contagious|arm|leg|limb|foot|feet|heel|toe|palm|fist|spine|vertebra|rib|skull|brain|nerve|gland|spleen|gallbladder|pancreas|bladder|rectum|anus|buttock|buttocks|breast|genital|penis|vagina|uterus|ovary|menstrual|menstruation|teardrop|sweat|perspiration|saliva|mucus|phlegm|vomit|urine|feces|faeces|heartbeat|breath|breathing|breathe|respiration|posture|childbirth|midwife|death|corpse|autopsy|disability|disabled|paralysis|blind|deaf|wound|injury|bruise|fracture|sprain|heal|hygiene|sanitation|sterilize|disinfect|nutrition|nutrient|vitamin|protein|cholesterol|calorie|therapy|pharmacy|pharmacist|prescription|dosage|pill|ointment|bandage)\b",
    "emotions": r"\b(?:cruel|cruelty|merciless|ruthless|despicable|sincere|sincerity|faithful|trustworthy|diligent|diligence|lazy|laziness|patience|impatient|careful|careless|cautious|attentive|childish|mature|maturity|temper|irritate|admire|admiration|despise|contempt|resent|resentment|regret|remorse|gratitude|grateful|disappointed|disappointment|despair|desperate|hopeless|hopeful|satisfied|satisfaction|disgust|humiliation|delight|affection|sympathy|compassion|gentleness|zeal|enthusiasm|enthusiastic|passionate|fascinated|obsession|ambition|ambitious|desire|yearning|pride|proud|nervous|frustration|ridicule|humor|humour|friendship|friendliness|hostile|hostility|romance|romantic|love|hatred|grudge|greed|greedy|bully|flatter|flattery|bashful|timid|resolve|determination|charisma|guilt|moral|morality|virtue)\b",
    "home": r"\b(?:reside|residence|residential|inhabit|accommodation|lodging|apartment|dormitory|villa|mansion|cottage|hut|palace|castle|fortress|tower|pagoda|pavilion|corridor|balcony|staircase|elevator|wall|door|gate|window|ceiling|eaves|threshold|fence|porch|attic|basement|cellar|chimney|fireplace|plumbing|toilet|washbasin|bathtub|sewer|sewage|garbage|rubbish|dustbin|detergent|soap|towel|toothbrush|toothpaste|quilt|duvet|bedspread|bedsheet|carpet|rug|mirror|padlock|door lock|key)\b",
    "food": r"\b(?:cuisine|culinary|ingredient|recipe|boiled|fried|stir-fry|stir-fried|braised|roasted|baked|steamed|grilled|stewed|simmer|seasoning|condiment|sauce|spice|pepper|mustard|ginger|garlic|onion|scallion|celery|spinach|lettuce|eggplant|aubergine|cucumber|radish|pumpkin|mushroom|leek|seaweed|edible|cereal|grain|wheat|barley|oats|millet|sorghum|sesame|peanut|almond|walnut|chestnut|lychee|longan|mango|melon|strawberry|pineapple|coconut|plum|apricot|sweet potato|yam|taro|starch|flour|dough|pastry|pancake|pudding|sausage|bacon|ham|steak|fillet|oyster sauce|cooking oil|vegetable oil|lard|chili|chilli|liquor|alcoholic drink|cocktail|soft drink|lemonade|mineral water|cutlery|ladle|spatula|saucepan|frying pan|wok|teapot|teacup|wineglass|bottle opener|menu|waiter|waitress|cafeteria|buffet|snack|dessert|appetizer|appetite|chew)\b",
    "clothing": r"\b(?:sleeve|collar|cuff|zipper|buckle|apron|waistcoat|underwear|underpants|pajamas|pyjamas|nightgown|robe|gown|tunic|uniform|shawl|cloak|raincoat|windbreaker|overalls|jeans|denim|stocking|tights|legging|sandals|slippers|sneakers|high heels|shoelace|leather|suede|fabric|textile|silk|cotton|linen|wool|velvet|satin|brocade|embroidery|embroider|weave|woven|knit|tailor|sewing|hairpin|comb|wig|lipstick|eyeliner|perfume|razor|shaving|beard|mustache|moustache|haircut|barber|manicure|nail polish|handbag|purse|wallet|backpack|rucksack|umbrella|parasol)\b",
    "study": r"\b(?:academy|campus|lecture|lecturer|seminar|tutorial|syllabus|assignment|diploma|doctoral|doctorate|dissertation|thesis|schoolbook|schoolbag|classmate|blackboard|whiteboard|chalk|pencil|eraser|exercise book|stationery|notebook|fountain pen|ballpoint|protractor|abacus|dictionary|encyclopedia|encyclopaedia|alumnus|alumni|graduation|enroll|enrol|literacy|literate|illiterate|scholastic|scholarly)\b",
    "work": r"\b(?:supervise|supervisor|supervision|meeting|conference|transaction|trading|commerce|commercial|enterprise|corporation|corporate|industry|industrial|manufacture|manufacturer|production|productivity|craftsman|artisan|apprentice|laborer|labourer|personnel|workforce|teamwork|overtime|retirement|pension|unemployed|unemployment|contractor|freelancer|internship|secretary|accountant|accounting|consultant|entrepreneur|salesperson|retailer|wholesale|distributor|customer|client|supplier|supply chain|warehouse|logistics|courier|express delivery|invoice|merchandise|commodity|inventory)\b",
    "money": r"\b(?:transaction|commerce|retail|wholesale|revenue|expenditure|funding|assets|liability|liabilities|dividend|securities|shareholder|mortgage|credit card|debit|deposit|withdrawal|ATM|remittance|exchange rate|inflation|deflation|recession|subsidy|allowance|compensation|reimbursement|refund|rebate|coupon|voucher|billing|gratuity|bargain|haggle|cashier|checkout|free of charge|cheap|expensive|affordable|costly|fiscal|monetary|tariff|customs duty|toll|rental|insurance|insured|bankruptcy|bankrupt|debit card)\b",
    "travel_transport": r"\b(?:port|harbor|harbour|dock|pier|quay|tunnel|rail track|railway track|tram|streetcar|car park|parking|gas station|petrol station|gasoline|petrol|diesel|navigation|steering|brake|accelerator|clutch|gearbox|license plate|traffic light|traffic jam|congestion|junction|intersection|roundabout|crossroads|crosswalk|sidewalk|pavement|handlebar|tyre|boarding|aboard|departure|arrival|itinerary|visa|check-in|boarding pass|seat belt|hostel|motel|sightseeing|scenic|excursion|outing|commute|sailboat|canoe|kayak|yacht|raft|rowing|paddle|oar|sailing|hull)\b",
    "time_number": r"\b(?:season|century|decade|era|epoch|moment|temporary|permanent|immediate|recent|previous|subsequent|simultaneous|meanwhile|beforehand|afterwards|afterward|before|since|until|during|whenever|sometime|occasionally|frequently|often|usually|seldom|rarely|never|already|soon|early|delay|postpone|deadline|chronological|lifelong|eternal|eternity|forever|speed|rapid|slowly|swift|pace|kilometer|kilometre|centimeter|centimetre|millimeter|millimetre|liter|litre|kilogram|tonne|mile|cubic|weight|weigh|length|width|height|depth|volume|capacity|proportion|ratio|fraction|multiple|arithmetic|subtraction|average|maximum|minimum|at least|at most|several|numerous|abundant|scarcity|ample|sufficient|insufficient|excessive)\b",
    "nature_weather": r"\b(?:sun|moon|planet|universe|cosmos|galaxy|astronomy|celestial|lunar|solar|eclipse|constellation|comet|meteor|tide|tidal|terrain|cliff|gorge|canyon|cave|cavern|plateau|prairie|grassland|meadow|marsh|swamp|wetland|pond|reservoir|spring water|brook|creek|ravine|peninsula|strait|archipelago|seabed|riverbed|glacier|iceberg|sleet|hail|drizzle|downpour|dew|humidity|temperature|freezing|thaw|flame|smoke|ash|dust|mud|pebble|gravel|boulder|mineral|ore|coal|petroleum|natural gas|pollution|recycle|conservation|biodiversity|habitat|ecosystem)\b",
    "animals": r"\b(?:wing|feather|fur|mane|horn|claw|hoof|paw|beak|gill|antler|antelope|buffalo|yak|camel|donkey|mule|pony|puppy|kitten|calf|foal|piglet|duckling|gosling|pigeon|dove|magpie|crow|raven|parrot|peacock|penguin|ostrich|heron|stork|kingfisher|woodpecker|nightingale|finch|silkworm|moth|dragonfly|cicada|cricket|grasshopper|locust|beetle|wasp|hornet|centipede|scorpion|leech|jellyfish|starfish|sea urchin|sea cucumber|abalone|cuttlefish|squid|tortoise|crocodile|alligator|gecko|python|cobra|viper|otter|weasel|squirrel|hedgehog|badger|raccoon|beaver|kangaroo|koala|rhinoceros|hippopotamus|leopard|cheetah|panther|lynx|hyena|jackal|gorilla|chimpanzee|orangutan|baboon|gibbon|walrus|porpoise|mackerel|herring|sardine|anchovy|eel|catfish|bream|perch|halibut|flounder|cod|tuna|sturgeon|prawn|crayfish|barnacle|scallop|cockle)\b",
    "plants": r"\b(?:shrub|herb|herbaceous|fern|moss|lichen|algae|fungus|fungi|cypress|fir|spruce|cedar|elm|birch|poplar|acacia|camphor|banyan|ginkgo|mulberry|tangerine|pomelo|grapefruit|citron|hawthorn|pomegranate|persimmon|loquat|jujube|fig|olive|soybean|mung bean|lentil|petal|pollen|stamen|pistil|calyx|sepal|tuber|rhizome|tendril|stalk|rootstock|orchard|plantation|agriculture|agricultural|irrigation|fertilizer|pesticide|herbicide|plow|plough|sowing|reaping|seedling|germination|wither|wilt)\b",
    "communication": r"\b(?:refute|argue|argument|debate|discuss|discussion|negotiate|persuade|convince|advise|advice|suggest|recommend|accuse|blame|criticize|praise|compliment|insult|slander|gossip|rumor|rumour|propaganda|advertisement|publicity|media|journalism|journalist|reporter|broadcast|newspaper|magazine|publication|publishing|editor|proofread|printing|photocopy|pronoun|conjunction|preposition|classifier|interjection|onomatopoeia|homophone|synonym|antonym|metaphor|simile|rhetoric|linguistics|dialect|vernacular|accent|alphabet|syllable|vowel|consonant|orthography|spelling|punctuation|paragraph|narrate|describe|utterance|mutter|murmur|whisper|shout|yell|exclaim|laugh|laughter|giggle|sob|weep)\b",
    "thinking": r"\b(?:wisdom|intelligence|intellect|cognition|perception|notion|insight|realize|intention|intend|purpose|objective|goal|strategy|tactic|assumption|hypothesis|presumption|supposition|suspicion|disbelief|distrust|uncertainty|obvious|evident|evidence|proof|inference|deduction|conclusion|comprehension|interpretation|evaluation|assessment|estimation|appraisal|reasonable|rational|irrational|absurd|plausible|contemplate|ponder|meditate|predict|anticipation|foresee|foresight|solution|solve|problem|confusion|puzzled|bewilder|mystery|illusion|delusion|misconception|misunderstanding|mistaken|erroneous)\b",
    "society": r"\b(?:militia|regiment|battalion|brigade|navy|naval|air force|troop|infantry|cavalry|artillery|armament|weapon|rifle|pistol|cannon|grenade|bomb|missile|armored|siege|combat|attack|defend|defense|defence|invasion|conquer|surrender|rebellion|revolt|uprising|revolution|civilization|institution|organization|organisation|faction|league|union|alliance|committee|council|parliament|cabinet|ministry|embassy|consulate|diplomacy|ambassador|citizenship|nationality|ethnicity|demographic|immigrant|emigrant|refugee|welfare|charity|volunteer|ceremony|etiquette|polite|courteous|honorable|honourable|hierarchy|social class|inequality|equality|fairness)\b",
    "law": r"\b(?:offense|offence|offender|felony|misdemeanor|illegal|illicit|legislation|ordinance|decree|prohibit|prohibition|forbidden|mandatory|ownership|inheritance|inherit|heir|custody|guardianship|alimony|bail|probation|parole|convict|acquit|plaintiff|defendant|prosecutor|jury|juror|tribunal|forensic|interrogate|detective|fraud|embezzle|bribe|bribery|corruption|smuggle|steal|stolen|theft|robbery|burglar|kidnap|ransom|murder|manslaughter|assault|rape|harassment|torture|trespass|infringe|plagiarism|breach of contract|violation)\b",
    "science_technology": r"\b(?:biochemistry|biotechnology|genetics|gene|genome|DNA|RNA|chromosome|bacteriology|microbiology|geology|mineralogy|meteorology|astrophysics|thermodynamics|electromagnetic|magnetic|magnet|gravity|quantum|neutron|proton|nucleus|nuclear|ionic|isotope|radiation|radioactive|catalyst|catalysis|oxidation|alkali|alkaline|oxide|sulfate|sulphate|chloride|nitrate|carbonate|phosphate|hydroxide|hydrogen|oxygen|nitrogen|carbon|sulfur|sulphur|phosphorus|silicon|sodium|potassium|calcium|magnesium|aluminum|aluminium|copper|zinc|mercury|metallurgy|electronic|electronics|electrode|electrolyte|semiconductor|capacitor|resistor|transistor|diode|microchip|microprocessor|integrated circuit|server|router|modem|browser|webpage|website|email|operating system|smartphone|cellphone|laptop|keyboard|printer|scanner|USB|Bluetooth|Wi-Fi|wireless|broadband|cybersecurity|encryption|artificial intelligence|machine learning|neural network|robot|automation|mechanism|apparatus|measurement|lubricant|hydraulic|pneumatic|turbine|piston|sprocket|pulley|spring mechanism)\b",
    "history": r"\b(?:ancient title|ancient official|official title|feudal lord|monarch|king|queen|duke|duchess|prince|princess|marquis|earl|viscount|baron|aristocrat|nobility|nobleman|noblewoman|peerage|knight|medieval|antiquity|antiquarian|relic|excavation|tomb|mausoleum|burial mound|bronze vessel|ritual vessel|ancient state|ancient capital|ancient kingdom|Warring States)\b",
    "culture": r"\b(?:literary work|literary style|literary criticism|composer|melody|rhythm|rhyme|verse|stanza|couplet|ballad|lyric|chorus|orchestra|symphony|concerto|sonata|sonnet|ballet|concert|recital|stage play|stage performance|actress|actor|fiction|fable|fairy tale|legend|saga|biography|autobiography|memoir|chronicle|anthology|manuscript|scroll|parchment|engraving|sculpture|sculptor|statue|figurine|pottery|porcelain|jade|gemstone|jewel|pearl|agate|amber|ruby|sapphire|emerald|ivory|lacquerware|tapestry|brushwork|inkstone|ink stick|photography|photograph|movie|cinema|screenplay|screenwriter|animation|cartoon|manga)\b",
    "religion": r"\b(?:hell|paradise|nirvana|karma|soul|divine|divinity|immortal|angel|devil|Satan|prophet|prophecy|oracle|omen|superstition|amulet|talisman|incense|sacrificial|altar|shrine|chapel|church|cathedral|mosque|synagogue|priest|bishop|abbot|abbess|lama|Zen|Bible|Quran|Koran|gospel|salvation|redemption|repentance|pilgrim|pilgrimage|exorcism)\b",
    "geography": r"\b(?:prefecture|municipality|metropolis|borough|suburb|hamlet|township|postcode|zip code|continent|Asia|Africa|Europe|America|Oceania|Australia|Antarctica|Arctic|Pacific|Atlantic|Indian Ocean|Mediterranean|Yangtze|Yellow River|Pearl River|country name|county name|city name|village name|mountain name|river name|place in)\b",
    "tools": r"\b(?:blade|knife|dagger|sword|spear|halberd|crossbow|bow and arrow|pickaxe|mattock|rake|sickle|scythe|spinning wheel|loom|spindle|anvil|bellows|forge|kiln|lathe|clamp|tongs|tweezers|auger|awl|grindstone|whetstone|mallet|abrasive|adhesive|glue|duct tape|wire|cable|cord|twine|thread|yarn|fiber|fibre|sheet metal|glass|concrete|asphalt|resin|rubber|plastic|nylon|polymer|polyethylene)\b",
}

ABSTRACT_WORDS = set("事情 事物 对象 事务 情况 情形 局面 现象 状况 状态 方式 方法 办法 方案 原因 结果 后果 效果 影响 关系 联系 条件 前提 基础 原则 规律 规则 制度 体制 系统 体系 结构 构成 组成 部分 整体 全体 群体 集体 个体 本身 自身 特点 特征 特色 特质 性质 属性 本质 实质 内涵 外延 内容 形式 核心 重点 要点 焦点 角度 方面 范围 领域 层次 程度 水平 标准 尺度 分寸 分量 比重 数量 质量 总量 容量 限度 限制 约束 障碍 困难 难题 矛盾 冲突 对立 差别 区别 差异 类别 种类 类型 品种 分类 方向 倾向 趋势 动向 动态 变化 改变 转变 演变 发展 进展 进步 过程 步骤 阶段 环节 程序 顺序 次序 秩序 安排 计划 目标 目的 任务 职责 责任 义务 权利 权力 能力 本领 技能 技巧 才能 才华 资格 资质 成就 成果 功劳 功绩 经验 体验 经历 历程 可能性 必要性 偶然性 必然性 需求 要求 愿望 理想 价值 意义 用途 用处 作用 功能 特性 效能 效率 优势 劣势 缺点 优点 长处 短处 利益 好处 坏处 利害 得失 荣誉 名誉 声誉 面子 威望 地位 声望 尊严 人格 品格 品德 道德 伦理 信誉 信用 机遇 机会 时机 运气 命运 缘分 前途 未来 远景 展望 背景 征兆 迹象 踪迹 痕迹 证据 凭据 依据 根据 理由 借口 把柄 秘密 隐私 机密 权益 实例 事例 例子 案例 范例 样例 典型 榜样 模范 象征 标志 记号 符号 代号 暗号 密码 形象 印象 情节 细节 要素 因素 因果 缘由 缘故 潜力 实力 势力 动力 活力 生命力 生命 生存 存在 实在 虚无 空虚 一致 统一 独立 自由 平等 公平 正义 真理 事实 现实 真相 实情 详情 情报 消息 信息 资讯 材料 资料 数据 资源 来源 根源 源头 出处 起源 起点 起因 结局 下场 终点 终局".split())
GRAMMAR_POS = re.compile(r"\b(?:pronoun|determiner|conjunction|preposition|postposition|particle|classifier|measure word|auxiliary|complement|modal|interrogative|demonstrative|ordinal|numeral|number phrase|quantity phrase|adverb pattern|construction|localizer)\b", re.I)
ABSTRACT_PATTERN = re.compile(r"\b(?:entity|phenomenon|situation|circumstance|relationship|relation|connection|cause|causality|consequence|result|outcome|effect|influence|function|purpose|objective|task|duty|responsibility|obligation|opportunity|chance|luck|destiny|fate|prospect|outlook|context|aspect|dimension|viewpoint|standpoint|perspective|approach|method|manner|pattern|category|classification|system|framework|structure|composition|component|element|factor|portion|segment|entirety|aggregate|essence|meaning|significance|symbol|indication|trace|clue|evidence|basis|foundation|resource|origin|advantage|disadvantage|benefit|achievement|accomplishment|merit|contribution|experience|skill|ability|capability|competence|talent|strength|weakness|momentum|tendency|trend|development|progress|process|procedure|sequence|arrangement|balance|conflict|contradiction|contrast|comparison|similarity|difference|distinction|affiliation|membership|ownership|possession|existence|reality|truth)\b", re.I)
OBJECT_PATTERN = re.compile(r"\b(?:artifact|artefact|utensil|implement|container|basket|box|carton|crate|casing|casket|sack|pouch|packet|package|parcel|bundle|bale|barrel|cask|vat|pot|jar|jug|pitcher|bottle|vial|flask|bucket|pail|basin|tub|trough|tray|lid|stopper|cork|hoop|hook|peg|rod|pole|shaft|slat|lath|plank|panel|ribbon|tassel|fringe|mesh|netting|gauze|sieve|strainer|funnel|spout|nozzle|hose|strap|handle|hilt|latch|eyelet|grommet|pennant|medallion|souvenir|keepsake|memento|receptacle|trinket|ornament|embellishment|powder|granule|bead|lump|chunk|shard|fragment|splinter|sliver|flake|scrap|debris|residue|sediment|sludge|slurry|solvent|mixture|alloy|paraffin|tallow|grease|latex|gelatin|gelatine|gypsum|sandstone|limestone|granite|marble|slate|shale|quartz|feldspar|mica|flint|obsidian|pumice|basalt|foil|cardboard|cardstock|pulp|vellum|cloth|rag|felt|fleece|wadding|stuffing|padding|foam|sponge)\b", re.I)


def lexical_forms(pos):
    """Assign only form labels supported by the entry's explicit POS string."""
    tags = []
    for pattern, tag in [
        (r"\bnoun\b|\b(?:location|locative|locational|time) word\b|^title$", "noun"),
        (r"\bverb\b", "verb"), (r"\badjective\b|^modifier$", "adjective"),
        (r"\badverb(?:ial)?\b", "adverb"),
        (r"\b(?:numeral|ordinal|classifier|measure word|number phrase|quantity phrase)\b", "number_measure"),
        (r"\bpronoun\b", "pronoun"),
        (r"\b(?:preposition|prepositional|postposition|conjunction|particle|determiner|auxiliary|complement|modal|localizer)\b", "function"),
        (r"\b(?:onomatopoeia|interjection|exclamation)\b", "sound_interjection"),
        (r"\b(?:phrase|clause|expression|formula|construction|pattern|greeting|salutation)\b", "phrase"),
        (r"\b(?:bound|morpheme|suffix|prefix|affix)\b", "morpheme"),
        (r"\b(?:abbreviation|acronym)\b", "abbreviation"),
        (r"\b(?:symbol|letter name|sign)\b", "symbol"),
    ]:
        if re.search(pattern, pos, re.I):
            tags.append("form_" + tag)
    return tags


def annotate(row):
    tags, rationale, ambiguity = _annotate_legacy(row)
    tags = set(tags)
    gloss, word, pos = row["英文释义"], row["组词"], row["词性"]
    idiom = "idiom" in re.split(r"[;/]", pos)
    reasons = [rationale] if rationale else []
    if pos == "greeting":
        tags.add("topic_communication")
        reasons.append("问候语属于交流表达。")
        if word in {"早上好", "早安", "晚上好"}:
            tags.add("usage_everyday")
            reasons.append("逐词确认的日常问候。")
    # Preserve exact editorial disambiguations, rather than undoing them with
    # another English keyword pass. Add reliable POS tags even to these entries.
    if not has_override(row):
        # Match regular plural lexical heads as the same semantic evidence;
        # preserve the original gloss so words such as "glass" are not lost.
        singular = []
        for token in re.findall(r"[A-Za-z-]+", gloss):
            if len(token) > 4 and token.endswith("ies"):
                singular.append(token[:-3] + "y")
            elif len(token) > 4 and token.endswith(("ches", "shes", "xes", "sses")):
                singular.append(token[:-2])
            elif len(token) > 3 and token.endswith("s"):
                singular.append(token[:-1])
        expanded_gloss = gloss + "; " + "; ".join(singular)
        for topic, pattern in EXTRA_PATTERNS.items():
            if idiom and topic not in {"emotions", "thinking", "communication", "society", "law"}:
                continue
            inspected = expanded_gloss
            if topic == "body_health":
                inspected = re.sub(r"\b(?:on the other hand|at hand|in hand|hand in hand|by hand|hand over|hand out|leg of a|arm of a)\b", "", inspected, flags=re.I)
            elif topic == "home":
                inspected = re.sub(r"\b(?:key to|key role|key point|key factor|key principle|key issue|key question|key position|key person|key element|column of text|column of troops|cell wall)\b", "", inspected, flags=re.I)
            match = re.search(pattern, inspected, re.I)
            if match:
                tags.add("topic_" + topic)
                reasons.append("释义语义：" + topic + "←“" + match.group(0) + "”。")
    specific = {tag for tag in tags if tag.startswith("topic_") and tag != "topic_others"}
    if GRAMMAR_POS.search(pos):
        tags.add("topic_grammar")
        reasons.append("语法功能依据本条词性／结构标记“" + pos + "”。")
    if re.search(r"\btime (?:noun|word|phrase|expression|localizer)\b", pos):
        tags.add("topic_time_number")
        reasons.append("时间义来自本条明确的时间词词性。")
    if re.search(r"\b(?:location|locative|locational) (?:noun|word|phrase|expression)\b", pos):
        tags.add("topic_space")
        reasons.append("方位／处所义来自本条明确的方位词词性。")
    if not specific and "topic_grammar" not in tags:
        if "adjective" in pos:
            tags.add("topic_properties")
            reasons.append("性质／状态依据本条形容词义，无更具体主题证据。")
        elif re.search(r"\bverb\b", pos) and not idiom:
            tags.add("topic_actions_processes")
            reasons.append("一般动作／变化依据本条动词义，无更具体主题证据。")
        elif word in ABSTRACT_WORDS or ABSTRACT_PATTERN.search(gloss):
            tags.add("topic_abstract_relations")
            reasons.append("概念／关系来自明确抽象释义或精确词表。")
        elif OBJECT_PATTERN.search(gloss):
            tags.add("topic_objects")
            reasons.append("实体物品／材料来自明确名物释义。")
    if pos in {"onomatopoeia", "interjection"}:
        tags.add("topic_communication")
        reasons.append("声音／感叹表达来自明确词性。")
    forms = lexical_forms(pos)
    tags.update(forms)
    if forms:
        reasons.append("词语类型直接依据本条词性“" + pos + "”，不代表语体或难度。")
    if "topic_furniture" in tags or "topic_appliances" in tags:
        tags.add("topic_home")
    if tags:
        tags.discard("topic_others")
        if ambiguity.startswith("Others"):
            ambiguity = "已依明确词性标注类型；具体语义主题仍可继续精修。"
    else:
        ambiguity = "Others：已检查本条释义／词性，缺少充分分类证据。"
    return sorted(tags), " ".join(reasons), ambiguity


def sample_rows(rows):
    chosen = []
    for stage in sorted({int(row["罕度"]) for row in rows}):
        pool = [row for row in rows if int(row["罕度"]) == stage]
        by_word = {row["组词"]: row for row in pool}
        picked = {by_word[word]["词条ID"] for word in ANCHORS.get(stage, "").split() if word in by_word}
        # Guarantee some non-anchor idioms and abstract/verb entries where available.
        stable = sorted(pool, key=lambda row: hashlib.sha256(("tags-pilot-1:" + row["词条ID"]).encode()).hexdigest())
        for category in ["idiom", "verb", "adjective", "noun"]:
            candidates = [row for row in stable if category in row["词性"].split(";") and row["词条ID"] not in picked]
            for row in candidates[:2]:
                if len(picked) < 40:
                    picked.add(row["词条ID"])
        for row in stable:
            if len(picked) >= 40:
                break
            picked.add(row["词条ID"])
        selected = sorted((row for row in pool if row["词条ID"] in picked), key=lambda row: row["词条ID"])
        if len(selected) != min(40, len(pool)):
            raise ValueError(f"Stage {stage} sample size does not match its available words")
        chosen.extend(selected)
    return chosen


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def outputs(source=SOURCE, verify_manifest=True, manifest=MANIFEST):
    content = source.read_bytes()
    digest = hashlib.sha256(content).hexdigest()
    snapshot = json.loads(manifest.read_text(encoding="utf-8")) if verify_manifest else None
    if snapshot is not None and digest != snapshot["sha256"]:
        raise ValueError("CSV does not match its reviewed snapshot manifest")
    rows = list(csv.DictReader(io.StringIO(content.decode("utf-8-sig"))))
    index = {row["词条ID"]: row for row in rows}
    if len(index) != len(rows) or (snapshot is not None and len(rows) != snapshot["words"]):
        raise ValueError("Unexpected word IDs/count")
    missing_overrides = set(OVERRIDES) - set(index)
    if missing_overrides:
        raise ValueError(f"Unknown override IDs: {missing_overrides}")
    tag_catalog = catalog()
    ids = {tag["id"] for tag in tag_catalog["tags"]}
    words, details = {}, {}
    for row in rows:
        result = annotate(row)
        details[row["词条ID"]] = result
        tagged = sorted(set(result[0]))
        if not set(tagged) <= ids:
            raise ValueError(f"Unknown tags for {row['词条ID']}")
        words[row["词条ID"]] = tagged or ["topic_others"]
        if "topic_others" in words[row["词条ID"]] and words[row["词条ID"]] != ["topic_others"]:
            raise ValueError("Others cannot coexist with a specific tag")
    annotated = dict(schemaVersion=1, wordlistSha256=digest, annotationVersion=VERSION, words=words,
                     headwords={row["词条ID"]: row["组词"] for row in rows})
    counts = collections.Counter(tag for tags in words.values() for tag in tags)
    stages = {}
    for stage in sorted({int(row["罕度"]) for row in rows}):
        pool = [row for row in rows if int(row["罕度"]) == stage]
        tagged_count = sum(words[row["词条ID"]] != ["topic_others"] for row in pool)
        stages[str(stage)] = dict(total=len(pool), tagged=tagged_count, others=len(pool) - tagged_count)
    sample = sample_rows(rows)
    text = io.StringIO(newline="")
    writer = csv.writer(text, lineterminator="\n")
    writer.writerow(["词条ID", "Stage", "组词", "拼音", "英文释义", "词性", "tag IDs", "中文标签", "标注理由", "歧义／复核说明", "复核状态"])
    labels = {tag["id"]: tag["labelZh"] for tag in tag_catalog["tags"]}
    for row in sample:
        tags, rationale, ambiguity = details[row["词条ID"]]
        tags = sorted(set(tags)) or ["topic_others"]
        if not rationale:
            rationale = "已检查本条教学义“" + row["英文释义"] + "”；当前目录无足够明确的分类依据，保留Others待补。"
        writer.writerow([row["词条ID"], row["罕度"], row["组词"], row["拼音"], row["英文释义"], row["词性"],
                         " | ".join(tags), " | ".join(labels[tag] for tag in tags), rationale, ambiguity, "全量规则检查；AI编辑判断，未作真人认证"])
    full_review = io.StringIO(newline="")
    full_writer = csv.writer(full_review, lineterminator="\n")
    full_writer.writerow(["词条ID", "Stage", "组词", "拼音", "英文释义", "词性", "tag IDs", "中文标签", "标注理由", "待复核说明"])
    uncertain_topics = []
    for row in rows:
        tags, rationale, ambiguity = details[row["词条ID"]]
        tags = sorted(set(tags)) or ["topic_others"]
        full_writer.writerow([row["词条ID"], row["罕度"], row["组词"], row["拼音"], row["英文释义"], row["词性"],
                              " | ".join(tags), " | ".join(labels[tag] for tag in tags), rationale, ambiguity])
        if not any(tag.startswith("topic_") and tag != "topic_others" for tag in tags):
            uncertain_topics.append({"id": row["词条ID"], "word": row["组词"], "gloss": row["英文释义"],
                                     "pos": row["词性"], "tags": tags,
                                     "reason": "Lexical form is classified; no sufficiently specific semantic topic established by the current definition-based pass."})
    report = dict(schemaVersion=1, annotationVersion=VERSION, wordlistSha256=digest, totalWords=len(rows),
                  taggedWords=sum(tags != ["topic_others"] for tags in words.values()),
                  othersWords=sum(tags == ["topic_others"] for tags in words.values()),
                  typeTaggedWords=sum(any(tag.startswith("form_") for tag in tags) for tags in words.values()),
                  semanticTopicWords=len(rows) - len(uncertain_topics),
                  topicReviewWords=len(uncertain_topics),
                  unspecifiedPosWords=sum(row["词性"].strip().lower() in {"", "word", "unknown"} for row in rows),
                  sampleWords=len(sample), sampleByStage=dict(sorted(collections.Counter(row["罕度"] for row in sample).items())),
                  stages=stages, tags={tag["id"]: counts[tag["id"]] for tag in tag_catalog["tags"]},
                  reviewStatus="Full definition/POS rule pass with editorial disambiguations; not human or dictionary certification")
    return {ASSETS / "catalog.json": encoded(tag_catalog), ASSETS / "annotations.json": encoded(annotated),
            ROOT / "docs/word-tags-sample.csv": text.getvalue().encode("utf-8-sig"),
            ROOT / "docs/word-tags-validation.json": encoded(report),
            ROOT / "tools/vocabulary_v2/word-tags-full-review.csv": full_review.getvalue().encode("utf-8-sig"),
            ROOT / "tools/vocabulary_v2/word-tags-topic-review-needed.json": encoded(uncertain_topics)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="read-only structural and reproducibility check")
    mode.add_argument("--write", action="store_true", help="regenerate tag assets and a 40-per-stage review sample")
    mode.add_argument("--preview", type=Path, help="write draft artifacts and full lexical evidence only in this directory")
    parser.add_argument("--source", type=Path, default=SOURCE, help="CSV input; only preview accepts an unmanifested draft")
    parser.add_argument("--manifest", type=Path, default=MANIFEST, help="reviewed CSV snapshot manifest")
    args = parser.parse_args()
    generated = outputs(args.source, verify_manifest=not bool(args.preview), manifest=args.manifest)
    if args.preview:
        args.preview.mkdir(parents=True, exist_ok=True)
        for path, content in generated.items():
            (args.preview / path.name).write_bytes(content)
        report = json.loads(generated[ROOT / "docs/word-tags-validation.json"])
        print(json.dumps({key: report[key] for key in ["totalWords", "taggedWords", "othersWords", "sampleWords", "sampleByStage"]}, ensure_ascii=False))
        print(f"Draft tags only: {args.preview}; {report['topicReviewWords']} entries have reliable form tags but no semantic topic yet")
        return
    for path, content in generated.items():
        if args.check:
            if not path.is_file() or path.read_bytes() != content:
                raise ValueError(f"Tag artifact is absent or stale: {path.relative_to(ROOT)}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    report = json.loads(generated[ROOT / "docs/word-tags-validation.json"])
    print(json.dumps({key: report[key] for key in ["totalWords", "taggedWords", "othersWords", "sampleWords", "sampleByStage"]}, ensure_ascii=False))
    print("Word tags: " + ("read-only check passed" if args.check else "tag artifacts generated; frozen CSV untouched"))


if __name__ == "__main__":
    main()
