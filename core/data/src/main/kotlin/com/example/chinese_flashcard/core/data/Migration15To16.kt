package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Filters are initially In total; all vocabulary, learning, and writing records are retained. */
internal object Migration15To16 : Migration(15, 16) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE app_state ADD COLUMN selectedTagIdsJson TEXT NOT NULL DEFAULT '[]'")
    db.execSQL("ALTER TABLE learning_contexts ADD COLUMN selectedTagIdsJson TEXT NOT NULL DEFAULT '[]'")
  }
}
