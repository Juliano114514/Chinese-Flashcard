"""Produce a traceable, source-assisted refinement overlay for the retained CSV.

This is a content-production tool, not a linguistic test or a claim that every
entry was manually reviewed. The frozen baseline preserves prior IDs, readings,
examples, and removal decisions. Only explicit editorial corrections replace
examples; dictionary-enrichment records say how they were produced.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
import re
from pathlib import Path

from complete_wordlist import identity, marked_pinyin, glosses, plain_pinyin, tokens

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "tools/wordlist_refined"
CACHE = ROOT / ".gradle/wordlist-refinement/mapull"
DICTIONARY = ROOT / ".gradle/wordlist-implementation/dictionary-index.json"
BASELINE = DATA / "baseline.csv"
MANUAL = DATA / "editorial.json"
OVERLAY = DATA / "overrides.json"
STAMP = "2026-10-04"
SOURCE = "https://github.com/mapull/chinese-dictionary"
TEMPLATE = "Character glosses are memory aids, not a claim about the word's origin; learn its complete contextual meaning."
FALSE_HISTORY = "Literary or historical vocabulary; the example presents this older usage."
NAME = re.compile(r"(?i)\b(?:surname|abbr\. for|county|province|prefecture|dynasty|Taiwan pr\.|old pr\.|Italy|France|Germany|Britain|Japan|China|India|Russia|Spain|Portugal|Korea|Vietnam|Laos|Canada|Australia|Myanmar|Thailand)\b")
NAMED_STORY = re.compile(r'(?i)legendary (?:Flame )?Emperor|Shennong|\bc\.\s*\d{3,4}\s*BC|also known as [A-Z]')
SEMANTIC_GROUPS = [
    'cure heal healing health healthy treat treatment illness disease medical medicine recover recovery',
    'clan family kin kinship lineage ethnic ethnicity nationality nation people tribe tribal group',
    'spirit vitality energy energetic vigor mental psyche expression lively radiant',
    'mountain valley ravine gorge hills geography landscape',
    'business enterprise company firm corporation commercial trade commerce industry',
    'work labor labour job working worker employment employee task',
    'study learn learning student scholar school education teach teaching',
    'money currency cash dollar cent finance payment price cost',
    'breathe breath breathing inhale inhalation exhale respiration air oxygen',
    'rule govern governing government administration administer manage management',
    'move movement go walk walking run running travel journey motion',
    'look gaze see watch stare view sight observe observation',
    'speak say speech speaking tell talk talking words language verbal',
    'water liquid aquatic river stream flowing flow sea ocean',
    'beautiful beauty pretty attractive fine handsome graceful elegant',
    'anger angry furious rage furious indignation resentment',
    'sad sadness grief sorrow sorrowful sorrowing unhappy unhappy tears crying',
    'bright brightness radiant light shine shining brilliant luminous',
    'dark darkness black dim gloomy opaque',
    'happy happiness joy joyful glad pleased pleasure delight delighted enjoyment',
]
REF = re.compile(r"(?:[\u3400-\u9fff]+\|)?([\u3400-\u9fff]+)\[[^\]]+\]")
NEUTRAL_AMBIGUOUS = {'精神','东西','买卖','结实','便宜','大夫','丈夫','当家','对头','人家','地道','利害','铺盖'}


def read_characters(path):
    # Upstream exports comma-separated objects without enclosing brackets.
    text = path.read_text(encoding="utf-8")
    decoder = json.JSONDecoder()
    whitespace = re.compile(r"[\s,]*")
    position, result = 0, {}
    while position < len(text):
        position = whitespace.match(text, position).end()
        if position == len(text):
            break
        value, position = decoder.raw_decode(text, position)
        result[value["char"]] = value
    return result


def clean_english(text):
    text = REF.sub(r"\1", text)
    text = re.sub(r"\s*\(abbr\. for\s*\)", "", text)
    text = re.sub(r"\s*\(variant of\s*\)", "", text)
    text = re.sub(r"\s*\(CL:\s*[,;\s]*\)", "", text)
    return re.sub(r"\s+", " ", text).strip(" ;")


def clean_chinese(text, word):
    text = str(text or "").replace("ㄧ", "；").replace("～", word)
    text = re.sub(r"(?:(?<=。)|^)同[“\"]?" + re.escape(word) + r"[”\"]?[。.]?", "", text)
    text = re.sub(r"[①②③④⑤⑥⑦⑧⑨⑩]", "；", text)
    text = re.sub(r"\s+", " ", text).strip(" ；")
    return text[:1450]


def same_reading(actual, supplied, neutral=False):
    if identity("", actual) == identity("", supplied):
        return True
    return neutral and plain_pinyin(actual) == plain_pinyin(supplied)


def neutral_variant(actual, supplied):
    a, b = actual.split(), supplied.split()
    if len(a) != len(b):
        return False
    for left, right in zip(a, b):
        if left == right:
            continue
        if plain_pinyin(left) != plain_pinyin(right):
            return False
        if left != plain_pinyin(left) and right != plain_pinyin(right):
            return False
    return True


def source_senses(word, reading, words, chars):
    entries = [r for r in words.get(word, []) if same_reading(r.get("pinyin", ""), reading)]
    kind = 'word'
    if not entries and word not in NEUTRAL_AMBIGUOUS:
        entries = [r for r in words.get(word, []) if neutral_variant(r.get('pinyin',''), reading)]
        kind = 'word-neutral-variant'
    if entries:
        values = [clean_chinese(r.get("explanation", ""), word) for r in entries]
        return list(dict.fromkeys(value for value in values if value)), kind
    if len(word) == 1:
        entries = chars.get(word, {}).get("pronunciations", [])
        matching = [p for p in entries if same_reading(p.get("pinyin", ""), reading)]
        values = []
        for pronunciation in matching:
            for explanation in pronunciation.get("explanations", []):
                content = clean_chinese(explanation.get("content", ""), word)
                if content and not content.startswith(("姓", "同", "见", "古同")):
                    values.append(content)
        return list(dict.fromkeys(values))[:8], "character" if matching else "unmatched"
    return [], "unmatched"


def dictionary_english(word, reading, index, allow_names=False):
    matches = [e for e in index.get(word, []) if same_reading(marked_pinyin(e["pinyin"]), reading)
               and (allow_names or not re.match(r'^[A-Z]', e['pinyin']))]
    result = [clean_english(g) for e in matches for g in glosses(e, index)]
    return list(dict.fromkeys(g for g in result if g and len(g) <= 280))


def contextual_tokens(meaning):
    target = tokens(meaning)
    for group in SEMANTIC_GROUPS:
        values = set(group.split())
        if target & values:
            target |= values
    return target


def sense_parts(row, index, chars, manual):
    word, reading, meaning = row["组词"], row["拼音"], row["英文释义"]
    if "parts" in manual:
        return manual["parts"], "editorial-context"
    if "partGlosses" in manual:
        if len(manual["partGlosses"]) != len(word) or len(reading.split()) != len(word):
            raise ValueError("Editorial character gloss count changed: " + word)
        return [dict(hanzi=c, pinyin=p, gloss=g) for c, p, g in zip(word, reading.split(), manual["partGlosses"])], "editorial-context"
    if manual.get('wholeUnit'):
        return [dict(hanzi=word, pinyin=reading, gloss=meaning)], 'editorial-lexical-unit'
    contextual = manual.get('componentEditorial', {})
    if 'parts' in contextual:
        return contextual['parts'], 'editorial-risk-correction'
    prior = json.loads(row["部件JSON"])
    # Preserve lexical units, names, and grammatical combinations already marked
    # inseparable. Do not turn the character decomposition into a false etymology.
    if len(prior) == 1 or len(reading.split()) != len(word):
        return [dict(hanzi=word, pinyin=reading, gloss=meaning)], "lexical-unit-preserved"
    parts = []
    curated_characters = contextual.get('characters', contextual.get('glossByCharacter', {}))
    for character, syllable in zip(word, reading.split()):
        if character in curated_characters:
            parts.append(dict(hanzi=character, pinyin=syllable, gloss=curated_characters[character]))
            continue
        allow_names = row['词性'] == 'proper noun' or contextual.get('preserveSurname')
        english = dictionary_english(character, syllable, index, allow_names)
        modern = [g for g in english if not NAME.search(g) and not NAMED_STORY.search(g) and not g.startswith(("see ", "variant "))]
        if row["词性"] == "proper noun" or contextual.get('preserveSurname'):
            modern = english
        # Longer, complete definitions win over the previous shortest-gloss
        # heuristic; overlap is evidence, otherwise display multiple documented
        # senses explicitly, rather than asserting an arbitrary contextual one.
        common = [g for g in modern if not re.search(r"(?i)\b(?:dialect|archaic|obsolete)\b", g)]
        pool = common or modern
        target = contextual_tokens(meaning)
        ordered = sorted(enumerate(pool), key=lambda pair: (-len(tokens(pair[1]) & target), pair[0]))
        chosen = [ordered[0][1]] if ordered else []
        if not chosen:
            # The whole-word meaning is attested even when a character is bound.
            return [dict(hanzi=word, pinyin=reading, gloss=meaning)], "bound-unit-preserved"
        # Upstream character explanations often begin with etymological images
        # (e.g. arrowheads for 族). Those are not contextual component meanings.
        # Keep them out of learner components; bilingual whole-word definitions
        # remain available separately.
        label = " / ".join(chosen)
        if len(label) > 300:
            label = " / ".join(chosen)
        if len(label) > 300:
            label = chosen[0]
        parts.append(dict(hanzi=character, pinyin=syllable, gloss=label))
    return parts, "editorial-risk-correction" if curated_characters else "same-reading-dictionary-memory-aids"


def retained_note(row):
    note = row["使用提示"].replace(TEMPLATE, "").replace(FALSE_HISTORY, "").strip()
    note = re.sub(r"^In these examples, .*? means .*?\.(?:\s|$)", "", note).strip()
    return note


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        baseline = list(csv.DictReader(BASELINE.open(encoding="utf-8-sig", newline="")))
        result = json.loads(OVERLAY.read_text(encoding="utf-8"))
        audit = json.loads((DATA / "audit.json").read_text(encoding="utf-8"))
        if set(result) != {r["词条ID"] for r in baseline}:
            raise ValueError("Refinement does not cover every retained stable ID.")
        if audit["baselineSha256"] != hashlib.sha256(BASELINE.read_bytes()).hexdigest():
            raise ValueError("Frozen baseline changed.")
        for row in baseline:
            changed = result[row["词条ID"]]
            for key in ("词条ID", "组词", "拼音"):
                if changed.get(key, row[key]) != row[key]:
                    raise ValueError("Refinement changed identity: " + row["词条ID"])
            if not changed.get("本义解释") or not changed.get("使用提示"):
                raise ValueError("Missing actual explanation: " + row["词条ID"])
        print(json.dumps({"checked": len(result), "evidence": "coverage, frozen identities and nonempty explanations"}))
        return
    DATA.mkdir(exist_ok=True)
    if not BASELINE.exists():
        BASELINE.write_bytes((ROOT / "wordlist.csv").read_bytes())
    baseline = list(csv.DictReader(BASELINE.open(encoding="utf-8-sig", newline="")))
    manual = json.loads(MANUAL.read_text(encoding="utf-8")) if MANUAL.exists() else {}
    definition_edits = json.loads((DATA / 'translation-editorial.json').read_text(encoding='utf-8'))
    for word, definition in definition_edits.items():
        manual.setdefault(word, {}).setdefault('definition', definition)
    additional_idioms = DATA / 'additional-idioms.json'
    if additional_idioms.exists():
        for word, content in json.loads(additional_idioms.read_text(encoding='utf-8')).items():
            manual.setdefault(word, {}).update(content)
            # The complete lexical meaning is documented. A character-by-
            # character origin is not implied when no contextual split was
            # authored for this newly identified retained idiom.
            if 'parts' not in content and 'partGlosses' not in content:
                manual[word]['wholeUnit'] = True
    examples = json.loads((DATA / 'examples.json').read_text(encoding='utf-8'))
    for word, sentences in examples.items():
        manual.setdefault(word, {}).update(sentences)
    if (DATA / 'component_editorial.json').exists():
        for word, content in json.loads((DATA / 'component_editorial.json').read_text(encoding='utf-8')).items():
            manual.setdefault(word, {})['componentEditorial'] = content
    for word, content in json.loads((DATA / 'contextual-components.json').read_text(encoding='utf-8')).items():
        entry = manual.setdefault(word, {})
        if 'definition' in content:
            if entry.get('pos') == 'idiom':
                entry.setdefault('definition', content['definition'])
            else:
                entry['definition'] = content['definition']
        if content.get('whole'):
            entry['wholeUnit'] = True
        else:
            if set(content.get('characters', {})) == set(word):
                entry.pop('wholeUnit', None)
            component = entry.setdefault('componentEditorial', {})
            target = dict(component.get('glossByCharacter', {}))
            target.update(component.get('characters', {}))
            target.update(content.get('characters', {}))
            component['characters'] = target
    literal_english = json.loads((DATA / 'literal-english.json').read_text(encoding='utf-8'))
    idiom_options = json.loads((DATA / 'idiom-options.json').read_text(encoding='utf-8'))
    machine = json.loads((DATA / 'machine-translations.json').read_text(encoding='utf-8')) if (DATA / 'machine-translations.json').exists() else {}
    translation_reviews = {}
    for review_path in sorted(DATA.glob('translation-reviewed-[012].json')):
        for word_id, content in json.loads(review_path.read_text(encoding='utf-8')).items():
            if word_id in translation_reviews:
                raise ValueError('Translation review repeated stable ID: ' + word_id)
            if not content.get('definition') or not content.get('reviewEvidence'):
                raise ValueError('Incomplete translation proofreading: ' + word_id)
            translation_reviews[word_id] = dict(content, reviewFile=review_path.name)
    index = json.loads(DICTIONARY.read_text(encoding="utf-8"))
    words = collections.defaultdict(list)
    for entry in json.loads((CACHE / "word.json").read_text(encoding="utf-8")):
        words[entry["word"]].append(entry)
    chars = read_characters(CACHE / "char_detail.json")
    upstream = json.loads((CACHE / "source.json").read_text(encoding="utf-8"))
    output, records, excerpts = {}, {}, {"words": {}, "characters": {}}
    counts = collections.Counter()
    for row in baseline:
        word_id, word = row["词条ID"], row["组词"]
        edit = manual.get(word, {})
        chinese, matched = source_senses(word, row["拼音"], words, chars)
        english = dictionary_english(word, row["拼音"], index, row['词性'] == 'proper noun')
        authored = edit.get("definition")
        literal = authored or "；".join(chinese)
        meaning = clean_english(idiom_options.get(word, edit.get("english", row["英文释义"])))
        translation = machine.get(word_id) if not literal else None
        translation_review = translation_reviews.get(word_id) if translation else None
        if translation:
            if translation['sourceEnglish'] != row['英文释义'] or translation['reading'] != row['拼音']:
                raise ValueError('Machine draft source identity changed: '+word_id)
            if translation_review:
                if translation_review.get('sourceEnglish', row['英文释义']) != row['英文释义'] or translation_review.get('hanzi', word) != word or translation_review.get('reading', row['拼音']) != row['拼音']:
                    raise ValueError('Proofread translation source identity changed: ' + word_id)
                literal = translation_review['definition']
            else:
                literal = translation['chinese']
        if not literal:
            literal = meaning
        extended = edit.get("figurative", "")
        if not extended:
            actual = [re.split(r"[。；]", x)[0] for x in re.findall(r"(?:比喻|引申为|引申指)[^。；]+", "；".join(chinese))]
            extended = "；".join(dict.fromkeys(actual))[:1000]
        english_literal = edit.get('literalEnglish', literal_english.get(word, meaning))
        definition = literal + ("\nEnglish: " + english_literal if literal != english_literal else "")
        if english and word not in literal_english and not edit.get('literalEnglish'):
            additional = [x for x in english if x.casefold() != meaning.casefold()][:4]
            if additional:
                definition += "\nOther attested senses with this reading: " + "; ".join(additional)
        # Ordinary words without an attested metaphor keep an empty extended
        # field. The app's short choice remains the contextual English gloss.
        effective_row = dict(row, **{'英文释义': meaning, '词性': edit.get('pos', row['词性'])})
        parts, part_policy = sense_parts(effective_row, index, chars, edit)
        prior_note = edit.get("note", retained_note(row))
        if edit.get('componentEditorial', {}).get('noteAppend'):
            prior_note += '\n' + edit['componentEditorial']['noteAppend']
        pieces = [prior_note] if prior_note else []
        if chinese and not authored:
            label = '中文词典参考（上游轻声标注不同，保留本词条读法）：' if matched == 'word-neutral-variant' else '同读音中文词典释义：'
            pieces.append(label + "；".join(chinese))
        elif authored:
            pieces.append(authored)
        elif english:
            pieces.append("Attested dictionary senses: " + "; ".join(english[:4]))
        else:
            pieces.append(meaning)
        if translation:
            if translation_review:
                pieces.append('中文参考释义（AI已对照英文义项、读音和例句润色，未独立词典或人工核验）：' + literal)
            else:
                pieces.append('中文参考释义（根据已保留的英文义项自动翻译，尚未独立核对）：' + translation['chinese'])
        # Pos is explicit editorial information, never guessed from the nouns
        # appearing in an example (e.g. 古书 does not make 兴趣 historical).
        if part_policy == "same-reading-dictionary-memory-aids":
            pieces.append("拆分提供同读音字典词义作记忆辅助；整词用法以两条例句和完整释义为准。")
        source = row["来源说明"]
        source += " Refinement 2026-10-04: " + ("editorial contextual correction" if edit else "automatic source-assisted enrichment; not individually manually reviewed") + "."
        if chinese:
            source += " Chinese same-reading reference: mapull/chinese-dictionary@" + upstream["commit"] + " (MIT; upstream provenance caveat retained)."
        if translation:
            source += ' Chinese draft translated from the retained English sense by Google Translate on 2026-10-04.'
            source += ' AI translation proofreading against retained English, reading and example; not dictionary/human verified.' if translation_review else ' Not independently dictionary-verified; unreviewed machine draft.'
        if extended:
            extended += '\nEnglish: ' + meaning
        change = {"英文释义": meaning, "部件JSON": json.dumps(parts, ensure_ascii=False, separators=(",", ":")),
                  "使用提示": "\n".join(pieces)[:2000], "本义解释": definition[:2000],
                  "引申义解释": extended, "来源说明": source[:2000]}
        if "first" in edit:
            change.update(dict(zip(("简单例句", "例句拼音", "例句英语翻译"), edit["first"])))
        if "second" in edit:
            change.update(dict(zip(("第二例句", "第二例句拼音", "第二例句英语翻译"), edit["second"])))
        if "pos" in edit:
            change["词性"] = edit["pos"]
        # Source dictionary excerpts may contain padding before embedded newlines.
        # Keep the raw excerpts for provenance, but trim teaching-text line ends.
        change = {key: "\n".join(line.rstrip() for line in value.split("\n"))
                  for key, value in change.items()}
        output[word_id] = change
        review = 'ai-translation-context-proofread' if translation_review else 'editorial-risk-correction' if set(edit) == {'componentEditorial'} else 'editorial-confirmed' if edit else 'automatic-source-assisted'
        records[word_id] = {"hanzi": word, "reading": row["拼音"], "review": review,
                            "chineseSource": 'editorial-contextual-definition' if authored else 'machine-translation-ai-context-proofread' if translation_review else 'machine-translation-unreviewed' if translation else matched,
                            "dictionaryMatch": matched, "partsPolicy": part_policy, "examplePolicy": "editorial-replacement" if "first" in edit or "second" in edit else "baseline-preserved",
                            "fields": [key for key, value in change.items() if row.get(key) != value]}
        if translation_review:
            records[word_id]['translationReview'] = {'file': translation_review['reviewFile'], 'method': 'AI translation proofreading (not dictionary/human verified)', 'evidence': translation_review['reviewEvidence']}
        if edit.get('componentEditorial'):
            records[word_id]['componentEvidence'] = edit['componentEditorial'].get('evidence', 'Scoped contextual character correction, separate from definition review.')
        counts["review:" + records[word_id]["review"]] += 1
        counts["chineseSource:" + records[word_id]['chineseSource']] += 1
        counts["partsPolicy:" + part_policy] += 1
        if word in words and chinese:
            excerpts["words"][word] = words[word]
        for character in word:
            if character in chars:
                excerpts["characters"][character] = chars[character]
    for name, payload in (("overrides.json", output), ("production-records.json", records), ("source-excerpts.json", excerpts)):
        (DATA / name).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    audit = {"producedOn": STAMP, "baselineRows": len(baseline), "baselineSha256": hashlib.sha256(BASELINE.read_bytes()).hexdigest(),
             "records": len(records), "counts": dict(counts), "source": upstream,
             "boundary": "All retained entries received source-assisted enrichment. Authored contextual definitions and AI translation proofreading are separately recorded. AI proofreading compares the retained English, reading and example; it is not independent dictionary verification or human review. Preserved examples received a full automated quality scan, not a full manual linguistic review.",
             "provenanceCaveat": "Upstream advertises MIT but notes that the original provenance of some collected material cannot be established. This data is retained as an attributed reference, not certified authoritative lexicography."}
    (DATA / "audit.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (DATA / "MAPULL-LICENSE.txt").write_bytes((CACHE / "LICENSE").read_bytes())
    print(json.dumps({"records": len(records), "counts": dict(counts)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
