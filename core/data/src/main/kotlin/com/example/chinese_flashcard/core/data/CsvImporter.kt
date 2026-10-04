package com.example.chinese_flashcard.core.data

import android.content.Context
import androidx.room.withTransaction
import com.example.chinese_flashcard.core.domain.CsvImportPreview
import com.example.chinese_flashcard.core.domain.CsvImportReport
import com.example.chinese_flashcard.core.domain.CsvImportRepository
import com.example.chinese_flashcard.core.domain.CsvSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Append-only import. A preview owns an immutable private snapshot, never the provider's URI. */
internal class CsvImporter(private val context: Context, private val database: FlashcardDatabase,
  private val dao: FlashcardDao, private val operationMutex: Mutex,
  private val prepare: suspend () -> Unit, private val touch: suspend () -> Unit) : CsvImportRepository {
  private val strokes = CsvStrokeResources(context)
  private val pendingMutex = Mutex()
  private val pending = mutableMapOf<String, File>()
  private val directory = File(context.cacheDir, "csv-import")
  private var cleaned = false

  override suspend fun preview(source: CsvSource): CsvImportPreview {
    var deliveredToken: String? = null
    try {
      return withContext(Dispatchers.IO) {
        prepare()
        val file = createSnapshot()
        var retained = false
        try {
          copySource(source, file)
          val issues = CsvIssues()
          val index = try { strokes.index() }
          catch (error: CancellationException) { throw error }
          catch (_: Exception) {
            issues.add(0, "笔顺资源", "The installed stroke resources are invalid or incomplete.")
            return@withContext CsvImportPreview(null, 0, 0, 0, issues.count, issues.displayed.toList())
          }
          val existing = operationMutex.withLock { readExisting() }
          val plan = validate(file, existing, index, issues)
          if (issues.count > 0)
            return@withContext CsvImportPreview(null, plan.totalRows, plan.newIds.size,
              plan.skipped, issues.count, issues.displayed.toList())
          currentCoroutineContext().ensureActive()
          val token = UUID.randomUUID().toString()
          pendingMutex.withLock {
            // Only one uncommitted preview is retained; commits remove their file before reading it.
            pending.values.forEach { it.delete() }
            pending.clear()
            pending[token] = file
          }
          deliveredToken = token
          retained = true
          currentCoroutineContext().ensureActive()
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

  override suspend fun commit(previewId: String): CsvImportReport = withContext(Dispatchers.IO) {
    val file = pendingMutex.withLock { pending.remove(previewId) }
      ?: throw IllegalStateException("This preview has expired. Choose the CSV again.")
    try {
      prepare()
      operationMutex.withLock {
        val issues = CsvIssues()
        val index = strokes.index()
        val plan = validate(file, readExisting(), index, issues)
        check(issues.count == 0) { "The wordbook changed or the CSV is invalid. Choose the file again." }
        currentCoroutineContext().ensureActive()
        database.withTransaction { append(file, plan, index) }
      }
    } finally {
      // discard is idempotent and cannot delete a file already owned by a running commit.
      file.delete()
    }
  }

  override suspend fun discard(previewId: String) = withContext(NonCancellable + Dispatchers.IO) {
    pendingMutex.withLock { pending.remove(previewId)?.delete() }
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
    val words = dao.wordIdentities()
    val meanings = dao.allMeanings()
    val primary = meanings.groupBy { it.wordId }.mapValues { it.value.first() }
    check(words.all { it.id in primary }) { "The saved wordbook is incomplete." }
    return ExistingWords(words.associateBy { it.id }, words.groupBy { wordIdentity(it.hanzi, it.pinyin) },
      primary, meanings.associateBy { it.id })
  }

  private suspend fun validate(file: File, existing: ExistingWords, index: StrokeIndex, issues: CsvIssues): ImportPlan {
    val rows = mutableListOf<WordMetadata>()
    val seenIds = mutableSetOf<String>()
    val batchIdentities = mutableMapOf<String, String>()
    val aliases = existing.byId.keys.associateWith { it }.toMutableMap()
    val newIds = mutableSetOf<String>()
    var skipped = 0
    val total = CsvDecoder.scan(file, issues) { word ->
      var valid = true
      if (!seenIds.add(word.id)) {
        issues.add(word.line, "词条ID", "Word IDs must be unique within the CSV.")
        valid = false
      }
      val sameId = existing.byId[word.id]
      if (sameId != null && wordIdentity(sameId.hanzi, sameId.pinyin) != word.identity) {
        issues.add(word.line, "词条ID", "This ID already belongs to a different word or pronunciation.")
        valid = false
      }
      if (valid) {
        val canonicalId = sameId?.id ?: existing.byIdentity[word.identity]?.firstOrNull()?.id
          ?: batchIdentities[word.identity] ?: word.id
        aliases[word.id] = canonicalId
        batchIdentities[word.identity] = canonicalId
        if (canonicalId in existing.byId || canonicalId != word.id) skipped++ else newIds += word.id
        val ids = word.glyphs.map { glyph ->
          val indexed = index.byGlyph[glyph]
          if (indexed == null) issues.add(word.line, "组词", "True stroke data is unavailable for character $glyph.")
          indexed?.id ?: glyphId(glyph)
        }
        rows += WordMetadata(word.line, word.id, canonicalId, word.english, word.distractorWordIds, ids)
      }
    }
    if (existing.byId.size + newIds.size > CSV_MAX_ROWS)
      issues.add(0, "CSV", "The resulting wordbook would contain more than 10,000 words.")
    val meanings = existing.primary.mapValues { it.value.id }.toMutableMap()
    val english = existing.primary.mapValues { it.value.english }.toMutableMap()
    for (row in rows.filter { it.id in newIds }) {
      val meaningId = csvMeaningId(row.id)
      if (meaningId in existing.allMeanings)
        issues.add(row.line, "词条ID", "The derived meaning ID is already used by the saved wordbook.")
      meanings[row.canonicalId] = meaningId
      english[row.canonicalId] = row.english
    }
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
    return ImportPlan(total, skipped, newIds, aliases, meanings,
      rows.filter { it.id in newIds }.flatMap { it.glyphIds }.toSet())
  }

  private suspend fun append(file: File, plan: ImportPlan, index: StrokeIndex): CsvImportReport {
    if (plan.newIds.isEmpty()) return CsvImportReport(0, plan.skipped)
    val maximumOrder = dao.maximumWordOrder() ?: -1
    check(maximumOrder.toLong() + plan.totalRows < Int.MAX_VALUE) { "The saved wordbook order is invalid." }
    val missingTracing = plan.glyphIds - dao.tracingIds().toSet()
    strokes.forEachBatch(index, missingTracing) { batch -> dao.putTracing(batch) }
    val words = mutableListOf<WordEntity>()
    val meanings = mutableListOf<MeaningEntity>()
    val links = mutableListOf<WordTracingEntity>()
    var added = 0
    suspend fun flush() {
      if (words.isEmpty()) return
      dao.putWords(words.toList())
      dao.putMeanings(meanings.toList())
      dao.putWordTracing(links.toList())
      words.clear(); meanings.clear(); links.clear()
    }
    val issues = CsvIssues()
    val total = CsvDecoder.scan(file, issues) { word ->
      if (word.id in plan.newIds) {
        val distractors = word.distractorWordIds.map { plan.meaningIds.getValue(plan.aliases.getValue(it)) }
        words += WordEntity(word.id, word.hanzi, word.pinyin, examplesJson(word.examples), partsJson(word.parts),
          word.note, stringsJson(distractors), maximumOrder + word.order + 1, word.rarity)
        meanings += MeaningEntity(csvMeaningId(word.id), word.id, word.english, word.partOfSpeech, 0)
        links += word.glyphs.mapIndexed { position, glyph -> WordTracingEntity(word.id, position, glyphId(glyph)) }
        added++
        if (words.size >= 100) flush()
      }
    }
    check(issues.count == 0 && total == plan.totalRows && added == plan.newIds.size) { "The CSV snapshot changed." }
    flush()
    currentCoroutineContext().ensureActive()
    touch()
    return CsvImportReport(added, plan.skipped)
  }
}

private data class ExistingWords(val byId: Map<String, WordIdentity>,
  val byIdentity: Map<String, List<WordIdentity>>, val primary: Map<String, MeaningEntity>,
  val allMeanings: Map<String, MeaningEntity>)
private data class WordMetadata(val line: Int, val id: String, val canonicalId: String,
  val english: String, val distractorIds: List<String>, val glyphIds: List<String>)
private data class ImportPlan(val totalRows: Int, val skipped: Int, val newIds: Set<String>,
  val aliases: Map<String, String>, val meaningIds: Map<String, String>, val glyphIds: Set<String>)
