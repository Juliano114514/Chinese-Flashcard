package com.example.chinese_flashcard.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.example.chinese_flashcard.core.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** One application-scoped owner. No destructive migration or read-error reset is installed. */
class FlashcardRepositories(context: Context) {
  private val app = context.applicationContext
  private val database = Room.databaseBuilder(app, FlashcardDatabase::class.java, "chinese-flashcard-v1.db")
    .addMigrations(FLASHCARD_MIGRATION_1_2).build()
  private val dao = database.flashcards()
  private val seedMutex = Mutex()
  private val operationMutex = Mutex()

  val study: StudyRepository = LocalStudy()
  val settings: SettingsRepository = LocalSettings()
  val writing: WritingRepository = LocalWriting()
  private val csvImporter = CsvImporter(app, database, dao, operationMutex, ::prepare, ::touch)
  val csvImport: CsvImportRepository = csvImporter

  suspend fun prepare() {
    seedMutex.withLock {
      csvImporter.cleanStaleSnapshots()
      if (dao.appState()?.seeded == true) return
      val content = withContext(Dispatchers.IO) { DemoDecoder.read(app) }
      database.withTransaction {
        if (dao.appState()?.seeded != true) {
          dao.putWords(content.words)
          dao.putMeanings(content.meanings)
          dao.putTracing(content.tracing)
          dao.putWordTracing(content.links)
          if (dao.settings() == null) dao.putSettings(SettingsEntity())
          dao.putAppState((dao.appState() ?: AppStateEntity()).copy(seeded = true, revision = 1))
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

  private inner class LocalSettings : SettingsRepository {
    override val settings: Flow<StudySettings> = flow {
      prepare()
      emitAll(dao.observeSettings().map { checkNotNull(it).toDomain().also(StudySettings::validate) }.distinctUntilChanged())
    }
    override suspend fun save(value: StudySettings) {
      value.validate()
      transaction {
        dao.putSettings(SettingsEntity(dailyWords = value.dailyWords, rounds = value.rounds,
          reviewDaysJson = daysJson(value.reviewDays), welcomed = value.welcomed))
        touch()
      }
    }
  }

  private inner class LocalStudy : StudyRepository {
    override val changes: Flow<Long> = flow {
      prepare()
      emitAll(dao.observeRevision().map { checkNotNull(it) }.distinctUntilChanged())
    }

    override suspend fun snapshot(): StudySnapshot = transaction {
      val day = ensureToday()
      snapshotAt(day)
    }

    override suspend fun start(kind: StudyKind): StudySnapshot = transaction {
      val day = ensureToday()
      val previous = appState()
      if (previous.selectedKind != kind.name) {
        val old = previous.currentCardId?.let { dao.card(it) }
        if (old != null && old.day == day && old.correct == true &&
          old.phase in listOf(CardPhase.FEEDBACK.name, CardPhase.EXPLANATION.name) &&
          dao.dailyItem(day, old.wordId)?.completed == true) {
          // The completed entry can become disabled on Today; its feedback must not remain stranded.
          dao.putCard(old.copy(phase = CardPhase.FINISHED.name))
        }
      }
      val pending = dao.pendingCard(day, kind.name)
      val card = pending ?: createNext(day, kind) ?: dao.finishedCard(day, kind.name)
      dao.putAppState(appState().copy(selectedKind = kind.name, currentCardId = card?.id))
      touch()
      snapshotAt(day)
    }

    override suspend fun submit(cardId: String, optionId: String?): StudySnapshot = cardTransaction(cardId) { day, card ->
      if (card.phase != CardPhase.QUESTION.name) return@cardTransaction snapshotAt(day)
      val optionIds = strings(JSONArray(card.optionsJson))
      require(optionId == null || optionId in optionIds)
      val correct = optionId != null && dao.meanings(card.wordId).any { it.id == optionId }
      val item = checkNotNull(dao.dailyItem(day, card.wordId))
      val cycle = checkNotNull(dao.cycle(item.cycleId))
      var answered = card.copy(phase = CardPhase.FEEDBACK.name, selectedOptionId = optionId, correct = correct)
      if (card.reviewRecall) {
        if (correct) {
          // One blind recall consumes every already-due node for this word, never future nodes.
          dao.putReviewNodes(dao.dueNodes(day).filter { it.wordId == card.wordId }.map { it.copy(status = "CONSUMED") })
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
        CardPhase.FEEDBACK -> {
          if (card.correct == false) dao.putCard(card.copy(phase = CardPhase.EXPLANATION.name))
          else finishAndSelect(card, day)
        }
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
      // Finish feedback/automatic writing before presenting the soft daily invitation.
      val pending = appState().currentCardId?.let { dao.card(it) }?.phase?.let { it != CardPhase.FINISHED.name } == true
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

  /** Commit a new daily plan before reporting a stale answer; throwing inside Room would roll it back. */
  private suspend fun cardTransaction(id: String, operation: suspend (Long, CardEntity) -> StudySnapshot): StudySnapshot {
    var changedDay = false
    val result = transaction {
      val day = ensureToday()
      val saved = dao.card(id)
      if (saved != null && saved.day != day) {
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

  /** A date freezes its goal and selection once, and never charges unfinished work to new quota. */
  private suspend fun ensureToday(): Long {
    val day = today()
    val state = appState()
    if (state.day == day && dao.plan(day) != null) return day
    dao.oldCards(day).forEach { dao.putCard(it.copy(phase = CardPhase.FINISHED.name)) }
    if (dao.plan(day) == null) {
      val value = savedSettings()
      dao.putPlan(DailyPlanEntity(day, value.dailyWords))
      val words = dao.words()
      val order = words.associate { it.id to it.sortOrder }
      val active = dao.activeCycles().sortedBy { order[it.wordId] ?: Int.MAX_VALUE }
      active.forEachIndexed { index, cycle ->
        val reset = cycle.copy(correctRounds = 0)
        dao.putCycle(reset)
        dao.putDailyItem(DailyItemEntity(day, cycle.wordId, StudyKind.CARRYOVER.name,
          cycle.id, index / 5, index.toLong()))
      }
      val activeIds = active.map { it.wordId }.toSet()
      val due = dao.dueNodes(day).filter { it.wordId !in activeIds }.distinctBy { it.wordId }
      due.forEachIndexed { index, node ->
        dao.putDailyItem(DailyItemEntity(day, node.wordId, StudyKind.REVIEW.name,
          node.cycleId, index / 5, index.toLong(), reviewRecall = true))
      }
      val progress = dao.progress().associateBy { it.wordId }
      val newWords = words.filter { progress[it.id]?.firstPassedDay == null && it.id !in activeIds }
        .take(value.dailyWords)
      newWords.forEachIndexed { index, word ->
        val cycle = CycleEntity(newId(), word.id, value.rounds, daysJson(value.reviewDays), startedDay = day)
        dao.putCycle(cycle)
        dao.putProgress((progress[word.id] ?: WordProgressEntity(word.id)).copy(activeCycleId = cycle.id))
        dao.putDailyItem(DailyItemEntity(day, word.id, StudyKind.NEW.name, cycle.id, index / 5, index.toLong()))
      }
    }
    dao.putAppState(state.copy(day = day, selectedKind = null, currentCardId = null, revision = state.revision + 1))
    return day
  }

  private suspend fun currentCard(id: String, day: Long): CardEntity? {
    val card = dao.card(id) ?: return null
    if (card.day != day) return null
    if (appState().currentCardId != id || card.phase == CardPhase.FINISHED.name) return null
    return card
  }

  private suspend fun createNext(day: Long, kind: StudyKind): CardEntity? {
    val item = dao.dailyItems(day).filter { it.kind == kind.name && !it.completed }
      .minWithOrNull(compareBy<DailyItemEntity> { it.batch }.thenBy { it.queueOrder }) ?: return null
    val cycle = checkNotNull(dao.cycle(item.cycleId))
    val word = word(item.wordId)
    val progress = dao.progress(item.wordId) ?: WordProgressEntity(item.wordId)
    val introduction = !item.reviewRecall && !progress.firstEncounterShown
    val optionIds = (listOf(word.meanings.first().id) + word.distractorMeaningIds).shuffled()
    val card = CardEntity(newId(), day, item.wordId, cycle.id, kind.name,
      if (introduction) CardPhase.INTRO.name else CardPhase.QUESTION.name,
      if (item.reviewRecall) 1 else cycle.correctRounds + 1,
      if (item.reviewRecall) 1 else cycle.targetRounds, item.reviewRecall,
      stringsJson(optionIds), createdAt = System.currentTimeMillis())
    dao.putCard(card)
    return card
  }

  private suspend fun finishAndSelect(card: CardEntity, day: Long) {
    dao.putCard(card.copy(phase = CardPhase.FINISHED.name))
    val item = checkNotNull(dao.dailyItem(day, card.wordId))
    if (!item.completed) {
      val last = dao.dailyItems(day).filter { it.kind == item.kind && it.batch == item.batch }
        .maxOfOrNull { it.queueOrder } ?: item.queueOrder
      dao.putDailyItem(item.copy(queueOrder = last + 1))
    }
    val next = createNext(day, StudyKind.valueOf(card.kind))
    dao.putAppState(appState().copy(currentCardId = next?.id ?: card.id))
  }

  private suspend fun completeCycle(day: Long, item: DailyItemEntity, cycle: CycleEntity) {
    dao.putCycle(cycle.copy(status = "COMPLETED", passedDay = day))
    val previous = dao.progress(item.wordId) ?: WordProgressEntity(item.wordId)
    dao.putProgress(previous.copy(firstPassedDay = previous.firstPassedDay ?: day, activeCycleId = null))
    dao.putDailyItem(item.copy(completed = true))
    dao.putReviewNodes(days(cycle.reviewDaysJson).map { offset ->
      ReviewNodeEntity("${cycle.id}:$offset", item.wordId, cycle.id, day + offset)
    })
  }

  private suspend fun snapshotAt(day: Long): StudySnapshot {
    val card = appState().currentCardId?.let { dao.card(it) }
      ?.takeIf { it.day == day }
    return StudySnapshot(summaryAt(day), card?.let { cardDomain(it) })
  }

  private suspend fun summaryAt(day: Long): TodaySummary {
    val plan = checkNotNull(dao.plan(day))
    val items = dao.dailyItems(day)
    fun count(kind: StudyKind, complete: Boolean? = null): Int = items.count {
      it.kind == kind.name && (complete == null || it.completed == complete)
    }
    val learned = dao.learnedCount()
    val total = dao.wordCount()
    val newWordIds = dao.progress().filter { it.firstPassedDay == day }.map { it.wordId }.toSet()
    val newWords = dao.words().filter { it.id in newWordIds }.map { word(it.id) }
    return TodaySummary(LocalDate.ofEpochDay(day).toString(), plan.dailyGoal, total,
      total - dao.startedCount(), learned, count(StudyKind.NEW), count(StudyKind.NEW, true),
      count(StudyKind.REVIEW), count(StudyKind.REVIEW, true),
      count(StudyKind.CARRYOVER), count(StudyKind.CARRYOVER, true), newWords,
      dao.resumableWriting()?.id,
      dao.nextReviewDay(day)?.let { LocalDate.ofEpochDay(it).toString() })
  }

  private suspend fun cardDomain(card: CardEntity): StudyCard {
    val options = strings(JSONArray(card.optionsJson)).map { id ->
      val meaning = checkNotNull(dao.meaning(id))
      AnswerOption(meaning.id, meaning.english)
    }
    return StudyCard(card.id, word(card.wordId), StudyKind.valueOf(card.kind), CardPhase.valueOf(card.phase),
      card.round, card.targetRounds, card.reviewRecall, options, card.selectedOptionId,
      card.correct, card.writingSessionId)
  }

  private suspend fun word(id: String): WordEntry {
    val value = checkNotNull(dao.word(id))
    return WordEntry(value.id, value.hanzi, value.pinyin, dao.meanings(id).map(MeaningEntity::toDomain),
      decodeExamples(JSONArray(value.examplesJson)), decodeParts(JSONArray(value.partsJson)),
      value.note, strings(JSONArray(value.distractorsJson)))
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
    val link = dao.wordTracing(entry.id).getOrNull(session.characterIndex)
    return WritingSnapshot(session.id, entry, session.wordIndex, wordIds.size, session.characterIndex,
      link?.let { dao.tracing(it.itemId)?.toDomain() }, decodePoints(JSONArray(session.acceptedJson)),
      session.mistakes, WritingStatus.valueOf(session.status), WritingReason.valueOf(session.reason),
      session.feedback, session.returnCardId)
  }

  private fun SettingsEntity.toDomain() = StudySettings(dailyWords, rounds, days(reviewDaysJson), welcomed)
}
