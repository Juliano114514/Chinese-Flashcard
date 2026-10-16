package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Correct answers allow immediate manual navigation for new and existing users. */
internal object Migration13To14 : Migration(13, 14) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN waitForCorrectAnswerAudio INTEGER NOT NULL DEFAULT 0")
  }
}
