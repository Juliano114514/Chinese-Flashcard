"""Generate only the original demo deck and its required cached stroke geometry."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVISION = "bddc96d41bef78427ed0e034e9f7e31d71fd1b92"
SOURCE = ROOT / ".gradle/stroke-source"
OUTPUT = ROOT / "core/data/src/main/assets/demo"

# Original beginner examples. Character glosses are memory cues, not etymologies.
ROWS = [
 ("agriculture", "农业", "nóngyè", "agriculture", "noun", [("农","nóng","farming"),("业","yè","industry")],
  [("他学习农业。","Tā xuéxí nóngyè.","He studies agriculture."),("农业需要水。","Nóngyè xūyào shuǐ.","Agriculture needs water.")], "A word for farming as a field of work or study.", ["school","work","weather"]),
 ("study", "学习", "xuéxí", "to study; to learn", "verb", [("学","xué","learn"),("习","xí","practice")],
  [("我学习中文。","Wǒ xuéxí Zhōngwén.","I study Chinese."),("我们一起学习。","Wǒmen yìqǐ xuéxí.","We study together.")], "Use 学习 with a subject, or on its own when the subject is understood.", ["work","like","meal"]),
 ("friend", "朋友", "péngyou", "friend", "noun", [("朋","péng","companion"),("友","yǒu","friend")],
  [("她是我的朋友。","Tā shì wǒ de péngyou.","She is my friend."),("我和朋友吃饭。","Wǒ hé péngyou chī fàn.","I eat with a friend.")], "The second syllable is usually neutral in everyday speech.", ["student","teacher","shop"]),
 ("chinese", "中文", "Zhōngwén", "Chinese (the language)", "noun", [("中","zhōng","China, in this word"),("文","wén","language; writing")],
  [("我喜欢中文。","Wǒ xǐhuan Zhōngwén.","I like Chinese."),("老师教中文。","Lǎoshī jiāo Zhōngwén.","The teacher teaches Chinese.")], "中文 refers to the Chinese language, especially written language in some contexts.", ["agriculture","weather","time"]),
 ("telephone", "电话", "diànhuà", "telephone; phone call", "noun", [("电","diàn","electricity"),("话","huà","speech")],
  [("我给朋友打电话。","Wǒ gěi péngyou dǎ diànhuà.","I call my friend."),("这是老师的电话。","Zhè shì lǎoshī de diànhuà.","This is the teacher's phone number.")], "打电话 means to make a phone call. 电话 can also refer to a phone number in context.", ["subway","shop","school"]),
 ("thanks", "谢谢", "xièxie", "thank you", "expression", [("谢","xiè","thank"),("谢","xiè","repeated for this expression")],
  [("谢谢你的帮助。","Xièxie nǐ de bāngzhù.","Thank you for your help."),("谢谢老师。","Xièxie lǎoshī.","Thank you, teacher.")], "A common expression of thanks. The second syllable is usually neutral.", ["goodbye","like","tomorrow"]),
 ("goodbye", "再见", "zàijiàn", "goodbye", "expression", [("再","zài","again"),("见","jiàn","see; meet")],
  [("老师，再见！","Lǎoshī, zàijiàn!","Goodbye, teacher!"),("明天再见。","Míngtiān zàijiàn.","See you tomorrow.")], "Literally a cue of seeing someone again; used as goodbye.", ["thanks","today","friend"]),
 ("like", "喜欢", "xǐhuan", "to like", "verb", [("喜","xǐ","joy"),("欢","huān","delight")],
  [("我喜欢喝水。","Wǒ xǐhuan hē shuǐ.","I like drinking water."),("她喜欢这个商店。","Tā xǐhuan zhège shāngdiàn.","She likes this shop.")], "喜欢 can be followed by a thing or an activity. The second syllable is usually neutral.", ["study","work","meal"]),
 ("school", "学校", "xuéxiào", "school", "noun", [("学","xué","learning"),("校","xiào","school")],
  [("学校很大。","Xuéxiào hěn dà.","The school is big."),("我在学校学习。","Wǒ zài xuéxiào xuéxí.","I study at school.")], "A place where students learn; 学 and 校 reinforce this meaning.", ["student","teacher","shop"]),
 ("student", "学生", "xuésheng", "student", "noun", [("学","xué","learning"),("生","shēng","person, in this word")],
  [("我是学生。","Wǒ shì xuésheng.","I am a student."),("学生学习中文。","Xuésheng xuéxí Zhōngwén.","The students study Chinese.")], "The second syllable is often neutral. 学生 can refer to one or several students.", ["friend","teacher","school"]),
 ("teacher", "老师", "lǎoshī", "teacher", "noun", [("老","lǎo","respect, in this title"),("师","shī","teacher; expert")],
  [("老师在学校。","Lǎoshī zài xuéxiào.","The teacher is at school."),("她是中文老师。","Tā shì Zhōngwén lǎoshī.","She is a Chinese teacher.")], "A respectful everyday title. 老 here does not mean that the teacher must be old.", ["student","friend","school"]),
 ("today", "今天", "jīntiān", "today", "time word", [("今","jīn","present"),("天","tiān","day")],
  [("今天我学习。","Jīntiān wǒ xuéxí.","I study today."),("今天天气很好。","Jīntiān tiānqì hěn hǎo.","The weather is good today.")], "A time word commonly placed before the subject or before the verb.", ["tomorrow","time","weather"]),
 ("tomorrow", "明天", "míngtiān", "tomorrow", "time word", [("明","míng","next, in this word"),("天","tiān","day")],
  [("明天我去学校。","Míngtiān wǒ qù xuéxiào.","I will go to school tomorrow."),("明天我们一起吃饭。","Míngtiān wǒmen yìqǐ chī fàn.","We will eat together tomorrow.")], "明天 is a complete time word. Its meaning is more useful than a literal translation of 明.", ["today","time","weather"]),
 ("work", "工作", "gōngzuò", "to work; work", "verb / noun", [("工","gōng","work"),("作","zuò","do; make")],
  [("我今天工作。","Wǒ jīntiān gōngzuò.","I work today."),("她喜欢这个工作。","Tā xǐhuan zhège gōngzuò.","She likes this job.")], "工作 can describe the activity of working or a job. Let the sentence tell you which.", ["study","meal","like"]),
 ("meal", "吃饭", "chī fàn", "to eat; to have a meal", "verb phrase", [("吃","chī","eat"),("饭","fàn","rice; meal")],
  [("我们一起吃饭。","Wǒmen yìqǐ chī fàn.","We have a meal together."),("学生在学校吃饭。","Xuésheng zài xuéxiào chī fàn.","The students eat at school.")], "吃饭 usually means having a meal, not specifically eating rice.", ["water","study","work"]),
 ("water", "喝水", "hē shuǐ", "to drink water", "verb phrase", [("喝","hē","drink"),("水","shuǐ","water")],
  [("我想喝水。","Wǒ xiǎng hē shuǐ.","I want to drink water."),("天气热，多喝水。","Tiānqì rè, duō hē shuǐ.","It is hot; drink more water.")], "喝 takes the drink as its object. Here that drink is 水.", ["meal","work","like"]),
 ("shop", "商店", "shāngdiàn", "shop; store", "noun", [("商","shāng","commerce"),("店","diàn","shop")],
  [("商店在学校旁边。","Shāngdiàn zài xuéxiào pángbiān.","The shop is beside the school."),("我去商店。","Wǒ qù shāngdiàn.","I am going to the shop.")], "A general word for a shop or store.", ["school","subway","telephone"]),
 ("subway", "地铁", "dìtiě", "subway; metro", "noun", [("地","dì","ground"),("铁","tiě","iron; rail cue")],
  [("我坐地铁去学校。","Wǒ zuò dìtiě qù xuéxiào.","I take the subway to school."),("地铁很快。","Dìtiě hěn kuài.","The subway is fast.")], "Use 坐地铁 for taking the subway. The component cue helps recall an underground railway.", ["telephone","shop","school"]),
 ("weather", "天气", "tiānqì", "weather", "noun", [("天","tiān","sky"),("气","qì","air")],
  [("今天天气很好。","Jīntiān tiānqì hěn hǎo.","The weather is good today."),("明天天气冷。","Míngtiān tiānqì lěng.","The weather will be cold tomorrow.")], "天气 describes weather conditions, not the sky as an object.", ["today","tomorrow","time"]),
 ("time", "时间", "shíjiān", "time", "noun", [("时","shí","time"),("间","jiān","interval")],
  [("我有时间学习。","Wǒ yǒu shíjiān xuéxí.","I have time to study."),("你有时间吗？","Nǐ yǒu shíjiān ma?","Do you have time?")], "有时间 means having time available to do something.", ["today","tomorrow","weather"]),
]

def build():
    words = []
    for slug, hanzi, pinyin, meaning, pos, parts, examples, note, distractors in ROWS:
        word_id = "demo_" + slug
        words.append(dict(id=word_id, hanzi=hanzi, pinyin=pinyin,
            meanings=[dict(id=word_id + "_meaning", english=meaning, partOfSpeech=pos)],
            examples=[dict(hanzi=h, pinyin=p, english=e) for h,p,e in examples],
            parts=[dict(hanzi=h, pinyin=p, gloss=g) for h,p,g in parts], note=note,
            distractorMeaningIds=["demo_" + s + "_meaning" for s in distractors],
            tracingItemIds=[f"glyph_U{ord(c):04X}" for c in hanzi]))
    required = {c for word in words for c in word["hanzi"]}
    if (SOURCE / "revision.txt").read_text(encoding="utf-8").strip() != REVISION:
        raise ValueError("Unexpected stroke-source revision; preserve the existing source and investigate.")
    geometry = {}
    with (SOURCE / "graphics.txt").open(encoding="utf-8") as source:
        for line in source:
            item = json.loads(line)
            if item["character"] in required:
                paths, medians = item["strokes"], item["medians"]
                if not (1 <= len(paths) <= 64 and len(paths) == len(medians)):
                    raise ValueError("Invalid stroke geometry")
                serialized = json.dumps(item, ensure_ascii=False, separators=(",", ":"))
                geometry[item["character"]] = dict(id=f"glyph_U{ord(item['character']):04X}",
                    glyph=item["character"], paths=paths,
                    medians=[[dict(x=x,y=y) for x,y in points] for points in medians],
                    revision=hashlib.sha256(serialized.encode()).hexdigest(),
                    attribution=f"Make Me a Hanzi ({REVISION}); Arphic Public License; required glyphs extracted for Chinese Flashcard.")
    missing = required - geometry.keys()
    if missing:
        raise ValueError("Missing true stroke data: " + ",".join(sorted(missing)))
    return {"version":1,"words":words}, {"version":1,"items":[geometry[c] for c in sorted(geometry)]}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Check generated assets without rewriting")
    args = parser.parse_args()
    catalog, strokes = build()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, value in (("catalog.json",catalog),("strokes.json",strokes)):
        content = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
        target = OUTPUT / name
        if args.check:
            if target.read_text(encoding="utf-8") != content:
                raise ValueError("Generated asset differs: " + name)
        else:
            target.write_text(content, encoding="utf-8")
    if not args.check:
        for name in ("COPYING", "ARPHICPL.TXT"):
            (OUTPUT / name).write_bytes((SOURCE / name).read_bytes())
    print(f"Demo catalog: {len(catalog['words'])} words; {len(strokes['items'])} independent tracing glyphs; all positions covered.")

if __name__ == "__main__":
    main()
