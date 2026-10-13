package com.example.chinese_flashcard.core.media

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** Immutable IPA lookup; never generates dictionary transcriptions from a speech model. */
class EnglishPronunciations private constructor(private val entries: Map<String, String>) {
  fun ipa(text: String): String? {
    val word = text.trim()
    if (!word.matches(singleWord)) return null
    return entries[word.lowercase(Locale.ROOT)]
  }

  companion object {
    private val singleWord = Regex("[\\p{L}]+(?:[-’'][\\p{L}]+)*")
    fun load(context: Context): EnglishPronunciations {
      val data = context.assets.open("english-teaching/ipa.json").use {
        JSONObject(it.readBytesBounded(2 * 1024 * 1024).toString(Charsets.UTF_8)).getJSONObject("entries")
      }
      return EnglishPronunciations(data.keys().asSequence().associateWith { data.getString(it) })
    }
  }
}
