package com.example.chinese_flashcard.core.domain

import kotlinx.coroutines.flow.Flow

enum class WordlistStatus { UNLEARNED, LEARNING, LEARNED }

data class WordlistItem(
  val id: String,
  val hanzi: String,
  val pinyin: String,
  val english: String,
  val searchMeanings: String,
  val difficulty: Int,
  val status: WordlistStatus,
  val correctRounds: Int,
  val targetRounds: Int,
)

/** Browsing does not create daily plans, learning cards, or review completions. */
interface WordlistRepository {
  val entries: Flow<List<WordlistItem>>
  suspend fun word(id: String): WordEntry?
}
