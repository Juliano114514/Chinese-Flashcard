package com.example.chinese_flashcard.core.data

import com.example.chinese_flashcard.core.domain.CsvImportIssue
import com.example.chinese_flashcard.core.domain.UiMessageKey
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.WordPart
import com.example.chinese_flashcard.core.domain.VocabularyStage
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
  fun add(line: Int, field: String, message: UiMessageKey,
    arguments: List<String> = emptyList(), fieldIndex: Int? = null) {
    count++
    if (displayed.size < 100) displayed += CsvImportIssue(line, field, message, arguments, fieldIndex)
  }
}

internal data class CsvWord(val line: Int, val order: Int, val id: String, val hanzi: String,
  val pinyin: String, val rarity: Int, val english: String, val partOfSpeech: String,
  val examples: List<ExampleSentence>, val parts: List<WordPart>,
  val distractorWordIds: List<String>,
  val literalExplanations: List<String> = emptyList(), val figurativeExplanations: List<String> = emptyList()) {
  val identity: String get() = wordIdentity(hanzi, pinyin)
  val glyphs: List<String> get() = hanzi.codePoints().toArray().map { String(Character.toChars(it)) }
}

internal fun wordIdentity(hanzi: String, pinyin: String): String =
  Normalizer.normalize(hanzi.trim(), Normalizer.Form.NFC) + "\u0000" +
    pinyinSyllables(Normalizer.normalize(pinyin, Normalizer.Form.NFC)).joinToString("").lowercase(Locale.ROOT)

internal fun normalizedEnglish(value: String): String =
  Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

internal fun glyphId(glyph: String): String =
  "glyph_U" + glyph.codePointAt(0).toString(16).uppercase(Locale.ROOT).padStart(4, '0')

internal fun csvMeaningId(wordId: String): String = wordId + "_meaning"

internal object CsvDecoder {
  private val wordIdPattern = Regex("[A-Za-z0-9_]{1,92}")
  private val pinyinLetter = Regex("[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêńǹňḿ]", RegexOption.IGNORE_CASE)
  private const val SENTENCE_PUNCTUATION = ".,!?;:，。！？；：、…—–-\"'“”‘’()（）[]"
  private val columns = listOf("罕度", "组词", "拼音", "词条ID", "英文释义", "词性",
    "例句JSON", "部件JSON", "干扰词ID", "来源说明", "本义解释JSON", "引申义解释JSON")

  /** A quick record-only pass for external files; quoted newlines do not increase the count. */
  suspend fun countRows(file: File, issues: CsvIssues): Int {
    var total = 0
    var names: List<String> = emptyList()
    try {
      CsvParser(file.inputStream()).use { parser ->
        names = parser.next()?.fields?.map(String::trim) ?: emptyList()
        while (true) {
          currentCoroutineContext().ensureActive()
          val record = parser.next() ?: break
          if (record.fields.size == 1 && record.fields[0].isBlank()) continue
          total++
          if (total > CSV_MAX_ROWS) {
            issues.add(record.line, "CSV", UiMessageKey.A_CSV_MAY_CONTAIN_AT_MOST_20_000_WORDS)
            break
          }
        }
      }
    } catch (error: CsvFormatException) {
      issues.add(error.line, names.getOrNull(error.fieldIndex).orEmpty(), error.code,
        fieldIndex = if (names.getOrNull(error.fieldIndex).isNullOrEmpty()) error.fieldIndex + 1 else null)
    } catch (_: java.nio.charset.CharacterCodingException) {
      issues.add(0, "CSV", UiMessageKey.THE_FILE_MUST_USE_VALID_UTF_8_ENCODING)
    }
    return total
  }

  suspend fun scan(file: File, issues: CsvIssues,
    onRecord: (Int, String?) -> Unit = { _, _ -> }, onWord: suspend (CsvWord) -> Unit): Int {
    var total = 0
    var headerNames: List<String> = emptyList()
    try {
      CsvParser(file.inputStream()).use { parser ->
        val header = parser.next()
        if (header == null) {
          issues.add(1, "CSV", UiMessageKey.THE_CSV_FILE_IS_EMPTY)
          return 0
        }
        val names = header.fields.map(String::trim)
        headerNames = names
        if (names.distinct().size != names.size) {
          issues.add(header.line, "表头", UiMessageKey.COLUMN_NAMES_MUST_BE_UNIQUE)
          return 0
        }
        val missing = columns.filterNot { it in names }
        if (missing.isNotEmpty()) {
          missing.forEach { issues.add(header.line, it, UiMessageKey.A_REQUIRED_COLUMN_IS_MISSING) }
          return 0
        }
        if (names.size != columns.size) {
          issues.add(header.line, "表头", UiMessageKey.USE_EXACTLY_THE_TWELVE_CURRENT_CSV_COLUMNS)
          return 0
        }
        val positions = names.withIndex().associate { it.value to it.index }
        while (true) {
          currentCoroutineContext().ensureActive()
          val record = parser.next() ?: break
          if (record.fields.size == 1 && record.fields[0].isBlank()) continue
          total++
          onRecord(total, record.fields.getOrNull(positions.getValue("组词")))
          if (total > CSV_MAX_ROWS) {
            issues.add(record.line, "CSV", UiMessageKey.A_CSV_MAY_CONTAIN_AT_MOST_20_000_WORDS)
            break
          }
          if (record.fields.size != names.size) {
            issues.add(record.line, "CSV", UiMessageKey.THE_NUMBER_OF_FIELDS_DOES_NOT_MATCH_THE_HEADER)
            continue
          }
          try {
            onWord(decode(record, positions, total - 1))
          } catch (error: CsvFieldException) {
            issues.add(record.line, error.field, error.code)
          }
        }
      }
    } catch (error: CsvFormatException) {
      issues.add(error.line, headerNames.getOrNull(error.fieldIndex).orEmpty(), error.code,
        fieldIndex = if (headerNames.getOrNull(error.fieldIndex).isNullOrEmpty()) error.fieldIndex + 1 else null)
    } catch (_: java.nio.charset.CharacterCodingException) {
      issues.add(0, "CSV", UiMessageKey.THE_FILE_MUST_USE_VALID_UTF_8_ENCODING)
    }
    if (total == 0 && issues.count == 0) issues.add(2, "CSV", UiMessageKey.THE_CSV_CONTAINS_NO_WORDS)
    return total
  }

  private fun decode(record: CsvRecord, positions: Map<String, Int>, order: Int): CsvWord {
    fun invalid(field: String, message: UiMessageKey): Nothing = throw CsvFieldException(field, message)
    fun field(name: String, max: Int): String {
      val value = record.fields[positions.getValue(name)].trim()
      if (value.isBlank() || value.length > max || value.any { it == '\u0000' })
        invalid(name, UiMessageKey.THIS_FIELD_IS_REQUIRED_AND_MUST_FIT_ITS_LENGTH_LIMIT)
      return value
    }
    fun id(value: String, fieldName: String): String {
      if (!value.matches(wordIdPattern))
        invalid(fieldName, UiMessageKey.IDS_MUST_CONTAIN_1_92_ASCII_LETTERS_DIGITS_OR_UNDERSCORES)
      return value
    }
    val rarity = field("罕度", 1).toIntOrNull()?.takeIf { it in VocabularyStage.rarityRange }
      ?: invalid("罕度", UiMessageKey.STAGE_MUST_BE_0_1_2_3_OR_4)
    val wordId = id(field("词条ID", 92), "词条ID")
    val hanzi = Normalizer.normalize(field("组词", 64), Normalizer.Form.NFC)
    val codePoints = hanzi.codePoints().toArray()
    if (codePoints.size !in 1..32 || codePoints.any { !isHanzi(it) })
      invalid("组词", UiMessageKey.A_WORD_MUST_CONTAIN_1_32_HAN_CHARACTERS)
    val pinyin = checkedPinyin(field("拼音", 128), "拼音")
    if (pinyinSyllables(pinyin).size != codePoints.size)
      invalid("拼音", UiMessageKey.SEPARATE_EACH_CHARACTER_S_PINYIN_SYLLABLE_WITH_A_SPACE_OR_AN_APOSTROPHE)
    val english = field("英文释义", 300)
    val partOfSpeech = field("词性", 80)
    val examples = try {
      decodeExamples(boundedArray(field("例句JSON", 16000), "例句JSON")).map { example ->
        example.copy(pinyin = checkedPinyin(example.pinyin, "例句JSON", true),
          chunks = example.chunks.map { chunk -> chunk.copy(pinyin = checkedPinyin(chunk.pinyin, "例句JSON", true)) })
      }
    } catch (error: CsvFieldException) { throw error }
    catch (_: Exception) { invalid("例句JSON", UiMessageKey.PROVIDE_TWO_VALID_EXAMPLES_WITH_HANZI_PINYIN_ENGLISH_AND_ALIGNED_ENGLISH_GLOSSED_CHUNKS) }
    if (examples[0].hanzi == examples[1].hanzi) invalid("例句JSON", UiMessageKey.PROVIDE_TWO_DIFFERENT_EXAMPLE_SENTENCES)
    examples.forEach { example ->
      if (!example.hanzi.contains(hanzi)) invalid("例句JSON", UiMessageKey.EACH_EXAMPLE_SENTENCE_MUST_CONTAIN_THE_COMPLETE_WORD)
    }
    val parts = try {
      val array = boundedArray(field("部件JSON", 16000), "部件JSON")
      if (array.length() !in 1..32) invalid("部件JSON", UiMessageKey.PROVIDE_1_32_WORD_PARTS)
      decodeParts(array).map { it.copy(pinyin = checkedPinyin(it.pinyin, "部件JSON")) }
    } catch (error: CsvFieldException) { throw error }
    catch (_: Exception) { invalid("部件JSON", UiMessageKey.WORD_PARTS_MUST_BE_A_VALID_JSON_ARRAY_WITH_HANZI_PINYIN_AND_GLOSS) }
    if (parts.joinToString("") { it.hanzi } != hanzi)
      invalid("部件JSON", UiMessageKey.THE_ORDERED_WORD_PARTS_MUST_RECONSTRUCT_THE_COMPLETE_WORD)
    field("来源说明", 2000) // Provenance stays in the user's CSV; no source field is added to the teaching model.
    fun explanations(name: String, required: Boolean): List<String> = try {
      decodeExplanations(boundedArray(field(name, 16000), name), required)
    } catch (error: CsvFieldException) { throw error }
    catch (_: Exception) { invalid(name, UiMessageKey.PROVIDE_AN_ENGLISH_STRING_ARRAY_WITH_UP_TO_SIXTEEN_SENSES_LITERAL_MEANINGS_CANNOT_BE_EMPTY) }
    val literalExplanations = explanations("本义解释JSON", required = true)
    val figurativeExplanations = explanations("引申义解释JSON", required = false)
    val distractors = try {
      val array = boundedArray(field("干扰词ID", 400), "干扰词ID")
      if (array.length() != 3 || (0 until array.length()).any { array.get(it) !is String })
        invalid("干扰词ID", UiMessageKey.PROVIDE_EXACTLY_THREE_WORD_IDS_AS_A_JSON_STRING_ARRAY)
      array.strings(3).map { id(it, "干扰词ID") }
    } catch (error: CsvFieldException) { throw error }
    catch (_: Exception) { invalid("干扰词ID", UiMessageKey.DISTRACTORS_MUST_BE_A_JSON_ARRAY_OF_THREE_WORD_IDS) }
    if (distractors.distinct().size != 3 || wordId in distractors)
      invalid("干扰词ID", UiMessageKey.DISTRACTOR_IDS_MUST_BE_DISTINCT_AND_CANNOT_INCLUDE_THIS_WORD)
    return CsvWord(record.line, order, wordId, hanzi, pinyin, rarity, english, partOfSpeech,
      examples, parts, distractors, literalExplanations, figurativeExplanations)
  }

  private fun isHanzi(codePoint: Int): Boolean = codePoint in 0x3400..0x4DBF ||
    codePoint in 0x4E00..0x9FFF || codePoint in 0xF900..0xFAFF ||
    codePoint in 0x20000..0x2FFFF || codePoint in 0x30000..0x323AF

  private fun checkedPinyin(value: String, field: String, sentence: Boolean = false): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
    if (!pinyinLetter.containsMatchIn(normalized) || normalized.any { character ->
      !character.isWhitespace() && !pinyinLetter.matches(character.toString()) &&
        character !in (if (sentence) SENTENCE_PUNCTUATION else "'-’")
    }) throw CsvFieldException(field, UiMessageKey.USE_TONE_MARKED_PINYIN_LETTERS_AND_SPACING_SENTENCE_PINYIN_MAY_INCLUDE_PUNCTUATION)
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
            if (position == value.length) throw CsvFieldException(field, UiMessageKey.A_JSON_ESCAPE_IS_INCOMPLETE)
            when (value[position++]) {
              '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
              'u' -> {
                if (position + 4 > value.length || value.substring(position, position + 4).any {
                  it !in "0123456789abcdefABCDEF"
                }) throw CsvFieldException(field, UiMessageKey.A_JSON_UNICODE_ESCAPE_MUST_CONTAIN_FOUR_HEXADECIMAL_DIGITS)
                position += 4
              }
              else -> throw CsvFieldException(field, UiMessageKey.THE_JSON_STRING_CONTAINS_AN_INVALID_ESCAPE)
            }
          }
          character == '"' -> quoted = false
          character.code < 0x20 -> throw CsvFieldException(field, UiMessageKey.JSON_STRINGS_MUST_ESCAPE_CONTROL_CHARACTERS)
        }
      } else when (character) {
        '"' -> quoted = true
        '[', '{' -> {
          depth++
          if (depth > 4) throw CsvFieldException(field, UiMessageKey.JSON_NESTING_MUST_NOT_EXCEED_FOUR_LEVELS)
        }
        ']', '}' -> {
          if (previous == ',') throw CsvFieldException(field, UiMessageKey.JSON_ARRAYS_AND_OBJECTS_CANNOT_END_WITH_A_COMMA)
          depth--
          if (depth < 0) throw CsvFieldException(field, UiMessageKey.JSON_BRACKETS_ARE_UNBALANCED)
        }
        ':' -> if (previous != '"')
          throw CsvFieldException(field, UiMessageKey.A_JSON_OBJECT_KEY_MUST_BE_A_DOUBLE_QUOTED_STRING)
        ',' -> if (previous !in "\"}]")
          throw CsvFieldException(field, UiMessageKey.JSON_ENTRIES_CANNOT_BE_EMPTY)
        else -> if (character !in " \t\r\n")
          throw CsvFieldException(field, UiMessageKey.THESE_JSON_ARRAYS_REQUIRE_DOUBLE_QUOTED_KEYS_AND_STRING_VALUES)
      }
      if (!quoted && character !in " \t\r\n") previous = character
    }
    if (depth != 0 || quoted) throw CsvFieldException(field, UiMessageKey.JSON_BRACKETS_OR_STRINGS_ARE_NOT_CLOSED)
    val tokener = JSONTokener(value)
    val array = tokener.nextValue()
    if (array !is JSONArray || tokener.nextClean() != '\u0000')
      throw CsvFieldException(field, UiMessageKey.THE_FIELD_MUST_CONTAIN_ONE_COMPLETE_JSON_ARRAY)
    return array
  }
}

private class CsvFieldException(val field: String, val code: UiMessageKey) : IllegalArgumentException(code.name)
