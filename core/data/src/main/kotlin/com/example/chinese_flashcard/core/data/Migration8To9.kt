package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Keep study state; growth starts when this mechanism is introduced. */
internal object Migration8To9 : Migration(8, 9) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN themeMode TEXT NOT NULL DEFAULT 'SYSTEM'")
    db.execSQL("ALTER TABLE app_state ADD COLUMN growthDays INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE app_state ADD COLUMN learnedCompletions INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE app_state ADD COLUMN clearedMistakes INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE daily_plans ADD COLUMN loginRewarded INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE daily_plans ADD COLUMN learnRewarded INTEGER NOT NULL DEFAULT 0")
  }
}
