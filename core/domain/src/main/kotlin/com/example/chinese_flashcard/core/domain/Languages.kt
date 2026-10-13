package com.example.chinese_flashcard.core.domain

enum class AppLanguage { EN, CN }

data class SpeechRequest(val text: String, val language: AppLanguage)

data class LearningContext(val learningLanguage: AppLanguage, val version: Long)

/** Split teaching alternatives without changing text inside parenthesized notes. */
fun splitEnglishMeanings(value: String): List<String> {
  var depth = 0
  var start = 0
  val result = mutableListOf<String>()
  value.forEachIndexed { index, character ->
    when (character) {
      '(', '[' -> depth++
      ')', ']' -> depth = (depth - 1).coerceAtLeast(0)
      ';', '；' -> if (depth == 0) {
        value.substring(start, index).trim().takeIf(String::isNotEmpty)?.let(result::add)
        start = index + 1
      }
    }
  }
  value.substring(start).trim().takeIf(String::isNotEmpty)?.let(result::add)
  return result
}
