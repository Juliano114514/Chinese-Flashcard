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

data class StudySettings(val dailyWords: Int = 10, val rounds: Int = StudySettingsRules.DEFAULT_ROUNDS,
  val reviewDays: List<Int> = StudySettingsRules.defaultReviewDays, val welcomed: Boolean = false,
  val displayName: String = "", val avatarId: String = "1", val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val uiLanguage: AppLanguage = AppLanguage.EN, val learningLanguage: AppLanguage = AppLanguage.CN,
  val speechOptions: SpeechOptions = SpeechOptions(), val explanationOptions: ExplanationOptions = ExplanationOptions(),
  val speechPreloadWords: Int = StudySettingsRules.DEFAULT_SPEECH_PRELOAD_WORDS,
  val speechCacheMiB: Int = StudySettingsRules.DEFAULT_SPEECH_CACHE_MIB,
  val homeShuffleQueueSize: Int = StudySettingsRules.DEFAULT_HOME_SHUFFLE_QUEUE_SIZE,
  val waitForCorrectAnswerAudio: Boolean = false) {
  fun validate() {
    require(dailyWords in StudySettingsRules.storedDailyWords && rounds in StudySettingsRules.rounds)
    require(reviewDays.isNotEmpty() && reviewDays == reviewDays.distinct().sorted())
    require(reviewDays.all { it in StudySettingsRules.reviewDays })
    require(displayName.length <= 40 && displayName.none { it.isISOControl() })
    require(avatarId.length <= 40 && avatarId.none { it.isISOControl() })
    require(speechPreloadWords in StudySettingsRules.speechPreloadWords)
    require(speechCacheMiB in StudySettingsRules.speechCacheMiB)
    require(homeShuffleQueueSize in StudySettingsRules.homeShuffleQueueSize)
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
  val sessionId: String? = null, val learningLanguage: AppLanguage = AppLanguage.CN,
  val questionMeaningId: String = word.meanings.firstOrNull()?.id.orEmpty(),
  val contextVersion: Long = 0) {
  val showContext: Boolean get() = !reviewRecall && round <= 2
  val canAnswer: Boolean get() = options.size == 4
}

data class TodaySummary(val date: String, val dailyGoal: Int, val totalWords: Int,
  val remainingWords: Int, val learnedWords: Int, val newPlanned: Int, val newCompleted: Int,
  val reviewPlanned: Int, val reviewCompleted: Int, val carryoverPlanned: Int,
  val carryoverCompleted: Int, val todayNewWords: List<WordEntry> = emptyList(),
  val resumableWritingId: String? = null, val nextReviewDate: String? = null,
  val availableNewWords: Int = 0, val learnMoreBlocker: StudyKind? = null,
  val stageProgress: StageProgress = StageProgress(),
  val collectionsAvailable: Int = 0, val mistakesAvailable: Int = 0,
  val growth: StudyGrowth = StudyGrowth(), val learningLanguage: AppLanguage = AppLanguage.CN,
  val contextVersion: Long = 0) {
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
  val practice: PracticeProgress? = null, val revision: Long = 0,
  val learningLanguage: AppLanguage = today.learningLanguage, val contextVersion: Long = today.contextVersion,
  val notice: UiMessageKey? = if (card != null && !card.canAnswer) {
    if (card.phase == CardPhase.FINISHED)
      UiMessageKey.NO_RELIABLE_QUESTION_IS_AVAILABLE_FOR_THIS_WORD_NO_LEARNING_PROGRESS_WAS_RECORDED
    else UiMessageKey.NO_RELIABLE_QUESTION_IS_AVAILABLE_FOR_THIS_WORD_VIEWING_IT_DOES_NOT_COUNT_AS_LEARNED
  } else null)

data class StrokePoint(val x: Float, val y: Float)
data class TracingItem(val id: String, val glyph: String, val paths: List<String>,
  val medians: List<List<StrokePoint>>, val revision: String, val attribution: String)
enum class WritingStatus { ACTIVE, COMPLETED, SKIPPED }
enum class WritingReason { FIRST_ENCOUNTER, REVIEW_ERROR, DAILY, MANUAL }
data class WritingCursor(val wordIndex: Int, val characterIndex: Int, val strokeIndex: Int, val mistakes: Int)
data class WritingSnapshot(val id: String, val word: WordEntry, val wordIndex: Int,
  val totalWords: Int, val characterIndex: Int, val item: TracingItem?,
  val accepted: List<List<StrokePoint>>, val mistakes: Int, val status: WritingStatus,
  val reason: WritingReason, val feedback: UiMessageKey? = null, val returnCardId: String? = null,
  val learningLanguage: AppLanguage = AppLanguage.CN, val contextVersion: Long = 0) {
  val cursor: WritingCursor get() = WritingCursor(wordIndex, characterIndex, accepted.size, mistakes)
}

interface SettingsRepository {
  val settings: kotlinx.coroutines.flow.Flow<StudySettings>
  suspend fun save(value: StudySettings)
}
interface StudyRepository {
  val changes: kotlinx.coroutines.flow.Flow<Long>
  suspend fun context(): LearningContext
  suspend fun snapshot(): StudySnapshot
  /** Current word and up to nextCount distinct words from the existing queue; never advances study. */
  suspend fun peekSpeechWords(cardId: String, nextCount: Int, expectedLanguage: AppLanguage,
    expectedContextVersion: Long): List<WordEntry>
  /** Up to maxWords from the saved Learn progress, including its resumable card; never creates a plan. */
  suspend fun peekLearnSpeechWords(maxWords: Int, expectedLanguage: AppLanguage,
    expectedContextVersion: Long, expectedStage: VocabularyStage): List<WordEntry>
  /** First words of the currently available Home entries; never creates or advances study work. */
  suspend fun peekEntrySpeechWords(expectedLanguage: AppLanguage, expectedContextVersion: Long): List<WordEntry>
  suspend fun start(kind: StudyKind, expectedLanguage: AppLanguage? = null): StudySnapshot
  suspend fun selectStage(stage: VocabularyStage, expectedLanguage: AppLanguage? = null): StudySnapshot
  suspend fun learnMore(expectedDate: String, expectedNewPlanned: Int,
    expectedStage: VocabularyStage, expectedLap: Int, expectedLanguage: AppLanguage? = null): StudySnapshot
  suspend fun submit(cardId: String, optionId: String?, expectedLanguage: AppLanguage? = null,
    expectedContextVersion: Long? = null): StudySnapshot
  suspend fun advance(cardId: String, expectedLanguage: AppLanguage? = null,
    expectedContextVersion: Long? = null): StudySnapshot
  suspend fun explain(cardId: String, expectedLanguage: AppLanguage? = null,
    expectedContextVersion: Long? = null): StudySnapshot
  suspend fun claimDailyInvitation(expectedLanguage: AppLanguage? = null): Boolean
  suspend fun startDailyWriting(expectedLanguage: AppLanguage? = null): String?
  suspend fun startManualWriting(wordId: String, expectedLanguage: AppLanguage? = null,
    expectedContextVersion: Long? = null): String
}
interface WritingRepository {
  suspend fun load(sessionId: String): WritingSnapshot
  suspend fun submitStroke(sessionId: String, cursor: WritingCursor, points: List<StrokePoint>,
    expectedLanguage: AppLanguage? = null, expectedContextVersion: Long? = null): WritingSnapshot
  suspend fun undo(sessionId: String, cursor: WritingCursor, expectedLanguage: AppLanguage? = null,
    expectedContextVersion: Long? = null): WritingSnapshot
  suspend fun restartCharacter(sessionId: String, cursor: WritingCursor, expectedLanguage: AppLanguage? = null,
    expectedContextVersion: Long? = null): WritingSnapshot
  suspend fun skip(sessionId: String, expectedLanguage: AppLanguage? = null,
    expectedContextVersion: Long? = null): WritingSnapshot
}

class NewStudyDayException : UiMessageException(UiMessageKey.A_NEW_DAY_HAS_STARTED_YOUR_DAILY_PLAN_HAS_BEEN_REFRESHED)
