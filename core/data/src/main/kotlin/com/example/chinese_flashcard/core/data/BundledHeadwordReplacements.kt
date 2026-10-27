package com.example.chinese_flashcard.core.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import org.json.JSONObject

/** Only the bundled editorial ledger can replace the spelling of an owned preset ID. */
internal class BundledHeadwordReplacements private constructor(
  private val entries: Map<String, Replacement>,
) {
  private data class Replacement(val oldHanzi: String, val newHanzi: String, val newPinyin: String)

  fun allows(sourceId: String, previousHanzi: String, hanzi: String, pinyin: String): Boolean {
    val entry = entries[sourceId] ?: return false
    return Normalizer.normalize(previousHanzi, Normalizer.Form.NFC) == entry.oldHanzi &&
      wordIdentity(hanzi, pinyin) == wordIdentity(entry.newHanzi, entry.newPinyin)
  }

  companion object {
    fun read(context: Context): BundledHeadwordReplacements {
      val bytes = ByteArrayOutputStream()
      context.assets.open("wordlist-sources/headword-replacements.json").use { input ->
        val block = ByteArray(8192)
        while (true) {
          val count = input.read(block)
          if (count < 0) break
          require(bytes.size() + count <= 4 * 1024 * 1024) { "The replacement ledger exceeds its limit." }
          bytes.write(block, 0, count)
        }
      }
      val root = JSONObject(bytes.toString(Charsets.UTF_8.name()))
      require(root.getInt("schemaVersion") == 1) { "Unsupported replacement ledger version." }
      val records = root.getJSONObject("replacements")
      require(records.length() <= 20_000) { "Too many replacement records." }
      val entries = mutableMapOf<String, Replacement>()
      for (id in records.keys()) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Invalid replacement source ID." }
        val entry = records.getJSONObject(id)
        text(entry, "oldPinyin", 200)
        entries[id] = Replacement(text(entry, "oldHanzi", 24), text(entry, "newHanzi", 24),
          text(entry, "newPinyin", 200))
      }
      return BundledHeadwordReplacements(entries)
    }

    private fun text(entry: JSONObject, key: String, limit: Int): String =
      Normalizer.normalize(entry.getString(key), Normalizer.Form.NFC).also {
        require(it.isNotBlank() && it.length <= limit && it.none(Char::isISOControl)) {
          "Invalid replacement record."
        }
      }
  }
}
