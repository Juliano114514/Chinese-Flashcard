package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing learning belongs to CN; bookmarks and calendar rewards remain shared. */
internal object Migration9To10 : Migration(9, 10) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN uiLanguage TEXT NOT NULL DEFAULT 'EN'")
    db.execSQL("ALTER TABLE settings ADD COLUMN learningLanguage TEXT NOT NULL DEFAULT 'CN'")
    db.execSQL("ALTER TABLE app_state ADD COLUMN contextVersion INTEGER NOT NULL DEFAULT 0")
    db.execSQL("CREATE TABLE IF NOT EXISTS learning_contexts (learningLanguage TEXT NOT NULL, day INTEGER, selectedKind TEXT, currentCardId TEXT, selectedStage INTEGER, PRIMARY KEY(learningLanguage))")
    db.execSQL("INSERT INTO learning_contexts SELECT 'CN', day, selectedKind, currentCardId, selectedStage FROM app_state WHERE id = 1")
    db.execSQL("INSERT OR IGNORE INTO learning_contexts (learningLanguage) VALUES ('EN')")
    db.execSQL("CREATE TABLE IF NOT EXISTS word_bookmarks (wordId TEXT NOT NULL, isCollected INTEGER NOT NULL, PRIMARY KEY(wordId))")
    db.execSQL("INSERT INTO word_bookmarks SELECT wordId, isCollected FROM word_progress WHERE isCollected = 1")
    db.execSQL("CREATE TABLE IF NOT EXISTS daily_growth_rewards (day INTEGER NOT NULL, loginRewarded INTEGER NOT NULL, learnRewarded INTEGER NOT NULL, PRIMARY KEY(day))")
    db.execSQL("INSERT INTO daily_growth_rewards SELECT day, loginRewarded, learnRewarded FROM daily_plans")
    db.execSQL("CREATE TABLE IF NOT EXISTS `daily_plans_v10` (`day` INTEGER NOT NULL, `dailyGoal` INTEGER NOT NULL, `invitationClaimed` INTEGER NOT NULL, `loginRewarded` INTEGER NOT NULL DEFAULT 0, `learnRewarded` INTEGER NOT NULL DEFAULT 0, `learningLanguage` TEXT NOT NULL DEFAULT 'CN', PRIMARY KEY(`day`, `learningLanguage`))")
    db.execSQL("INSERT INTO daily_plans_v10 SELECT *, 'CN' FROM daily_plans")
    db.execSQL("DROP TABLE daily_plans")
    db.execSQL("ALTER TABLE daily_plans_v10 RENAME TO daily_plans")
    db.execSQL("CREATE TABLE IF NOT EXISTS `word_progress_v10` (`wordId` TEXT NOT NULL, `firstEncounterShown` INTEGER NOT NULL, `firstPassedDay` INTEGER, `activeCycleId` TEXT, `isCollected` INTEGER NOT NULL DEFAULT 0, `isSkipped` INTEGER NOT NULL DEFAULT 0, `mistakePending` INTEGER NOT NULL DEFAULT 0, `learningLanguage` TEXT NOT NULL DEFAULT 'CN', PRIMARY KEY(`wordId`, `learningLanguage`))")
    db.execSQL("INSERT INTO word_progress_v10 SELECT *, 'CN' FROM word_progress")
    db.execSQL("DROP TABLE word_progress")
    db.execSQL("ALTER TABLE word_progress_v10 RENAME TO word_progress")
    db.execSQL("CREATE TABLE IF NOT EXISTS `stage_states_v10` (`stage` INTEGER NOT NULL, `lap` INTEGER NOT NULL, `learningLanguage` TEXT NOT NULL DEFAULT 'CN', PRIMARY KEY(`stage`, `learningLanguage`))")
    db.execSQL("INSERT INTO stage_states_v10 SELECT *, 'CN' FROM stage_states")
    db.execSQL("DROP TABLE stage_states")
    db.execSQL("ALTER TABLE stage_states_v10 RENAME TO stage_states")
    db.execSQL("CREATE TABLE IF NOT EXISTS `stage_completions_v10` (`stage` INTEGER NOT NULL, `lap` INTEGER NOT NULL, `wordId` TEXT NOT NULL, `completedDay` INTEGER NOT NULL, `learningLanguage` TEXT NOT NULL DEFAULT 'CN', PRIMARY KEY(`stage`, `lap`, `wordId`, `learningLanguage`))")
    db.execSQL("INSERT INTO stage_completions_v10 SELECT *, 'CN' FROM stage_completions")
    db.execSQL("DROP TABLE stage_completions")
    db.execSQL("ALTER TABLE stage_completions_v10 RENAME TO stage_completions")
    db.execSQL("CREATE TABLE IF NOT EXISTS `stage_daily_plans_v10` (`day` INTEGER NOT NULL, `stage` INTEGER NOT NULL, `dailyGoal` INTEGER NOT NULL, `learningLanguage` TEXT NOT NULL DEFAULT 'CN', PRIMARY KEY(`day`, `stage`, `learningLanguage`))")
    db.execSQL("INSERT INTO stage_daily_plans_v10 SELECT *, 'CN' FROM stage_daily_plans")
    db.execSQL("DROP TABLE stage_daily_plans")
    db.execSQL("ALTER TABLE stage_daily_plans_v10 RENAME TO stage_daily_plans")
    db.execSQL("ALTER TABLE learning_cycles ADD COLUMN learningLanguage TEXT NOT NULL DEFAULT 'CN'")
    db.execSQL("ALTER TABLE daily_items ADD COLUMN learningLanguage TEXT NOT NULL DEFAULT 'CN'")
    db.execSQL("ALTER TABLE practice_sessions ADD COLUMN learningLanguage TEXT NOT NULL DEFAULT 'CN'")
    db.execSQL("ALTER TABLE practice_items ADD COLUMN learningLanguage TEXT NOT NULL DEFAULT 'CN'")
    db.execSQL("ALTER TABLE review_nodes ADD COLUMN learningLanguage TEXT NOT NULL DEFAULT 'CN'")
    db.execSQL("ALTER TABLE study_cards ADD COLUMN learningLanguage TEXT NOT NULL DEFAULT 'CN'")
    db.execSQL("ALTER TABLE writing_sessions ADD COLUMN learningLanguage TEXT NOT NULL DEFAULT 'CN'")
    db.execSQL("ALTER TABLE writing_completions ADD COLUMN learningLanguage TEXT NOT NULL DEFAULT 'CN'")
    db.execSQL("ALTER TABLE study_cards ADD COLUMN questionMeaningId TEXT NOT NULL DEFAULT ''")
    db.execSQL("UPDATE study_cards SET questionMeaningId = COALESCE((SELECT id FROM meanings WHERE wordId = study_cards.wordId ORDER BY position, id LIMIT 1), '')")
  }
}
