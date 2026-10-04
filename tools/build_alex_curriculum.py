"""Create Alex's first four original practice lessons and a portable course ZIP.

Run from any directory with Python 3: python tools/build_alex_curriculum.py
This writes only curriculum/alex and course-packages/alex-practice.zip/.md.
No network, historical chat transcripts, audio recordings or stroke data are used.
"""

import copy
import json
from pathlib import Path
import zipfile


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "core/data/src/main/assets/curriculum/alex"
PACKAGE_DIR = ROOT / "course-packages"
VERSION = 1
TITLE = "Alex · Everyday Mandarin practice"
LICENSE = """Alex · Everyday Mandarin practice
Original educational content prepared for the Chinese Self-Study Tutor project, 2026.
Permission is granted to use, copy, adapt and redistribute this teaching content.
Keep this notice with redistributed copies. The material is provided without warranty.
No student chat transcript, personal diary, third-party media, recorded voice or stroke
data is included. Examples describe invented situations, not the student's biography.
The app may use an available device Mandarin voice; no audio model file is bundled.
"""


def sentence(hanzi, pinyin, gloss, translation, note):
    """Keep authored Hanzi/pinyin/literal chunks aligned in Chinese order."""
    h, p, g = hanzi.split("/"), pinyin.split("/"), gloss.split("/")
    if not len(h) == len(p) == len(g):
        raise ValueError("Unaligned sentence chunks: " + hanzi)
    return dict(hanzi="".join(h), pinyin=" ".join(p), translation=translation,
                chunks=[dict(hanzi=a, pinyin=b, gloss=c) for a, b, c in zip(h, p, g)], note=note)


def option(key, hanzi, pinyin):
    return dict(id="alex_" + key, hanzi=hanzi, pinyin=pinyin)


WEARING = [
    sentence("今天，/我/和朋友/一起/出门。", "Jīntiān,/wǒ/hé péngyou/yīqǐ/chūmén.",
             "today/I/with a friend/together/go out", "Today I go out with a friend.",
             "The time phrase comes before the main action. This is an invented practice story."),
    sentence("我/穿着/蓝色的衣服，/戴着/帽子。", "Wǒ/chuān zhe/lánsè de yīfu,/dài zhe/màozi.",
             "I/wear, in that state/blue clothes/wear, in that state/a hat",
             "I am wearing blue clothes and a hat.",
             "穿 (chuān) is used for clothes; 戴 (dài) for hats and glasses. 着 (zhe) presents a continuing state."),
    sentence("朋友/戴着/眼镜，/背着/一个包。", "Péngyou/dài zhe/yǎnjìng,/bēi zhe/yī ge bāo.",
             "the friend/wears, in that state/glasses/carries on the back/a bag",
             "My friend is wearing glasses and carrying a bag on their back.",
             "背 (bēi) is the verb 'carry on the back'; 背 (bèi) has a different reading in words about the back."),
    sentence("他/骑/自行车，/我/走路。", "Tā/qí/zìxíngchē,/wǒ/zǒulù.",
             "he/rides/a bicycle/I/walk", "He rides a bicycle, and I walk.",
             "骑 (qí) describes riding a bicycle or a horse; it does not mean riding in any vehicle."),
    sentence("我们/在学校门口/见/老师。", "Wǒmen/zài xuéxiào ménkǒu/jiàn/lǎoshī.",
             "we/at the school entrance/meet/the teacher", "We meet the teacher at the school entrance.",
             "The place phrase precedes the verb. This story makes no claim about Alex's real school or teacher."),
]

CALLING = [
    sentence("我/在学校门口/等/朋友。", "Wǒ/zài xuéxiào ménkǒu/děng/péngyou.",
             "I/at the school entrance/wait for/a friend", "I wait for a friend at the school entrance.",
             "Place comes before the verb. 等 (děng) can take the person you are waiting for as its object."),
    sentence("学校旁边/有/一家银行。", "Xuéxiào pángbiān/yǒu/yī jiā yínháng.",
             "beside the school/there is/a bank", "There is a bank beside the school.",
             "A place + 有 (yǒu) introduces what is there. 家 (jiā) is a measure word used for businesses here."),
    sentence("我/给朋友/打电话。", "Wǒ/gěi péngyou/dǎ diànhuà.",
             "I/to a friend/make a phone call", "I phone my friend.",
             "给某人打电话 (gěi mǒu rén dǎ diànhuà) places the person before the call expression."),
    sentence("他说：/“我/在/银行旁边。”", "Tā shuō:/“Wǒ/zài/yínháng pángbiān.”",
             "he says/I/am located/beside the bank", "He says, 'I am beside the bank.'",
             "在 (zài) states a location here. It is not the progressive marker before an action."),
    sentence("我/走过去，/我们/一起/回学校。", "Wǒ/zǒu guòqù,/wǒmen/yīqǐ/huí xuéxiào.",
             "I/walk over/we/together/return to school", "I walk over, and we return to school together.",
             "一起 (yīqǐ) means together. Each side of the comma has its own subject and action."),
]

DIRECTIONS = [
    sentence("我/想去/图书馆，/先/看地图。", "Wǒ/xiǎng qù/túshūguǎn,/xiān/kàn dìtú.",
             "I/want to go to/the library/first/look at a map", "I want to go to the library, so I first look at a map.",
             "想 (xiǎng) introduces what you want to do. 先 (xiān) marks the first step."),
    sentence("路口/在/学校前面。", "Lùkǒu/zài/xuéxiào qiánmiàn.",
             "the intersection/is located/in front of the school", "The intersection is in front of the school.",
             "Put the reference place before 前面 (qiánmiàn): 学校前面 (xuéxiào qiánmiàn)."),
    sentence("我/一直往前走，/在路口/左转。", "Wǒ/yīzhí wǎng qián zǒu,/zài lùkǒu/zuǒ zhuǎn.",
             "I/keep walking forward/at the intersection/turn left",
             "I keep walking straight ahead and turn left at the intersection.",
             "左转 (zuǒ zhuǎn) is an action; 左边 (zuǒbian) is a location. Instructions can also use 直走 (zhí zǒu)."),
    sentence("图书馆/在银行对面，/不在/银行右边。", "Túshūguǎn/zài yínháng duìmiàn,/bù zài/yínháng yòubian.",
             "the library/is opposite the bank/is not/on the right of the bank",
             "The library is opposite the bank, not on its right.",
             "对面 (duìmiàn) means opposite or across from; 右边 (yòubian) means the right side."),
    sentence("门/开着，/老师/正在/看书。", "Mén/kāi zhe,/lǎoshī/zhèngzài/kàn shū.",
             "the door/is open, in that state/the teacher/currently/reads a book",
             "The door is open, and the teacher is reading a book.",
             "开着 (kāi zhe) describes the door's continuing state; 正在看书 (zhèngzài kàn shū) describes an action in progress."),
]

MEANING = [
    sentence("我/喜欢/这个游戏，/因为/它/很有意思。", "Wǒ/xǐhuan/zhè ge yóuxì,/yīnwèi/tā/hěn yǒu yìsi.",
             "I/like/this game/because/it/is very interesting", "I like this game because it is interesting.",
             "喜欢 (xǐhuan) expresses liking. 有意思 (yǒu yìsi) often means interesting, rather than literally 'has a meaning'."),
    sentence("朋友/问：/“这个词/是什么意思？”", "Péngyou/wèn:/“Zhè ge cí/shì shénme yìsi?”",
             "a friend/asks/this word/is what meaning", "A friend asks, 'What does this word mean?'",
             "Use 是什么意思 (shì shénme yìsi) to ask what a word means. 意思 (yìsi) ends with a neutral-tone syllable."),
    sentence("我/说：/“‘爱’/可以表达/很深的感情。”", "Wǒ/shuō:/“‘Ài’/kěyǐ biǎodá/hěn shēn de gǎnqíng.”",
             "I/say/love/can express/very deep feelings", "I say, 'Love can express deep feelings.'",
             "爱 (ài) and 喜欢 (xǐhuan) overlap, but their strength and collocations vary. Do not turn this contrast into a ban on either word."),
    sentence("朋友/说：/“我/明白了。/‘意识’和‘意思’/不一样。”", "Péngyou/shuō:/“Wǒ/míngbai le./‘Yìshí’ hé ‘yìsi’/bù yīyàng.”",
             "the friend/says/I/now understand/awareness and meaning/are not the same",
             "My friend says, 'I understand now. Awareness and meaning are different.'",
             "意识 (yìshí) means awareness or consciousness; 意思 (yìsi) means meaning or intention. 了 (le) marks a new understanding here."),
    sentence("我们/玩了一会儿游戏，/然后/一起/学习中文。", "Wǒmen/wán le yīhuìr yóuxì,/ránhòu/yīqǐ/xuéxí Zhōngwén.",
             "we/played games for a while/then/together/study Chinese",
             "We play games for a while, then study Chinese together.",
             "This is an invented interest-based scenario. It contains no copied diary or historical chat wording."),
]


# Words have independent example sentences; a known story sentence may be reused.
LESSONS = [
    dict(key="wearing", course="people", title="Clothes, people and getting around",
         summary="Describe what an imaginary person wears and how they travel.", passage=WEARING,
         objectives=["Choose 穿 (chuān), 戴 (dài), 背 (bēi) or 骑 (qí) for the right object.",
                     "Read five connected sentences and notice continuing states with 着 (zhe).",
                     "Listen, repeat one useful sentence, then write two descriptions of an imaginary person."],
         words=[("wear", "穿", "chuān", "wear clothing; put on clothes", 1),
                ("accessories", "戴", "dài", "wear a hat, glasses or another accessory", 1),
                ("carry", "背", "bēi", "carry on the back (verb)", 2),
                ("ride", "骑", "qí", "ride a bicycle or a horse", 3),
                ("clothes", "衣服", "yīfu", "clothes; clothing", 1),
                ("hat", "帽子", "màozi", "hat; cap", 1),
                ("glasses", "眼镜", "yǎnjìng", "glasses; spectacles", 2),
                ("bike", "自行车", "zìxíngchē", "bicycle", 3)],
         knowledge=[("verbs", "Choose a verb by its object",
                     "Use 穿衣服 (chuān yīfu, wear clothes), 戴帽子 (dài màozi, wear a hat), 戴眼镜 (dài yǎnjìng, wear glasses), 背包 (bēi bāo, carry a bag on the back) and 骑自行车 (qí zìxíngchē, ride a bicycle). These are practical collocations, not a rule that every English 'wear' or 'ride' has one Chinese translation. 背 (bēi) is the carrying verb; the noun 'back' is commonly 背 (bèi)."),
                    ("network", "A useful clothes word family",
                     "Use 衣 (yī) as a shared-character link: 上衣 (shàngyī, top), 外衣 (wàiyī, outer garment), 大衣 (dàyī, overcoat), 雨衣 (yǔyī, raincoat) and 睡衣 (shuìyī, sleepwear). The words share 衣 (yī), but this is a vocabulary memory network, not a historical account of how their characters were formed."),
                    ("state", "A state, then your own description",
                     "穿着衣服 (chuān zhe yīfu, be wearing clothes) describes a continuing state. In the reading, the person remains dressed while doing something else. For writing practice, invent a person and write two short sentences about clothing or transport. Keep the facts you choose consistent; say one sentence, listen to your own replay and try it once more.")],
         blank=sentence("他把帽子/____/在头上。", "Tā bǎ màozi/____/zài tóu shàng.",
                        "he, the hat/____/on his head", "He ____ the hat on his head.",
                        "Choose the verb that fits a hat. The target stays blank in all support lines."),
         choices=[("wear", "穿", "chuān"), ("ride", "骑", "qí"), ("accessories", "戴", "dài"), ("carry", "背", "bēi")],
         answer="accessories", why="戴 (dài) fits putting a hat on the head. 穿 (chuān) is the usual clothing verb; 背 (bēi) carries something on the back; 骑 (qí) is for riding a bicycle or a horse.",
         grammar=sentence("他/穿着/蓝色的/衣服。", "Tā/chuān zhe/lánsè de/yīfu.",
                          "he/wears, in that state/blue/clothes", "He is wearing blue clothes.",
                          "Treat 穿着 (chuān zhe) as one verb-plus-state chunk for this beginner analysis."),
         roles=["SUBJECT", "PREDICATE_HEAD", "ATTRIBUTIVE", "OBJECT"],
         grammar_why="他 (tā) is the subject. 穿着 (chuān zhe) is the predicate-head chunk, including the state marker. 蓝色的 (lánsè de) modifies the noun 衣服 (yīfu), which is the object. The complete predicate includes the clothing phrase.",
         reading_question="Where do the two people meet the teacher?",
         reading_choices=[("bank", "银行里", "yínháng lǐ"), ("school", "学校门口", "xuéxiào ménkǒu"), ("shop", "商店里", "shāngdiàn lǐ"), ("home", "家里", "jiā lǐ")],
         reading_answer="school", reading_why="The final sentence says 在学校门口见老师 (zài xuéxiào ménkǒu jiàn lǎoshī): meet the teacher at the school entrance.",
         listening_range=[2, 3], listening_question="How does the friend travel?",
         listening_choices=[("walk", "走路", "zǒulù"), ("bike", "骑自行车", "qí zìxíngchē"), ("bus", "坐公共汽车", "zuò gōnggòng qìchē"), ("taxi", "坐出租车", "zuò chūzūchē")],
         listening_answer="bike", listening_why="The friend is the person described as 他 (tā); he 骑自行车 (qí zìxíngchē, rides a bicycle). The narrator walks.",
         speaking=[1, 2, 3]),
    dict(key="calling", course="daily", title="Meeting a friend and making a phone call",
         summary="Find a person, describe a nearby place and phone someone.", passage=CALLING,
         objectives=["Use 给某人打电话 (gěi mǒu rén dǎ diànhuà) to say who you phone.",
                     "Locate a person with 在 (zài) and a place with 旁边 (pángbiān).",
                     "Read and listen to a meeting plan; write a short invented message saying where you will wait."],
         words=[("to", "给", "gěi", "to; for; give (the meaning depends on the construction)", 2),
                ("phone", "打电话", "dǎ diànhuà", "make a phone call; phone someone", 2),
                ("friend", "朋友", "péngyou", "friend", 0),
                ("wait", "等", "děng", "wait; wait for", 0),
                ("entrance", "门口", "ménkǒu", "entrance; doorway", 0),
                ("beside", "旁边", "pángbiān", "beside; next to", 1),
                ("bank", "银行", "yínháng", "bank (financial institution)", 1),
                ("school", "学校", "xuéxiào", "school", 0)],
         knowledge=[("call", "Put the person before the phone call",
                     "The useful pattern is 给 + person + 打电话 (gěi + person + dǎ diànhuà). 我给朋友打电话 (Wǒ gěi péngyou dǎ diànhuà) means 'I phone my friend'. 打电话 (dǎ diànhuà) is a whole call expression; do not translate 打 (dǎ) as 'hit' in this compound."),
                    ("location", "Where someone is; what is there",
                     "我在学校门口 (Wǒ zài xuéxiào ménkǒu, I am at the school entrance) states a person's location. 学校旁边有银行 (Xuéxiào pángbiān yǒu yínháng, there is a bank beside the school) introduces a thing at a place. The reference place precedes 旁边 (pángbiān). Write a two-sentence invented meeting message, then say it aloud one sentence at a time."),
                    ("network", "A telephone word family",
                     "The shared-character family of 电话 (diànhuà, telephone) includes 打电话 (dǎ diànhuà, make a call), 电话机 (diànhuàjī, telephone set), 电话卡 (diànhuàkǎ, phone card), 电话费 (diànhuàfèi, telephone charges) and 电话号码 (diànhuà hàomǎ, telephone number). Their modern uses help memory; this is not a claim about ancient character origins.")],
         blank=sentence("我想和朋友通话，/就/____/朋友打电话。", "Wǒ xiǎng hé péngyou tōnghuà,/jiù/____/péngyou dǎ diànhuà.",
                        "I want to speak with a friend/so/____/a friend, make a phone call",
                        "I want to speak with my friend, so I make a phone call ____ my friend.",
                        "Choose the small word that introduces the person being called."),
         choices=[("at", "在", "zài"), ("have", "有", "yǒu"), ("to", "给", "gěi"), ("be", "是", "shì")],
         answer="to", why="给 (gěi) introduces the person being called: 给朋友打电话 (gěi péngyou dǎ diànhuà). 在 (zài) describes location or an ongoing action; 有 (yǒu) means have or there is; 是 (shì) identifies something.",
         grammar=CALLING[2], roles=["SUBJECT", "ADVERBIAL", "PREDICATE_HEAD"],
         grammar_why="我 (wǒ) is the subject. 给朋友 (gěi péngyou) is the adverbial phrase naming the recipient. 打电话 (dǎ diànhuà) is kept as one predicate-head chunk here; this exercise does not ask for its internal verb-object structure.",
         reading_question="Where does the friend say he is?",
         reading_choices=[("door", "学校门口", "xuéxiào ménkǒu"), ("bank", "银行旁边", "yínháng pángbiān"), ("home", "家里", "jiā lǐ"), ("library", "图书馆", "túshūguǎn")],
         reading_answer="bank", reading_why="The reported words are 我在银行旁边 (Wǒ zài yínháng pángbiān): I am beside the bank. The narrator initially waits at the school entrance.",
         listening_range=[0, 1, 2], listening_question="What does the narrator do to contact the friend?",
         listening_choices=[("call", "打电话", "dǎ diànhuà"), ("read", "看书", "kàn shū"), ("ride", "骑自行车", "qí zìxíngchē"), ("shop", "买衣服", "mǎi yīfu")],
         listening_answer="call", listening_why="我给朋友打电话 (Wǒ gěi péngyou dǎ diànhuà) states that the narrator phones the friend.",
         speaking=[0, 2, 4]),
    dict(key="directions", course="daily", title="A short route and an open door",
         summary="Follow simple directions and separate location, ongoing action and continuing state.", passage=DIRECTIONS,
         objectives=["Follow a route with 直走 (zhí zǒu) and 左转 (zuǒ zhuǎn).",
                     "Distinguish 左边 (zuǒbian), 右边 (yòubian) and 对面 (duìmiàn).",
                     "Compare 正在 (zhèngzài) with 着 (zhe); write and say a two-step invented route."],
         words=[("junction", "路口", "lùkǒu", "intersection; road junction", 1),
                ("left", "左边", "zuǒbian", "left side; on the left",
                 sentence("银行/在/学校左边。", "Yínháng/zài/xuéxiào zuǒbian.", "the bank/is located/on the school's left", "The bank is on the left of the school.", "左边 (zuǒbian) names a location; it does not tell someone to turn.")),
                ("right", "右边", "yòubian", "right side; on the right", 3),
                ("straight", "直走", "zhí zǒu", "go straight ahead",
                 sentence("请/直走，/然后/左转。", "Qǐng/zhí zǒu,/ránhòu/zuǒ zhuǎn.", "please/go straight/then/turn left", "Please go straight, then turn left.", "The instructions give the order of two actions.")),
                ("turn", "左转", "zuǒ zhuǎn", "turn left", 2),
                ("opposite", "对面", "duìmiàn", "opposite; across from", 3),
                ("ongoing", "正在", "zhèngzài", "right now; marker of an action in progress", 4),
                ("map", "地图", "dìtú", "map", 0)],
         knowledge=[("route", "Actions and reference places",
                     "直走 (zhí zǒu, go straight) and 左转 (zuǒ zhuǎn, turn left) tell someone what to do. 左边 (zuǒbian, the left side), 右边 (yòubian, the right side) and 对面 (duìmiàn, opposite) describe where something is relative to a place. Use 在路口左转 (zài lùkǒu zuǒ zhuǎn, turn left at the intersection). Draw an imaginary map and write two route instructions; do not use real private addresses."),
                    ("aspect", "Location, an action in progress and a state",
                     "在银行对面 (zài yínháng duìmiàn, opposite the bank) is a location. 正在看书 (zhèngzài kàn shū, currently reading) describes an action in progress. 门开着 (mén kāi zhe, the door is open) describes a continuing state. 着 (zhe) is not simply a replacement for every English '-ing', and 在 (zài) does not always mean an action is in progress."),
                    ("network", "A road word family",
                     "Connect 路口 (lùkǒu, intersection) with five common words that share 路 (lù): 路牌 (lùpái, road sign), 路边 (lùbiān, roadside), 路上 (lùshang, on the way), 马路 (mǎlù, road or street) and 走路 (zǒulù, walk). These are modern vocabulary links, not historical etymologies. Repeat one route sentence at a time, then try your own route without copying the model.")],
         blank=sentence("门/开/____，/老师/正在看书。", "Mén/kāi/____,/lǎoshī/zhèngzài kàn shū.",
                        "the door/is open/____/the teacher/is currently reading", "The door ____ open, and the teacher is reading.",
                        "Choose the marker for the door's continuing state; no completed model is linked to this blank."),
         choices=[("state", "着", "zhe"), ("question", "吗", "ma"), ("modifier", "的", "de"), ("plural", "们", "men")],
         answer="state", why="着 (zhe) marks the continuing open state in 门开着 (mén kāi zhe). The action in the other clause is 正在看书 (zhèngzài kàn shū). The other particles do not complete this description of the door's state.",
         grammar=sentence("我们/在这里/等/老师。", "Wǒmen/zài zhèlǐ/děng/lǎoshī.",
                          "we/here/wait for/the teacher", "We wait for the teacher here.",
                          "The whole location phrase comes before the waiting action."),
         roles=["SUBJECT", "ADVERBIAL", "PREDICATE_HEAD", "OBJECT"],
         grammar_why="我们 (wǒmen) is the subject. 在这里 (zài zhèlǐ) is the location adverbial. 等 (děng) is the verb head, and 老师 (lǎoshī) is its object. The predicate includes the location phrase, verb and object; 在 (zài) is not the progressive marker in this location phrase.",
         reading_question="Which instruction belongs to the route to the library?",
         reading_choices=[("right", "在路口右转", "zài lùkǒu yòu zhuǎn"), ("left", "在路口左转", "zài lùkǒu zuǒ zhuǎn"), ("back", "回家", "huí jiā"), ("call", "给朋友打电话", "gěi péngyou dǎ diànhuà")],
         reading_answer="left", reading_why="The third sentence gives 在路口左转 (zài lùkǒu zuǒ zhuǎn): turn left at the intersection. It does not tell the narrator to turn right.",
         listening_range=[3, 4], listening_question="Where is the library in relation to the bank?",
         listening_choices=[("right", "右边", "yòubian"), ("inside", "里面", "lǐmiàn"), ("opposite", "对面", "duìmiàn"), ("behind", "后面", "hòumiàn")],
         listening_answer="opposite", listening_why="The passage says 图书馆在银行对面 (Túshūguǎn zài yínháng duìmiàn): the library is opposite the bank. It explicitly rules out the bank's right side.",
         speaking=[1, 2, 4]),
    dict(key="meaning", course="interests", title="Meaning, feelings and a game",
         summary="Talk about an interest and distinguish words that look or sound similar.", passage=MEANING,
         objectives=["Distinguish 意思 (yìsi) from 意识 (yìshí) by sound and meaning.",
                     "Use 喜欢 (xǐhuan), 爱 (ài) and 感情 (gǎnqíng) in suitable contexts.",
                     "Ask what a word means; write two original sentences about an imaginary game or another interest."],
         words=[("love", "爱", "ài", "love; to love", 2),
                ("like", "喜欢", "xǐhuan", "like; enjoy", 0),
                ("feelings", "感情", "gǎnqíng", "feelings; affection; emotional relationship", 2),
                ("meaning", "意思", "yìsi", "meaning; intention", 1),
                ("awareness", "意识", "yìshí", "awareness; consciousness", 3),
                ("game", "游戏", "yóuxì", "game", 0),
                ("interesting", "有意思", "yǒu yìsi", "interesting", 0),
                ("understand", "明白", "míngbai", "understand; be clear", 3)],
         knowledge=[("contrast", "Meaning is not awareness",
                     "意思 (yìsi) has a neutral final syllable and means meaning or intention in these contexts. 意识 (yìshí) has a second-tone final syllable and means awareness or consciousness. 这个词是什么意思 (Zhè ge cí shì shénme yìsi, what does this word mean?) asks about meaning. 意识到 (yìshí dào, realize) is a useful related verb form; it is not a substitute for 意思 (yìsi). Listen to the two words, say each, and compare your recordings; self-listening alone is not an automatic tone diagnosis."),
                    ("feelings", "Like, love and feelings",
                     "喜欢游戏 (xǐhuan yóuxì, like games) is an everyday interest expression. 爱 (ài, love) often expresses stronger affection, but actual use depends on the relationship and context. 感情 (gǎnqíng) names feelings or an emotional relationship; it is not another verb meaning 'like'. For writing, choose an imaginary game, book or activity and explain in two simple sentences why it is interesting. Preserve your own meaning; a natural sentence needs no forced rewrite."),
                    ("network", "A shared-character family does not give one fixed meaning",
                     "Five useful words with 意 (yì) are 有意思 (yǒu yìsi, interesting), 不好意思 (bù hǎoyìsi, sorry or embarrassed, depending on context), 同意 (tóngyì, agree), 注意 (zhùyì, pay attention) and 愿意 (yuànyì, be willing). Learn their established whole meanings and common uses. The shared character is a memory link, not proof that every word has the same meaning or a literal English formula.")],
         blank=sentence("请问，/这个词/是什么/____？", "Qǐngwèn,/zhè ge cí/shì shénme/____?",
                        "may I ask/this word/is what/____", "May I ask what ____ this word has?",
                        "Choose the word used when asking for a word's meaning; all support lines leave that span blank."),
         choices=[("awareness", "意识", "yìshí"), ("meaning", "意思", "yìsi"), ("feelings", "感情", "gǎnqíng"), ("game", "游戏", "yóuxì")],
         answer="meaning", why="意思 (yìsi) completes 是什么意思 (shì shénme yìsi), the usual question about meaning. 意识 (yìshí) is awareness, 感情 (gǎnqíng) is feelings and 游戏 (yóuxì) is a game.",
         grammar=sentence("我/喜欢/这个/游戏。", "Wǒ/xǐhuan/zhè ge/yóuxì.",
                          "I/like/this/game", "I like this game.",
                          "The demonstrative plus measure word modifies the object noun."),
         roles=["SUBJECT", "PREDICATE_HEAD", "ATTRIBUTIVE", "OBJECT"],
         grammar_why="我 (wǒ) is the subject. 喜欢 (xǐhuan) is the predicate head. 这个 (zhè ge) modifies 游戏 (yóuxì), the object. The complete predicate is 喜欢这个游戏 (xǐhuan zhè ge yóuxì).",
         reading_question="What do the friends do after playing games for a while?",
         reading_choices=[("study", "学习中文", "xuéxí Zhōngwén"), ("shop", "买衣服", "mǎi yīfu"), ("phone", "打电话", "dǎ diànhuà"), ("bank", "去银行", "qù yínháng")],
         reading_answer="study", reading_why="The final sentence uses 然后 (ránhòu, then) before 一起学习中文 (yīqǐ xuéxí Zhōngwén, study Chinese together).",
         listening_range=[0, 1], listening_question="Why does the narrator like this game?",
         listening_choices=[("interesting", "很有意思", "hěn yǒu yìsi"), ("near", "很近", "hěn jìn"), ("blue", "是蓝色的", "shì lánsè de"), ("open", "开着", "kāi zhe")],
         listening_answer="interesting", listening_why="The reason follows 因为 (yīnwèi, because): 它很有意思 (tā hěn yǒu yìsi), it is interesting.",
         speaking=[0, 1, 3]),
]


COURSES = [
    ("people", "Alex · Clothes and people", "Describe clothing, accessories and transport in a small everyday story."),
    ("daily", "Alex · Directions and daily plans", "Make a phone call, locate a friend and follow a short route."),
    ("interests", "Alex · Meaning, feelings and interests", "Use an interest to practise natural expression and confusing word pairs."),
]


def build_catalog():
    catalog = dict(schemaVersion=2, packageId="builtin-alex", contentVersion=VERSION,
                   courses=[], units=[], lessons=[], words=[], characters=[], radicals=[],
                   knowledge=[], exercises=[], pronunciation=[], audio=[], strokes=[])
    for key, title, description in COURSES:
        catalog["courses"].append(dict(id=f"alex_course_{key}", title=title, description=description,
                                      unitIds=[f"alex_unit_{x['key']}" for x in LESSONS if x["course"] == key]))
    for spec in LESSONS:
        key = spec["key"]
        lesson_id, unit_id = f"alex_lesson_{key}", f"alex_unit_{key}"
        knowledge_ids = [f"alex_knowledge_{key}_{name}" for name, _, _ in spec["knowledge"]]
        catalog["knowledge"].extend(dict(id=ident, title=title, explanation=text, relatedIds=[])
                                    for ident, (_, title, text) in zip(knowledge_ids, spec["knowledge"]))
        word_ids = []
        for name, hanzi, pinyin, meaning, example in spec["words"]:
            word_id = f"alex_word_{key}_{name}"
            word_ids.append(word_id)
            catalog["words"].append(dict(id=word_id, hanzi=hanzi, pinyin=pinyin, meaning=meaning,
                                        example=spec["passage"][example] if isinstance(example, int) else example))
        exercise_ids = []

        def exercise(kind, title, **fields):
            exercise_id = f"alex_exercise_{key}_{kind}"
            exercise_ids.append(exercise_id)
            catalog["exercises"].append(dict(id=exercise_id, lessonId=lesson_id, title=title,
                                             knowledgeIds=knowledge_ids, **fields))

        choices = [option(f"{key}_cloze_{name}", h, p) for name, h, p in spec["choices"]]
        exercise("cloze", "Choose a word that fits the context", type="cloze", prompt=spec["blank"],
                 options=choices, answerId=f"alex_{key}_cloze_{spec['answer']}", explanation=spec["why"])
        tokens = [dict(id=f"alex_{key}_token_{i}", hanzi=chunk["hanzi"].rstrip("。"),
                       pinyin=chunk["pinyin"].rstrip(".")) for i, chunk in enumerate(spec["grammar"]["chunks"])]
        exercise("grammar", "Find the subject, head and supporting chunks", type="grammar",
                 sentence=spec["grammar"], tokens=tokens,
                 clauses=[dict(id=f"alex_{key}_clause", label="Main clause",
                               tokenIds=[t["id"] for t in tokens],
                               roles={t["id"]: role for t, role in zip(tokens, spec["roles"])},
                               predicateTokenIds=[t["id"] for t, role in zip(tokens, spec["roles"]) if role != "SUBJECT"],
                               explanation=spec["grammar_why"])])
        for mode in ("reading", "listening"):
            passage = spec["passage"] if mode == "reading" else [spec["passage"][i] for i in spec["listening_range"]]
            exercise(mode, "Understand the short reading" if mode == "reading" else "Listen for the key detail",
                     type="comprehension", mode=mode, passage=passage, question=spec[f"{mode}_question"],
                     options=[option(f"{key}_{mode}_{name}", h, p) for name, h, p in spec[f"{mode}_choices"]],
                     answerId=f"alex_{key}_{mode}_{spec[f'{mode}_answer']}", explanation=spec[f"{mode}_why"])
        catalog["units"].append(dict(id=unit_id, courseId=f"alex_course_{spec['course']}",
                                     title=spec["title"], description=spec["summary"], lessonIds=[lesson_id]))
        catalog["lessons"].append(dict(id=lesson_id, unitId=unit_id, title=spec["title"], summary=spec["summary"],
                                       objectives=spec["objectives"], prerequisites=["Recognise 我 (wǒ), 你 (nǐ), 他 (tā), 有 (yǒu) and basic verb-object sentences. Written pinyin uses dictionary tones."],
                                       knowledgeIds=knowledge_ids, sentences=spec["passage"], wordIds=word_ids,
                                       characterIds=[], exerciseIds=exercise_ids))
        catalog["pronunciation"].append(dict(id=f"alex_speaking_{key}", title=f"Alex · {spec['title']}",
                                             explanation="Listen to one model, say it, replay your own attempt, then try one original sentence. Choose one useful point to improve. Self-assessment is not an automatic pronunciation score. Written pinyin keeps dictionary tones; speech may show normal tone changes.",
                                             prompts=[dict(hanzi=spec["passage"][i]["hanzi"], pinyin=spec["passage"][i]["pinyin"],
                                                           explanation=spec["passage"][i]["translation"] + " " + spec["passage"][i]["note"])
                                                      for i in spec["speaking"]]))
    return catalog


GUIDE = """# Alex 首批专项课程包

版本 1；3 门课程、4 课、32 个目标词、20 句连贯阅读、12 个知识点、16 道练习、12 个听说提示。

| 课 | 重点 |
| --- | --- |
| 衣服与人物 | 穿/戴/背/骑，衣服、帽子、眼镜、自行车；着表示持续状态 |
| 相约与电话 | 给某人打电话、等朋友、学校门口、银行旁边 |
| 问路与状态 | 路口、直走/左转、左右/对面；位置在、正在动作、着状态 |
| 意思、感情与游戏 | 爱/喜欢/感情，意思/意识，有意思、明白；兴趣主题短文 |

每课先读词与短文，再做语境填空、句法标注、阅读理解和听力理解；听说页一次练一句。
知识点保留同字词网络和写作提示。英文解释、带声调拼音和中文语序 chunks 随每个示例保存。
写作提示是开放表达任务，不提供虚假的自动判分；需结合应用的写作/教师反馈能力使用。

这些句子均为新写的虚构教学情境，不复制历史 chat、日记或学生隐私。词语关联是现代记忆提示，不编造字源。
包内不带录音或笔顺；听力和示范使用应用可用的设备中文语音。设备没有合适语音时不能视为完成听力验证。

`alex-practice.zip` 可从应用 Course packages 菜单导入；ZIP 根目录直接包含 manifest.json、catalog.json 和 LICENSE.txt。
它使用 manifest schema 1 / catalog schema 2，新增 comprehension 题型，需要本次配套应用版本。
内置资产 packageId 为 builtin-alex；独立导入包为 alex-practice，两者 local ID 相同、导入时另加包域前缀。
已带内置课程时通常无需重复安装此 ZIP；它用于转移或独立教材分发。

用 `python tools/build_alex_curriculum.py` 重新生成。更新内容时保留稳定 ID 并同时提高生成器 VERSION。
生成完成只证明产物已经写出，不代表设备播放、课程导入或课堂效果已经验收。
"""


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def manifest(package_id):
    return dict(schemaVersion=1, packageId=package_id, title=TITLE, contentVersion=VERSION,
                catalog="catalog.json", licenseFile="LICENSE.txt")


def main():
    catalog = build_catalog()
    OUT.mkdir(parents=True, exist_ok=True)
    PACKAGE_DIR.mkdir(parents=True, exist_ok=True)
    (OUT / "catalog.json").write_bytes(json_bytes(catalog))
    (OUT / "manifest.json").write_bytes(json_bytes(manifest("builtin-alex")))
    (OUT / "LICENSE.txt").write_text(LICENSE, encoding="utf-8", newline="\n")
    (PACKAGE_DIR / "alex-practice.md").write_text(GUIDE, encoding="utf-8", newline="\n")
    portable = copy.deepcopy(catalog)
    portable["packageId"] = "alex-practice"
    payloads = {"manifest.json": json_bytes(manifest("alex-practice")), "catalog.json": json_bytes(portable),
                "LICENSE.txt": LICENSE.encode("utf-8"), "README.md": GUIDE.encode("utf-8")}
    with zipfile.ZipFile(PACKAGE_DIR / "alex-practice.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, payload in payloads.items():
            entry = zipfile.ZipInfo(name, date_time=(2026, 10, 3, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, payload)
    print(f"Generated {len(catalog['courses'])} courses, {len(catalog['lessons'])} lessons, "
          f"{len(catalog['words'])} words and {len(catalog['exercises'])} exercises in {OUT}")
    print(PACKAGE_DIR / "alex-practice.zip")


if __name__ == "__main__":
    main()
