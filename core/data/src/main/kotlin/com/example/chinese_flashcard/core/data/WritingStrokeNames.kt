package com.example.chinese_flashcard.core.data

import android.content.Context
import com.example.chinese_flashcard.core.domain.StrokeName
import com.example.chinese_flashcard.core.domain.TracingItem
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Supplemental metadata only: existing Room rows and imported glyphs stay intact. */
internal class WritingStrokeNames(context: Context) {
  private val entries = context.assets.open("writing-strokes/names.json").use {
    val output = ByteArrayOutputStream()
    val block = ByteArray(8192)
    while (true) {
      val count = it.read(block)
      if (count < 0) break
      require(output.size() + count <= 2 * 1024 * 1024)
      output.write(block, 0, count)
    }
    val bytes = output.toByteArray()
    JSONObject(bytes.toString(Charsets.UTF_8)).also { root ->
      require(root.getInt("version") == 1)
      val catalog = root.getJSONArray("catalog")
      require(catalog.length() == StrokeName.entries.size && StrokeName.entries.withIndex().all { (index, name) ->
        catalog.getString(index) == name.name
      })
    }
      .getJSONObject("items")
  }

  fun attach(item: TracingItem): TracingItem {
    val entry = entries.optJSONArray(item.glyph) ?: return item
    val hash = MessageDigest.getInstance("SHA-256").digest(item.paths.joinToString("\n").toByteArray())
      .joinToString("") { "%02x".format(it.toInt() and 255) }
    if (hash != entry.getString(0)) return item
    val names = entry.getJSONArray(1)
    if (names.length() != item.paths.size) return item
    return item.copy(strokeNames = List(names.length()) { index ->
      if (names.isNull(index)) null else StrokeName.entries.getOrNull(names.optInt(index, -1))
    })
  }
}
