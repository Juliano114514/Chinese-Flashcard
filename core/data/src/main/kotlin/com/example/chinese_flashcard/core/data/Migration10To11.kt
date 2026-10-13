package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add presentation preferences without changing vocabulary or learning records. */
internal object Migration10To11 : Migration(10, 11) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN speechModesJson TEXT NOT NULL DEFAULT '{}'")
    db.execSQL("ALTER TABLE settings ADD COLUMN explanationMask INTEGER NOT NULL DEFAULT 15")
  }
}
