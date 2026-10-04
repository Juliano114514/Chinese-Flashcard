package com.example.chinese_flashcard.core.domain

import java.io.InputStream

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
  suspend fun preview(source: CsvSource): CsvImportPreview
  suspend fun commit(previewId: String): CsvImportReport
  suspend fun discard(previewId: String)
}
