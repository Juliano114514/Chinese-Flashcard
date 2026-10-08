package com.example.chinese_flashcard.core.data

import android.content.Context
import androidx.room.withTransaction
import com.example.chinese_flashcard.core.domain.CsvImportPreview
import com.example.chinese_flashcard.core.domain.CsvImportReport
import com.example.chinese_flashcard.core.domain.CsvImportRepository
import com.example.chinese_flashcard.core.domain.CsvSource
import com.example.chinese_flashcard.core.domain.WordlistLoadProgress
import com.example.chinese_flashcard.core.domain.WordlistLoadStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

/** User imports append only; only the bundled preset can refresh owned teaching content. */
internal class CsvImporter(private val context: Context, private val database: FlashcardDatabase,
  private val dao: FlashcardDao, private val operationMutex: Mutex,
  private val prepare: suspend () -> Unit, private val touch: suspend () -> Unit,
  private val reconcilePresetStages: suspend () -> Unit) : CsvImportRepository {
  private val strokes = CsvStrokeResources(context)
  private val pendingMutex = Mutex()
  private data class PendingCsv(val file: File, val totalRows: Int)
  private val pending = mutableMapOf<String, PendingCsv>()
  private val directory = File(context.cacheDir, "csv-import")
  private var cleaned = false
  private val mutableProgress = MutableStateFlow<WordlistLoadProgress?>(null)
  override val progress = mutableProgress.asStateFlow()

  /** Called under the repository's seed lock; never calls back into prepare(). */
  internal suspend fun bootstrapDefaultWordlist(onProgress: (WordlistLoadProgress) -> Unit): Unit = withContext(Dispatchers.IO) {
    val reporter = WordlistProgressReporter(onProgress)
    val version = defaultWordlistVersion()
    operationMutex.withLock {
      val saved = dao.appState()
      if (saved?.seeded == true && saved.presetCsvVersion == version) return@withLock
      val expectedRows = defaultWordlistRows()
      reporter.totalWords(expectedRows)
      val file = createSnapshot()
      try {
        copySource(CsvSource { context.assets.open("default-wordlist/wordlist.csv") }, file)
        check(snapshotHash(file) == version) { "The bundled default wordlist version does not match its content." }
        val issues = CsvIssues()
        val index = strokes.index()
        val plan = validate(file, readExisting(), index, issues, reporter, expectedRows, preset = true)
        check(issues.count == 0) {
          "The default wordlist could not be added. " +
            (issues.displayed.firstOrNull()?.message ?: "The bundled CSV is invalid.")
        }
        check(plan.totalRows == expectedRows) { "The bundled wordlist count does not match its content." }
        validatePendingCards(plan)
        currentCoroutineContext().ensureActive()
        database.withTransaction {
          if (dao.settings() == null) dao.putSettings(SettingsEntity())
          if (dao.appState() == null) dao.putAppState(AppStateEntity())
          val report = append(file, plan, index, reporter)
          var updated = 0
          for ((rarity, rows) in plan.rarities.entries.groupBy { it.value }) {
            for (ids in rows.map { it.key }.chunked(100)) {
              currentCoroutineContext().ensureActive()
              updated += dao.updateDefaultRarity(ids, rarity)
            }
          }
          reorderWords()
          // Stage ownership follows the final preset classification in this same atomic commit.
          reconcilePresetStages()
          if (updated > 0 || report.addedWords > 0 || plan.refreshes.isNotEmpty()) touch()
          currentCoroutineContext().ensureActive()
          reporter.update(WordlistLoadStage.COMMITTING)
          val latest = checkNotNull(dao.appState())
          dao.putAppState(latest.copy(seeded = true, presetCsvVersion = version))
        }
        reporter.finish()
      } finally {
        file.delete()
      }
    }
  }

  private fun defaultWordlistRows(): Int = context.assets.open("default-wordlist/rows.txt")
    .bufferedReader(Charsets.US_ASCII).use { reader ->
      val buffer = CharArray(32)
      val count = reader.read(buffer)
      check(count in 1..31 && reader.read() == -1) { "The bundled wordlist count is invalid." }
      String(buffer, 0, count).trim().toIntOrNull()?.takeIf { it in 1..CSV_MAX_ROWS }
        ?: error("The bundled wordlist count is invalid.")
    }

  private fun defaultWordlistVersion(): String = context.assets.open("default-wordlist/version.txt")
    .bufferedReader(Charsets.US_ASCII).use { reader ->
      val text = StringBuilder()
      while (true) {
        val character = reader.read()
        if (character < 0) break
        check(text.length < 128) { "The bundled default wordlist version is invalid." }
        text.append(character.toChar())
      }
      text.toString().trim().also {
        check(it.matches(Regex("[0-9a-f]{64}"))) { "The bundled default wordlist version is invalid." }
      }
    }

  private suspend fun snapshotHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val block = ByteArray(8192)
      while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(block)
        if (count < 0) break
        digest.update(block, 0, count)
      }
    }
    return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
  }

  override suspend fun preview(source: CsvSource): CsvImportPreview {
    val reporter = WordlistProgressReporter({ mutableProgress.value = it }, previewOnly = true)
    var deliveredToken: String? = null
    try {
      return withContext(Dispatchers.IO) {
        prepare()
        val file = createSnapshot()
        var retained = false
        try {
          copySource(source, file)
          val issues = CsvIssues()
          val totalRows = CsvDecoder.countRows(file, issues)
          if (issues.count > 0)
            return@withContext CsvImportPreview(null, totalRows, 0, 0, issues.count, issues.displayed.toList())
          reporter.totalWords(totalRows)
          val index = try { strokes.index() }
          catch (error: CancellationException) { throw error }
          catch (_: Exception) {
            issues.add(0, "笔顺资源", "The installed stroke resources are invalid or incomplete.")
            return@withContext CsvImportPreview(null, 0, 0, 0, issues.count, issues.displayed.toList())
          }
          val existing = operationMutex.withLock { readExisting() }
          val plan = validate(file, existing, index, issues, reporter, totalRows)
          if (issues.count > 0)
            return@withContext CsvImportPreview(null, plan.totalRows, plan.newIds.size,
              plan.skipped, issues.count, issues.displayed.toList())
          try {
            strokes.verify(index) { completed, total, glyph ->
              reporter.update(WordlistLoadStage.STROKES, completed, total, glyph)
            }
          } catch (error: CancellationException) { throw error }
          catch (_: Exception) {
            issues.add(0, "笔顺资源", "The installed stroke resources are invalid or incomplete.")
            return@withContext CsvImportPreview(null, plan.totalRows, plan.newIds.size,
              plan.skipped, issues.count, issues.displayed.toList())
          }
          currentCoroutineContext().ensureActive()
          val token = UUID.randomUUID().toString()
          pendingMutex.withLock {
            // Only one uncommitted preview is retained; commits remove their file before reading it.
            pending.values.forEach { it.file.delete() }
            pending.clear()
            pending[token] = PendingCsv(file, plan.totalRows)
          }
          deliveredToken = token
          retained = true
          currentCoroutineContext().ensureActive()
          reporter.finish()
          CsvImportPreview(token, plan.totalRows, plan.newIds.size, plan.skipped, 0, emptyList())
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
          val issues = CsvIssues().also { it.add(0, "CSV", "The file could not be read. Choose a valid UTF-8 CSV up to 32 MiB.") }
          CsvImportPreview(null, 0, 0, 0, issues.count, issues.displayed.toList())
        } finally {
          if (!retained) file.delete()
        }
      }
    } catch (error: CancellationException) {
      // withContext may cancel delivery after the IO block has registered a valid token.
      deliveredToken?.let { token -> withContext(NonCancellable) { discard(token) } }
      throw error
    }
  }

  override suspend fun commit(previewId: String): CsvImportReport {
    val reporter = WordlistProgressReporter({ mutableProgress.value = it })
    return withContext(Dispatchers.IO) {
      val snapshot = pendingMutex.withLock { pending.remove(previewId) }
        ?: throw IllegalStateException("This preview has expired. Choose the CSV again.")
      val file = snapshot.file
      reporter.totalWords(snapshot.totalRows)
      try {
        prepare()
        operationMutex.withLock {
          val issues = CsvIssues()
          val index = strokes.index()
          val plan = validate(file, readExisting(), index, issues, reporter, snapshot.totalRows)
          check(issues.count == 0) { "The wordbook changed or the CSV is invalid. Choose the file again." }
          check(plan.totalRows == snapshot.totalRows) { "The CSV snapshot changed." }
          currentCoroutineContext().ensureActive()
          val report = database.withTransaction {
            append(file, plan, index, reporter).also {
              reporter.update(WordlistLoadStage.COMMITTING)
            }
          }
          reporter.finish()
          report
        }
      } finally {
        // discard is idempotent and cannot delete a file already owned by a running commit.
        file.delete()
      }
    }
  }

  override suspend fun discard(previewId: String) = withContext(NonCancellable + Dispatchers.IO) {
    pendingMutex.withLock { pending.remove(previewId)?.file?.delete() }
    Unit
  }

  suspend fun cleanStaleSnapshots() = pendingMutex.withLock {
    if (cleaned) return@withLock
    check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
    if (directory.exists()) {
      check(directory.isDirectory) { "The CSV cache is unavailable." }
      directory.listFiles()?.filter { it.isFile && it.name.startsWith("preview-") && it.name.endsWith(".csv") }
        ?.forEach { it.delete() }
    }
    cleaned = true
  }

  private suspend fun createSnapshot(): File {
    cleanStaleSnapshots()
    return pendingMutex.withLock {
      check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
      check(directory.isDirectory || directory.mkdirs()) { "The CSV cache is unavailable." }
      File.createTempFile("preview-", ".csv", directory)
    }
  }

  private suspend fun copySource(source: CsvSource, target: File) {
    source.open().use { input ->
      target.outputStream().use { output ->
        val block = ByteArray(8192)
        var bytes = 0L
        while (true) {
          currentCoroutineContext().ensureActive()
          val count = input.read(block)
          if (count < 0) break
          if (count == 0) {
            val single = input.read()
            if (single < 0) break
            bytes++
            require(bytes <= CSV_MAX_BYTES) { "The CSV exceeds 32 MiB." }
            output.write(single)
          } else {
            bytes += count
            require(bytes <= CSV_MAX_BYTES) { "The CSV exceeds 32 MiB." }
            output.write(block, 0, count)
          }
        }
      }
    }
  }

  private suspend fun readExisting(): ExistingWords {
    val words = dao.words()
    val meanings = dao.allMeanings()
    val primary = meanings.groupBy { it.wordId }.mapValues { it.value.first() }
    check(words.all { it.id in primary }) { "The saved wordbook is incomplete." }
    return ExistingWords(words.associateBy { it.id }, words.groupBy { wordIdentity(it.hanzi, it.pinyin) },
      primary, meanings.associateBy { it.id })
  }

  private suspend fun validate(file: File, existing: ExistingWords, index: StrokeIndex, issues: CsvIssues,
    reporter: WordlistProgressReporter, totalRows: Int, preset: Boolean = false): ImportPlan {
    val rows = mutableListOf<WordMetadata>()
    val seenIds = mutableSetOf<String>()
    val batchIdentities = mutableMapOf<String, String>()
    val aliases = existing.byId.keys.associateWith { it }.toMutableMap()
    val newIds = mutableSetOf<String>()
    val ownedWords = if (preset) existing.byId.values.filter { it.presetSourceId.isNotEmpty() } else emptyList()
    check(ownedWords.map { it.presetSourceId }.distinct().size == ownedWords.size) {
      "The preset ownership mapping is ambiguous."
    }
    val bySourceId = ownedWords.associateBy { it.presetSourceId }
    val refreshes = mutableMapOf<String, PresetRefresh>()
    var skipped = 0
    reporter.update(WordlistLoadStage.CHECKING, 0, totalRows)
    val total = CsvDecoder.scan(file, issues, onRecord = { count, word ->
      reporter.update(WordlistLoadStage.CHECKING, count, totalRows, word, processedWords = count)
    }) { word ->
      var valid = true
      if (!seenIds.add(word.id)) {
        issues.add(word.line, "词条ID", "Word IDs must be unique within the CSV.")
        valid = false
      }
      val sameId = existing.byId[word.id]
      val owned = bySourceId[word.id]
      if (owned != null && (sameId != null && sameId.id != owned.id ||
          Normalizer.normalize(owned.hanzi, Normalizer.Form.NFC) != word.hanzi)) {
        issues.add(word.line, "词条ID", "An owned preset ID cannot be reassigned to a different word.")
        valid = false
      } else if (owned == null && sameId != null && wordIdentity(sameId.hanzi, sameId.pinyin) != word.identity) {
        issues.add(word.line, "词条ID", "This ID already belongs to a different word or pronunciation.")
        valid = false
      }
      if (valid) {
        val canonicalId = owned?.id ?: sameId?.id ?: existing.byIdentity[word.identity]?.firstOrNull()?.id
          ?: batchIdentities[word.identity] ?: word.id
        aliases[word.id] = canonicalId
        batchIdentities[word.identity] = canonicalId
        if (canonicalId in existing.byId || canonicalId != word.id) skipped++ else newIds += word.id
        if (owned != null) refreshes[word.id] = PresetRefresh(owned, existing.primary.getValue(owned.id))
        val ids = word.glyphs.map { glyph ->
          val indexed = index.byGlyph[glyph]
          if (indexed == null) issues.add(word.line, "组词", "True stroke data is unavailable for character $glyph.")
          indexed?.id ?: glyphId(glyph)
        }
        rows += WordMetadata(word.line, word.id, canonicalId, word.english, word.distractorWordIds, ids, word.rarity)
      }
    }
    if (existing.byId.size + newIds.size > CSV_MAX_ROWS)
      issues.add(0, "CSV", "The resulting wordbook would contain more than 20,000 words.")
    val meanings = existing.primary.mapValues { it.value.id }.toMutableMap()
    val english = existing.primary.mapValues { it.value.english }.toMutableMap()
    for (row in rows.filter { it.id in newIds }) {
      val meaningId = csvMeaningId(row.id)
      if (meaningId in existing.allMeanings)
        issues.add(row.line, "词条ID", "The derived meaning ID is already used by the saved wordbook.")
      meanings[row.canonicalId] = meaningId
      english[row.canonicalId] = row.english
    }
    for (row in rows.filter { it.id in refreshes }) english[row.canonicalId] = row.english
    for (row in rows) {
      val targets = row.distractorIds.map { aliases[it] }
      if (targets.any { it == null || it !in meanings }) {
        issues.add(row.line, "干扰词ID", "A distractor word is missing from the CSV and saved wordbook.")
      } else {
        val ids = targets.filterNotNull()
        if (ids.distinct().size != 3 || row.canonicalId in ids)
          issues.add(row.line, "干扰词ID", "Distractors resolve to repeated words or this word itself.")
        val options = (listOf(row.canonicalId) + ids).map { normalizedEnglish(english.getValue(it)) }
        if (options.distinct().size != 4)
          issues.add(row.line, "干扰词ID", "The correct meaning and three distractor meanings must differ.")
      }
    }
    val projectedMeanings = existing.allMeanings.toMutableMap()
    for (row in rows.filter { it.id in refreshes }) {
      val original = refreshes.getValue(row.id).meaning
      projectedMeanings[original.id] = original.copy(english = row.english)
    }
    return ImportPlan(total, skipped, newIds, aliases, meanings,
      rows.filter { it.id in newIds }.flatMap { it.glyphIds }.toSet(),
      rows.filter { !preset || it.id in newIds || it.id in refreshes }
        .groupBy { it.canonicalId }.mapValues { (_, matches) -> matches.minOf { it.rarity } },
      preset, refreshes, projectedMeanings)
  }

  /** Cards freeze IDs and order, while labels are resolved live. Never rewrite a chosen answer. */
  private suspend fun validatePendingCards(plan: ImportPlan) {
    for (card in dao.unfinishedCards()) {
      currentCoroutineContext().ensureActive()
      val optionIds = strings(JSONArray(card.optionsJson))
      val options = optionIds.map { plan.projectedMeanings[it] ?: throw PresetCardConflictException() }
      if (optionIds.size != 4 || optionIds.distinct().size != 4 ||
          options.count { it.wordId == card.wordId } != 1 ||
          options.map { normalizedEnglish(it.english) }.distinct().size != 4 ||
          card.selectedOptionId?.let { it !in optionIds } == true)
        throw PresetCardConflictException()
    }
  }

  private suspend fun append(file: File, plan: ImportPlan, index: StrokeIndex,
    reporter: WordlistProgressReporter): CsvImportReport {
    val missingTracing = plan.glyphIds - dao.tracingIds().toSet()
    strokes.forEachBatch(index, missingTracing, { completed, total, glyph ->
      reporter.update(WordlistLoadStage.STROKES, completed, total, glyph)
    }) { batch -> dao.putTracing(batch) }
    val words = mutableListOf<WordEntity>()
    val meanings = mutableListOf<MeaningEntity>()
    val links = mutableListOf<WordTracingEntity>()
    var added = 0
    var refreshed = 0
    suspend fun flush() {
      if (words.isEmpty()) return
      dao.putWords(words.toList())
      dao.putMeanings(meanings.toList())
      dao.putWordTracing(links.toList())
      words.clear(); meanings.clear(); links.clear()
    }
    val issues = CsvIssues()
    reporter.update(WordlistLoadStage.SAVING, 0, plan.totalRows)
    val total = CsvDecoder.scan(file, issues, onRecord = { completed, word ->
      reporter.update(WordlistLoadStage.SAVING, completed, plan.totalRows, word)
    }) { word ->
      if (word.id in plan.newIds) {
        val distractors = word.distractorWordIds.map { plan.meaningIds.getValue(plan.aliases.getValue(it)) }
        words += WordEntity(word.id, word.hanzi, word.pinyin, examplesJson(word.examples), partsJson(word.parts),
          stringsJson(distractors), word.order, word.rarity,
          stringsJson(word.literalExplanations), stringsJson(word.figurativeExplanations), if (plan.preset) word.id else "")
        meanings += MeaningEntity(csvMeaningId(word.id), word.id, word.english, word.partOfSpeech, 0)
        links += word.glyphs.mapIndexed { position, glyph -> WordTracingEntity(word.id, position, glyphId(glyph)) }
        added++
      } else {
        val refresh = plan.refreshes[word.id]
        if (refresh != null) {
          val distractors = word.distractorWordIds.map { plan.meaningIds.getValue(plan.aliases.getValue(it)) }
          words += refresh.word.copy(pinyin = word.pinyin, examplesJson = examplesJson(word.examples),
            partsJson = partsJson(word.parts), distractorsJson = stringsJson(distractors),
            rarity = word.rarity, literalExplanationsJson = stringsJson(word.literalExplanations),
            figurativeExplanationsJson = stringsJson(word.figurativeExplanations), presetSourceId = word.id)
          meanings += refresh.meaning.copy(english = word.english, partOfSpeech = word.partOfSpeech)
          refreshed++
        }
      }
      if (words.size >= 100) flush()
    }
    check(issues.count == 0 && total == plan.totalRows && added == plan.newIds.size && refreshed == plan.refreshes.size) {
      "The CSV snapshot changed."
    }
    flush()
    currentCoroutineContext().ensureActive()
    if (!plan.preset) reorderWords()
    if (added > 0 && !plan.preset) touch()
    return CsvImportReport(added, plan.skipped)
  }

  /** Called inside the same transaction that publishes imported or refreshed words. */
  private suspend fun reorderWords() {
    for ((rank, word) in PinyinOrdering.sorted(dao.words()).withIndex()) {
      currentCoroutineContext().ensureActive()
      if (word.sortOrder != rank) dao.updateWordOrder(word.id, rank)
    }
  }
}

private data class ExistingWords(val byId: Map<String, WordEntity>,
  val byIdentity: Map<String, List<WordEntity>>, val primary: Map<String, MeaningEntity>,
  val allMeanings: Map<String, MeaningEntity>)
private data class WordMetadata(val line: Int, val id: String, val canonicalId: String,
  val english: String, val distractorIds: List<String>, val glyphIds: List<String>, val rarity: Int)
private data class ImportPlan(val totalRows: Int, val skipped: Int, val newIds: Set<String>,
  val aliases: Map<String, String>, val meaningIds: Map<String, String>, val glyphIds: Set<String>,
  val rarities: Map<String, Int>, val preset: Boolean, val refreshes: Map<String, PresetRefresh>,
  val projectedMeanings: Map<String, MeaningEntity>)
private data class PresetRefresh(val word: WordEntity, val meaning: MeaningEntity)

internal class PresetCardConflictException : IllegalStateException(
  "Finish the saved study card, then retry the default wordlist update.")
