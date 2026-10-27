package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Reserve stage 0 for Kindergarten without discarding any word, lap, card or practice record. */
internal object Migration16To17 : Migration(16, 17) {
  override fun migrate(db: SupportSQLiteDatabase) {
    // Keyed tables need a collision-free intermediate range: SQLite may update rows in any order.
    for (table in listOf("stage_states", "stage_completions", "stage_daily_plans")) {
      db.execSQL("UPDATE $table SET stage = stage + 100 WHERE stage BETWEEN 0 AND 4")
      db.execSQL("UPDATE $table SET stage = stage - 99 WHERE stage BETWEEN 100 AND 104")
    }
    for (table in listOf("app_state", "learning_contexts")) {
      db.execSQL("UPDATE $table SET selectedStage = selectedStage + 1 WHERE selectedStage BETWEEN 0 AND 4")
    }
    for (table in listOf("learning_cycles", "daily_items")) {
      db.execSQL("UPDATE $table SET originStage = originStage + 1 WHERE originStage BETWEEN 0 AND 4")
    }
    // Bundled entries receive their reviewed values in the atomic preset refresh; user imports
    // retain their former educational band under its new number.
    db.execSQL("UPDATE words SET rarity = rarity + 1 WHERE rarity BETWEEN 0 AND 4")
    db.execSQL("UPDATE app_state SET revision = revision + 1, contextVersion = contextVersion + 1")
  }
}
