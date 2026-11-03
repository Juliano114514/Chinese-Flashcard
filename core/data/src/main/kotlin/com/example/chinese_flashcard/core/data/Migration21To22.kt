package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object Migration21To22 : Migration(21, 22) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE settings ADD COLUMN chineseSpeechEngine TEXT NOT NULL DEFAULT 'AI'")
    db.execSQL("ALTER TABLE settings ADD COLUMN englishSpeechEngine TEXT NOT NULL DEFAULT 'AI'")
  }
}
