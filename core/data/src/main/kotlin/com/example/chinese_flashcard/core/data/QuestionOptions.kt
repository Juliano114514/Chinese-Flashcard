package com.example.chinese_flashcard.core.data

import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.splitEnglishMeanings

private val initialNote = Regex("^(?:\\([^)]*\\)\\s*)+")
private val infinitive = Regex("^to\\s+")
private val commonAliases = mapOf("dad" to "father", "daddy" to "father", "papa" to "father",
  "mom" to "mother", "mum" to "mother", "mummy" to "mother", "mama" to "mother",
  "kid" to "child", "kids" to "children", "be fond of" to "like", "hi" to "hello")

/** A conservative collision check; short glosses cannot certify full semantic equivalence. */
internal fun englishSenseKeys(text: String): Set<String> = splitEnglishMeanings(text).map { sense ->
  val key = normalizedEnglish(sense).replace(initialNote, "").replace(infinitive, "")
    .trim().trimEnd('.')
  commonAliases[key] ?: key
}.filter(String::isNotEmpty).toSet()

/** Preserve the supplied distractors when safe, then fill from the existing wordbook. */
internal suspend fun questionOptions(entry: WordEntry, dao: ScopedFlashcardDao): List<String> {
  val correct = entry.meanings.firstOrNull() ?: return emptyList()
  val chosen = mutableListOf(correct.id)
  val hanzi = mutableSetOf(entry.hanzi)
  val senses = englishSenseKeys(correct.english).toMutableSet()
  suspend fun add(id: String) {
    if (id in chosen || chosen.size == 4) return
    val meaning = dao.meaning(id) ?: return
    if (meaning.wordId == entry.id) return
    val word = dao.word(meaning.wordId) ?: return
    val keys = englishSenseKeys(meaning.english)
    if (word.hanzi in hanzi || keys.isEmpty() || keys.any { it in senses }) return
    chosen += id
    hanzi += word.hanzi
    senses += keys
  }
  for (id in entry.distractorMeaningIds) add(id)
  if (chosen.size < 4) {
    val stages = dao.studyWords().associate { it.id to it.rarity }
    val stage = stages[entry.id]
    for (meaning in dao.allMeanings().sortedBy { if (stages[it.wordId] == stage) 0 else 1 }) {
      add(meaning.id)
      if (chosen.size == 4) break
    }
  }
  return if (chosen.size == 4) chosen.shuffled() else emptyList()
}
