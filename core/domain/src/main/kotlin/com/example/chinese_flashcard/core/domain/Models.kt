package com.example.chinese_flashcard.core.domain

data class Meaning(val id: String, val english: String, val partOfSpeech: String = "")
data class ExampleSentence(val hanzi: String, val pinyin: String, val english: String)
data class WordPart(val hanzi: String, val pinyin: String, val gloss: String)
data class WordEntry(val id: String, val hanzi: String, val pinyin: String,
  val meanings: List<Meaning>, val examples: List<ExampleSentence>, val parts: List<WordPart>,
  val note: String, val distractorMeaningIds: List<String>)

data class StudySettings(val dailyWords: Int = 10, val rounds: Int = 4,
  val reviewDays: List<Int> = listOf(1, 3, 7), val welcomed: Boolean = false) {
  fun validate() {
    require(dailyWords in 1..100 && rounds in 2..8)
    require(reviewDays.isNotEmpty() && reviewDays == reviewDays.distinct().sorted())
    require(reviewDays.all { it in listOf(1, 3, 7, 14, 30) })
  }
}

enum class StudyKind { NEW, REVIEW, CARRYOVER }
enum class CardPhase { INTRO, QUESTION, FEEDBACK, EXPLANATION, WRITING, FINISHED }
data class AnswerOption(val id: String, val english: String)
data class StudyCard(val id: String, val word: WordEntry, val kind: StudyKind,
  val phase: CardPhase, val round: Int, val targetRounds: Int, val reviewRecall: Boolean,
  val options: List<AnswerOption>, val selectedOptionId: String? = null,
  val correct: Boolean? = null, val writingSessionId: String? = null) {
  val showContext: Boolean get() = !reviewRecall && round <= 2
}

data class TodaySummary(val date: String, val dailyGoal: Int, val totalWords: Int,
  val remainingWords: Int, val learnedWords: Int, val newPlanned: Int, val newCompleted: Int,
  val reviewPlanned: Int, val reviewCompleted: Int, val carryoverPlanned: Int,
  val carryoverCompleted: Int, val todayNewWords: List<WordEntry> = emptyList(),
  val resumableWritingId: String? = null, val nextReviewDate: String? = null) {
  val planned: Int get() = newPlanned + reviewPlanned + carryoverPlanned
  val completed: Int get() = newCompleted + reviewCompleted + carryoverCompleted
  val allComplete: Boolean get() = planned > 0 && completed == planned
}
data class StudySnapshot(val today: TodaySummary, val card: StudyCard?)

data class StrokePoint(val x: Float, val y: Float)
data class TracingItem(val id: String, val glyph: String, val paths: List<String>,
  val medians: List<List<StrokePoint>>, val revision: String, val attribution: String)
enum class WritingStatus { ACTIVE, COMPLETED, SKIPPED }
enum class WritingReason { FIRST_ENCOUNTER, REVIEW_ERROR, DAILY, MANUAL }
data class WritingCursor(val wordIndex: Int, val characterIndex: Int, val strokeIndex: Int, val mistakes: Int)
data class WritingSnapshot(val id: String, val word: WordEntry, val wordIndex: Int,
  val totalWords: Int, val characterIndex: Int, val item: TracingItem?,
  val accepted: List<List<StrokePoint>>, val mistakes: Int, val status: WritingStatus,
  val reason: WritingReason, val feedback: String? = null, val returnCardId: String? = null) {
  val cursor: WritingCursor get() = WritingCursor(wordIndex, characterIndex, accepted.size, mistakes)
}

interface SettingsRepository {
  val settings: kotlinx.coroutines.flow.Flow<StudySettings>
  suspend fun save(value: StudySettings)
}
interface StudyRepository {
  val changes: kotlinx.coroutines.flow.Flow<Long>
  suspend fun snapshot(): StudySnapshot
  suspend fun start(kind: StudyKind): StudySnapshot
  suspend fun submit(cardId: String, optionId: String?): StudySnapshot
  suspend fun advance(cardId: String): StudySnapshot
  suspend fun explain(cardId: String): StudySnapshot
  suspend fun claimDailyInvitation(): Boolean
  suspend fun startDailyWriting(): String?
  suspend fun startManualWriting(wordId: String): String
}
interface WritingRepository {
  suspend fun load(sessionId: String): WritingSnapshot
  suspend fun submitStroke(sessionId: String, cursor: WritingCursor, points: List<StrokePoint>): WritingSnapshot
  suspend fun undo(sessionId: String, cursor: WritingCursor): WritingSnapshot
  suspend fun restartCharacter(sessionId: String, cursor: WritingCursor): WritingSnapshot
  suspend fun skip(sessionId: String): WritingSnapshot
}

class NewStudyDayException : IllegalStateException("A new day has started. Your daily plan has been refreshed.")
