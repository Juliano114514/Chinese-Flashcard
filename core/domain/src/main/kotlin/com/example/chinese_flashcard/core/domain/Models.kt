package com.example.chinese_flashcard.core.domain

data class Meaning(val id: String, val english: String, val partOfSpeech: String = "")
data class ExampleChunk(val hanzi: String, val pinyin: String, val gloss: String)
data class ExampleSentence(val hanzi: String, val pinyin: String, val english: String,
  val chunks: List<ExampleChunk>)
data class WordPart(val hanzi: String, val pinyin: String, val gloss: String)
data class WordEntry(val id: String, val hanzi: String, val pinyin: String,
  val meanings: List<Meaning>, val examples: List<ExampleSentence>, val parts: List<WordPart>,
  val distractorMeaningIds: List<String>,
  val literalExplanations: List<String> = emptyList(), val figurativeExplanations: List<String> = emptyList())

data class StudySettings(val dailyWords: Int = 10, val rounds: Int = 4,
  val reviewDays: List<Int> = listOf(1, 3, 7), val welcomed: Boolean = false,
  val displayName: String = "", val avatarId: String = "1") {
  fun validate() {
    require(dailyWords in 1..100 && rounds in 2..8)
    require(reviewDays.isNotEmpty() && reviewDays == reviewDays.distinct().sorted())
    require(reviewDays.all { it in listOf(1, 3, 5, 7, 14, 30) })
    require(displayName.length <= 40 && displayName.none { it.isISOControl() })
    require(avatarId.length <= 40 && avatarId.none { it.isISOControl() })
  }
}

enum class StudyKind { NEW, REVIEW, CARRYOVER, COLLECTION, MISTAKES }
enum class CardPhase { INTRO, QUESTION, FEEDBACK, EXPLANATION, WRITING, FINISHED }
data class AnswerOption(val id: String, val english: String, val hanzi: String = "", val pinyin: String = "")
data class StudyCard(val id: String, val word: WordEntry, val kind: StudyKind,
  val phase: CardPhase, val round: Int, val targetRounds: Int, val reviewRecall: Boolean,
  val options: List<AnswerOption>, val selectedOptionId: String? = null,
  val correct: Boolean? = null, val writingSessionId: String? = null,
  val isCollected: Boolean = false, val isSkipped: Boolean = false,
  val sessionId: String? = null) {
  val showContext: Boolean get() = !reviewRecall && round <= 2
}

data class TodaySummary(val date: String, val dailyGoal: Int, val totalWords: Int,
  val remainingWords: Int, val learnedWords: Int, val newPlanned: Int, val newCompleted: Int,
  val reviewPlanned: Int, val reviewCompleted: Int, val carryoverPlanned: Int,
  val carryoverCompleted: Int, val todayNewWords: List<WordEntry> = emptyList(),
  val resumableWritingId: String? = null, val nextReviewDate: String? = null,
  val availableNewWords: Int = 0, val learnMoreBlocker: StudyKind? = null,
  val stageProgress: StageProgress = StageProgress(),
  val collectionsAvailable: Int = 0, val mistakesAvailable: Int = 0) {
  val planned: Int get() = newPlanned + reviewPlanned + carryoverPlanned
  val completed: Int get() = newCompleted + reviewCompleted + carryoverCompleted
  val allComplete: Boolean get() = planned > 0 && completed == planned
  val showLearnMore: Boolean get() = newCompleted == newPlanned
  val canLearnMore: Boolean get() = showLearnMore && completed == planned &&
    learnMoreBlocker == null && availableNewWords > 0
}
data class PracticeProgress(val kind: StudyKind, val completed: Int, val planned: Int,
  val paused: Boolean = false)
data class StudySnapshot(val today: TodaySummary, val card: StudyCard?,
  val practice: PracticeProgress? = null)

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
  suspend fun selectStage(stage: VocabularyStage): StudySnapshot
  suspend fun learnMore(expectedDate: String, expectedNewPlanned: Int,
    expectedStage: VocabularyStage, expectedLap: Int): StudySnapshot
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
