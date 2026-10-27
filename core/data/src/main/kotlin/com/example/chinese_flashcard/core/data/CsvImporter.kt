package com.example.chinese_flashcard.core.data

import android.content.Context
import androidx.room.withTransaction
import com.example.chinese_flashcard.core.domain.CsvImportPreview
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.UiMessageKey
import com.example.chinese_flashcard.core.domain.UiMessageException
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
  private val reconcilePresetStages: suspend (Map<String, Int>) -> Unit) : CsvImportRepository {
  private val strokes = CsvStrokeResources(context)
  private val headwordReplacements by lazy { BundledHeadwordReplacements.read(context) }
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
        check(snapshotHash(file) == version) { UiMessageKey.THE_BUNDLED_DEFAULT_WORDLIST_VERSION_DOES_NOT_MATCH_ITS_CONTENT }
        val issues = CsvIssues()
        val index = strokes.index()
        val existing = readExisting()
        val plan = validate(file, existing, index, issues, reporter, expectedRows, preset = true)
        check(issues.count == 0) {
          (issues.displayed.firstOrNull()?.message ?: UiMessageKey.THE_BUNDLED_CSV_IS_INVALID).name
        }
        check(plan.totalRows == expectedRows) { UiMessageKey.THE_BUNDLED_WORDLIST_COUNT_DOES_NOT_MATCH_ITS_CONTENT }
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
          reconcilePresetStages(existing.byId.mapValues { it.value.rarity })
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
      check(count in 1..31 && reader.read() == -1) { UiMessageKey.THE_BUNDLED_WORDLIST_COUNT_IS_INVALID }
      String(buffer, 0, count).trim().toIntOrNull()?.takeIf { it in 1..CSV_MAX_ROWS }
        ?: error(UiMessageKey.THE_BUNDLED_WORDLIST_COUNT_IS_INVALID)
    }

  private fun defaultWordlistVersion(): String = context.assets.open("default-wordlist/version.txt")
    .bufferedReader(Charsets.US_ASCII).use { reader ->
      val text = StringBuilder()
      while (true) {
        val character = reader.read()
        if (character < 0) break
        check(text.length < 128) { UiMessageKey.THE_BUNDLED_DEFAULT_WORDLIST_VERSION_IS_INVALID }
        text.append(character.toChar())
      }
      text.toString().trim().also {
        check(it.matches(Regex("[0-9a-f]{64}"))) { UiMessageKey.THE_BUNDLED_DEFAULT_WORDLIST_VERSION_IS_INVALID }
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
            issues.add(0, "笔顺资源", UiMessageKey.THE_INSTALLED_STROKE_RESOURCES_ARE_INVALID_OR_INCOMPLETE)
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
            issues.add(0, "笔顺资源", UiMessageKey.THE_INSTALLED_STROKE_RESOURCES_ARE_INVALID_OR_INCOMPLETE)
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
          val issues = CsvIssues().also { it.add(0, "CSV", UiMessageKey.THE_FILE_COULD_NOT_BE_READ_CHOOSE_A_VALID_UTF_8_CSV_UP_TO_32_MIB) }
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
        ?: throw UiMessageException(UiMessageKey.THIS_PREVIEW_HAS_EXPIRED_CHOOSE_THE_CSV_AGAIN)
      val file = snapshot.file
      reporter.totalWords(snapshot.totalRows)
      try {
        prepare()
        operationMutex.withLock {
          val issues = CsvIssues()
          val index = strokes.index()
          val plan = validate(file, readExisting(), index, issues, reporter, snapshot.totalRows)
          check(issues.count == 0) { UiMessageKey.THE_WORDBOOK_CHANGED_OR_THE_CSV_IS_INVALID_CHOOSE_THE_FILE_AGAIN }
          check(plan.totalRows == snapshot.totalRows) { UiMessageKey.THE_CSV_SNAPSHOT_CHANGED }
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
      check(directory.isDirectory) { UiMessageKey.THE_CSV_CACHE_IS_UNAVAILABLE }
      directory.listFiles()?.filter { it.isFile && it.name.startsWith("preview-") && it.name.endsWith(".csv") }
        ?.forEach { it.delete() }
    }
    cleaned = true
  }

  private suspend fun createSnapshot(): File {
    cleanStaleSnapshots()
    return pendingMutex.withLock {
      check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
      check(directory.isDirectory || directory.mkdirs()) { UiMessageKey.THE_CSV_CACHE_IS_UNAVAILABLE }
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
            require(bytes <= CSV_MAX_BYTES) { UiMessageKey.THE_CSV_EXCEEDS_32_MIB }
            output.write(single)
          } else {
            bytes += count
            require(bytes <= CSV_MAX_BYTES) { UiMessageKey.THE_CSV_EXCEEDS_32_MIB }
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
    check(words.all { it.id in primary }) { UiMessageKey.THE_SAVED_WORDBOOK_IS_INCOMPLETE }
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
      UiMessageKey.THE_PRESET_OWNERSHIP_MAPPING_IS_AMBIGUOUS
    }
    val bySourceId = ownedWords.associateBy { it.presetSourceId }
    val refreshes = mutableMapOf<String, PresetRefresh>()
    val projectedHanzi = existing.byId.mapValues { it.value.hanzi }.toMutableMap()
    var skipped = 0
    reporter.update(WordlistLoadStage.CHECKING, 0, totalRows)
    val total = CsvDecoder.scan(file, issues, onRecord = { count, word ->
      reporter.update(WordlistLoadStage.CHECKING, count, totalRows, word, processedWords = count)
    }) { word ->
      var valid = true
      if (!seenIds.add(word.id)) {
        issues.add(word.line, "词条ID", UiMessageKey.WORD_IDS_MUST_BE_UNIQUE_WITHIN_THE_CSV)
        valid = false
      }
      val sameId = existing.byId[word.id]
      val owned = bySourceId[word.id]
      val userIdCollision = preset && owned == null && sameId != null &&
        sameId.presetSourceId.isEmpty()
      if (owned != null && (sameId != null && sameId.id != owned.id && sameId.presetSourceId.isNotEmpty() ||
          Normalizer.normalize(owned.hanzi, Normalizer.Form.NFC) != word.hanzi &&
          !headwordReplacements.allows(owned.presetSourceId, owned.hanzi, word.hanzi, word.pinyin))) {
        issues.add(word.line, "词条ID", UiMessageKey.AN_OWNED_PRESET_ID_CANNOT_BE_REASSIGNED_TO_A_DIFFERENT_WORD)
        valid = false
      } else if (owned == null && sameId != null && !userIdCollision && wordIdentity(sameId.hanzi, sameId.pinyin) != word.identity) {
        issues.add(word.line, "词条ID", UiMessageKey.THIS_ID_ALREADY_BELONGS_TO_A_DIFFERENT_WORD_OR_PRONUNCIATION)
        valid = false
      }
      if (valid) {
        val fallbackId = if (userIdCollision) {
          var candidate = "preset_${word.id}"
          var suffix = 1
          while (candidate in existing.byId || candidate in aliases.values) candidate = "preset_${word.id}_${suffix++}"
          candidate
        } else word.id
        // A preset must not adopt a user-authored word merely because its spelling matches.
        val canonicalId = if (preset) owned?.id ?: fallbackId else
          sameId?.id ?: existing.byIdentity[word.identity]?.firstOrNull()?.id ?: batchIdentities[word.identity] ?: word.id
        aliases[word.id] = canonicalId
        projectedHanzi[canonicalId] = word.hanzi
        batchIdentities[word.identity] = canonicalId
        if (canonicalId in existing.byId || (canonicalId != word.id && canonicalId != fallbackId)) skipped++ else newIds += word.id
        if (owned != null) refreshes[word.id] = PresetRefresh(owned, existing.primary.getValue(owned.id))
        val ids = word.glyphs.map { glyph ->
          val indexed = index.byGlyph[glyph]
          if (indexed == null) issues.add(word.line, "组词", UiMessageKey.TRUE_STROKE_DATA_IS_UNAVAILABLE_FOR_CHARACTER_1_S, arguments = listOf(glyph))
          indexed?.id ?: glyphId(glyph)
        }
        rows += WordMetadata(word.line, word.id, canonicalId, word.english, word.distractorWordIds, ids, word.rarity)
      }
    }
    check(!preset || ownedWords.all { it.presetSourceId in seenIds }) {
      "A bundled vocabulary update cannot remove an existing preset word."
    }
    // A preset refresh must retain the old user-imported library as well as the new preset.
    val savedWordLimit = if (preset) CSV_MAX_ROWS * 2 else CSV_MAX_ROWS
    check(!preset || existing.byId.size + newIds.size <= savedWordLimit) { UiMessageKey.THE_BUNDLED_CSV_IS_INVALID }
    if (!preset && existing.byId.size + newIds.size > savedWordLimit)
      issues.add(0, "CSV", UiMessageKey.THE_RESULTING_WORDBOOK_WOULD_CONTAIN_MORE_THAN_20_000_WORDS)
    val meanings = existing.primary.mapValues { it.value.id }.toMutableMap()
    val english = existing.primary.mapValues { it.value.english }.toMutableMap()
    for (row in rows.filter { it.id in newIds }) {
      val meaningId = csvMeaningId(row.canonicalId)
      if (meaningId in existing.allMeanings)
        issues.add(row.line, "词条ID", UiMessageKey.THE_DERIVED_MEANING_ID_IS_ALREADY_USED_BY_THE_SAVED_WORDBOOK)
      meanings[row.canonicalId] = meaningId
      english[row.canonicalId] = row.english
    }
    for (row in rows.filter { it.id in refreshes }) english[row.canonicalId] = row.english
    for (row in rows) {
      val targets = row.distractorIds.map { aliases[it] }
      if (targets.any { it == null || it !in meanings }) {
        issues.add(row.line, "干扰词ID", UiMessageKey.A_DISTRACTOR_WORD_IS_MISSING_FROM_THE_CSV_AND_SAVED_WORDBOOK)
      } else {
        val ids = targets.filterNotNull()
        if (ids.distinct().size != 3 || row.canonicalId in ids)
          issues.add(row.line, "干扰词ID", UiMessageKey.DISTRACTORS_RESOLVE_TO_REPEATED_WORDS_OR_THIS_WORD_ITSELF)
        val options = (listOf(row.canonicalId) + ids).map { normalizedEnglish(english.getValue(it)) }
        if (options.distinct().size != 4)
          issues.add(row.line, "干扰词ID", UiMessageKey.THE_CORRECT_MEANING_AND_THREE_DISTRACTOR_MEANINGS_MUST_DIFFER)
      }
    }
    val projectedMeanings = existing.allMeanings.toMutableMap()
    for (row in rows.filter { it.id in refreshes }) {
      val original = refreshes.getValue(row.id).meaning
      projectedMeanings[original.id] = original.copy(english = row.english)
    }
    return ImportPlan(total, skipped, newIds, aliases, meanings,
      rows.filter { it.id in newIds || it.id in refreshes }.flatMap { it.glyphIds }.toSet(),
      rows.filter { !preset || it.id in newIds || it.id in refreshes }
        .groupBy { it.canonicalId }.mapValues { (_, matches) -> matches.minOf { it.rarity } },
      preset, refreshes, projectedMeanings, projectedHanzi)
  }

  /** Cards freeze IDs and order, while labels are resolved live. Never rewrite a chosen answer. */
  private suspend fun validatePendingCards(plan: ImportPlan) {
    val changedWords = plan.refreshes.values.filter {
      it.word.hanzi != plan.projectedHanzi[it.word.id]
    }.mapTo(mutableSetOf()) { it.word.id }
    if (changedWords.isNotEmpty()) {
      // Accepted strokes refer to the old glyph positions. Wait for that writing task
      // to finish rather than resetting it or carrying those strokes into another word.
      for (writing in dao.unfinishedWritingAll()) {
        val ids = strings(JSONArray(writing.wordIdsJson))
        val remaining = ids.drop(writing.wordIndex)
        if (remaining.any { it in changedWords }) {
          val currentWord = ids.getOrNull(writing.wordIndex)?.let { dao.word(it) }
            ?: throw PresetCardConflictException()
          throw PresetWritingConflictException(PresetWritingResume(writing.id,
            AppLanguage.valueOf(writing.learningLanguage), currentWord.hanzi))
        }
      }
    }
    for (card in dao.unfinishedCardsAll()) {
      currentCoroutineContext().ensureActive()
      val optionIds = strings(JSONArray(card.optionsJson))
      if (optionIds.isEmpty()) continue // A view-only card never asserted a correct option.
      val options = optionIds.map { plan.projectedMeanings[it] ?: throw PresetCardConflictException() }
      val senseKeys = options.map { englishSenseKeys(it.english) }
      if (optionIds.size != 4 || optionIds.distinct().size != 4 ||
          options.count { it.wordId == card.wordId } != 1 ||
          options.map { normalizedEnglish(it.english) }.distinct().size != 4 ||
          options.map { plan.projectedHanzi[it.wordId] }.distinct().size != 4 ||
          senseKeys.indices.any { left -> (left + 1 until senseKeys.size).any { right ->
            senseKeys[left].any { it in senseKeys[right] }
          } } ||
          card.questionMeaningId !in optionIds ||
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
    val relinkedWords = mutableListOf<String>()
    var added = 0
    var refreshed = 0
    suspend fun flush() {
      if (words.isEmpty()) return
      dao.putWords(words.toList())
      dao.putMeanings(meanings.toList())
      // Only the word-to-glyph links change. Existing glyphs and writing history stay intact.
      if (relinkedWords.isNotEmpty()) dao.deleteWordTracing(relinkedWords.toList())
      dao.putWordTracing(links.toList())
      words.clear(); meanings.clear(); links.clear(); relinkedWords.clear()
    }
    val issues = CsvIssues()
    reporter.update(WordlistLoadStage.SAVING, 0, plan.totalRows)
    val total = CsvDecoder.scan(file, issues, onRecord = { completed, word ->
      reporter.update(WordlistLoadStage.SAVING, completed, plan.totalRows, word)
    }) { word ->
      if (word.id in plan.newIds) {
        val canonicalId = plan.aliases.getValue(word.id)
        val distractors = word.distractorWordIds.map { plan.meaningIds.getValue(plan.aliases.getValue(it)) }
        words += WordEntity(canonicalId, word.hanzi, word.pinyin, examplesJson(word.examples), partsJson(word.parts),
          stringsJson(distractors), word.order, word.rarity,
          stringsJson(word.literalExplanations), stringsJson(word.figurativeExplanations), if (plan.preset) word.id else "")
        meanings += MeaningEntity(csvMeaningId(canonicalId), canonicalId, word.english, word.partOfSpeech, 0)
        links += word.glyphs.mapIndexed { position, glyph -> WordTracingEntity(canonicalId, position, glyphId(glyph)) }
        added++
      } else {
        val refresh = plan.refreshes[word.id]
        if (refresh != null) {
          val distractors = word.distractorWordIds.map { plan.meaningIds.getValue(plan.aliases.getValue(it)) }
          words += refresh.word.copy(hanzi = word.hanzi, pinyin = word.pinyin, examplesJson = examplesJson(word.examples),
            partsJson = partsJson(word.parts), distractorsJson = stringsJson(distractors),
            rarity = word.rarity, literalExplanationsJson = stringsJson(word.literalExplanations),
            figurativeExplanationsJson = stringsJson(word.figurativeExplanations), presetSourceId = word.id)
          meanings += refresh.meaning.copy(english = word.english, partOfSpeech = word.partOfSpeech)
          if (refresh.word.hanzi != word.hanzi) {
            relinkedWords += refresh.word.id
            links += word.glyphs.mapIndexed { position, glyph ->
              WordTracingEntity(refresh.word.id, position, glyphId(glyph))
            }
          }
          refreshed++
        }
      }
      if (words.size >= 100) flush()
    }
    check(issues.count == 0 && total == plan.totalRows && added == plan.newIds.size && refreshed == plan.refreshes.size) {
      UiMessageKey.THE_CSV_SNAPSHOT_CHANGED
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
  val projectedMeanings: Map<String, MeaningEntity>, val projectedHanzi: Map<String, String>)
private data class PresetRefresh(val word: WordEntity, val meaning: MeaningEntity)

internal open class PresetCardConflictException : UiMessageException(
  UiMessageKey.FINISH_THE_SAVED_STUDY_CARD_THEN_RETRY_THE_DEFAULT_WORDLIST_UPDATE)

internal class PresetWritingConflictException(val writing: PresetWritingResume) : PresetCardConflictException()
