package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing users receive the same ten-word default as new installations. */
internal object Migration14To15 : Migration(14, 15) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN reviewBatchSize INTEGER NOT NULL DEFAULT 10")
  }
}
