package com.example.chinese_flashcard.core.data

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.Normalizer

/** Bundled history establishes ownership; a matching word identity alone does not. */
internal class PresetWordlist(private val context: Context) {
  private data class LegacyWord(val sourceId: String, val fingerprint: String)
  private data class ReadingCorrection(val sourceId: String, val hanzi: String,
    val before: String, val after: String)

  private val legacy: Map<String, LegacyWord> by lazy {
    val root = readAsset("default-wordlist-history.json", 4 * 1024 * 1024)
    require(root.getInt("version") == 1)
    val entries = root.getJSONArray("entries").objects(CSV_MAX_ROWS + 20).map { entry ->
      val key = checkedId(entry.getString("id"))
      val sourceId = checkedId(entry.getString("sourceId"))
      val fingerprint = entry.getString("fingerprint")
      require(fingerprint.matches(Regex("[0-9a-f]{64}")))
      key to LegacyWord(sourceId, fingerprint)
    }
    require(entries.map { it.first }.distinct().size == entries.size)
    entries.toMap()
  }

  private val corrections: List<ReadingCorrection> by lazy {
    val root = readAsset("default-wordlist-corrections.json", 2 * 1024 * 1024)
    require(root.getInt("version") == 1)
    root.getJSONArray("corrections").objects(CSV_MAX_ROWS).map { entry ->
      val hanzi = normalized(entry.getString("hanzi"))
      val before = entry.getString("fromPinyin")
      val after = entry.getString("toPinyin")
      require(hanzi.isNotBlank() && hanzi.length <= 64 && before.length in 1..128 && after.length in 1..128)
      ReadingCorrection(checkedId(entry.getString("sourceId")), hanzi,
        wordIdentity(hanzi, before), wordIdentity(hanzi, after)).also { require(it.before != it.after) }
    }
  }

  /** Claims remain in memory until the whole preset transaction commits. */
  suspend fun owners(words: Map<String, WordEntity>, meanings: Map<String, MeaningEntity>): Map<String, String> {
    val grouped = meanings.values.groupBy { it.wordId }
    val result = mutableMapOf<String, String>()
    for (word in words.values) {
      currentCoroutineContext().ensureActive()
      if (word.presetSourceId.isNotEmpty()) {
        result[word.id] = checkedId(word.presetSourceId)
        continue
      }
      val expected = legacy[word.id] ?: continue
      val wordMeanings = grouped[word.id].orEmpty().sortedWith(compareBy<MeaningEntity> { it.position }.thenBy { it.id })
      if (fingerprint(word, wordMeanings, words, meanings) == expected.fingerprint)
        result[word.id] = expected.sourceId
    }
    check(result.values.distinct().size == result.size) { "The preset ownership mapping is ambiguous." }
    return result
  }

  fun allowsReading(sourceId: String, stored: WordEntity, incoming: CsvWord): Boolean {
    if (normalized(stored.hanzi) != normalized(incoming.hanzi)) return false
    val before = wordIdentity(stored.hanzi, stored.pinyin)
    if (before == incoming.identity) return true
    return corrections.any { it.sourceId == sourceId && it.hanzi == normalized(incoming.hanzi) &&
      it.before == before && it.after == incoming.identity }
  }

  private fun fingerprint(word: WordEntity, meanings: List<MeaningEntity>,
    words: Map<String, WordEntity>, allMeanings: Map<String, MeaningEntity>): String? {
    val distractors = strings(JSONArray(word.distractorsJson)).map { id ->
      val meaning = allMeanings[id] ?: return null
      val target = words[meaning.wordId] ?: return null
      wordIdentity(target.hanzi, target.pinyin)
    }.sorted()
    val examples = decodeExamples(JSONArray(word.examplesJson))
    val parts = decodeParts(JSONArray(word.partsJson))
    val digest = MessageDigest.getInstance("SHA-256")
    fun token(value: String) {
      val bytes = value.toByteArray(Charsets.UTF_8)
      digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
      digest.update(bytes)
    }
    token("preset-content-v1"); token(normalized(word.hanzi)); token(reading(word.pinyin))
    token(meanings.size.toString())
    meanings.forEach { token(normalized(it.english)); token(normalized(it.partOfSpeech)) }
    token(examples.size.toString())
    examples.forEach { token(normalized(it.hanzi)); token(reading(it.pinyin)); token(normalized(it.english)) }
    token(parts.size.toString())
    parts.forEach { token(normalized(it.hanzi)); token(reading(it.pinyin)); token(normalized(it.gloss)) }
    token(normalized(word.note)); token(normalized(word.literalExplanation)); token(normalized(word.figurativeExplanation))
    token(distractors.size.toString()); distractors.forEach(::token)
    return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
  }

  private fun readAsset(path: String, maximumBytes: Int): JSONObject {
    val buffer = ByteArrayOutputStream()
    context.assets.open(path).use { input ->
      val block = ByteArray(8192)
      while (true) {
        val count = input.read(block)
        if (count < 0) break
        require(buffer.size().toLong() + count <= maximumBytes)
        buffer.write(block, 0, count)
      }
    }
    return JSONObject(buffer.toString(Charsets.UTF_8.name()))
  }

  private fun checkedId(value: String): String = value.also { require(it.matches(Regex("[A-Za-z0-9_]{1,92}"))) }
  private fun normalized(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
  private fun reading(value: String): String = wordIdentity("", value).substringAfter('\u0000')
}

internal class PresetCardConflictException : IllegalStateException(
  "Finish the saved study card, then retry the default wordlist update.")
