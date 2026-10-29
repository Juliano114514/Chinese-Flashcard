package com.example.chinese_flashcard.core.domain

const val MAX_PROGRESS_CODE_LENGTH = 400_000

data class ProgressTransferSummary(val chinese: Int, val mistakes: Int, val matched: Int, val unknown: Int)

/** Lightweight word state only: no review schedule, rewards, settings or vocabulary content. */
interface ProgressTransferRepository {
  suspend fun exportCode(): String
  suspend fun preview(code: String): ProgressTransferSummary
  suspend fun merge(code: String): ProgressTransferSummary
}
