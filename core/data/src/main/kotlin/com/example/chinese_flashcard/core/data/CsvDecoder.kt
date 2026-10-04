package com.example.chinese_flashcard.core.data

import com.example.chinese_flashcard.core.domain.CsvImportIssue
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.WordPart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File
import java.text.Normalizer
import java.util.Locale

internal class CsvIssues {
  var count: Int = 0
    private set
  val displayed = mutableListOf<CsvImportIssue>()
  fun add(line: Int, field: String, message: String) {
    count++
    if (displayed.size < 100) displayed += CsvImportIssue(line, field, message)
  }
}

internal data class CsvWord(val line: Int, val order: Int, val id: String, val hanzi: String,
  val pinyin: String, val rarity: Int, val english: String, val partOfSpeech: String,
  val examples: List<ExampleSentence>, val parts: List<WordPart>, val note: String,
  val distractorWordIds: List<String>) {
  val identity: String get() = wordIdentity(hanzi, pinyin)
  val glyphs: List<String> get() = hanzi.codePoints().toArray().map { String(Character.toChars(it)) }
}

internal fun wordIdentity(hanzi: String, pinyin: String): String =
  Normalizer.normalize(hanzi.trim(), Normalizer.Form.NFC) + "\u0000" +
    Normalizer.normalize(pinyin, Normalizer.Form.NFC).filterNot(Char::isWhitespace).lowercase(Locale.ROOT)

internal fun normalizedEnglish(value: String): String =
  Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

internal fun glyphId(glyph: String): String =
  "glyph_U" + glyph.codePointAt(0).toString(16).uppercase(Locale.ROOT).padStart(4, '0')

internal fun csvMeaningId(wordId: String): String = wordId + "_meaning"

internal object CsvDecoder {
  private val pinyinLetter = Regex("[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹňḿ]", RegexOption.IGNORE_CASE)
  private const val SENTENCE_PUNCTUATION = ".,!?;:，。！？；：、…—–-\"'“”‘’()（）[]"
  private val columns = listOf("罕度", "组词", "拼音", "简单例句", "例句拼音", "例句英语翻译",
    "词条ID", "英文释义", "词性", "第二例句", "第二例句拼音", "第二例句英语翻译",
    "部件JSON", "使用提示", "干扰词ID", "来源说明")

  suspend fun scan(file: File, issues: CsvIssues, onWord: suspend (CsvWord) -> Unit): Int {
    var total = 0
    var headerNames: List<String> = emptyList()
    try {
      CsvParser(file.inputStream()).use { parser ->
        val header = parser.next()
        if (header == null) {
          issues.add(1, "CSV", "The CSV file is empty.")
          return 0
        }
        val names = header.fields.map(String::trim)
        headerNames = names
        if (names.distinct().size != names.size) {
          issues.add(header.line, "表头", "Column names must be unique.")
          return 0
        }
        val missing = columns.filterNot { it in names }
        if (missing.isNotEmpty()) {
          missing.forEach { issues.add(header.line, it, "A required column is missing.") }
          return 0
        }
        val positions = names.withIndex().associate { it.value to it.index }
        while (true) {
          currentCoroutineContext().ensureActive()
          val record = parser.next() ?: break
          if (record.fields.size == 1 && record.fields[0].isBlank()) continue
          total++
          if (total > CSV_MAX_ROWS) {
            issues.add(record.line, "CSV", "A CSV may contain at most 10,000 words.")
            break
          }
          if (record.fields.size != names.size) {
            issues.add(record.line, "CSV", "The number of fields does not match the header.")
            continue
          }
          try {
            onWord(decode(record, positions, total - 1))
          } catch (error: CsvFieldException) {
            issues.add(record.line, error.field, error.message ?: "The field is invalid.")
          }
        }
      }
    } catch (error: CsvFormatException) {
      issues.add(error.line, headerNames.getOrNull(error.fieldIndex) ?: "Column ${error.fieldIndex + 1}",
        error.message ?: "The CSV format is invalid.")
    } catch (_: java.nio.charset.CharacterCodingException) {
      issues.add(0, "CSV", "The file must use valid UTF-8 encoding.")
    }
    if (total == 0 && issues.count == 0) issues.add(2, "CSV", "The CSV contains no words.")
    return total
  }

  private fun decode(record: CsvRecord, positions: Map<String, Int>, order: Int): CsvWord {
    fun invalid(field: String, message: String): Nothing = throw CsvFieldException(field, message)
    fun field(name: String, max: Int): String {
      val value = record.fields[positions.getValue(name)].trim()
      if (value.isBlank() || value.length > max || value.any { it == '\u0000' })
        invalid(name, "This field is required and must fit its length limit.")
      return value
    }
    fun id(value: String, fieldName: String): String {
      if (!value.matches(Regex("[A-Za-z0-9_]{1,92}")))
        invalid(fieldName, "IDs must contain 1–92 ASCII letters, digits or underscores.")
      return value
    }
    val rarity = field("罕度", 1).toIntOrNull()?.takeIf { it in 0..2 }
      ?: invalid("罕度", "Rarity must be 0, 1 or 2.")
    val wordId = id(field("词条ID", 92), "词条ID")
    val hanzi = Normalizer.normalize(field("组词", 64), Normalizer.Form.NFC)
    val codePoints = hanzi.codePoints().toArray()
    if (codePoints.size !in 1..32 || codePoints.any { !isHanzi(it) })
      invalid("组词", "A word must contain 1–32 Han characters.")
    val pinyin = checkedPinyin(field("拼音", 128), "拼音")
    val english = field("英文释义", 300)
    val partOfSpeech = field("词性", 80)
    val examples = listOf(
      ExampleSentence(field("简单例句", 1000), checkedPinyin(field("例句拼音", 2000), "例句拼音", true), field("例句英语翻译", 2000)),
      ExampleSentence(field("第二例句", 1000), checkedPinyin(field("第二例句拼音", 2000), "第二例句拼音", true), field("第二例句英语翻译", 2000)))
    if (examples[0].hanzi == examples[1].hanzi) invalid("第二例句", "Provide two different example sentences.")
    examples.forEachIndexed { position, example ->
      if (!example.hanzi.contains(hanzi)) invalid(if (position == 0) "简单例句" else "第二例句",
        "Each example sentence must contain the complete word.")
    }
    val parts = try {
      val array = boundedArray(field("部件JSON", 16000), "部件JSON")
      if (array.length() !in 1..32) invalid("部件JSON", "Provide 1–32 word parts.")
      array.objects(32).forEach { item ->
        if (listOf("hanzi", "pinyin", "gloss").any { item.opt(it) !is String })
          invalid("部件JSON", "Each part needs string hanzi, pinyin and gloss fields.")
      }
      decodeParts(array).map { it.copy(pinyin = checkedPinyin(it.pinyin, "部件JSON")) }
    } catch (error: CsvFieldException) { throw error }
    catch (_: Exception) { invalid("部件JSON", "Word parts must be a valid JSON array with hanzi, pinyin and gloss.") }
    if (parts.joinToString("") { it.hanzi } != hanzi)
      invalid("部件JSON", "The ordered word parts must reconstruct the complete word.")
    val note = field("使用提示", 2000)
    field("来源说明", 2000) // Provenance stays in the user's CSV; no source field is added to the teaching model.
    val distractors = try {
      val array = boundedArray(field("干扰词ID", 400), "干扰词ID")
      if (array.length() != 3 || (0 until array.length()).any { array.get(it) !is String })
        invalid("干扰词ID", "Provide exactly three word IDs as a JSON string array.")
      array.strings(3).map { id(it, "干扰词ID") }
    } catch (error: CsvFieldException) { throw error }
    catch (_: Exception) { invalid("干扰词ID", "Distractors must be a JSON array of three word IDs.") }
    if (distractors.distinct().size != 3 || wordId in distractors)
      invalid("干扰词ID", "Distractor IDs must be distinct and cannot include this word.")
    return CsvWord(record.line, order, wordId, hanzi, pinyin, rarity, english, partOfSpeech,
      examples, parts, note, distractors)
  }

  private fun isHanzi(codePoint: Int): Boolean = codePoint in 0x3400..0x4DBF ||
    codePoint in 0x4E00..0x9FFF || codePoint in 0xF900..0xFAFF ||
    codePoint in 0x20000..0x2FFFF || codePoint in 0x30000..0x323AF

  private fun checkedPinyin(value: String, field: String, sentence: Boolean = false): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
    if (!pinyinLetter.containsMatchIn(normalized) || normalized.any { character ->
      !character.isWhitespace() && !pinyinLetter.matches(character.toString()) &&
        character !in (if (sentence) SENTENCE_PUNCTUATION else "'-’")
    }) throw CsvFieldException(field, "Use tone-marked pinyin letters and spacing; sentence pinyin may include punctuation.")
    return normalized
  }

  private fun boundedArray(value: String, field: String): JSONArray {
    var depth = 0
    var quoted = false
    var position = 0
    var previous = '\u0000'
    while (position < value.length) {
      val character = value[position++]
      if (quoted) {
        when {
          character == '\\' -> {
            if (position == value.length) throw CsvFieldException(field, "A JSON escape is incomplete.")
            when (value[position++]) {
              '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
              'u' -> {
                if (position + 4 > value.length || value.substring(position, position + 4).any {
                  it !in "0123456789abcdefABCDEF"
                }) throw CsvFieldException(field, "A JSON Unicode escape must contain four hexadecimal digits.")
                position += 4
              }
              else -> throw CsvFieldException(field, "The JSON string contains an invalid escape.")
            }
          }
          character == '"' -> quoted = false
          character.code < 0x20 -> throw CsvFieldException(field, "JSON strings must escape control characters.")
        }
      } else when (character) {
        '"' -> quoted = true
        '[', '{' -> {
          depth++
          if (depth > 4) throw CsvFieldException(field, "JSON nesting must not exceed four levels.")
        }
        ']', '}' -> {
          if (previous == ',') throw CsvFieldException(field, "JSON arrays and objects cannot end with a comma.")
          depth--
          if (depth < 0) throw CsvFieldException(field, "JSON brackets are unbalanced.")
        }
        ':' -> if (previous != '"')
          throw CsvFieldException(field, "A JSON object key must be a double-quoted string.")
        ',' -> if (previous !in "\"}]")
          throw CsvFieldException(field, "JSON entries cannot be empty.")
        else -> if (character !in " \t\r\n")
          throw CsvFieldException(field, "These JSON arrays require double-quoted keys and string values.")
      }
      if (!quoted && character !in " \t\r\n") previous = character
    }
    if (depth != 0 || quoted) throw CsvFieldException(field, "JSON brackets or strings are not closed.")
    val tokener = JSONTokener(value)
    val array = tokener.nextValue()
    if (array !is JSONArray || tokener.nextClean() != '\u0000')
      throw CsvFieldException(field, "The field must contain one complete JSON array.")
    return array
  }
}

private class CsvFieldException(val field: String, message: String) : IllegalArgumentException(message)
