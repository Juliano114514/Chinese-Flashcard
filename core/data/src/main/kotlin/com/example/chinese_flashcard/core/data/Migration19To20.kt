package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing users also default to writing after every answer; learning history stays intact. */
internal object Migration19To20 : Migration(19, 20) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN writingMode TEXT NOT NULL DEFAULT 'EVERY_ENCOUNTER'")
  }
}
