package com.example.chinese_flashcard.core.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "words", indices = [Index("sortOrder")])
internal data class WordEntity(@PrimaryKey val id: String, val hanzi: String, val pinyin: String,
  val examplesJson: String, val partsJson: String,
  val distractorsJson: String, val sortOrder: Int,
  @ColumnInfo(defaultValue = "0") val rarity: Int = 0,
  @ColumnInfo(defaultValue = "'[]'") val literalExplanationsJson: String = "[]",
  @ColumnInfo(defaultValue = "'[]'") val figurativeExplanationsJson: String = "[]",
  @ColumnInfo(defaultValue = "''") val presetSourceId: String = "")

internal data class WordIdentity(val id: String, val hanzi: String, val pinyin: String)

internal data class WordStudyRow(val id: String, val rarity: Int)

internal data class WordlistRow(val id: String, val hanzi: String, val pinyin: String,
  val english: String, val partOfSpeech: String, val searchMeanings: String, val difficulty: Int,
  val firstEncounterShown: Boolean, val firstPassedDay: Long?,
  val correctRounds: Int, val targetRounds: Int,
  val isCollected: Boolean, val isSkipped: Boolean, val mistakePending: Boolean)

@Entity(tableName = "meanings", indices = [Index("wordId")])
internal data class MeaningEntity(@PrimaryKey val id: String, val wordId: String,
  val english: String, val partOfSpeech: String, val position: Int)

@Entity(tableName = "tracing_items", indices = [Index(value = ["glyph"], unique = true)])
internal data class TracingEntity(@PrimaryKey val id: String, val glyph: String,
  val pathsJson: String, val mediansJson: String, val revision: String, val attribution: String)

/** Position is part of the key: 谢谢 refers to the same tracing item twice. */
@Entity(tableName = "word_tracing", primaryKeys = ["wordId", "position"], indices = [Index("itemId")],
  foreignKeys = [ForeignKey(entity = WordEntity::class, parentColumns = ["id"], childColumns = ["wordId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = TracingEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.RESTRICT)])
internal data class WordTracingEntity(val wordId: String, val position: Int, val itemId: String)

@Entity(tableName = "settings")
internal data class SettingsEntity(@PrimaryKey val id: Int = 1, val dailyWords: Int = 10,
  val rounds: Int = 4, val reviewDaysJson: String = "[1,3,7]", val welcomed: Boolean = false,
  @ColumnInfo(defaultValue = "''") val displayName: String = "",
  @ColumnInfo(defaultValue = "'1'") val avatarId: String = "1",
  @ColumnInfo(defaultValue = "'SYSTEM'") val themeMode: String = "SYSTEM",
  @ColumnInfo(defaultValue = "'EN'") val uiLanguage: String = "EN",
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN",
  @ColumnInfo(defaultValue = "'{}'") val speechModesJson: String = "{}",
  @ColumnInfo(defaultValue = "15") val explanationMask: Int = 15,
  @ColumnInfo(defaultValue = "5") val speechPreloadWords: Int = 5,
  @ColumnInfo(defaultValue = "256") val speechCacheMiB: Int = 256,
  @ColumnInfo(defaultValue = "5") val homeShuffleQueueSize: Int = 5,
  @ColumnInfo(defaultValue = "0") val waitForCorrectAnswerAudio: Boolean = false,
  @ColumnInfo(defaultValue = "10") val reviewBatchSize: Int = 10)

@Entity(tableName = "app_state")
internal data class AppStateEntity(@PrimaryKey val id: Int = 1, val seeded: Boolean = false,
  val day: Long? = null, val selectedKind: String? = null, val currentCardId: String? = null,
  val revision: Long = 0,
  @ColumnInfo(defaultValue = "''") val presetCsvVersion: String = "",
  val selectedStage: Int? = null,
  @ColumnInfo(defaultValue = "0") val growthDays: Long = 0,
  @ColumnInfo(defaultValue = "0") val learnedCompletions: Long = 0,
  @ColumnInfo(defaultValue = "0") val clearedMistakes: Long = 0,
  @ColumnInfo(defaultValue = "0") val contextVersion: Long = 0)

@Entity(tableName = "daily_plans", primaryKeys = ["day", "learningLanguage"])
internal data class DailyPlanEntity(val day: Long, val dailyGoal: Int,
  val invitationClaimed: Boolean = false,
  @ColumnInfo(defaultValue = "0") val loginRewarded: Boolean = false,
  @ColumnInfo(defaultValue = "0") val learnRewarded: Boolean = false,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "word_progress", primaryKeys = ["wordId", "learningLanguage"])
internal data class WordProgressEntity(val wordId: String,
  val firstEncounterShown: Boolean = false, val firstPassedDay: Long? = null,
  val activeCycleId: String? = null,
  @ColumnInfo(defaultValue = "0") val isCollected: Boolean = false,
  @ColumnInfo(defaultValue = "0") val isSkipped: Boolean = false,
  @ColumnInfo(defaultValue = "0") val mistakePending: Boolean = false,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "stage_states", primaryKeys = ["stage", "learningLanguage"])
internal data class StageStateEntity(val stage: Int, val lap: Int = 1,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "stage_completions", primaryKeys = ["stage", "lap", "wordId", "learningLanguage"])
internal data class StageCompletionEntity(val stage: Int, val lap: Int, val wordId: String,
  val completedDay: Long,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "stage_daily_plans", primaryKeys = ["day", "stage", "learningLanguage"])
internal data class StageDailyPlanEntity(val day: Long, val stage: Int, val dailyGoal: Int,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "learning_cycles", indices = [Index("wordId"), Index("status")])
internal data class CycleEntity(@PrimaryKey val id: String, val wordId: String,
  val targetRounds: Int, val reviewDaysJson: String, val correctRounds: Int = 0,
  val status: String = "ACTIVE", val startedDay: Long, val passedDay: Long? = null,
  val reviewErrorWritingOffered: Boolean = false,
  @ColumnInfo(defaultValue = "'NORMAL'") val scope: String = "NORMAL",
  val originStage: Int? = null, val originLap: Int? = null,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "daily_items",
  indices = [Index(value = ["day", "kind"]), Index("cycleId")])
internal data class DailyItemEntity(val day: Long, val wordId: String, val kind: String,
  val cycleId: String, val batch: Int, val queueOrder: Long, val completed: Boolean = false,
  val reviewRecall: Boolean = false,
  @PrimaryKey val id: String = "$day:$kind:$cycleId:$wordId",
  val originStage: Int? = null, val originLap: Int? = null,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "practice_sessions", indices = [Index(value = ["kind", "status"])])
internal data class PracticeSessionEntity(@PrimaryKey val id: String, val kind: String,
  val status: String = "ACTIVE", val lap: Int = 1, val createdAt: Long,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "practice_items", primaryKeys = ["sessionId", "wordId"],
  indices = [Index("cycleId")])
internal data class PracticeItemEntity(val sessionId: String, val wordId: String,
  val cycleId: String, val queueOrder: Long, val completed: Boolean = false,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "review_nodes", indices = [Index(value = ["status", "dueDay"]), Index("cycleId"), Index("wordId")])
internal data class ReviewNodeEntity(@PrimaryKey val id: String, val wordId: String,
  val cycleId: String, val dueDay: Long, val status: String = "PENDING",
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "study_cards", indices = [Index(value = ["day", "kind", "phase"]), Index("wordId"), Index("sessionId")])
internal data class CardEntity(@PrimaryKey val id: String, val day: Long, val wordId: String,
  val cycleId: String, val kind: String, val phase: String, val round: Int,
  val targetRounds: Int, val reviewRecall: Boolean, val optionsJson: String,
  val selectedOptionId: String? = null, val correct: Boolean? = null,
  val writingSessionId: String? = null, val createdAt: Long,
  val itemId: String? = null, val sessionId: String? = null,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN",
  @ColumnInfo(defaultValue = "''") val questionMeaningId: String = "")

@Entity(tableName = "writing_sessions", indices = [Index("status"), Index("returnCardId")])
internal data class WritingSessionEntity(@PrimaryKey val id: String, val wordIdsJson: String,
  val wordIndex: Int = 0, val characterIndex: Int = 0, val acceptedJson: String = "[]",
  val mistakes: Int = 0, val status: String = "ACTIVE", val reason: String,
  val returnCardId: String? = null, val feedback: String? = null,
  val createdAt: Long, val day: Long, val completedAt: Long? = null,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "writing_completions", indices = [Index("sessionId"), Index("wordId")])
internal data class WritingCompletionEntity(@PrimaryKey val id: String, val sessionId: String,
  val wordId: String, val characterPosition: Int, val tracingItemId: String,
  val revision: String, val mistakes: Int, val completedAt: Long,
  @ColumnInfo(defaultValue = "'CN'") val learningLanguage: String = "CN")

@Entity(tableName = "learning_contexts")
internal data class LearningContextEntity(@PrimaryKey val learningLanguage: String,
  val day: Long? = null, val selectedKind: String? = null, val currentCardId: String? = null,
  val selectedStage: Int? = null)

@Entity(tableName = "word_bookmarks")
internal data class WordBookmarkEntity(@PrimaryKey val wordId: String, val isCollected: Boolean = false)

@Entity(tableName = "daily_growth_rewards")
internal data class DailyGrowthRewardEntity(@PrimaryKey val day: Long,
  val loginRewarded: Boolean = false, val learnRewarded: Boolean = false)

@Dao
internal interface FlashcardDao {
  @Query("""
    SELECT w.id, w.hanzi, w.pinyin, w.rarity AS difficulty,
      COALESCE((SELECT english FROM meanings WHERE wordId = w.id ORDER BY position, id LIMIT 1), '') AS english,
      COALESCE((SELECT partOfSpeech FROM meanings WHERE wordId = w.id ORDER BY position, id LIMIT 1), '') AS partOfSpeech,
      COALESCE(GROUP_CONCAT(m.english, ' '), '') AS searchMeanings,
      COALESCE(p.firstEncounterShown, 0) AS firstEncounterShown, p.firstPassedDay,
      COALESCE(c.correctRounds, 0) AS correctRounds, COALESCE(c.targetRounds, 0) AS targetRounds,
      COALESCE(b.isCollected, 0) AS isCollected, COALESCE(p.isSkipped, 0) AS isSkipped,
      COALESCE(p.mistakePending, 0) AS mistakePending
    FROM words w
    LEFT JOIN word_progress p ON p.wordId = w.id AND p.learningLanguage = :language
    LEFT JOIN learning_cycles c ON c.id = p.activeCycleId AND c.status = 'ACTIVE' AND c.learningLanguage = :language
    LEFT JOIN word_bookmarks b ON b.wordId = w.id
    LEFT JOIN meanings m ON m.wordId = w.id
    GROUP BY w.id
    ORDER BY w.rarity, w.sortOrder, w.id
  """)
  fun observeWordlist(language: String): Flow<List<WordlistRow>>
  @Query("SELECT * FROM words ORDER BY rarity, sortOrder, id") suspend fun words(): List<WordEntity>
  @Query("SELECT id, rarity FROM words ORDER BY rarity, sortOrder, id") suspend fun studyWords(): List<WordStudyRow>
  @Query("SELECT id, hanzi, pinyin FROM words ORDER BY id") suspend fun wordIdentities(): List<WordIdentity>
  @Query("UPDATE words SET sortOrder = :rank WHERE id = :id AND sortOrder != :rank")
  suspend fun updateWordOrder(id: String, rank: Int)
  @Query("UPDATE words SET rarity = :rarity WHERE id IN (:ids) AND rarity != :rarity")
  suspend fun updateDefaultRarity(ids: List<String>, rarity: Int): Int
  @Query("SELECT * FROM meanings ORDER BY wordId, position") suspend fun allMeanings(): List<MeaningEntity>
  @Query("SELECT id FROM tracing_items") suspend fun tracingIds(): List<String>
  @Query("SELECT * FROM words WHERE id = :id") suspend fun word(id: String): WordEntity?
  @Query("SELECT COUNT(*) FROM words") suspend fun wordCount(): Int
  @Query("SELECT * FROM meanings WHERE wordId = :wordId ORDER BY position") suspend fun meanings(wordId: String): List<MeaningEntity>
  @Query("SELECT * FROM meanings WHERE id = :id") suspend fun meaning(id: String): MeaningEntity?
  @Query("SELECT * FROM tracing_items WHERE id = :id") suspend fun tracing(id: String): TracingEntity?
  @Query("SELECT * FROM word_tracing WHERE wordId = :wordId ORDER BY position") suspend fun wordTracing(wordId: String): List<WordTracingEntity>
  @Upsert suspend fun putWords(values: List<WordEntity>)
  @Upsert suspend fun putMeanings(values: List<MeaningEntity>)
  @Upsert suspend fun putTracing(values: List<TracingEntity>)
  @Upsert suspend fun putWordTracing(values: List<WordTracingEntity>)

  @Query("SELECT * FROM learning_contexts WHERE learningLanguage = :language")
  suspend fun learningContext(language: String): LearningContextEntity?
  @Upsert suspend fun putLearningContext(value: LearningContextEntity)
  @Query("SELECT * FROM word_bookmarks") suspend fun bookmarks(): List<WordBookmarkEntity>
  @Query("SELECT * FROM word_bookmarks WHERE wordId = :wordId") suspend fun bookmark(wordId: String): WordBookmarkEntity?
  @Query("SELECT * FROM word_bookmarks WHERE wordId = :wordId") fun observeBookmark(wordId: String): Flow<WordBookmarkEntity?>
  @Upsert suspend fun putBookmark(value: WordBookmarkEntity)
  @Query("SELECT * FROM daily_growth_rewards WHERE day = :day") suspend fun growthReward(day: Long): DailyGrowthRewardEntity?
  @Upsert suspend fun putGrowthReward(value: DailyGrowthRewardEntity)
  @Query("SELECT * FROM study_cards WHERE phase != 'FINISHED'") suspend fun unfinishedCardsAll(): List<CardEntity>

  @Query("SELECT * FROM settings WHERE id = 1") suspend fun settings(): SettingsEntity?
  @Query("SELECT * FROM settings WHERE id = 1") fun observeSettings(): Flow<SettingsEntity?>
  @Upsert suspend fun putSettings(value: SettingsEntity)
  @Query("SELECT * FROM app_state WHERE id = 1") suspend fun appState(): AppStateEntity?
  @Query("SELECT revision FROM app_state WHERE id = 1") fun observeRevision(): Flow<Long?>
  @Upsert suspend fun putAppState(value: AppStateEntity)

  @Query("SELECT * FROM daily_plans WHERE learningLanguage = :language AND day = :day") suspend fun plan(day: Long, language: String): DailyPlanEntity?
  @Upsert suspend fun putPlan(value: DailyPlanEntity)
  @Query("SELECT * FROM word_progress WHERE learningLanguage = :language") suspend fun progress(language: String): List<WordProgressEntity>
  @Query("SELECT * FROM word_progress WHERE learningLanguage = :language AND wordId = :wordId") suspend fun progress(wordId: String, language: String): WordProgressEntity?
  @Query("SELECT * FROM word_progress WHERE learningLanguage = :language AND wordId = :wordId") fun observeWordProgress(wordId: String, language: String): Flow<WordProgressEntity?>
  @Query("SELECT COUNT(*) FROM word_progress WHERE learningLanguage = :language AND firstPassedDay IS NOT NULL") suspend fun learnedCount(language: String): Int
  @Query("SELECT COUNT(*) FROM word_progress WHERE learningLanguage = :language AND firstEncounterShown = 1") suspend fun startedCount(language: String): Int
  @Upsert suspend fun putProgress(value: WordProgressEntity)
  @Query("SELECT * FROM learning_cycles WHERE learningLanguage = :language AND id = :id") suspend fun cycle(id: String, language: String): CycleEntity?
  @Query("SELECT * FROM learning_cycles WHERE learningLanguage = :language AND status = 'ACTIVE' ORDER BY startedDay, id") suspend fun activeCycles(language: String): List<CycleEntity>
  @Query("""SELECT * FROM learning_cycles WHERE learningLanguage = :language AND rowid IN
    (SELECT MAX(rowid) FROM learning_cycles WHERE learningLanguage = :language
      AND scope = 'NORMAL' AND status = 'COMPLETED' GROUP BY wordId)""")
  suspend fun learnedReviewCycles(language: String): List<CycleEntity>
  @Upsert suspend fun putCycle(value: CycleEntity)
  @Query("SELECT * FROM daily_items WHERE learningLanguage = :language AND day = :day ORDER BY kind, batch, queueOrder") suspend fun dailyItems(day: Long, language: String): List<DailyItemEntity>
  @Query("SELECT * FROM daily_items WHERE learningLanguage = :language AND day = :day AND wordId = :wordId ORDER BY queueOrder LIMIT 1") suspend fun dailyItem(day: Long, wordId: String, language: String): DailyItemEntity?
  @Query("SELECT * FROM daily_items WHERE learningLanguage = :language AND id = :id") suspend fun dailyItemById(id: String, language: String): DailyItemEntity?
  @Query("SELECT * FROM daily_items WHERE learningLanguage = :language AND cycleId = :cycleId ORDER BY day DESC, queueOrder DESC LIMIT 1")
  suspend fun latestDailyItemForCycle(cycleId: String, language: String): DailyItemEntity?
  @Upsert suspend fun putDailyItem(value: DailyItemEntity)

  @Query("SELECT * FROM stage_states WHERE learningLanguage = :language AND stage = :stage") suspend fun stageState(stage: Int, language: String): StageStateEntity?
  @Query("SELECT * FROM stage_states WHERE learningLanguage = :language ORDER BY stage") suspend fun stageStates(language: String): List<StageStateEntity>
  @Upsert suspend fun putStageState(value: StageStateEntity)
  @Query("SELECT * FROM stage_completions WHERE learningLanguage = :language AND stage = :stage AND lap = :lap ORDER BY completedDay, wordId")
  suspend fun stageCompletions(stage: Int, lap: Int, language: String): List<StageCompletionEntity>
  @Upsert suspend fun putStageCompletion(value: StageCompletionEntity)
  @Query("SELECT * FROM stage_daily_plans WHERE learningLanguage = :language AND day = :day AND stage = :stage") suspend fun stagePlan(day: Long, stage: Int, language: String): StageDailyPlanEntity?
  @Upsert suspend fun putStagePlan(value: StageDailyPlanEntity)

  @Query("SELECT * FROM practice_sessions WHERE learningLanguage = :language AND id = :id") suspend fun practiceSession(id: String, language: String): PracticeSessionEntity?
  @Query("SELECT * FROM practice_sessions WHERE learningLanguage = :language AND kind = :kind AND status = 'ACTIVE' ORDER BY createdAt DESC, id DESC LIMIT 1")
  suspend fun activePracticeSession(kind: String, language: String): PracticeSessionEntity?
  @Query("SELECT * FROM practice_sessions WHERE learningLanguage = :language AND kind = :kind ORDER BY lap, createdAt, id")
  suspend fun practiceSessions(kind: String, language: String): List<PracticeSessionEntity>
  @Upsert suspend fun putPracticeSession(value: PracticeSessionEntity)
  @Query("SELECT * FROM practice_items WHERE learningLanguage = :language AND sessionId = :sessionId ORDER BY queueOrder, wordId")
  suspend fun practiceItems(sessionId: String, language: String): List<PracticeItemEntity>
  @Query("SELECT * FROM practice_items WHERE learningLanguage = :language AND sessionId = :sessionId AND wordId = :wordId")
  suspend fun practiceItem(sessionId: String, wordId: String, language: String): PracticeItemEntity?
  @Upsert suspend fun putPracticeItem(value: PracticeItemEntity)

  @Query("SELECT * FROM review_nodes WHERE learningLanguage = :language AND status = 'PENDING' AND dueDay <= :day ORDER BY dueDay, id") suspend fun dueNodes(day: Long, language: String): List<ReviewNodeEntity>
  @Query("SELECT * FROM review_nodes WHERE learningLanguage = :language AND cycleId = :cycleId AND status = 'PENDING'") suspend fun pendingNodes(cycleId: String, language: String): List<ReviewNodeEntity>
  @Query("""
    SELECT MIN(n.dueDay) FROM review_nodes n
    LEFT JOIN word_progress p ON p.wordId = n.wordId AND p.learningLanguage = :language
    WHERE n.learningLanguage = :language AND n.status = 'PENDING' AND n.dueDay > :day AND COALESCE(p.isSkipped, 0) = 0
  """) suspend fun nextReviewDay(day: Long, language: String): Long?
  @Upsert suspend fun putReviewNodes(values: List<ReviewNodeEntity>)
  @Query("SELECT * FROM study_cards WHERE learningLanguage = :language AND id = :id") suspend fun card(id: String, language: String): CardEntity?
  @Query("SELECT * FROM study_cards WHERE learningLanguage = :language AND day = :day AND kind = :kind AND phase != 'FINISHED' ORDER BY createdAt LIMIT 1") suspend fun pendingCard(day: Long, kind: String, language: String): CardEntity?
  @Query("SELECT * FROM study_cards WHERE learningLanguage = :language AND day = :day AND kind = :kind AND phase = 'FINISHED' ORDER BY createdAt DESC LIMIT 1") suspend fun finishedCard(day: Long, kind: String, language: String): CardEntity?
  @Query("SELECT * FROM study_cards WHERE learningLanguage = :language AND day != :day AND phase != 'FINISHED'") suspend fun oldCards(day: Long, language: String): List<CardEntity>
  @Query("SELECT * FROM study_cards WHERE learningLanguage = :language AND phase != 'FINISHED'") suspend fun unfinishedCards(language: String): List<CardEntity>
  @Query("SELECT * FROM study_cards WHERE learningLanguage = :language AND sessionId = :sessionId AND phase != 'FINISHED' ORDER BY createdAt, id LIMIT 1")
  suspend fun pendingPracticeCard(sessionId: String, language: String): CardEntity?
  @Query("SELECT * FROM study_cards WHERE learningLanguage = :language AND sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT 1")
  suspend fun latestPracticeCard(sessionId: String, language: String): CardEntity?
  @Query("SELECT DISTINCT wordId FROM study_cards WHERE learningLanguage = :language AND day = :day AND kind = :kind AND sessionId IS NULL AND optionsJson = '[]' AND phase = 'FINISHED'")
  suspend fun viewOnlyDailyWords(day: Long, kind: String, language: String): List<String>
  @Query("SELECT DISTINCT wordId FROM study_cards WHERE learningLanguage = :language AND sessionId = :sessionId AND optionsJson = '[]' AND phase = 'FINISHED'")
  suspend fun viewOnlyPracticeWords(sessionId: String, language: String): List<String>
  @Upsert suspend fun putCard(value: CardEntity)

  @Query("SELECT * FROM writing_sessions WHERE learningLanguage = :language AND id = :id") suspend fun writing(id: String, language: String): WritingSessionEntity?
  @Query("SELECT * FROM writing_sessions WHERE learningLanguage = :language AND status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1") suspend fun resumableWriting(language: String): WritingSessionEntity?
  @Query("SELECT * FROM writing_sessions WHERE learningLanguage = :language AND day = :day AND reason = 'DAILY' AND status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1") suspend fun dailyWriting(day: Long, language: String): WritingSessionEntity?
  @Upsert suspend fun putWriting(value: WritingSessionEntity)
  @Upsert suspend fun putWritingCompletion(value: WritingCompletionEntity)
}

@Database(entities = [WordEntity::class, MeaningEntity::class, TracingEntity::class,
  WordTracingEntity::class, SettingsEntity::class, AppStateEntity::class, DailyPlanEntity::class,
  WordProgressEntity::class, CycleEntity::class, DailyItemEntity::class, ReviewNodeEntity::class,
  CardEntity::class, WritingSessionEntity::class, WritingCompletionEntity::class,
  StageStateEntity::class, StageCompletionEntity::class, StageDailyPlanEntity::class,
  PracticeSessionEntity::class, PracticeItemEntity::class,
  LearningContextEntity::class, WordBookmarkEntity::class, DailyGrowthRewardEntity::class],
  version = 15, exportSchema = true)
internal abstract class FlashcardDatabase : RoomDatabase() {
  abstract fun flashcards(): FlashcardDao
}
