package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add an independent feedback-cue preference; retain all speech and learning settings. */
internal object Migration18To19 : Migration(18, 19) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN learningSoundsEnabled INTEGER NOT NULL DEFAULT 1")
  }
}
