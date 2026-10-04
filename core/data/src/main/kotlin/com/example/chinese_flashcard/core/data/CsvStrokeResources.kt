package com.example.chinese_flashcard.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal data class IndexedGlyph(val id: String, val glyph: String, val asset: String)
internal data class StrokeIndex(val byId: Map<String, IndexedGlyph>, val byGlyph: Map<String, IndexedGlyph>)

/** Two bounded shards decode concurrently; the owner validates totals and consumes writes serially. */
internal class CsvStrokeResources(private val context: Context) {
  private val mutex = Mutex()
  private var loaded: StrokeIndex? = null
  private var verified: StrokeIndex? = null
  private val assetPath = Regex("wordlist-strokes/shard_[0-9]{3,5}\\.json")
  private val pathToken = Regex("[MLQCZ]|[-+]?(?:[0-9]*\\.)?[0-9]+(?:[eE][-+]?[0-9]+)?")

  suspend fun index(): StrokeIndex = mutex.withLock {
    loaded?.let { return@withLock it }
    val root = readAsset("wordlist-strokes/index.json", 4 * 1024 * 1024).first
    require(root.getInt("version") == 1) { "Unsupported stroke index version." }
    val entries = root.getJSONArray("items").objects(10000).map { item ->
      val glyph = requiredText(item, "glyph", 4)
      require(glyph.codePointCount(0, glyph.length) == 1) { "Invalid glyph in stroke index." }
      val id = requiredText(item, "id", 100)
      require(id == glyphId(glyph)) { "Invalid glyph ID in stroke index." }
      val asset = requiredText(item, "asset", 100)
      require(asset.matches(assetPath)) { "Invalid stroke asset path." }
      IndexedGlyph(id, glyph, asset)
    }
    require(entries.isNotEmpty() && entries.map { it.id }.distinct().size == entries.size &&
      entries.map { it.glyph }.distinct().size == entries.size) { "Duplicate or empty stroke index." }
    val result = StrokeIndex(entries.associateBy { it.id }, entries.associateBy { it.glyph })
    loaded = result
    result
  }

  suspend fun verify(index: StrokeIndex, onProgress: (Int, Int, String?) -> Unit) {
    forEachBatch(index, emptySet(), onProgress) { }
  }

  suspend fun forEachBatch(index: StrokeIndex, wantedIds: Set<String>,
    onProgress: (Int, Int, String?) -> Unit, consume: suspend (List<TracingEntity>) -> Unit) = mutex.withLock {
    require(wantedIds.all { it in index.byId }) { "Missing stroke resource." }
    val validateAll = verified !== index
    val groups = index.byId.values.groupBy { it.asset }.entries.filter { (_, expected) ->
      validateAll || expected.any { it.id in wantedIds }
    }
    val total = groups.sumOf { it.value.size }
    var completed = 0
    var totalBytes = 0L
    onProgress(0, total, null)
    coroutineScope {
      // Await a window before creating another: no unbounded queue or coroutine per glyph.
      for (window in groups.chunked(2)) {
        val results = window.map { (asset, expected) ->
          async(Dispatchers.Default) { readShard(index, asset, expected, wantedIds) }
        }.awaitAll()
        for (result in results) {
          currentCoroutineContext().ensureActive()
          totalBytes += result.bytes
          require(totalBytes <= 64L * 1024 * 1024) { "The stroke bundle exceeds 64 MiB." }
          if (result.selected.isNotEmpty()) consume(result.selected)
          completed += result.count
          onProgress(completed, total, result.lastGlyph)
        }
      }
    }
    if (validateAll) verified = index
    // An already verified bundle with no missing resources still completes this phase.
    if (total == 0) onProgress(1, 1, null)
  }

  private data class DecodedShard(val selected: List<TracingEntity>, val bytes: Int,
    val count: Int, val lastGlyph: String?)

  private suspend fun readShard(index: StrokeIndex, asset: String, expected: List<IndexedGlyph>,
    wantedIds: Set<String>): DecodedShard {
    val (root, byteCount) = readAsset(asset, 1024 * 1024)
    require(root.getInt("version") == 1) { "Unsupported stroke shard version." }
    val entries = root.getJSONArray("items").objects(128)
    require(entries.isNotEmpty() && entries.size == expected.size) { "Stroke shard count differs from the index." }
    val seen = mutableSetOf<String>()
    val selected = mutableListOf<TracingEntity>()
    var lastGlyph: String? = null
    for (item in entries) {
      currentCoroutineContext().ensureActive()
      val tracing = decode(item)
      val indexed = index.byId[tracing.id]
      require(seen.add(tracing.id) && indexed != null && indexed.asset == asset && indexed.glyph == tracing.glyph) {
        "Stroke shard differs from the index."
      }
      if (tracing.id in wantedIds) selected += tracing
      lastGlyph = tracing.glyph
    }
    require(seen == expected.map { it.id }.toSet()) { "Stroke shard is incomplete." }
    return DecodedShard(selected, byteCount, entries.size, lastGlyph)
  }

  private fun decode(json: JSONObject): TracingEntity {
    val id = requiredText(json, "id", 100)
    val glyph = requiredText(json, "glyph", 4)
    require(glyph.codePointCount(0, glyph.length) == 1 && id == glyphId(glyph)) { "Invalid stroke glyph." }
    val paths = json.getJSONArray("paths").strings(64)
    val medians = decodePoints(json.getJSONArray("medians"))
    require(paths.isNotEmpty() && paths.size == medians.size) { "Stroke paths and medians differ." }
    paths.forEach(::validatePath)
    require(medians.all { stroke -> stroke.size in 2..512 && stroke.all {
      it.x.isFinite() && it.y.isFinite() && it.x in -512f..1536f && it.y in -512f..1536f
    } }) { "Invalid stroke medians." }
    return TracingEntity(id, glyph, stringsJson(paths), pointsJson(medians),
      requiredText(json, "revision", 128), requiredText(json, "attribution", 4000))
  }

  private suspend fun readAsset(path: String, limit: Int): Pair<JSONObject, Int> {
    val buffer = ByteArrayOutputStream()
    context.assets.open(path).use { input ->
      val block = ByteArray(8192)
      while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(block)
        if (count < 0) break
        require(buffer.size().toLong() + count <= limit) { "A stroke asset exceeds its size limit." }
        buffer.write(block, 0, count)
      }
    }
    return JSONObject(buffer.toString(Charsets.UTF_8.name())) to buffer.size()
  }

  private fun requiredText(json: JSONObject, field: String, maximum: Int): String {
    require(json.opt(field) is String) { "Invalid stroke resource field." }
    return json.getString(field).also { require(it.isNotBlank() && it.length <= maximum) }
  }

  private fun validatePath(path: String) {
    require(path.length in 1..16384 && path.startsWith("M"))
    require(pathToken.replace(path, "").all { it.isWhitespace() || it == ',' })
    val parts = pathToken.findAll(path).map { it.value }.toList()
    var position = 0
    while (position < parts.size) {
      val count = when (parts[position++]) {
        "M", "L" -> 2; "Q" -> 4; "C" -> 6; "Z" -> 0; else -> error("Invalid stroke path.")
      }
      if (count == 0) continue
      var numbers = 0
      while (position < parts.size && parts[position].first() !in "MLQCZ") {
        val number = parts[position++].toFloat()
        require(number.isFinite() && number in -8192f..8192f)
        numbers++
      }
      require(numbers >= count && numbers % count == 0)
    }
  }
}
