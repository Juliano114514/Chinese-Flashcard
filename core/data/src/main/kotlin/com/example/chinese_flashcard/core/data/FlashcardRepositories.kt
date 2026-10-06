package com.example.chinese_flashcard.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.example.chinese_flashcard.core.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class DefaultWordlistState(val loading: Boolean = false, val error: String? = null)

/** One application-scoped owner. The rebuilt library starts in its own database. */
class FlashcardRepositories(context: Context) {
  private val app = context.applicationContext
  private val database = Room.databaseBuilder(app, FlashcardDatabase::class.java, "chinese-flashcard-v2.db")
    .addMigrations(Migration7To8).build()
  private val dao = database.flashcards()
  private val seedMutex = Mutex()
  private val operationMutex = Mutex()
  private var prepared = false
  private val writingBreakdowns: Map<Pair<String, String>, List<WordPart>> by lazy {
    try { DemoDecoder.readWritingBreakdowns(app) }
    catch (_: IOException) { emptyMap() }
    catch (_: JSONException) { emptyMap() }
    catch (_: IllegalArgumentException) { emptyMap() }
  }
  private val mutableDefaultWordlistState = MutableStateFlow(DefaultWordlistState())
  val defaultWordlistState = mutableDefaultWordlistState.asStateFlow()
  private val mutableDefaultWordlistProgress = MutableStateFlow<WordlistLoadProgress?>(null)
  val defaultWordlistProgress = mutableDefaultWordlistProgress.asStateFlow()

  val study: StudyRepository = LocalStudy()
  val settings: SettingsRepository = LocalSettings()
  val writing: WritingRepository = LocalWriting()
  val wordlist: WordlistRepository = LocalWordlist()
  val wordState: WordStateRepository = LocalWordState()
  private val csvImporter: CsvImporter = CsvImporter(app, database, dao, operationMutex,
    ::prepare, ::touch, ::reconcilePresetStages)
  val csvImport: CsvImportRepository = csvImporter

  suspend fun prepare() = prepareDefaultWordlist(forceRetry = false)

  suspend fun retryDefaultWordlist() = prepareDefaultWordlist(forceRetry = true)

  private suspend fun prepareDefaultWordlist(forceRetry: Boolean) = withContext(Dispatchers.IO) {
    seedMutex.withLock {
      if (prepared && !forceRetry) return@withLock
      val previousStatus = mutableDefaultWordlistState.value
      mutableDefaultWordlistProgress.value = WordlistLoadProgress()
      mutableDefaultWordlistState.value = DefaultWordlistState(loading = true)
      var hasSavedWordlist = false
      try {
        hasSavedWordlist = dao.appState()?.seeded == true
        csvImporter.cleanStaleSnapshots()
        csvImporter.bootstrapDefaultWordlist { mutableDefaultWordlistProgress.value = it }
        prepared = true
        mutableDefaultWordlistState.value = DefaultWordlistState()
      } catch (error: CancellationException) {
        mutableDefaultWordlistState.value = previousStatus.copy(loading = false)
        throw error
      } catch (error: Exception) {
        mutableDefaultWordlistState.value = DefaultWordlistState(error =
          if (error is PresetCardConflictException) error.message else "Default wordlist couldn't be loaded.")
        if (hasSavedWordlist) {
          // Preserve the usable library and avoid retrying on every snapshot in this process.
          prepared = true
        } else {
          throw IllegalStateException("Default wordlist couldn't be loaded.", error)
        }
      }
    }
  }

  private suspend fun <T> transaction(block: suspend () -> T): T {
    prepare()
    return operationMutex.withLock { database.withTransaction { block() } }
  }

  private fun today(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
  private fun newId(): String = UUID.randomUUID().toString()
  private suspend fun appState(): AppStateEntity = checkNotNull(dao.appState())
  private suspend fun savedSettings(): StudySettings = checkNotNull(dao.settings()).toDomain().also { it.validate() }
  private suspend fun touch() {
    val state = appState()
    dao.putAppState(state.copy(revision = state.revision + 1))
  }

  /** Called only inside preset bootstrap's Room transaction; never re-enters prepare or its locks. */
  private suspend fun reconcilePresetStages() {
    val words = dao.studyWords().associateBy { it.id }
    val states = dao.stageStates().associateBy { it.stage }.toMutableMap()
    for (rarity in words.values.map { it.rarity }.distinct()) {
      if (rarity !in states) {
        val state = StageStateEntity(rarity)
        dao.putStageState(state)
        states[rarity] = state
      }
    }
    for (progress in dao.progress()) {
      val passedDay = progress.firstPassedDay ?: continue
      val word = words[progress.wordId] ?: continue
      dao.putStageCompletion(StageCompletionEntity(word.rarity, 1, word.id, passedDay))
    }
    val saved = appState()
    val current = saved.currentCardId?.let { dao.card(it) }
    val currentOriginalStage = current?.takeIf {
      it.kind == StudyKind.NEW.name && it.phase != CardPhase.FINISHED.name
    }?.let { card ->
      dailyItem(card)?.originStage ?: dao.cycle(card.cycleId)?.originStage
    }
    val cycles = dao.activeCycles().filter { it.scope == "NORMAL" || it.scope == "STAGE_REPEAT" }
      .associateBy { it.id }.toMutableMap()
    for (cycle in cycles.values.toList()) {
      val word = words[cycle.wordId] ?: continue
      // Review relearning is shared and intentionally has no stage/lap ownership.
      if (cycle.originStage == null || cycle.originStage == word.rarity) continue
      val shifted = cycle.copy(originStage = word.rarity, originLap = checkNotNull(states[word.rarity]).lap)
      dao.putCycle(shifted)
      cycles[cycle.id] = shifted
    }
    saved.day?.let { day ->
      val goal = dao.plan(day)?.dailyGoal
      for (item in dao.dailyItems(day).filter { it.kind == StudyKind.NEW.name || it.kind == StudyKind.CARRYOVER.name }) {
        val word = words[item.wordId] ?: continue
        val cycle = cycles[item.cycleId]
        if (item.originStage == null && cycle?.originStage == null) continue
        val lap = if (!item.completed && cycle?.originStage == word.rarity) cycle.originLap else item.originLap
        val shifted = item.copy(originStage = word.rarity, originLap = lap)
        if (shifted != item) dao.putDailyItem(shifted)
        if (item.kind == StudyKind.NEW.name && goal != null && dao.stagePlan(day, word.rarity) == null) {
          // Preserve the frozen quota and all existing tasks; classification changes do not enqueue work.
          dao.putStagePlan(StageDailyPlanEntity(day, word.rarity, goal))
        }
      }
    }
    if (currentOriginalStage != null) {
      current?.let { card -> words[card.wordId] }?.takeIf { it.rarity != currentOriginalStage }?.let { word ->
        dao.putAppState(appState().copy(selectedStage = word.rarity))
      }
    }
  }

  private inner class LocalSettings : SettingsRepository {
    override val settings: Flow<StudySettings> = flow {
      prepare()
      emitAll(dao.observeSettings().map { checkNotNull(it).toDomain().also(StudySettings::validate) }.distinctUntilChanged())
    }
    override suspend fun save(value: StudySettings) {
      value.validate()
      transaction {
        dao.putSettings(SettingsEntity(dailyWords = value.dailyWords, rounds = value.rounds,
          reviewDaysJson = daysJson(value.reviewDays), welcomed = value.welcomed,
          displayName = value.displayName.trim(), avatarId = value.avatarId))
        touch()
      }
    }
  }

  private inner class LocalWordlist : WordlistRepository {
    override val entries: Flow<List<WordlistItem>> = flow {
      prepare()
      emitAll(dao.observeWordlist().map { rows ->
        rows.map { row ->
          val learningStatus = when {
            row.firstPassedDay != null -> WordlistStatus.LEARNED
            row.firstEncounterShown -> WordlistStatus.LEARNING
            else -> WordlistStatus.UNLEARNED
          }
          val status = if (row.isSkipped) WordlistStatus.SKIPPED else learningStatus
          WordlistItem(row.id, row.hanzi, row.pinyin, row.english, row.searchMeanings,
            row.difficulty, status, row.correctRounds, row.targetRounds,
            row.isCollected, row.isSkipped, row.mistakePending, learningStatus)
        }
      }.distinctUntilChanged())
    }.flowOn(Dispatchers.Default)

    override suspend fun word(id: String): WordEntry? = transaction {
      dao.word(id)?.let { this@FlashcardRepositories.word(it) }
    }
  }

  private inner class LocalWordState : WordStateRepository {
    override fun observe(wordId: String): Flow<WordUserState> = flow {
      prepare()
      emitAll(dao.observeWordProgress(wordId).map { value ->
        WordUserState(value?.isCollected == true, value?.isSkipped == true, value?.mistakePending == true)
      }.distinctUntilChanged())
    }

    override suspend fun setCollected(wordId: String, value: Boolean) = transaction {
      checkNotNull(dao.word(wordId))
      val saved = dao.progress(wordId) ?: WordProgressEntity(wordId)
      if (saved.isCollected != value) {
        dao.putProgress(saved.copy(isCollected = value))
        touch()
      }
    }

    override suspend fun setSkipped(wordId: String, value: Boolean) = transaction {
      checkNotNull(dao.word(wordId))
      val day = ensureToday()
      val saved = dao.progress(wordId) ?: WordProgressEntity(wordId)
      if (saved.isSkipped == value) return@transaction
      dao.putProgress(saved.copy(isSkipped = value))
      if (value) {
        // Pausing a word preserves its cycle and due nodes, but closes every visible card.
        val state = appState()
        val current = state.currentCardId?.let { dao.card(it) }
        dao.unfinishedCards().filter { it.wordId == wordId }.forEach { card ->
          dao.putCard(card.copy(phase = CardPhase.FINISHED.name))
          card.writingSessionId?.let { id ->
            dao.writing(id)?.takeIf { it.status == WritingStatus.ACTIVE.name }?.let { writing ->
              dao.putWriting(writing.copy(status = WritingStatus.SKIPPED.name,
                completedAt = System.currentTimeMillis(), feedback = null))
            }
          }
        }
        if (current?.wordId == wordId && current.phase != CardPhase.FINISHED.name) {
          val next = if (current.sessionId != null) createPracticeCard(current.sessionId, day)
          else createNext(day, StudyKind.valueOf(current.kind))
          dao.putAppState(appState().copy(currentCardId = next?.id ?: current.id))
        }
      } else restorePausedWork(day, wordId)
      touch()
    }
  }

  private inner class LocalStudy : StudyRepository {
    override val changes: Flow<Long> = flow {
      prepare()
      emitAll(dao.observeRevision().map { checkNotNull(it) }.distinctUntilChanged())
    }

    override suspend fun snapshot(): StudySnapshot = transaction { snapshotAt(ensureToday()) }

    override suspend fun selectStage(stage: VocabularyStage): StudySnapshot = transaction {
      val day = ensureToday()
      val saved = appState()
      if (saved.selectedStage != stage.rarity) {
        val current = saved.currentCardId?.let { dao.card(it) }
        dao.putAppState(saved.copy(selectedStage = stage.rarity,
          currentCardId = if (current?.kind == StudyKind.NEW.name) null else saved.currentCardId))
        ensureStagePlan(day, stage)
        touch()
      }
      snapshotAt(day)
    }

    override suspend fun start(kind: StudyKind): StudySnapshot = transaction {
      val day = ensureToday()
      val previous = appState()
      if (previous.selectedKind != kind.name) {
        val old = previous.currentCardId?.let { dao.card(it) }
        if (old != null && old.correct == true &&
          old.phase in listOf(CardPhase.FEEDBACK.name, CardPhase.EXPLANATION.name) && cardCompleted(old)) {
          // A completed feedback card must not become stranded after its entry is disabled.
          dao.putCard(old.copy(phase = CardPhase.FINISHED.name))
          old.sessionId?.let { finishPracticeIfReady(it) }
        }
      }
      val card = if (kind.isPractice()) startPractice(day, kind)
      else pendingDailyCard(day, kind) ?: createNext(day, kind) ?: finishedDailyCard(day, kind)
      dao.putAppState(appState().copy(selectedKind = kind.name, currentCardId = card?.id))
      touch()
      snapshotAt(day)
    }

    override suspend fun learnMore(expectedDate: String, expectedNewPlanned: Int,
      expectedStage: VocabularyStage, expectedLap: Int): StudySnapshot {
      var changedDay = false
      val result = transaction {
        val day = ensureToday()
        val current = snapshotAt(day)
        if (current.today.date != expectedDate) {
          changedDay = true
          return@transaction current
        }
        if (current.today.stageProgress.stage != expectedStage || current.today.stageProgress.lap != expectedLap ||
          current.today.newPlanned != expectedNewPlanned || !current.today.canLearnMore) return@transaction current
        if (!enqueueStageWords(day, expectedStage, 5, allowNewLap = true)) return@transaction current
        val card = checkNotNull(createNext(day, StudyKind.NEW))
        dao.putAppState(appState().copy(selectedKind = StudyKind.NEW.name, currentCardId = card.id))
        touch()
        snapshotAt(day)
      }
      if (changedDay) throw NewStudyDayException()
      return result
    }

    override suspend fun submit(cardId: String, optionId: String?): StudySnapshot = cardTransaction(cardId) { day, card ->
      if (card.phase != CardPhase.QUESTION.name) return@cardTransaction snapshotAt(day)
      val optionIds = strings(JSONArray(card.optionsJson))
      require(optionId == null || optionId in optionIds)
      val correct = optionId != null && dao.meanings(card.wordId).any { it.id == optionId }
      if (!correct) {
        val progress = dao.progress(card.wordId) ?: WordProgressEntity(card.wordId)
        dao.putProgress(progress.copy(mistakePending = true))
      }
      var answered = card.copy(phase = CardPhase.FEEDBACK.name, selectedOptionId = optionId, correct = correct)
      if (card.sessionId != null) {
        val item = checkNotNull(dao.practiceItem(card.sessionId, card.wordId))
        val cycle = checkNotNull(dao.cycle(item.cycleId))
        check(cycle.status == "ACTIVE")
        val updated = cycle.copy(correctRounds = if (correct) cycle.correctRounds + 1 else 0)
        if (correct && updated.correctRounds >= updated.targetRounds) {
          dao.putCycle(updated.copy(status = "COMPLETED", passedDay = day))
          dao.putPracticeItem(item.copy(completed = true))
          clearMistake(card.wordId, answeringCardId = card.id)
        } else dao.putCycle(updated)
      } else {
        val item = checkNotNull(dailyItem(card))
        val cycle = checkNotNull(dao.cycle(item.cycleId))
        if (card.reviewRecall) {
          if (correct) {
            dao.putReviewNodes(dao.dueNodes(day).filter { it.wordId == card.wordId }
              .map { it.copy(status = "CONSUMED") })
            dao.putDailyItem(item.copy(completed = true))
          } else {
            dao.putCycle(cycle.copy(status = "CANCELLED"))
            dao.putReviewNodes(dao.pendingNodes(cycle.id).map { it.copy(status = "CANCELLED") })
            val value = savedSettings()
            val relearn = CycleEntity(newId(), card.wordId, value.rounds, daysJson(value.reviewDays), startedDay = day)
            dao.putCycle(relearn)
            val progress = dao.progress(card.wordId) ?: WordProgressEntity(card.wordId)
            dao.putProgress(progress.copy(activeCycleId = relearn.id))
            dao.putDailyItem(item.copy(cycleId = relearn.id, reviewRecall = false, completed = false))
            answered = answered.copy(cycleId = relearn.id)
          }
        } else {
          check(cycle.status == "ACTIVE")
          val updated = cycle.copy(correctRounds = if (correct) cycle.correctRounds + 1 else 0)
          if (correct && updated.correctRounds >= updated.targetRounds) completeCycle(day, item, updated)
          else dao.putCycle(updated)
        }
      }
      dao.putCard(answered)
      touch()
      snapshotAt(day)
    }

    override suspend fun advance(cardId: String): StudySnapshot = cardTransaction(cardId) { day, card ->
      when (CardPhase.valueOf(card.phase)) {
        CardPhase.INTRO -> {
          val writingId = createWriting(listOf(card.wordId), WritingReason.FIRST_ENCOUNTER, card.id, day)
          val progress = dao.progress(card.wordId) ?: WordProgressEntity(card.wordId)
          dao.putProgress(progress.copy(firstEncounterShown = true))
          dao.putCard(card.copy(phase = CardPhase.WRITING.name, writingSessionId = writingId))
        }
        CardPhase.FEEDBACK -> dao.putCard(card.copy(phase = CardPhase.EXPLANATION.name))
        CardPhase.EXPLANATION -> {
          val cycle = checkNotNull(dao.cycle(card.cycleId))
          if (card.correct == false && card.reviewRecall && !cycle.reviewErrorWritingOffered) {
            val writingId = createWriting(listOf(card.wordId), WritingReason.REVIEW_ERROR, card.id, day)
            dao.putCycle(cycle.copy(reviewErrorWritingOffered = true))
            dao.putCard(card.copy(phase = CardPhase.WRITING.name, writingSessionId = writingId))
          } else finishAndSelect(card, day)
        }
        CardPhase.WRITING -> {
          val session = card.writingSessionId?.let { dao.writing(it) }
          if (session != null && session.status != WritingStatus.ACTIVE.name) {
            if (session.reason == WritingReason.FIRST_ENCOUNTER.name)
              dao.putCard(card.copy(phase = CardPhase.QUESTION.name))
            else finishAndSelect(card, day)
          }
        }
        CardPhase.QUESTION, CardPhase.FINISHED -> return@cardTransaction snapshotAt(day)
      }
      touch()
      snapshotAt(day)
    }

    override suspend fun explain(cardId: String): StudySnapshot = cardTransaction(cardId) { day, card ->
      if (card.phase == CardPhase.FEEDBACK.name) {
        dao.putCard(card.copy(phase = CardPhase.EXPLANATION.name))
        touch()
      }
      snapshotAt(day)
    }

    override suspend fun claimDailyInvitation(): Boolean = transaction {
      val day = ensureToday()
      val plan = checkNotNull(dao.plan(day))
      val summary = summaryAt(day)
      val pending = appState().currentCardId?.let { dao.card(it) }
        ?.let { it.sessionId == null && it.phase != CardPhase.FINISHED.name } == true
      if (plan.invitationClaimed || !summary.allComplete || summary.todayNewWords.isEmpty() || pending) false
      else {
        dao.putPlan(plan.copy(invitationClaimed = true))
        touch()
        true
      }
    }

    override suspend fun startDailyWriting(): String? = transaction {
      val day = ensureToday()
      val summary = summaryAt(day)
      if (!summary.allComplete || summary.todayNewWords.isEmpty()) return@transaction null
      val existing = dao.dailyWriting(day)
      if (existing != null) existing.id
      else createWriting(summary.todayNewWords.map { it.id }, WritingReason.DAILY, null, day).also { touch() }
    }

    override suspend fun startManualWriting(wordId: String): String = transaction {
      checkNotNull(dao.word(wordId))
      createWriting(listOf(wordId), WritingReason.MANUAL, null, today()).also { touch() }
    }
  }

  private fun StudyKind.isPractice() = this == StudyKind.COLLECTION || this == StudyKind.MISTAKES

  private suspend fun clearMistake(wordId: String, answeringCardId: String? = null) {
    val saved = dao.progress(wordId) ?: WordProgressEntity(wordId)
    if (saved.mistakePending) dao.putProgress(saved.copy(mistakePending = false))
    // Mastery ends the old mistake attempt, so a later error cannot inherit its partial rounds.
    val sessions = dao.practiceSessions(StudyKind.MISTAKES.name)
    for (session in sessions) {
      val item = dao.practiceItem(session.id, wordId) ?: continue
      if (!item.completed) {
        dao.putPracticeItem(item.copy(completed = true))
        dao.cycle(item.cycleId)?.takeIf { it.status == "ACTIVE" }?.let { cycle ->
          dao.putCycle(cycle.copy(status = "CANCELLED"))
        }
      }
    }
    dao.unfinishedCards().filter { it.kind == StudyKind.MISTAKES.name && it.wordId == wordId &&
      it.phase == CardPhase.QUESTION.name && it.id != answeringCardId }
      .forEach { dao.putCard(it.copy(phase = CardPhase.FINISHED.name)) }
    for (session in sessions.filter { it.status != "COMPLETED" }) {
      // Feedback and explanation remain readable until the learner advances or changes chains.
      finishPracticeIfReady(session.id)
    }
  }

  private suspend fun selectedStage(): VocabularyStage {
    val state = appState()
    state.selectedStage?.let { VocabularyStage.fromRarity(it) }?.let { return it }
    val progress = dao.progress().associateBy { it.wordId }
    val eligible = dao.studyWords().filter { progress[it.id]?.isSkipped != true }
    val stage = state.currentCardId?.let { dao.card(it) }?.takeIf {
      it.kind == StudyKind.NEW.name && it.phase != CardPhase.FINISHED.name
    }?.let { dao.word(it.wordId) }?.let { VocabularyStage.fromRarity(it.rarity) }
      ?: eligible.firstOrNull { progress[it.id]?.firstPassedDay == null }?.let { VocabularyStage.fromRarity(it.rarity) }
      ?: eligible.firstOrNull()?.let { VocabularyStage.fromRarity(it.rarity) } ?: VocabularyStage.PRIMARY
    dao.putAppState(state.copy(selectedStage = stage.rarity))
    return stage
  }

  private suspend fun stageState(stage: VocabularyStage): StageStateEntity {
    dao.stageState(stage.rarity)?.let { return it }
    val state = StageStateEntity(stage.rarity)
    dao.putStageState(state)
    val progress = dao.progress().associateBy { it.wordId }
    dao.studyWords().filter { it.rarity == stage.rarity }.forEach { word ->
      progress[word.id]?.firstPassedDay?.let { day ->
        dao.putStageCompletion(StageCompletionEntity(stage.rarity, state.lap, word.id, day))
      }
    }
    return state
  }

  private suspend fun activeLearningCycles(): List<CycleEntity> = dao.activeCycles()
    .filter { it.scope == "NORMAL" || it.scope == "STAGE_REPEAT" }

  private suspend fun ensureStagePlan(day: Long, stage: VocabularyStage) {
    stageState(stage)
    if (dao.stagePlan(day, stage.rarity) != null) return
    val goal = checkNotNull(dao.plan(day)).dailyGoal
    dao.putStagePlan(StageDailyPlanEntity(day, stage.rarity, goal))
    enqueueStageWords(day, stage, goal, allowNewLap = false)
  }

  private suspend fun stageCandidates(stage: VocabularyStage, allowNewLap: Boolean): List<WordStudyRow> {
    val state = stageState(stage)
    val progress = dao.progress().associateBy { it.wordId }
    val words = dao.studyWords()
    val completed = dao.stageCompletions(stage.rarity, state.lap).map { it.wordId }.toSet()
    return stageCandidates(stage, allowNewLap, words, progress, completed)
  }

  private suspend fun stageCandidates(stage: VocabularyStage, allowNewLap: Boolean,
    words: List<WordStudyRow>, progress: Map<String, WordProgressEntity>, completed: Set<String>): List<WordStudyRow> {
    val eligible = words.filter { it.rarity == stage.rarity && progress[it.id]?.isSkipped != true }
    val active = activeLearningCycles().map { it.wordId }.toSet()
    val remaining = eligible.filter { it.id !in completed && it.id !in active }
    if (remaining.isNotEmpty() || !allowNewLap || eligible.isEmpty() || eligible.any { it.id !in completed }) return remaining
    return eligible.filter { it.id !in active }
  }

  private suspend fun enqueueStageWords(day: Long, stage: VocabularyStage, limit: Int, allowNewLap: Boolean): Boolean {
    var state = stageState(stage)
    val candidates = stageCandidates(stage, allowNewLap).take(limit)
    if (candidates.isEmpty()) return false
    val completed = dao.stageCompletions(stage.rarity, state.lap).map { it.wordId }.toSet()
    if (candidates.all { it.id in completed }) {
      state = state.copy(lap = state.lap + 1)
      dao.putStageState(state)
    }
    val settings = savedSettings()
    val items = dao.dailyItems(day).filter { it.kind == StudyKind.NEW.name && it.originStage == stage.rarity }
    val firstBatch = (items.maxOfOrNull { it.batch } ?: -1) + 1
    candidates.forEachIndexed { index, word ->
      val saved = dao.progress(word.id) ?: WordProgressEntity(word.id)
      val normal = saved.firstPassedDay == null
      val cycle = CycleEntity(newId(), word.id, settings.rounds, daysJson(settings.reviewDays), startedDay = day,
        scope = if (normal) "NORMAL" else "STAGE_REPEAT", originStage = stage.rarity, originLap = state.lap)
      dao.putCycle(cycle)
      if (normal) dao.putProgress(saved.copy(activeCycleId = cycle.id))
      dao.putDailyItem(DailyItemEntity(day, word.id, StudyKind.NEW.name, cycle.id,
        firstBatch + index / 5, index.toLong(), originStage = stage.rarity, originLap = state.lap))
    }
    return true
  }

  /** Practice cards survive midnight; normal daily cards retain the existing stale-date guard. */
  private suspend fun cardTransaction(id: String, operation: suspend (Long, CardEntity) -> StudySnapshot): StudySnapshot {
    var changedDay = false
    val result = transaction {
      val day = ensureToday()
      val saved = dao.card(id)
      if (saved != null && saved.sessionId == null && saved.day != day) {
        changedDay = true
        snapshotAt(day)
      } else {
        val current = currentCard(id, day)
        if (current == null) snapshotAt(day) else operation(day, current)
      }
    }
    if (changedDay) throw NewStudyDayException()
    return result
  }

  private suspend fun ensureToday(): Long {
    val day = today()
    val state = appState()
    if (state.day != day || dao.plan(day) == null) {
      dao.oldCards(day).filter { it.sessionId == null }.forEach { dao.putCard(it.copy(phase = CardPhase.FINISHED.name)) }
      if (dao.plan(day) == null) {
        val settings = savedSettings()
        dao.putPlan(DailyPlanEntity(day, settings.dailyWords))
        val words = dao.studyWords()
        val progress = dao.progress().associateBy { it.wordId }
        val order = words.mapIndexed { rank, word -> word.id to rank }.toMap()
        val active = activeLearningCycles().filter { progress[it.wordId]?.isSkipped != true }
          .sortedBy { order[it.wordId] ?: Int.MAX_VALUE }
        active.forEachIndexed { index, cycle ->
          dao.putCycle(cycle.copy(correctRounds = 0))
          dao.putDailyItem(DailyItemEntity(day, cycle.wordId, StudyKind.CARRYOVER.name,
            cycle.id, index / 5, index.toLong(), originStage = cycle.originStage, originLap = cycle.originLap))
        }
        val normalActiveIds = active.filter { it.scope == "NORMAL" }.map { it.wordId }.toSet()
        val due = dao.dueNodes(day).filter { it.wordId !in normalActiveIds && progress[it.wordId]?.isSkipped != true }
          .distinctBy { it.wordId }
        due.forEachIndexed { index, node ->
          dao.putDailyItem(DailyItemEntity(day, node.wordId, StudyKind.REVIEW.name,
            node.cycleId, index / 5, index.toLong(), reviewRecall = true))
        }
      }
      val current = state.currentCardId?.let { dao.card(it) }
      dao.putAppState(appState().copy(day = day,
        selectedKind = if (current?.sessionId != null) state.selectedKind else null,
        currentCardId = current?.takeIf { it.sessionId != null }?.id, revision = appState().revision + 1))
    }
    ensureStagePlan(day, selectedStage())
    return day
  }

  private suspend fun restorePausedWork(day: Long, wordId: String) {
    val items = dao.dailyItems(day)
    val cycles = activeLearningCycles().filter { it.wordId == wordId }
    cycles.forEach { cycle ->
      if (items.none { it.cycleId == cycle.id && !it.completed }) {
        val order = (items.filter { it.kind == StudyKind.CARRYOVER.name }.maxOfOrNull { it.queueOrder } ?: -1) + 1
        dao.putDailyItem(DailyItemEntity(day, wordId, StudyKind.CARRYOVER.name, cycle.id,
          0, order, originStage = cycle.originStage, originLap = cycle.originLap))
      }
    }
    if (cycles.none { it.scope == "NORMAL" } && items.none { it.wordId == wordId && it.kind == StudyKind.REVIEW.name }) {
      dao.dueNodes(day).firstOrNull { it.wordId == wordId }?.let { node ->
        val order = (items.filter { it.kind == StudyKind.REVIEW.name }.maxOfOrNull { it.queueOrder } ?: -1) + 1
        dao.putDailyItem(DailyItemEntity(day, wordId, StudyKind.REVIEW.name, node.cycleId, 0, order, reviewRecall = true))
      }
    }
  }

  private suspend fun currentCard(id: String, day: Long): CardEntity? {
    val card = dao.card(id) ?: return null
    if (card.sessionId == null && card.day != day) return null
    if (appState().currentCardId != id || card.phase == CardPhase.FINISHED.name || dao.progress(card.wordId)?.isSkipped == true)
      return null
    return card
  }

  private suspend fun dailyItem(card: CardEntity): DailyItemEntity? =
    card.itemId?.let { dao.dailyItemById(it) } ?: dao.dailyItem(card.day, card.wordId)

  private suspend fun cardCompleted(card: CardEntity): Boolean = if (card.sessionId != null)
    dao.practiceItem(card.sessionId, card.wordId)?.completed == true else dailyItem(card)?.completed == true

  private suspend fun pendingDailyCard(day: Long, kind: StudyKind): CardEntity? {
    val stage = selectedStage()
    val progress = dao.progress().associateBy { it.wordId }
    return dao.unfinishedCards().filter { it.sessionId == null && it.day == day && it.kind == kind.name &&
      progress[it.wordId]?.isSkipped != true }.sortedBy { it.createdAt }.firstOrNull {
      kind != StudyKind.NEW || dailyItem(it)?.originStage == stage.rarity
    }
  }

  private suspend fun finishedDailyCard(day: Long, kind: StudyKind): CardEntity? {
    // Each new task normally has a pending card; the last finished card supplies the completion screen.
    return dao.finishedCard(day, kind.name)?.takeIf {
      kind != StudyKind.NEW || dailyItem(it)?.originStage == selectedStage().rarity
    }
  }

  private suspend fun createNext(day: Long, kind: StudyKind, stage: Int? = null): CardEntity? {
    check(!kind.isPractice())
    val selected = stage ?: selectedStage().rarity
    val progress = dao.progress().associateBy { it.wordId }
    val item = dao.dailyItems(day).filter { it.kind == kind.name && !it.completed && progress[it.wordId]?.isSkipped != true &&
      (kind != StudyKind.NEW || it.originStage == selected) }
      .minWithOrNull(compareBy<DailyItemEntity> { it.batch }.thenBy { it.queueOrder }) ?: return null
    val cycle = checkNotNull(dao.cycle(item.cycleId))
    val entry = word(item.wordId)
    val saved = progress[item.wordId] ?: WordProgressEntity(item.wordId)
    val introduction = cycle.scope == "NORMAL" && !item.reviewRecall && !saved.firstEncounterShown
    val options = (listOf(entry.meanings.first().id) + entry.distractorMeaningIds).shuffled()
    val card = CardEntity(newId(), day, item.wordId, cycle.id, kind.name,
      if (introduction) CardPhase.INTRO.name else CardPhase.QUESTION.name,
      if (item.reviewRecall) 1 else cycle.correctRounds + 1,
      if (item.reviewRecall) 1 else cycle.targetRounds, item.reviewRecall,
      stringsJson(options), createdAt = System.currentTimeMillis(), itemId = item.id)
    dao.putCard(card)
    return card
  }

  private suspend fun startPractice(day: Long, kind: StudyKind): CardEntity? {
    check(kind.isPractice())
    val progress = dao.progress().associateBy { it.wordId }
    val sessions = dao.practiceSessions(kind.name).sortedBy { it.createdAt }
    for (session in sessions.filter { it.status != "COMPLETED" }) {
      val pending = dao.pendingPracticeCard(session.id)?.takeIf { progress[it.wordId]?.isSkipped != true &&
        (kind != StudyKind.MISTAKES || it.phase != CardPhase.QUESTION.name || progress[it.wordId]?.mistakePending == true) }
      val available = dao.practiceItems(session.id).any { !it.completed && progress[it.wordId]?.isSkipped != true &&
        (kind != StudyKind.MISTAKES || progress[it.wordId]?.mistakePending == true) }
      if (pending != null || available) {
        if (session.status != "ACTIVE") dao.putPracticeSession(session.copy(status = "ACTIVE"))
        return pending ?: createPracticeCard(session.id, day)
      }
      finishPracticeIfReady(session.id)
    }
    val eligible = dao.studyWords().filter { progress[it.id]?.isSkipped != true &&
      if (kind == StudyKind.COLLECTION) progress[it.id]?.isCollected == true else progress[it.id]?.mistakePending == true }
    if (eligible.isEmpty()) return null
    var lap = sessions.maxOfOrNull { it.lap } ?: 1
    val completed = mutableSetOf<String>()
    if (kind == StudyKind.COLLECTION) {
      sessions.filter { it.lap == lap }.forEach { session ->
        completed += dao.practiceItems(session.id).filter { it.completed }.map { it.wordId }
      }
    }
    var candidates = eligible.filter { it.id !in completed }
    if (candidates.isEmpty()) {
      lap += 1
      candidates = eligible
    }
    val session = PracticeSessionEntity(newId(), kind.name, lap = lap, createdAt = System.currentTimeMillis())
    dao.putPracticeSession(session)
    val settings = savedSettings()
    candidates.take(5).forEachIndexed { index, word ->
      val cycle = CycleEntity(newId(), word.id, settings.rounds, daysJson(settings.reviewDays),
        startedDay = day, scope = kind.name)
      dao.putCycle(cycle)
      dao.putPracticeItem(PracticeItemEntity(session.id, word.id, cycle.id, index.toLong()))
    }
    return createPracticeCard(session.id, day)
  }

  private suspend fun createPracticeCard(sessionId: String, day: Long): CardEntity? {
    val session = checkNotNull(dao.practiceSession(sessionId))
    val progress = dao.progress().associateBy { it.wordId }
    val item = dao.practiceItems(sessionId).filter { !it.completed && progress[it.wordId]?.isSkipped != true &&
      (session.kind != StudyKind.MISTAKES.name || progress[it.wordId]?.mistakePending == true) }
      .minByOrNull { it.queueOrder }
    if (item == null) {
      finishPracticeIfReady(sessionId)
      return null
    }
    val cycle = checkNotNull(dao.cycle(item.cycleId))
    val entry = word(item.wordId)
    val options = (listOf(entry.meanings.first().id) + entry.distractorMeaningIds).shuffled()
    val card = CardEntity(newId(), day, item.wordId, cycle.id, session.kind, CardPhase.QUESTION.name,
      cycle.correctRounds + 1, cycle.targetRounds, false, stringsJson(options),
      createdAt = System.currentTimeMillis(), sessionId = sessionId)
    dao.putCard(card)
    return card
  }

  private suspend fun finishPracticeIfReady(sessionId: String) {
    val session = checkNotNull(dao.practiceSession(sessionId))
    val progress = dao.progress().associateBy { it.wordId }
    val items = dao.practiceItems(sessionId)
    val remaining = items.filter { !it.completed &&
      (session.kind != StudyKind.MISTAKES.name || progress[it.wordId]?.mistakePending == true) }
    val pending = dao.pendingPracticeCard(sessionId)?.let { progress[it.wordId]?.isSkipped != true } == true
    val status = when {
      pending || remaining.any { progress[it.wordId]?.isSkipped != true } -> "ACTIVE"
      remaining.isNotEmpty() -> "PAUSED"
      else -> "COMPLETED"
    }
    if (session.status != status) dao.putPracticeSession(session.copy(status = status))
  }

  private suspend fun finishAndSelect(card: CardEntity, day: Long) {
    dao.putCard(card.copy(phase = CardPhase.FINISHED.name))
    val next = if (card.sessionId != null) {
      val item = checkNotNull(dao.practiceItem(card.sessionId, card.wordId))
      if (!item.completed) {
        val last = dao.practiceItems(card.sessionId).maxOfOrNull { it.queueOrder } ?: item.queueOrder
        dao.putPracticeItem(item.copy(queueOrder = last + 1))
      }
      createPracticeCard(card.sessionId, day)
    } else {
      val item = checkNotNull(dailyItem(card))
      if (!item.completed) {
        val last = dao.dailyItems(day).filter { it.kind == item.kind && it.batch == item.batch &&
          (item.kind != StudyKind.NEW.name || it.originStage == item.originStage) }
          .maxOfOrNull { it.queueOrder } ?: item.queueOrder
        dao.putDailyItem(item.copy(queueOrder = last + 1))
      }
      createNext(day, StudyKind.valueOf(card.kind), item.originStage)
    }
    dao.putAppState(appState().copy(currentCardId = next?.id ?: card.id))
  }

  private suspend fun completeCycle(day: Long, item: DailyItemEntity, cycle: CycleEntity) {
    dao.putCycle(cycle.copy(status = "COMPLETED", passedDay = day))
    dao.putDailyItem(item.copy(completed = true))
    if (cycle.scope == "NORMAL") {
      val previous = dao.progress(item.wordId) ?: WordProgressEntity(item.wordId)
      dao.putProgress(previous.copy(firstPassedDay = previous.firstPassedDay ?: day,
        activeCycleId = if (previous.activeCycleId == cycle.id) null else previous.activeCycleId))
      dao.putReviewNodes(days(cycle.reviewDaysJson).map { offset ->
        ReviewNodeEntity("${cycle.id}:$offset", item.wordId, cycle.id, day + offset)
      })
    }
    val stage = cycle.originStage ?: item.originStage
    val lap = cycle.originLap ?: item.originLap
    if (stage != null && lap != null) dao.putStageCompletion(StageCompletionEntity(stage, lap, item.wordId, day))
    clearMistake(item.wordId)
  }

  private suspend fun snapshotAt(day: Long): StudySnapshot {
    val card = appState().currentCardId?.let { dao.card(it) }?.takeIf { it.day == day || it.sessionId != null }
    val practice = card?.sessionId?.let { id ->
      val session = checkNotNull(dao.practiceSession(id))
      val progress = dao.progress().associateBy { it.wordId }
      val items = dao.practiceItems(id).filter { progress[it.wordId]?.isSkipped != true &&
        (session.kind != StudyKind.MISTAKES.name || it.completed || progress[it.wordId]?.mistakePending == true) }
      PracticeProgress(StudyKind.valueOf(session.kind), items.count { it.completed }, items.size,
        paused = session.status == "PAUSED")
    }
    val summary = summaryAt(day)
    val studyCard = card?.let { cardDomain(it) }
    return StudySnapshot(summary, studyCard, practice, revision = appState().revision)
  }

  private suspend fun summaryAt(day: Long): TodaySummary {
    val stage = selectedStage()
    val state = stageState(stage)
    val plan = checkNotNull(dao.stagePlan(day, stage.rarity))
    val progress = dao.progress().associateBy { it.wordId }
    val items = dao.dailyItems(day).filter { progress[it.wordId]?.isSkipped != true }
    fun count(kind: StudyKind, complete: Boolean? = null): Int = items.count {
      it.kind == kind.name && (kind != StudyKind.NEW || it.originStage == stage.rarity) &&
        (complete == null || it.completed == complete)
    }
    val words = dao.studyWords()
    val stageWords = words.filter { it.rarity == stage.rarity && progress[it.id]?.isSkipped != true }
    val completed = dao.stageCompletions(stage.rarity, state.lap).map { it.wordId }.toSet()
    val stageProgress = StageProgress(stage, stageWords.count { it.id in completed }, stageWords.size, state.lap)
    val newWords = words.filter { progress[it.id]?.firstPassedDay == day && progress[it.id]?.isSkipped != true }
      .map { word(it.id) }
    val available = stageCandidates(stage, allowNewLap = true,
      words = words, progress = progress, completed = completed).size
    val blocker = learnMoreBlocker(day, items, stage)
    val collectionIds = words.filter { progress[it.id]?.isCollected == true && progress[it.id]?.isSkipped != true }
      .mapTo(mutableSetOf()) { it.id }
    dao.practiceSessions(StudyKind.COLLECTION.name).filter { it.status != "COMPLETED" }.forEach { session ->
      // A collection toggle changes the next batch; an existing batch remains resumable.
      collectionIds += dao.practiceItems(session.id).filter { !it.completed && progress[it.wordId]?.isSkipped != true }
        .map { it.wordId }
    }
    return TodaySummary(LocalDate.ofEpochDay(day).toString(), plan.dailyGoal, dao.wordCount(),
      dao.wordCount() - dao.startedCount(), dao.learnedCount(), count(StudyKind.NEW), count(StudyKind.NEW, true),
      count(StudyKind.REVIEW), count(StudyKind.REVIEW, true), count(StudyKind.CARRYOVER), count(StudyKind.CARRYOVER, true),
      newWords, dao.resumableWriting()?.id,
      dao.nextReviewDay(day)?.let { LocalDate.ofEpochDay(it).toString() }, available, blocker, stageProgress,
      collectionIds.size,
      words.count { progress[it.id]?.mistakePending == true && progress[it.id]?.isSkipped != true })
  }

  private suspend fun learnMoreBlocker(day: Long, items: List<DailyItemEntity>, stage: VocabularyStage): StudyKind? {
    for (kind in listOf(StudyKind.REVIEW, StudyKind.CARRYOVER, StudyKind.NEW)) {
      if (items.any { it.kind == kind.name && !it.completed &&
          (kind != StudyKind.NEW || it.originStage == stage.rarity) } || pendingDailyCard(day, kind) != null) return kind
    }
    return null
  }

  private suspend fun cardDomain(card: CardEntity): StudyCard {
    val options = strings(JSONArray(card.optionsJson)).map { id ->
      val meaning = checkNotNull(dao.meaning(id))
      val optionWord = checkNotNull(dao.word(meaning.wordId))
      AnswerOption(meaning.id, meaning.english, optionWord.hanzi, optionWord.pinyin)
    }
    val state = dao.progress(card.wordId)
    return StudyCard(card.id, word(card.wordId), StudyKind.valueOf(card.kind), CardPhase.valueOf(card.phase),
      card.round, card.targetRounds, card.reviewRecall, options, card.selectedOptionId,
      card.correct, card.writingSessionId, state?.isCollected == true, state?.isSkipped == true, card.sessionId)
  }

  private suspend fun word(id: String): WordEntry = word(checkNotNull(dao.word(id)))

  private suspend fun word(value: WordEntity): WordEntry {
    return WordEntry(value.id, value.hanzi, value.pinyin, dao.meanings(value.id).map(MeaningEntity::toDomain),
      decodeExamples(JSONArray(value.examplesJson)), decodeParts(JSONArray(value.partsJson)),
      strings(JSONArray(value.distractorsJson)), decodeExplanations(JSONArray(value.literalExplanationsJson), required = true),
      decodeExplanations(JSONArray(value.figurativeExplanationsJson), required = false))
  }

  private suspend fun createWriting(wordIds: List<String>, reason: WritingReason, returnCardId: String?, day: Long): String {
    require(wordIds.isNotEmpty() && wordIds.size <= 2000)
    wordIds.forEach { checkNotNull(dao.word(it)) }
    val session = WritingSessionEntity(newId(), stringsJson(wordIds), reason = reason.name,
      returnCardId = returnCardId, createdAt = System.currentTimeMillis(), day = day)
    dao.putWriting(session)
    return session.id
  }

  private inner class LocalWriting : WritingRepository {
    override suspend fun load(sessionId: String): WritingSnapshot = transaction {
      writingSnapshot(checkNotNull(dao.writing(sessionId)))
    }

    override suspend fun submitStroke(sessionId: String, cursor: WritingCursor, points: List<StrokePoint>): WritingSnapshot = transaction {
      val session = checkNotNull(dao.writing(sessionId))
      if (session.status != WritingStatus.ACTIVE.name || !matchesCursor(session, cursor))
        return@transaction writingSnapshot(session)
      require(points.size <= 1024 && points.all { it.x.isFinite() && it.y.isFinite() && it.x in -2f..3f && it.y in -2f..3f })
      val wordIds = strings(JSONArray(session.wordIdsJson))
      val wordId = wordIds[session.wordIndex]
      val links = dao.wordTracing(wordId)
      val link = links.getOrNull(session.characterIndex)
      val item = link?.let { dao.tracing(it.itemId)?.toDomain() }
      if (item == null) {
        val missing = session.copy(feedback = "Stroke data is unavailable. You can skip this writing session.")
        dao.putWriting(missing)
        touch()
        return@transaction writingSnapshot(missing)
      }
      val accepted = decodePoints(JSONArray(session.acceptedJson))
      val expected = item.medians.getOrNull(accepted.size)
      if (expected == null) return@transaction writingSnapshot(session)
      val error = StrokeMatcher.feedback(points, expected.map { StrokePoint(it.x / 1024f, (900f - it.y) / 1024f) })
      if (error != null) {
        val rejected = session.copy(mistakes = session.mistakes + 1, feedback = error)
        dao.putWriting(rejected)
        touch()
        return@transaction writingSnapshot(rejected)
      }
      val updatedStrokes = accepted + listOf(points)
      val now = System.currentTimeMillis()
      var updated = session.copy(acceptedJson = pointsJson(updatedStrokes), feedback = "Stroke accepted.")
      if (updatedStrokes.size == item.paths.size) {
        dao.putWritingCompletion(WritingCompletionEntity("${session.id}:${session.wordIndex}:${session.characterIndex}",
          session.id, wordId, session.characterIndex, item.id, item.revision, session.mistakes, now))
        updated = when {
          session.characterIndex + 1 < links.size -> session.copy(characterIndex = session.characterIndex + 1,
            acceptedJson = "[]", mistakes = 0, feedback = "Character completed. Write the next character.")
          session.wordIndex + 1 < wordIds.size -> session.copy(wordIndex = session.wordIndex + 1,
            characterIndex = 0, acceptedJson = "[]", mistakes = 0, feedback = "Word completed. Write the next word.")
          else -> updated.copy(status = WritingStatus.COMPLETED.name, completedAt = now, feedback = "Writing completed.")
        }
      }
      dao.putWriting(updated)
      touch()
      writingSnapshot(updated)
    }

    override suspend fun undo(sessionId: String, cursor: WritingCursor): WritingSnapshot = transaction {
      val session = checkNotNull(dao.writing(sessionId))
      if (session.status != WritingStatus.ACTIVE.name || !matchesCursor(session, cursor)) return@transaction writingSnapshot(session)
      val accepted = decodePoints(JSONArray(session.acceptedJson))
      val updated = session.copy(acceptedJson = pointsJson(accepted.dropLast(1)), feedback = "Last accepted stroke removed.")
      dao.putWriting(updated)
      touch()
      writingSnapshot(updated)
    }

    override suspend fun restartCharacter(sessionId: String, cursor: WritingCursor): WritingSnapshot = transaction {
      val session = checkNotNull(dao.writing(sessionId))
      if (session.status != WritingStatus.ACTIVE.name || !matchesCursor(session, cursor)) return@transaction writingSnapshot(session)
      val updated = session.copy(acceptedJson = "[]", mistakes = 0, feedback = null)
      dao.putWriting(updated)
      touch()
      writingSnapshot(updated)
    }

    override suspend fun skip(sessionId: String): WritingSnapshot = transaction {
      val session = checkNotNull(dao.writing(sessionId))
      if (session.status != WritingStatus.ACTIVE.name) return@transaction writingSnapshot(session)
      val updated = session.copy(status = WritingStatus.SKIPPED.name, completedAt = System.currentTimeMillis(), feedback = null)
      dao.putWriting(updated)
      touch()
      writingSnapshot(updated)
    }
  }

  /** Persisted cursor makes retry-after-commit safe for accept, reject, undo and restart alike. */
  private fun matchesCursor(session: WritingSessionEntity, cursor: WritingCursor): Boolean =
    cursor == WritingCursor(session.wordIndex, session.characterIndex,
      decodePoints(JSONArray(session.acceptedJson)).size, session.mistakes)

  private suspend fun writingSnapshot(session: WritingSessionEntity): WritingSnapshot {
    val wordIds = strings(JSONArray(session.wordIdsJson))
    require(wordIds.isNotEmpty() && session.wordIndex in wordIds.indices)
    val entry = word(wordIds[session.wordIndex])
    val needsBreakdown = entry.hanzi.codePointCount(0, entry.hanzi.length) > 1 &&
      (entry.parts.isEmpty() || (entry.parts.size == 1 && entry.parts.single().hanzi == entry.hanzi))
    val displayEntry = if (needsBreakdown) {
      val parts = withContext(Dispatchers.IO) { writingBreakdowns[writingBreakdownKey(entry.hanzi, entry.pinyin)] }
      if (parts != null) entry.copy(parts = parts) else entry
    } else entry
    val link = dao.wordTracing(entry.id).getOrNull(session.characterIndex)
    return WritingSnapshot(session.id, displayEntry, session.wordIndex, wordIds.size, session.characterIndex,
      link?.let { dao.tracing(it.itemId)?.toDomain() }, decodePoints(JSONArray(session.acceptedJson)),
      session.mistakes, WritingStatus.valueOf(session.status), WritingReason.valueOf(session.reason),
      session.feedback, session.returnCardId)
  }

  private fun SettingsEntity.toDomain() = StudySettings(dailyWords, rounds, days(reviewDaysJson), welcomed, displayName, avatarId)
}
