package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Default to word narration without changing writing frequency or learning history. */
internal object Migration20To21 : Migration(20, 21) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN writingBroadcast TEXT NOT NULL DEFAULT 'WORD'")
  }
}
