package com.example.chinese_flashcard.core.data

import android.content.Context
import com.example.chinese_flashcard.core.domain.WordTag
import com.example.chinese_flashcard.core.domain.WordTagGroup
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Metadata uses the managed preset ID; imported and unannotated words fall into Others. */
internal class BundledWordTags private constructor(val catalog: List<WordTag>,
  private val annotations: Map<String, Set<String>>, private val headwords: Map<String, String>,
  val annotationVersion: String) {
  fun tagsFor(presetSourceId: String, actualHanzi: String): Set<String> {
    // A deferred preset refresh still exposes the old owned word. Its stable ID
    // must not attach replacement-word tags until the new spelling is committed.
    if (headwords[presetSourceId] != actualHanzi) return others
    return annotations[presetSourceId].orEmpty().ifEmpty { others }
  }

  companion object {
    private val others = setOf("topic_others")
    private val tagId = Regex("[a-z][a-z0-9_-]{0,63}")

    fun read(context: Context): BundledWordTags {
      val definitions = readJson(context, "word-tags/catalog.json", 256 * 1024)
      require(definitions.getInt("schemaVersion") == 1) { "Unsupported tag catalog version." }
      val catalog = definitions.getJSONArray("tags").objects(256).map { entry ->
        val id = text(entry, "id", 64)
        require(tagId.matches(id)) { "Invalid tag ID." }
        WordTag(id, WordTagGroup.valueOf(text(entry, "group", 16).uppercase(Locale.ROOT)),
          text(entry, "labelEn", 120), text(entry, "labelZh", 120),
          optionalText(entry, "descriptionEn", 1000), optionalText(entry, "descriptionZh", 1000))
      }
      require(catalog.isNotEmpty() && catalog.map { it.id }.distinct().size == catalog.size) {
        "The tag catalog must contain unique IDs."
      }
      require(catalog.any { it.id == "topic_others" && it.group == WordTagGroup.TOPIC }) {
        "The tag catalog must include Others."
      }
      val known = catalog.mapTo(mutableSetOf()) { it.id }
      val root = readJson(context, "word-tags/annotations.json", 4 * 1024 * 1024)
      require(root.getInt("schemaVersion") == 1) { "Unsupported word tags version." }
      val expectedWordlist = context.assets.open("default-wordlist/version.txt").use { input ->
        val buffer = ByteArray(66)
        var count = 0
        while (count < buffer.size) {
          val read = input.read(buffer, count, buffer.size - count)
          if (read < 0) break
          if (read > 0) count += read
        }
        require(count in 64..66 && input.read() == -1) { "Invalid bundled wordlist version." }
        String(buffer, 0, count, Charsets.US_ASCII).trim()
      }
      val sourceHash = text(root, "wordlistSha256", 64)
      require(sourceHash.matches(Regex("[a-f0-9]{64}")) && sourceHash == expectedWordlist) {
        "The word tags do not match the bundled wordlist."
      }
      val version = text(root, "annotationVersion", 64)
      val words = root.getJSONObject("words")
      require(words.length() <= 20_000) { "The word tag mapping exceeds the library limit." }
      val expectedHeadwords = root.optJSONObject("headwords") ?: JSONObject()
      require(expectedHeadwords.length() <= 20_000) { "The tag headword mapping exceeds the library limit." }
      val annotations = mutableMapOf<String, Set<String>>()
      val headwords = mutableMapOf<String, String>()
      val ids = words.keys()
      while (ids.hasNext()) {
        val id = ids.next()
        require(id.isNotBlank() && id.length <= 120 && id.none { it.isISOControl() }) { "Invalid word tag key." }
        val tags = words.getJSONArray(id).strings(64)
        require(tags.distinct().size == tags.size && tags.all { it in known }) { "Unknown or duplicate word tag." }
        require("topic_others" !in tags || tags.size == 1) { "Others cannot coexist with annotated tags." }
        annotations[id] = tags.toSet()
        if (expectedHeadwords.has(id)) headwords[id] = text(expectedHeadwords, id, 32)
      }
      return BundledWordTags(catalog, annotations, headwords, version)
    }

    private fun readJson(context: Context, path: String, maxBytes: Int): JSONObject {
      val bytes = ByteArrayOutputStream()
      context.assets.open(path).use { input ->
        val block = ByteArray(8192)
        while (true) {
          val count = input.read(block)
          if (count < 0) break
          require(bytes.size() + count <= maxBytes) { "The tag resource exceeds its size limit." }
          bytes.write(block, 0, count)
        }
      }
      return JSONObject(bytes.toString(Charsets.UTF_8.name()))
    }

    private fun text(json: JSONObject, key: String, maxLength: Int): String = json.getString(key).also {
      require(it.isNotBlank() && it.length <= maxLength && it.none(Char::isISOControl)) { "Invalid tag text." }
    }

    private fun optionalText(json: JSONObject, key: String, maxLength: Int): String =
      if (json.has(key)) text(json, key, maxLength) else ""
  }
}
