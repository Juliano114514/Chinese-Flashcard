package com.example.chinese_flashcard.core.data

import java.text.Normalizer
import java.util.Locale

internal fun pinyinSyllables(value: String): List<String> = value.trim()
  .split(Regex("[\\s'’\\-]+")).filter(String::isNotEmpty)

/** Compare each recorded syllable's letters, then its tone, before the next syllable. */
internal object PinyinOrdering {
  private data class Syllable(val letters: String, val tone: Int)
  private data class KeyedWord(val word: WordEntity, val syllables: List<Syllable>)

  fun sorted(words: List<WordEntity>): List<WordEntity> = words.map { word ->
    KeyedWord(word, pinyinSyllables(word.pinyin).map(::syllable))
  }.sortedWith { left, right ->
    val rarity = left.word.rarity.compareTo(right.word.rarity)
    if (rarity != 0) rarity else {
      val reading = compareSyllables(left.syllables, right.syllables)
      if (reading != 0) reading else left.word.id.compareTo(right.word.id)
    }
  }.map { it.word }

  private fun syllable(value: String): Syllable {
    var tone = 5
    val letters = StringBuilder()
    Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).forEach { character ->
      when (character) {
        '\u0304' -> tone = 1
        '\u0301' -> tone = 2
        '\u030c' -> tone = 3
        '\u0300' -> tone = 4
        else -> letters.append(character)
      }
    }
    return Syllable(Normalizer.normalize(letters.toString(), Normalizer.Form.NFC), tone)
  }

  private fun compareSyllables(left: List<Syllable>, right: List<Syllable>): Int {
    for (index in 0 until minOf(left.size, right.size)) {
      val letters = compareLetters(left[index].letters, right[index].letters)
      if (letters != 0) return letters
      val tone = left[index].tone.compareTo(right[index].tone)
      if (tone != 0) return tone
    }
    return left.size.compareTo(right.size)
  }

  private fun compareLetters(left: String, right: String): Int {
    fun weight(character: Char): Int = when (character) {
      'ü' -> 'u'.code * 2 + 1
      'ê' -> 'e'.code * 2 + 1
      else -> character.code * 2
    }
    for (index in 0 until minOf(left.length, right.length)) {
      val letters = weight(left[index]).compareTo(weight(right[index]))
      if (letters != 0) return letters
    }
    return left.length.compareTo(right.length)
  }
}
