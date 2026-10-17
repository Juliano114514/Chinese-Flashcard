package com.example.chinese_flashcard.core.data

import kotlinx.coroutines.flow.Flow

/** Used under the operation lock; observers capture their direction when subscribed. */
internal class ScopedFlashcardDao(private val shared: FlashcardDao, private val language: () -> String) {
  suspend fun words(): List<WordEntity> = shared.words()
  suspend fun studyWords(): List<WordStudyRow> = shared.studyWords()
  suspend fun wordIdentities(): List<WordIdentity> = shared.wordIdentities()
  suspend fun updateWordOrder(id: String, rank: Int): Unit = shared.updateWordOrder(id, rank)
  suspend fun updateDefaultRarity(ids: List<String>, rarity: Int): Int = shared.updateDefaultRarity(ids, rarity)
  suspend fun allMeanings(): List<MeaningEntity> = shared.allMeanings()
  suspend fun tracingIds(): List<String> = shared.tracingIds()
  suspend fun word(id: String): WordEntity? = shared.word(id)
  suspend fun wordCount(): Int = shared.wordCount()
  suspend fun meanings(wordId: String): List<MeaningEntity> = shared.meanings(wordId)
  suspend fun meaning(id: String): MeaningEntity? = shared.meaning(id)
  suspend fun tracing(id: String): TracingEntity? = shared.tracing(id)
  suspend fun wordTracing(wordId: String): List<WordTracingEntity> = shared.wordTracing(wordId)
  suspend fun putWords(values: List<WordEntity>): Unit = shared.putWords(values)
  suspend fun putMeanings(values: List<MeaningEntity>): Unit = shared.putMeanings(values)
  suspend fun putTracing(values: List<TracingEntity>): Unit = shared.putTracing(values)
  suspend fun putWordTracing(values: List<WordTracingEntity>): Unit = shared.putWordTracing(values)
  suspend fun settings(): SettingsEntity? = shared.settings()
  fun observeSettings(): Flow<SettingsEntity?> = shared.observeSettings()
  suspend fun putSettings(value: SettingsEntity): Unit = shared.putSettings(value)
  fun observeRevision(): Flow<Long?> = shared.observeRevision()
  suspend fun learnedCount(): Int = shared.learnedCount(language = language())
  suspend fun startedCount(): Int = shared.startedCount(language = language())
  suspend fun putProgress(value: WordProgressEntity): Unit = shared.putProgress(value.copy(learningLanguage = language()))
  suspend fun cycle(id: String): CycleEntity? = shared.cycle(id, language = language())
  suspend fun activeCycles(): List<CycleEntity> = shared.activeCycles(language = language())
  suspend fun learnedReviewCycles(): List<CycleEntity> = shared.learnedReviewCycles(language = language())
  suspend fun putCycle(value: CycleEntity): Unit = shared.putCycle(value.copy(learningLanguage = language()))
  suspend fun dailyItems(day: Long): List<DailyItemEntity> = shared.dailyItems(day, language = language())
  suspend fun dailyItem(day: Long, wordId: String): DailyItemEntity? = shared.dailyItem(day, wordId, language = language())
  suspend fun dailyItemById(id: String): DailyItemEntity? = shared.dailyItemById(id, language = language())
  suspend fun latestDailyItemForCycle(cycleId: String): DailyItemEntity? = shared.latestDailyItemForCycle(cycleId, language = language())
  suspend fun putDailyItem(value: DailyItemEntity): Unit = shared.putDailyItem(value.copy(learningLanguage = language()))
  suspend fun stageState(stage: Int): StageStateEntity? = shared.stageState(stage, language = language())
  suspend fun stageStates(): List<StageStateEntity> = shared.stageStates(language = language())
  suspend fun putStageState(value: StageStateEntity): Unit = shared.putStageState(value.copy(learningLanguage = language()))
  suspend fun stageCompletions(stage: Int, lap: Int): List<StageCompletionEntity> = shared.stageCompletions(stage, lap, language = language())
  suspend fun putStageCompletion(value: StageCompletionEntity): Unit = shared.putStageCompletion(value.copy(learningLanguage = language()))
  suspend fun stagePlan(day: Long, stage: Int): StageDailyPlanEntity? = shared.stagePlan(day, stage, language = language())
  suspend fun putStagePlan(value: StageDailyPlanEntity): Unit = shared.putStagePlan(value.copy(learningLanguage = language()))
  suspend fun practiceSession(id: String): PracticeSessionEntity? = shared.practiceSession(id, language = language())
  suspend fun activePracticeSession(kind: String): PracticeSessionEntity? = shared.activePracticeSession(kind, language = language())
  suspend fun practiceSessions(kind: String): List<PracticeSessionEntity> = shared.practiceSessions(kind, language = language())
  suspend fun putPracticeSession(value: PracticeSessionEntity): Unit = shared.putPracticeSession(value.copy(learningLanguage = language()))
  suspend fun practiceItems(sessionId: String): List<PracticeItemEntity> = shared.practiceItems(sessionId, language = language())
  suspend fun practiceItem(sessionId: String, wordId: String): PracticeItemEntity? = shared.practiceItem(sessionId, wordId, language = language())
  suspend fun putPracticeItem(value: PracticeItemEntity): Unit = shared.putPracticeItem(value.copy(learningLanguage = language()))
  suspend fun dueNodes(day: Long): List<ReviewNodeEntity> = shared.dueNodes(day, language = language())
  suspend fun pendingNodes(cycleId: String): List<ReviewNodeEntity> = shared.pendingNodes(cycleId, language = language())
  suspend fun nextReviewDay(day: Long): Long? = shared.nextReviewDay(day, language = language())
  suspend fun putReviewNodes(values: List<ReviewNodeEntity>): Unit = shared.putReviewNodes(values.map { it.copy(learningLanguage = language()) })
  suspend fun card(id: String): CardEntity? = shared.card(id, language = language())
  suspend fun pendingCard(day: Long, kind: String): CardEntity? = shared.pendingCard(day, kind, language = language())
  suspend fun finishedCard(day: Long, kind: String): CardEntity? = shared.finishedCard(day, kind, language = language())
  suspend fun oldCards(day: Long): List<CardEntity> = shared.oldCards(day, language = language())
  suspend fun unfinishedCards(): List<CardEntity> = shared.unfinishedCards(language = language())
  suspend fun pendingPracticeCard(sessionId: String): CardEntity? = shared.pendingPracticeCard(sessionId, language = language())
  suspend fun latestPracticeCard(sessionId: String): CardEntity? = shared.latestPracticeCard(sessionId, language = language())
  suspend fun viewOnlyDailyWords(day: Long, kind: String): List<String> = shared.viewOnlyDailyWords(day, kind, language())
  suspend fun viewOnlyPracticeWords(sessionId: String): List<String> = shared.viewOnlyPracticeWords(sessionId, language())
  suspend fun putCard(value: CardEntity): Unit = shared.putCard(value.copy(learningLanguage = language()))
  suspend fun writing(id: String): WritingSessionEntity? = shared.writing(id, language = language())
  suspend fun resumableWriting(): WritingSessionEntity? = shared.resumableWriting(language = language())
  suspend fun dailyWriting(day: Long): WritingSessionEntity? = shared.dailyWriting(day, language = language())
  suspend fun putWriting(value: WritingSessionEntity): Unit = shared.putWriting(value.copy(learningLanguage = language()))
  suspend fun putWritingCompletion(value: WritingCompletionEntity): Unit = shared.putWritingCompletion(value.copy(learningLanguage = language()))
  suspend fun appState(): AppStateEntity? {
    val global = shared.appState() ?: return null
    val context = shared.learningContext(language()) ?: LearningContextEntity(language())
    return global.copy(day = context.day, selectedKind = context.selectedKind,
      currentCardId = context.currentCardId, selectedStage = context.selectedStage)
  }

  suspend fun putAppState(value: AppStateEntity) {
    shared.putAppState(value)
    shared.putLearningContext(LearningContextEntity(language(), value.day,
      value.selectedKind, value.currentCardId, value.selectedStage))
  }

  suspend fun plan(day: Long): DailyPlanEntity? {
    val plan = shared.plan(day, language()) ?: return null
    val reward = shared.growthReward(day)
    return plan.copy(loginRewarded = reward?.loginRewarded ?: false,
      learnRewarded = reward?.learnRewarded ?: false)
  }

  suspend fun putPlan(value: DailyPlanEntity) {
    val reward = shared.growthReward(value.day)
    shared.putGrowthReward(DailyGrowthRewardEntity(value.day,
      value.loginRewarded || reward?.loginRewarded == true,
      value.learnRewarded || reward?.learnRewarded == true))
    shared.putPlan(value.copy(learningLanguage = language()))
  }

  suspend fun progress(): List<WordProgressEntity> {
    val bookmarks = shared.bookmarks().filter { it.isCollected }.map { it.wordId }.toSet()
    val rows = shared.progress(language()).map { it.copy(isCollected = it.wordId in bookmarks) }
    val ids = rows.map { it.wordId }.toSet()
    return rows + bookmarks.filter { it !in ids }.map {
      WordProgressEntity(it, isCollected = true, learningLanguage = language())
    }
  }

  suspend fun progress(wordId: String): WordProgressEntity? {
    val row = shared.progress(wordId, language())
    val collected = shared.bookmark(wordId)?.isCollected == true
    return row?.copy(isCollected = collected) ?: if (collected)
      WordProgressEntity(wordId, isCollected = true, learningLanguage = language()) else null
  }
}
