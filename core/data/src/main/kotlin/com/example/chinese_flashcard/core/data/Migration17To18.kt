package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Merge the two introductory bands and restore five stages without resetting learning. */
internal object Migration17To18 : Migration(17, 18) {
  override fun migrate(db: SupportSQLiteDatabase) {
    // Stages 0 and 1 may have different laps. Keep the furthest lap and carry each
    // source's current-lap credits forward, while retaining all historical laps.
    db.execSQL("CREATE TEMP TABLE stages_before_merge AS SELECT * FROM stage_states")
    db.execSQL("CREATE TEMP TABLE completions_before_merge AS SELECT * FROM stage_completions")
    db.execSQL("CREATE TEMP TABLE plans_before_merge AS SELECT * FROM stage_daily_plans")
    val stage = "CASE WHEN stage BETWEEN 1 AND 5 THEN stage - 1 ELSE stage END"
    db.execSQL("DELETE FROM stage_states")
    db.execSQL("""
      INSERT INTO stage_states (stage, lap, learningLanguage)
      SELECT $stage, MAX(lap), learningLanguage FROM stages_before_merge
      GROUP BY $stage, learningLanguage
    """.trimIndent())
    db.execSQL("DELETE FROM stage_completions")
    db.execSQL("""
      INSERT INTO stage_completions (stage, lap, wordId, completedDay, learningLanguage)
      SELECT stage, lap, wordId, MIN(completedDay), learningLanguage FROM (
        SELECT $stage AS stage, lap, wordId, completedDay, learningLanguage
        FROM completions_before_merge
        UNION ALL
        SELECT 0 AS stage, destination.lap, completion.wordId, completion.completedDay,
          completion.learningLanguage
        FROM completions_before_merge AS completion
        JOIN stages_before_merge AS source ON source.stage = completion.stage
          AND source.learningLanguage = completion.learningLanguage AND source.lap = completion.lap
        JOIN stage_states AS destination ON destination.stage = 0
          AND destination.learningLanguage = completion.learningLanguage
        WHERE completion.stage IN (0, 1)
      ) GROUP BY stage, lap, wordId, learningLanguage
    """.trimIndent())
    db.execSQL("DELETE FROM stage_daily_plans")
    db.execSQL("""
      INSERT INTO stage_daily_plans (day, stage, dailyGoal, learningLanguage)
      SELECT day, $stage, MAX(dailyGoal), learningLanguage FROM plans_before_merge
      GROUP BY day, $stage, learningLanguage
    """.trimIndent())
    for (table in listOf("learning_cycles", "daily_items")) {
      db.execSQL("""
        UPDATE $table SET originLap = (
          SELECT lap FROM stage_states WHERE stage = 0
            AND learningLanguage = $table.learningLanguage
        ) WHERE originStage IN (0, 1) AND originLap = (
          SELECT lap FROM stages_before_merge WHERE stage = $table.originStage
            AND learningLanguage = $table.learningLanguage
        )
      """.trimIndent())
      db.execSQL("UPDATE $table SET originStage = originStage - 1 WHERE originStage BETWEEN 1 AND 5")
    }
    for (table in listOf("app_state", "learning_contexts")) {
      db.execSQL("UPDATE $table SET selectedStage = selectedStage - 1 WHERE selectedStage BETWEEN 1 AND 5")
    }
    // Preset refresh applies reviewed values afterwards; custom imports keep their band.
    db.execSQL("UPDATE words SET rarity = rarity - 1 WHERE rarity BETWEEN 1 AND 5")
    db.execSQL("UPDATE app_state SET revision = revision + 1, contextVersion = contextVersion + 1")
    db.execSQL("DROP TABLE plans_before_merge")
    db.execSQL("DROP TABLE completions_before_merge")
    db.execSQL("DROP TABLE stages_before_merge")
  }
}
