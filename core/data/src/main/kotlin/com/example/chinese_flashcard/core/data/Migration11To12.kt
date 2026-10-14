package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Audio preparation settings preserve all vocabulary and learning records. */
internal object Migration11To12 : Migration(11, 12) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN speechPreloadWords INTEGER NOT NULL DEFAULT 3")
    db.execSQL("ALTER TABLE settings ADD COLUMN speechCacheMiB INTEGER NOT NULL DEFAULT 256")
    db.execSQL("ALTER TABLE settings ADD COLUMN homeShuffleQueueSize INTEGER NOT NULL DEFAULT 5")
  }
}
