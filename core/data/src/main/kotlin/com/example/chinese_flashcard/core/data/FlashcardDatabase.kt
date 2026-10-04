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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "words", indices = [Index("sortOrder")])
internal data class WordEntity(@PrimaryKey val id: String, val hanzi: String, val pinyin: String,
  val examplesJson: String, val partsJson: String, val note: String,
  val distractorsJson: String, val sortOrder: Int,
  @ColumnInfo(defaultValue = "0") val rarity: Int = 0)

internal data class WordIdentity(val id: String, val hanzi: String, val pinyin: String)

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
  @ColumnInfo(defaultValue = "'1'") val avatarId: String = "1")

@Entity(tableName = "app_state")
internal data class AppStateEntity(@PrimaryKey val id: Int = 1, val seeded: Boolean = false,
  val day: Long? = null, val selectedKind: String? = null, val currentCardId: String? = null,
  val revision: Long = 0)

@Entity(tableName = "daily_plans")
internal data class DailyPlanEntity(@PrimaryKey val day: Long, val dailyGoal: Int,
  val invitationClaimed: Boolean = false)

@Entity(tableName = "word_progress")
internal data class WordProgressEntity(@PrimaryKey val wordId: String,
  val firstEncounterShown: Boolean = false, val firstPassedDay: Long? = null,
  val activeCycleId: String? = null)

@Entity(tableName = "learning_cycles", indices = [Index("wordId"), Index("status")])
internal data class CycleEntity(@PrimaryKey val id: String, val wordId: String,
  val targetRounds: Int, val reviewDaysJson: String, val correctRounds: Int = 0,
  val status: String = "ACTIVE", val startedDay: Long, val passedDay: Long? = null,
  val reviewErrorWritingOffered: Boolean = false)

@Entity(tableName = "daily_items", primaryKeys = ["day", "wordId"],
  indices = [Index(value = ["day", "kind"]), Index("cycleId")])
internal data class DailyItemEntity(val day: Long, val wordId: String, val kind: String,
  val cycleId: String, val batch: Int, val queueOrder: Long, val completed: Boolean = false,
  val reviewRecall: Boolean = false)

@Entity(tableName = "review_nodes", indices = [Index(value = ["status", "dueDay"]), Index("cycleId"), Index("wordId")])
internal data class ReviewNodeEntity(@PrimaryKey val id: String, val wordId: String,
  val cycleId: String, val dueDay: Long, val status: String = "PENDING")

@Entity(tableName = "study_cards", indices = [Index(value = ["day", "kind", "phase"]), Index("wordId")])
internal data class CardEntity(@PrimaryKey val id: String, val day: Long, val wordId: String,
  val cycleId: String, val kind: String, val phase: String, val round: Int,
  val targetRounds: Int, val reviewRecall: Boolean, val optionsJson: String,
  val selectedOptionId: String? = null, val correct: Boolean? = null,
  val writingSessionId: String? = null, val createdAt: Long)

@Entity(tableName = "writing_sessions", indices = [Index("status"), Index("returnCardId")])
internal data class WritingSessionEntity(@PrimaryKey val id: String, val wordIdsJson: String,
  val wordIndex: Int = 0, val characterIndex: Int = 0, val acceptedJson: String = "[]",
  val mistakes: Int = 0, val status: String = "ACTIVE", val reason: String,
  val returnCardId: String? = null, val feedback: String? = null,
  val createdAt: Long, val day: Long, val completedAt: Long? = null)

@Entity(tableName = "writing_completions", indices = [Index("sessionId"), Index("wordId")])
internal data class WritingCompletionEntity(@PrimaryKey val id: String, val sessionId: String,
  val wordId: String, val characterPosition: Int, val tracingItemId: String,
  val revision: String, val mistakes: Int, val completedAt: Long)

@Dao
internal interface FlashcardDao {
  @Query("SELECT * FROM words ORDER BY rarity, sortOrder, id") suspend fun words(): List<WordEntity>
  @Query("SELECT id, hanzi, pinyin FROM words ORDER BY id") suspend fun wordIdentities(): List<WordIdentity>
  @Query("SELECT MAX(sortOrder) FROM words") suspend fun maximumWordOrder(): Int?
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

  @Query("SELECT * FROM settings WHERE id = 1") suspend fun settings(): SettingsEntity?
  @Query("SELECT * FROM settings WHERE id = 1") fun observeSettings(): Flow<SettingsEntity?>
  @Upsert suspend fun putSettings(value: SettingsEntity)
  @Query("SELECT * FROM app_state WHERE id = 1") suspend fun appState(): AppStateEntity?
  @Query("SELECT revision FROM app_state WHERE id = 1") fun observeRevision(): Flow<Long?>
  @Upsert suspend fun putAppState(value: AppStateEntity)

  @Query("SELECT * FROM daily_plans WHERE day = :day") suspend fun plan(day: Long): DailyPlanEntity?
  @Upsert suspend fun putPlan(value: DailyPlanEntity)
  @Query("SELECT * FROM word_progress") suspend fun progress(): List<WordProgressEntity>
  @Query("SELECT * FROM word_progress WHERE wordId = :wordId") suspend fun progress(wordId: String): WordProgressEntity?
  @Query("SELECT COUNT(*) FROM word_progress WHERE firstPassedDay IS NOT NULL") suspend fun learnedCount(): Int
  @Query("SELECT COUNT(*) FROM word_progress WHERE firstEncounterShown = 1") suspend fun startedCount(): Int
  @Upsert suspend fun putProgress(value: WordProgressEntity)
  @Query("SELECT * FROM learning_cycles WHERE id = :id") suspend fun cycle(id: String): CycleEntity?
  @Query("SELECT * FROM learning_cycles WHERE status = 'ACTIVE' ORDER BY startedDay, id") suspend fun activeCycles(): List<CycleEntity>
  @Upsert suspend fun putCycle(value: CycleEntity)
  @Query("SELECT * FROM daily_items WHERE day = :day ORDER BY kind, batch, queueOrder") suspend fun dailyItems(day: Long): List<DailyItemEntity>
  @Query("SELECT * FROM daily_items WHERE day = :day AND wordId = :wordId") suspend fun dailyItem(day: Long, wordId: String): DailyItemEntity?
  @Upsert suspend fun putDailyItem(value: DailyItemEntity)

  @Query("SELECT * FROM review_nodes WHERE status = 'PENDING' AND dueDay <= :day ORDER BY dueDay, id") suspend fun dueNodes(day: Long): List<ReviewNodeEntity>
  @Query("SELECT * FROM review_nodes WHERE cycleId = :cycleId AND status = 'PENDING'") suspend fun pendingNodes(cycleId: String): List<ReviewNodeEntity>
  @Query("SELECT MIN(dueDay) FROM review_nodes WHERE status = 'PENDING' AND dueDay > :day") suspend fun nextReviewDay(day: Long): Long?
  @Upsert suspend fun putReviewNodes(values: List<ReviewNodeEntity>)
  @Query("SELECT * FROM study_cards WHERE id = :id") suspend fun card(id: String): CardEntity?
  @Query("SELECT * FROM study_cards WHERE day = :day AND kind = :kind AND phase != 'FINISHED' ORDER BY createdAt LIMIT 1") suspend fun pendingCard(day: Long, kind: String): CardEntity?
  @Query("SELECT * FROM study_cards WHERE day = :day AND kind = :kind AND phase = 'FINISHED' ORDER BY createdAt DESC LIMIT 1") suspend fun finishedCard(day: Long, kind: String): CardEntity?
  @Query("SELECT * FROM study_cards WHERE day != :day AND phase != 'FINISHED'") suspend fun oldCards(day: Long): List<CardEntity>
  @Upsert suspend fun putCard(value: CardEntity)

  @Query("SELECT * FROM writing_sessions WHERE id = :id") suspend fun writing(id: String): WritingSessionEntity?
  @Query("SELECT * FROM writing_sessions WHERE status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1") suspend fun resumableWriting(): WritingSessionEntity?
  @Query("SELECT * FROM writing_sessions WHERE day = :day AND reason = 'DAILY' AND status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1") suspend fun dailyWriting(day: Long): WritingSessionEntity?
  @Upsert suspend fun putWriting(value: WritingSessionEntity)
  @Upsert suspend fun putWritingCompletion(value: WritingCompletionEntity)
}

@Database(entities = [WordEntity::class, MeaningEntity::class, TracingEntity::class,
  WordTracingEntity::class, SettingsEntity::class, AppStateEntity::class, DailyPlanEntity::class,
  WordProgressEntity::class, CycleEntity::class, DailyItemEntity::class, ReviewNodeEntity::class,
  CardEntity::class, WritingSessionEntity::class, WritingCompletionEntity::class],
  version = 4, exportSchema = true)
internal abstract class FlashcardDatabase : RoomDatabase() {
  abstract fun flashcards(): FlashcardDao
}

internal val FLASHCARD_MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE words ADD COLUMN rarity INTEGER NOT NULL DEFAULT 0")
  }
}

internal val FLASHCARD_MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
  }
}

internal val FLASHCARD_MIGRATION_3_4 = object : Migration(3, 4) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN avatarId TEXT NOT NULL DEFAULT '1'")
  }
}
