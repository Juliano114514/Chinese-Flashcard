package com.example.chinese_flashcard.core.domain

import kotlinx.coroutines.flow.Flow

enum class WordlistStatus { UNLEARNED, LEARNING, LEARNED, SKIPPED }

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
  val isCollected: Boolean = false,
  val isSkipped: Boolean = false,
  val mistakePending: Boolean = false,
  val learningStatus: WordlistStatus = status,
)

data class WordUserState(val isCollected: Boolean = false, val isSkipped: Boolean = false,
  val mistakePending: Boolean = false)

/** User labels are independent from vocabulary content and normal learning progress. */
interface WordStateRepository {
  fun observe(wordId: String): Flow<WordUserState>
  suspend fun setCollected(wordId: String, value: Boolean)
  suspend fun setSkipped(wordId: String, value: Boolean)
}

/** Browsing does not create daily plans, learning cards, or review completions. */
interface WordlistRepository {
  val entries: Flow<List<WordlistItem>>
  suspend fun word(id: String): WordEntry?
}
