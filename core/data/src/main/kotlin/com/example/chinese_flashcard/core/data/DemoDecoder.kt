package com.example.chinese_flashcard.core.data

import android.content.Context
import com.example.chinese_flashcard.core.domain.ExampleChunk
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.Meaning
import com.example.chinese_flashcard.core.domain.StrokePoint
import com.example.chinese_flashcard.core.domain.TracingItem
import com.example.chinese_flashcard.core.domain.WordPart
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.Locale

internal data class DemoContent(val words: List<WordEntity>, val meanings: List<MeaningEntity>,
  val tracing: List<TracingEntity>, val links: List<WordTracingEntity>)

/** Both assets are completely checked before the first database transaction publishes content. */
internal object DemoDecoder {
  fun read(context: Context): DemoContent {
    val wordRoot = readAsset(context, "demo/catalog.json")
    val strokeRoot = readAsset(context, "demo/strokes.json")
    require(wordRoot.getInt("version") == 1 && strokeRoot.getInt("version") == 1)
    val tracing = strokeRoot.getJSONArray("items").objects(1000).map { json ->
      val item = TracingItem(id(json.getString("id")), text(json, "glyph", 4),
        json.getJSONArray("paths").strings(64), decodePoints(json.getJSONArray("medians")),
        text(json, "revision", 128), text(json, "attribution", 4000))
      require(item.glyph.codePointCount(0, item.glyph.length) == 1)
      require(item.paths.isNotEmpty() && item.paths.size == item.medians.size)
      item.paths.forEach(::validatePath)
      require(item.medians.all { stroke -> stroke.size in 2..512 && stroke.all {
        it.x.isFinite() && it.y.isFinite() && it.x in -512f..1536f && it.y in -512f..1536f
      } })
      TracingEntity(item.id, item.glyph, stringsJson(item.paths), pointsJson(item.medians), item.revision, item.attribution)
    }
    require(tracing.map { it.id }.distinct().size == tracing.size)
    require(tracing.map { it.glyph }.distinct().size == tracing.size)
    val byId = tracing.associateBy { it.id }
    val words = mutableListOf<WordEntity>()
    val meanings = mutableListOf<MeaningEntity>()
    val links = mutableListOf<WordTracingEntity>()
    wordRoot.getJSONArray("words").objects(2000).forEachIndexed { order, json ->
      val wordId = id(json.getString("id"))
      val hanzi = text(json, "hanzi", 64)
      val pinyin = text(json, "pinyin", 128)
      val wordMeanings = json.getJSONArray("meanings").objects(8).mapIndexed { position, meaning ->
        MeaningEntity(id(meaning.getString("id")), wordId, text(meaning, "english", 300),
          meaning.optString("partOfSpeech").also { require(it.length <= 80) }, position)
      }
      val examples = decodeExamples(json.getJSONArray("examples"))
      val parts = decodeParts(json.getJSONArray("parts"))
      val distractors = json.getJSONArray("distractorMeaningIds").strings(3).map(::id)
      require(wordMeanings.isNotEmpty() && examples.isNotEmpty() && parts.isNotEmpty())
      require(distractors.size == 3 && distractors.distinct().size == 3)
      val tracingIds = json.getJSONArray("tracingItemIds").strings(32)
      val glyphs = hanzi.codePoints().toArray().map { String(Character.toChars(it)) }
      require(tracingIds.size == glyphs.size)
      tracingIds.forEachIndexed { position, tracingId ->
        require(byId[tracingId]?.glyph == glyphs[position])
        links += WordTracingEntity(wordId, position, tracingId)
      }
      words += WordEntity(wordId, hanzi, pinyin, examplesJson(examples), partsJson(parts),
        stringsJson(distractors), order,
        literalExplanationsJson = stringsJson(decodeExplanations(json.getJSONArray("literalExplanations"), required = true)),
        figurativeExplanationsJson = stringsJson(decodeExplanations(json.getJSONArray("figurativeExplanations"), required = false)))
      meanings += wordMeanings
    }
    require(words.isNotEmpty() && words.map { it.id }.distinct().size == words.size)
    require(meanings.map { it.id }.distinct().size == meanings.size)
    val meaningMap = meanings.associateBy { it.id }
    words.forEach { word ->
      val correct = meanings.first { it.wordId == word.id }
      val distractors = strings(JSONArray(word.distractorsJson)).map { meaningMap[it] ?: error("Missing meaning.") }
      require(distractors.all { it.wordId != word.id })
      require((distractors + correct).map { it.english.trim().lowercase(java.util.Locale.ROOT) }.distinct().size == 4)
    }
    return DemoContent(words, meanings, tracing, links)
  }

  /** Optional writing display content; this does not load strokes or publish database rows. */
  fun readWritingBreakdowns(context: Context): Map<Pair<String, String>, List<WordPart>> {
    val root = readAsset(context, "demo/catalog.json", maxBytes = 256 * 1024)
    require(root.getInt("version") == 1)
    val entries = root.getJSONArray("words").objects(200).mapNotNull { json ->
      val hanzi = text(json, "hanzi", 64)
      val pinyin = text(json, "pinyin", 128)
      val parts = decodeParts(json.getJSONArray("parts"))
      if (hanzi.codePointCount(0, hanzi.length) > 1 && parts.size > 1 &&
        parts.joinToString("") { it.hanzi } == hanzi) writingBreakdownKey(hanzi, pinyin) to parts else null
    }
    require(entries.map { it.first }.distinct().size == entries.size)
    return entries.toMap()
  }

  private fun readAsset(context: Context, path: String, maxBytes: Int = 4 * 1024 * 1024): JSONObject {
    val buffer = ByteArrayOutputStream()
    context.assets.open(path).use { input ->
      val block = ByteArray(8192)
      while (true) {
        val count = input.read(block)
        if (count < 0) break
        require(buffer.size() + count <= maxBytes)
        buffer.write(block, 0, count)
      }
    }
    return JSONObject(buffer.toString(Charsets.UTF_8.name()))
  }

  private fun id(value: String): String = value.also { require(it.matches(Regex("[A-Za-z0-9_]{1,100}"))) }
  private fun text(json: JSONObject, key: String, max: Int): String = json.getString(key).also { require(it.isNotBlank() && it.length <= max) }
  private fun validatePath(path: String) {
    require(path.length in 1..16384 && path.startsWith("M"))
    val token = Regex("[MLQCZ]|[-+]?(?:[0-9]*\\.)?[0-9]+(?:[eE][-+]?[0-9]+)?")
    require(token.replace(path, "").all { it.isWhitespace() || it == ',' })
    val parts = token.findAll(path).map { it.value }.toList()
    var index = 0
    while (index < parts.size) {
      val count = when (parts[index++]) { "M", "L" -> 2; "Q" -> 4; "C" -> 6; "Z" -> 0; else -> error("Invalid stroke path.") }
      if (count == 0) continue
      var numbers = 0
      while (index < parts.size && parts[index].first() !in "MLQCZ") {
        val value = parts[index++].toFloat()
        require(value.isFinite() && value in -8192f..8192f)
        numbers++
      }
      require(numbers >= count && numbers % count == 0)
    }
  }
}

/** Ignore presentation separators while keeping tone distinctions and polyphonic readings. */
internal fun writingBreakdownKey(hanzi: String, pinyin: String): Pair<String, String> = hanzi to
  Normalizer.normalize(pinyin, Normalizer.Form.NFC)
    .filterNot { it.isWhitespace() || it == '\'' || it == '’' }.lowercase(Locale.ROOT)

internal fun JSONArray.objects(limit: Int): List<JSONObject> {
  require(length() <= limit)
  return List(length()) { getJSONObject(it) }
}
internal fun JSONArray.strings(limit: Int): List<String> {
  require(length() <= limit)
  return List(length()) { index ->
    require(get(index) is String) { "JSON string arrays cannot contain other value types." }
    getString(index)
  }
}
internal fun strings(json: JSONArray): List<String> = json.strings(2000)
internal fun stringsJson(values: List<String>): String = JSONArray(values).toString()
internal fun daysJson(values: List<Int>): String = JSONArray(values).toString()
internal fun days(json: String): List<Int> = JSONArray(json).let { array ->
  require(array.length() in 1..6)
  List(array.length()) { array.getInt(it) }
}
internal fun decodeExamples(array: JSONArray): List<ExampleSentence> = array.objects(2).also {
  require(it.size == 2) { "Provide exactly two example sentences." }
}.map { example ->
  example.requireKeys("hanzi", "pinyin", "english", "chunks")
  val chunks = example.getJSONArray("chunks").objects(64).also { require(it.isNotEmpty()) }.map { chunk ->
    chunk.requireKeys("hanzi", "pinyin", "gloss")
    ExampleChunk(chunk.teachingText("hanzi", 1000), chunk.teachingText("pinyin", 2000),
      englishTeachingText(chunk.teachingText("gloss", 2000)))
  }
  ExampleSentence(example.teachingText("hanzi", 1000), example.teachingText("pinyin", 2000),
    englishTeachingText(example.teachingText("english", 2000)), chunks).also {
    require(chunks.joinToString("") { chunk -> chunk.hanzi } == it.hanzi) {
      "The ordered chunks must reconstruct the complete example sentence."
    }
    require(normalizedExamplePinyin(chunks.joinToString(" ") { chunk -> chunk.pinyin }) ==
      normalizedExamplePinyin(it.pinyin)) { "Chunk readings must match the complete sentence pinyin." }
  }
}
internal fun decodeParts(array: JSONArray): List<WordPart> = array.objects(32).map { part ->
  part.requireKeys("hanzi", "pinyin", "gloss")
  WordPart(part.teachingText("hanzi", 64), part.teachingText("pinyin", 128), part.teachingText("gloss", 300))
}
internal fun decodeExplanations(array: JSONArray, required: Boolean): List<String> {
  require(array.length() in (if (required) 1 else 0)..16) { "Provide up to sixteen English senses." }
  return array.strings(16).map { value ->
    require(value.isNotBlank() && value.length <= 2000 && '\u0000' !in value)
    englishTeachingText(value.trim())
  }
}
private fun JSONObject.requireKeys(vararg names: String) {
  require(keys().asSequence().toSet() == names.toSet()) { "Unexpected or missing teaching JSON fields." }
}
private fun JSONObject.teachingText(name: String, maximum: Int): String {
  require(opt(name) is String) { "Teaching JSON text fields must be strings." }
  return getString(name).also { require(it.isNotBlank() && it.length <= maximum && '\u0000' !in it) }
}
private fun englishTeachingText(value: String): String = value.also {
  require(it.any { character -> character in 'A'..'Z' || character in 'a'..'z' } &&
    it.codePoints().toArray().none { point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN }) {
    "Teaching translations and explanations must use English."
  }
}
internal fun normalizedExamplePinyin(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
  .filterNot { it.isWhitespace() || it == '\'' || it == '’' }.lowercase(Locale.ROOT)
internal fun examplesJson(values: List<ExampleSentence>): String = JSONArray(values.map {
  JSONObject().put("hanzi", it.hanzi).put("pinyin", it.pinyin).put("english", it.english)
    .put("chunks", JSONArray(it.chunks.map { chunk ->
      JSONObject().put("hanzi", chunk.hanzi).put("pinyin", chunk.pinyin).put("gloss", chunk.gloss)
    }))
}).toString()
internal fun partsJson(values: List<WordPart>): String = JSONArray(values.map {
  JSONObject().put("hanzi", it.hanzi).put("pinyin", it.pinyin).put("gloss", it.gloss)
}).toString()
internal fun decodePoints(array: JSONArray): List<List<StrokePoint>> {
  require(array.length() <= 64)
  return List(array.length()) { index ->
    array.getJSONArray(index).objects(1024).map {
      StrokePoint(it.getDouble("x").toFloat(), it.getDouble("y").toFloat()).also { point ->
        require(point.x.isFinite() && point.y.isFinite())
      }
    }
  }
}
internal fun pointsJson(values: List<List<StrokePoint>>): String = JSONArray(values.map { stroke ->
  JSONArray(stroke.map { JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble()) })
}).toString()
internal fun MeaningEntity.toDomain() = Meaning(id, english, partOfSpeech)
internal fun TracingEntity.toDomain() = TracingItem(id, glyph, strings(JSONArray(pathsJson)),
  decodePoints(JSONArray(mediansJson)), revision, attribution)
