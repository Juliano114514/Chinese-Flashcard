package com.example.chinese_flashcard.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Keep the v2 library and its progress while separating stage laps and practice batches. */
internal val Migration7To8 = object : Migration(7, 8) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE word_progress ADD COLUMN isCollected INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE word_progress ADD COLUMN isSkipped INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE word_progress ADD COLUMN mistakePending INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE app_state ADD COLUMN selectedStage INTEGER")
    db.execSQL("ALTER TABLE learning_cycles ADD COLUMN scope TEXT NOT NULL DEFAULT 'NORMAL'")
    db.execSQL("ALTER TABLE learning_cycles ADD COLUMN originStage INTEGER")
    db.execSQL("ALTER TABLE learning_cycles ADD COLUMN originLap INTEGER")
    db.execSQL("ALTER TABLE study_cards ADD COLUMN itemId TEXT")
    db.execSQL("ALTER TABLE study_cards ADD COLUMN sessionId TEXT")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_study_cards_sessionId ON study_cards(sessionId)")

    // A word can now appear in normal review and a repeated stage lap on the same day.
    db.execSQL("ALTER TABLE daily_items RENAME TO daily_items_v7")
    db.execSQL("""
      CREATE TABLE IF NOT EXISTS daily_items (
        day INTEGER NOT NULL, wordId TEXT NOT NULL, kind TEXT NOT NULL,
        cycleId TEXT NOT NULL, batch INTEGER NOT NULL, queueOrder INTEGER NOT NULL,
        completed INTEGER NOT NULL, reviewRecall INTEGER NOT NULL,
        id TEXT NOT NULL, originStage INTEGER, originLap INTEGER,
        PRIMARY KEY(id))
    """.trimIndent())
    // Review-error relearning also carried over, but must keep its shared review ownership.
    db.execSQL("""
      INSERT INTO daily_items (day, wordId, kind, cycleId, batch, queueOrder, completed,
        reviewRecall, id, originStage, originLap)
      SELECT d.day, d.wordId, d.kind, d.cycleId, d.batch, d.queueOrder, d.completed,
        d.reviewRecall, CAST(d.day AS TEXT) || ':' || d.kind || ':' || d.cycleId || ':' || d.wordId,
        CASE WHEN d.kind = 'NEW' OR (d.kind = 'CARRYOVER' AND
          (source.hadNew = 1 OR source.hadReview = 0)) THEN w.rarity ELSE NULL END,
        CASE WHEN (d.kind = 'NEW' OR (d.kind = 'CARRYOVER' AND
          (source.hadNew = 1 OR source.hadReview = 0))) AND w.id IS NOT NULL THEN 1 ELSE NULL END
      FROM daily_items_v7 d LEFT JOIN words w ON w.id = d.wordId
      LEFT JOIN (
        SELECT cycleId, MAX(CASE WHEN kind = 'NEW' THEN 1 ELSE 0 END) AS hadNew,
          MAX(CASE WHEN kind = 'REVIEW' THEN 1 ELSE 0 END) AS hadReview
        FROM daily_items_v7 GROUP BY cycleId
      ) source ON source.cycleId = d.cycleId
    """.trimIndent())
    db.execSQL("DROP TABLE daily_items_v7")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_daily_items_day_kind ON daily_items(day, kind)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_daily_items_cycleId ON daily_items(cycleId)")
    db.execSQL("""
      UPDATE study_cards SET itemId = (
        SELECT d.id FROM daily_items d
        WHERE d.day = study_cards.day AND d.wordId = study_cards.wordId LIMIT 1)
    """.trimIndent())
    db.execSQL("""
      UPDATE learning_cycles SET originStage = (
        SELECT d.originStage FROM daily_items d
        WHERE d.cycleId = learning_cycles.id AND d.originStage IS NOT NULL
        ORDER BY d.day LIMIT 1), originLap = (
        SELECT d.originLap FROM daily_items d
        WHERE d.cycleId = learning_cycles.id AND d.originLap IS NOT NULL
        ORDER BY d.day LIMIT 1)
    """.trimIndent())

    db.execSQL("""
      CREATE TABLE IF NOT EXISTS stage_states (
        stage INTEGER NOT NULL, lap INTEGER NOT NULL, PRIMARY KEY(stage))
    """.trimIndent())
    db.execSQL("""
      CREATE TABLE IF NOT EXISTS stage_completions (
        stage INTEGER NOT NULL, lap INTEGER NOT NULL, wordId TEXT NOT NULL,
        completedDay INTEGER NOT NULL, PRIMARY KEY(stage, lap, wordId))
    """.trimIndent())
    db.execSQL("""
      CREATE TABLE IF NOT EXISTS stage_daily_plans (
        day INTEGER NOT NULL, stage INTEGER NOT NULL, dailyGoal INTEGER NOT NULL,
        PRIMARY KEY(day, stage))
    """.trimIndent())
    db.execSQL("INSERT INTO stage_states (stage, lap) SELECT DISTINCT rarity, 1 FROM words")
    db.execSQL("""
      INSERT INTO stage_completions (stage, lap, wordId, completedDay)
      SELECT w.rarity, 1, p.wordId, p.firstPassedDay
      FROM word_progress p JOIN words w ON w.id = p.wordId
      WHERE p.firstPassedDay IS NOT NULL
    """.trimIndent())
    db.execSQL("""
      INSERT INTO stage_daily_plans (day, stage, dailyGoal)
      SELECT DISTINCT p.day, d.originStage, p.dailyGoal
      FROM daily_plans p JOIN daily_items d ON d.day = p.day
      WHERE d.kind = 'NEW' AND d.originStage IS NOT NULL
    """.trimIndent())
    // Keep an in-flight Learn card; otherwise choose after the final preset grading is loaded.
    db.execSQL("""
      UPDATE app_state SET selectedStage = (
        SELECT d.originStage FROM study_cards c JOIN daily_items d ON d.id = c.itemId
        WHERE c.id = app_state.currentCardId AND c.kind = 'NEW' AND c.phase != 'FINISHED')
    """.trimIndent())
    db.execSQL("""
      CREATE TABLE IF NOT EXISTS practice_sessions (
        id TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL,
        lap INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))
    """.trimIndent())
    db.execSQL("CREATE INDEX IF NOT EXISTS index_practice_sessions_kind_status ON practice_sessions(kind, status)")
    db.execSQL("""
      CREATE TABLE IF NOT EXISTS practice_items (
        sessionId TEXT NOT NULL, wordId TEXT NOT NULL, cycleId TEXT NOT NULL,
        queueOrder INTEGER NOT NULL, completed INTEGER NOT NULL,
        PRIMARY KEY(sessionId, wordId))
    """.trimIndent())
    db.execSQL("CREATE INDEX IF NOT EXISTS index_practice_items_cycleId ON practice_items(cycleId)")
  }
}
