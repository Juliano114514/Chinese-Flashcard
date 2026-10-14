package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Change the former default without resetting any other setting or learning record. */
internal object Migration12To13 : Migration(12, 13) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("""
      CREATE TABLE settings_new (
        id INTEGER NOT NULL PRIMARY KEY, dailyWords INTEGER NOT NULL, rounds INTEGER NOT NULL,
        reviewDaysJson TEXT NOT NULL, welcomed INTEGER NOT NULL,
        displayName TEXT NOT NULL DEFAULT '', avatarId TEXT NOT NULL DEFAULT '1',
        themeMode TEXT NOT NULL DEFAULT 'SYSTEM', uiLanguage TEXT NOT NULL DEFAULT 'EN',
        learningLanguage TEXT NOT NULL DEFAULT 'CN', speechModesJson TEXT NOT NULL DEFAULT '{}',
        explanationMask INTEGER NOT NULL DEFAULT 15, speechPreloadWords INTEGER NOT NULL DEFAULT 5,
        speechCacheMiB INTEGER NOT NULL DEFAULT 256, homeShuffleQueueSize INTEGER NOT NULL DEFAULT 5
      )
    """.trimIndent())
    db.execSQL("""
      INSERT INTO settings_new
      SELECT id, dailyWords, rounds, reviewDaysJson, welcomed, displayName, avatarId, themeMode,
        uiLanguage, learningLanguage, speechModesJson, explanationMask,
        CASE WHEN speechPreloadWords = 3 THEN 5 ELSE speechPreloadWords END,
        speechCacheMiB, homeShuffleQueueSize FROM settings
    """.trimIndent())
    db.execSQL("DROP TABLE settings")
    db.execSQL("ALTER TABLE settings_new RENAME TO settings")
  }
}
