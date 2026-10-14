package com.example.chinese_flashcard.core.media

import com.example.chinese_flashcard.core.domain.ChinesePronunciation
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Unpadded model phones; boundary and add_blank processing belong to ChineseSpeech. */
internal data class MandarinPhones(val phoneIds: LongArray, val tones: LongArray)

/** Uses only the supplied reading. An incomplete or ambiguous request returns no phones. */
internal class MandarinPhonemes(metadata: JSONObject) {
  private data class Syllable(val base: String, val tone: Int)
  private data class PhoneUnit(val text: String, val syllable: Syllable?, val group: Int)
  private data class Alignment(val units: List<PhoneUnit>, val groups: Int)
  private data class Analysis(val last: Syllable?, val previous: Analysis?)

  private val syllables: Map<String, LongArray>
  private val punctuation: Map<String, Long>
  private val maximumSyllableLength: Int
  private val minimumPhonesPerSyllable: Int

  init {
    require(metadata.getInt("schemaVersion") == 1 && metadata.getInt("blankTokenId") == 0)
    val entries = metadata.getJSONObject("syllables")
    require(entries.length() in 1..512)
    syllables = entries.keys().asSequence().associateWith { base ->
      require(base.matches(Regex("[a-z]{1,6}")))
      val ids = entries.getJSONArray(base)
      require(ids.length() in 1..8)
      LongArray(ids.length()) { index ->
        ids.getLong(index).also { require(it in 1L..102L) }
      }
    }
    val symbols = metadata.getJSONObject("punctuation")
    punctuation = punctuationAliases.values.toSet().associateWith { symbol ->
      symbols.getLong(symbol).also { require(it in 103L..109L) }
    }
    maximumSyllableLength = syllables.keys.maxOf { it.length } + 1
    minimumPhonesPerSyllable = syllables.values.minOf { it.size }
  }

  fun compile(pronunciation: ChinesePronunciation): MandarinPhones? {
    if (pronunciation.text.length > MAX_TEXT || pronunciation.pinyin.length > MAX_PINYIN) return null
    val text = nfc(pronunciation.text)
    val pinyin = nfc(pronunciation.pinyin).lowercase(Locale.ROOT)
    if (text.isBlank() || pinyin.isBlank() || text.length > MAX_TEXT || pinyin.length > MAX_PINYIN) return null
    val units = ArrayList<PhoneUnit>()
    if (pronunciation.chunks.isEmpty()) {
      units.addAll(align(text, pinyin, 0)?.units ?: return null)
    } else {
      if (pronunciation.chunks.size > MAX_CHUNKS) return null
      if (nfc(pronunciation.chunks.joinToString("") { it.hanzi }) != text) return null
      if (compact(pronunciation.chunks.joinToString(" ") { it.pinyin }) != compact(pinyin)) return null
      var groups = 0
      for (chunk in pronunciation.chunks) {
        if (chunk.hanzi.length > MAX_TEXT || chunk.pinyin.length > MAX_PINYIN) return null
        val aligned = align(nfc(chunk.hanzi), nfc(chunk.pinyin).lowercase(Locale.ROOT), groups) ?: return null
        units.addAll(aligned.units)
        groups += aligned.groups
      }
    }
    if (units.none { it.syllable != null }) return null
    val adjusted = applySandhi(units) ?: return null
    val phones = ArrayList<Long>()
    val tones = ArrayList<Long>()
    for (unit in adjusted) {
      val syllable = unit.syllable
      if (syllable == null) {
        phones.add(punctuation[unit.text] ?: return null)
        tones.add(0L)
      } else {
        val ids = syllables[syllable.base] ?: return null
        phones.addAll(ids.asList())
        repeat(ids.size) { tones.add(syllable.tone.toLong()) }
      }
      if (phones.size > MAX_PHONES) return null
    }
    return MandarinPhones(phones.toLongArray(), tones.toLongArray())
  }

  private fun syllable(value: String): Syllable? {
    var raw = value
    val explicitTone = raw.lastOrNull()?.takeIf { it in '1'..'5' }?.digitToInt()
    if (explicitTone != null) raw = raw.dropLast(1)
    val letters = StringBuilder()
    var markedTone: Int? = null
    for (character in Normalizer.normalize(raw, Normalizer.Form.NFD)) {
      val tone = toneMarks[character]
      if (tone != null) {
        if (markedTone != null || explicitTone != null) return null
        markedTone = tone
      } else letters.append(character)
    }
    val base = nfc(letters.toString()).replace('ü', 'v')
    if (base !in syllables) return null
    return Syllable(base, explicitTone ?: markedTone ?: 5)
  }

  private fun splitSyllables(runs: List<String>, count: Int): List<Syllable>? {
    if (count == 0) return if (runs.isEmpty()) emptyList() else null
    if (runs.size > count || count > MAX_PHONES / minimumPhonesPerSyllable) return null
    val source = runs.joinToString("")
    val ends = IntArray(source.length)
    val completeRuns = HashMap<Int, Int>()
    var offset = 0
    for (run in runs) {
      val end = offset + run.length
      // Explicitly delimited valid syllables remain single syllables: xian is not xi + an.
      if (syllable(run) != null) completeRuns[offset] = end
      for (index in offset until end) ends[index] = end
      offset = end
    }
    val states = HashMap<Int, MutableMap<Int, MutableList<Analysis>>>()
    states[0] = mutableMapOf(0 to mutableListOf(Analysis(null, null)))
    var stateCount = 1
    for (start in source.indices) {
      val entries = states.remove(start) ?: continue
      for ((used, paths) in entries) {
        if (used >= count) continue
        val wholeRunEnd = completeRuns[start]
        val candidates = if (wholeRunEnd != null) wholeRunEnd..wholeRunEnd
          else start + 1..minOf(ends[start], start + maximumSyllableLength)
        for (end in candidates) {
          val reading = syllable(source.substring(start, end)) ?: continue
          val remaining = source.length - end
          val needed = count - used - 1
          if (remaining < needed || remaining > needed * maximumSyllableLength) continue
          val bucket = states.getOrPut(end) { mutableMapOf() }
          val target = bucket[used + 1] ?: run {
            stateCount++
            if (stateCount > MAX_SEGMENTATION_STATES) return null
            mutableListOf<Analysis>().also { bucket[used + 1] = it }
          }
          // Two distinct complete analyses suffice to reject ambiguity. Linked paths bound memory.
          for (path in paths) if (target.size < 2) target.add(Analysis(reading, path))
        }
      }
    }
    val solutions = states[source.length]?.get(count) ?: return null
    if (solutions.size != 1) return null
    val result = ArrayList<Syllable>(count)
    var path: Analysis? = solutions.single()
    while (path != null) {
      path.last?.let { result.add(it) }
      path = path.previous
    }
    result.reverse()
    return result
  }

  private fun align(text: String, pinyin: String, firstGroup: Int): Alignment? {
    val textSpans = mutableListOf(StringBuilder())
    val textPunctuation = ArrayList<String>()
    for (point in text.codePoints().toArray()) {
      val character = String(Character.toChars(point))
      when {
        isHan(point) -> textSpans.last().append(character)
        whitespace(point) -> Unit
        character in punctuationAliases -> {
          textPunctuation.add(punctuationAliases.getValue(character))
          textSpans.add(StringBuilder())
        }
        else -> return null
      }
    }
    val readingSpans = mutableListOf(mutableListOf<String>())
    val readingPunctuation = ArrayList<String>()
    val run = StringBuilder()
    fun flush() {
      if (run.isNotEmpty()) {
        readingSpans.last().add(run.toString())
        run.setLength(0)
      }
    }
    for ((index, character) in pinyin.withIndex()) {
      if (pinyinLetter(character)) { run.append(character); continue }
      if (character in '1'..'5') {
        if (run.isEmpty()) return null
        run.append(character)
        flush()
        continue
      }
      val separator = character in "'’-" && index > 0 && index + 1 < pinyin.length &&
        (pinyinLetter(pinyin[index - 1]) || pinyin[index - 1] in '1'..'5') && pinyinLetter(pinyin[index + 1])
      flush()
      if (whitespace(character.code) || separator) continue
      val symbol = punctuationAliases[character.toString()] ?: return null
      readingPunctuation.add(symbol)
      readingSpans.add(mutableListOf())
    }
    flush()
    if (readingPunctuation != textPunctuation || readingSpans.size != textSpans.size) return null
    val units = ArrayList<PhoneUnit>()
    for (index in textSpans.indices) {
      val characters = textSpans[index].toString().codePoints().toArray()
      val readings = splitSyllables(readingSpans[index], characters.size) ?: return null
      for (position in characters.indices) {
        units.add(PhoneUnit(String(Character.toChars(characters[position])), readings[position], firstGroup + index))
      }
      if (index < textPunctuation.size) units.add(PhoneUnit(textPunctuation[index], null, firstGroup + index))
    }
    return Alignment(units, textSpans.size)
  }

  private fun applySandhi(source: List<PhoneUnit>): List<PhoneUnit>? {
    val result = source.toMutableList()
    val groupSizes = source.filter { it.syllable != null }.groupingBy { it.group }.eachCount()
    var index = 0
    while (index < source.size) {
      if (source[index].syllable?.tone != 3) { index++; continue }
      var end = index + 1
      while (end < source.size && source[end].syllable?.tone == 3) end++
      val length = end - index
      // Long chains need reliable lexical/prosodic grouping, absent from this import frontend.
      if (length > 2) return null
      if (length == 2) {
        val first = source[index]
        if (first.group != source[index + 1].group || groupSizes[first.group] != 2) return null
        result[index] = first.copy(syllable = first.syllable?.copy(tone = 2))
      }
      index = end
    }
    for (position in source.indices) {
      val unit = source[position]
      val reading = unit.syllable ?: continue
      val next = source.getOrNull(position + 1)?.takeIf { it.syllable != null } ?: continue
      val previous = source.getOrNull(position - 1)?.takeIf { it.syllable != null }
      if (unit.text == "一" && reading.base == "yi" && reading.tone == 1) {
        if (previous?.text == "第" || previous?.text in numerals || next.text in numerals) continue
        // A一A normally uses a neutral tone; never invent it over an explicitly recorded tone.
        if (previous?.text == next.text) return null
        val nextTone = next.syllable?.tone ?: return null
        // A neutral following syllable does not reveal the citation tone needed by this rule.
        if (nextTone == 5) return null
        result[position] = unit.copy(syllable = reading.copy(tone = if (nextTone == 4) 2 else 4))
      } else if (unit.text == "不" && reading.base == "bu" && reading.tone == 4) {
        if (previous?.text == next.text) return null
        if (next.syllable?.tone == 4) result[position] = unit.copy(syllable = reading.copy(tone = 2))
      }
    }
    return result
  }

  companion object {
    private const val MAX_TEXT = 1000
    private const val MAX_PINYIN = 2000
    private const val MAX_PHONES = 510
    private const val MAX_CHUNKS = 64
    private const val MAX_SEGMENTATION_STATES = 20_000
    private val toneMarks = mapOf('\u0304' to 1, '\u0301' to 2, '\u030c' to 3, '\u0300' to 4)
    private val numerals = "一二三四五六七八九零〇十百千万亿两".map { it.toString() }.toSet()
    private val punctuationAliases = mapOf(
      "," to ",", "，" to ",", "、" to ",", ":" to ",", "：" to ",", ";" to ",", "；" to ",",
      "." to ".", "。" to ".", "!" to "!", "！" to "!", "?" to "?", "？" to "?", "…" to "…",
      "'" to "'", "\"" to "'", "“" to "'", "”" to "'", "‘" to "'", "’" to "'",
      "(" to "'", ")" to "'", "（" to "'", "）" to "'", "[" to "'", "]" to "'",
      "《" to "'", "》" to "'", "「" to "'", "」" to "'", "—" to "-", "–" to "-", "-" to "-",
    )

    private fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
    private fun whitespace(point: Int): Boolean = Character.isWhitespace(point) || Character.isSpaceChar(point)
    private fun compact(value: String): String {
      val reading = nfc(value).lowercase(Locale.ROOT)
      return reading.filterIndexed { index, character ->
        val separator = character in "'’" && index > 0 && index + 1 < reading.length &&
          (pinyinLetter(reading[index - 1]) || reading[index - 1] in '1'..'5') && pinyinLetter(reading[index + 1])
        !whitespace(character.code) && !separator
      }
    }
    private fun pinyinLetter(character: Char): Boolean = character.isLetter() &&
      Normalizer.normalize(character.toString(), Normalizer.Form.NFD).all {
        it in 'a'..'z' || Character.getType(it) == Character.NON_SPACING_MARK.toInt()
      }
    private fun isHan(point: Int): Boolean = point in 0x3400..0x4dbf || point in 0x4e00..0x9fff ||
      point in 0xf900..0xfaff || point in 0x20000..0x2ffff || point in 0x30000..0x323af
  }
}
