package com.example.chinese_flashcard.core.domain

import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow

enum class WordlistLoadStage { PREPARING, CHECKING, STROKES, SAVING, COMMITTING, COMPLETE }

/** Counts scanned CSV records, including duplicates; COMPLETE means preview ready or import committed. */
data class WordlistLoadProgress(
  val stage: WordlistLoadStage = WordlistLoadStage.PREPARING,
  val processedWords: Int = 0,
  val totalWords: Int? = null,
  val currentEntry: String? = null,
  val fraction: Float = 0f,
)

fun interface CsvSource {
  fun open(): InputStream
}

data class CsvImportIssue(val line: Int, val field: String, val message: String)

data class CsvImportPreview(
  val previewId: String?,
  val totalRows: Int,
  val newWords: Int,
  val duplicateWords: Int,
  val errorCount: Int,
  val issues: List<CsvImportIssue>,
) {
  val canImport: Boolean get() = previewId != null && errorCount == 0
}

data class CsvImportReport(val addedWords: Int, val skippedWords: Int)

interface CsvImportRepository {
  val progress: StateFlow<WordlistLoadProgress?>
  suspend fun preview(source: CsvSource): CsvImportPreview
  suspend fun commit(previewId: String): CsvImportReport
  suspend fun discard(previewId: String)
}
