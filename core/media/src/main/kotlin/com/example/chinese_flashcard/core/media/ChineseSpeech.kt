package com.example.chinese_flashcard.core.media

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.ChinesePronunciation
import com.example.chinese_flashcard.core.domain.SpeechRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.sqrt

/** Locally bundled Melo FP32 with the auditioned boundary protocol and 0.7 speed. */
internal class ChineseSpeech(context: Context) {
  private val app = context.applicationContext
  private val lock = Mutex()
  private var session: OrtSession? = null
  // Keep the direct APK mapping alive until the native session has been closed.
  private var modelBuffer: ByteBuffer? = null
  private var manifest: JSONObject? = null
  private var phonemes: MandarinPhonemes? = null
  private var closed = false
  private val shards = object : LinkedHashMap<String, JSONObject>(8, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean = size > 8
  }

  suspend fun prepare(): Unit = withContext(Dispatchers.IO) {
    lock.withLock {
      check(!closed)
      val metadata = metadata()
      preparePhonemes(metadata)
      prepareModel(metadata)
      currentCoroutineContext().ensureActive()
    }
  }

  /** Unsupported readings return null before emitting anything, for the offline system fallback. */
  suspend fun synthesize(request: SpeechRequest,
    onChunk: suspend (SpeechAudioChunk) -> Unit = {}): SpeechAudio? = withContext(Dispatchers.IO) {
    lock.withLock {
      check(!closed)
      if (request.language != AppLanguage.CN) return@withLock null
      val hint = request.chinesePronunciation ?: return@withLock null
      val text = request.text.trim()
      if (text.isEmpty() || text.length > 2000 || hint.text.trim() != text || hint.pinyin.isBlank() || hint.pinyin.length > 4000 ||
        hint.chunks.size > 64) return@withLock null
      if (hint.chunks.any { it.hanzi.length > 2000 || it.pinyin.length > 4000 } ||
        hint.chunks.sumOf { it.hanzi.length } > 2000 || hint.chunks.sumOf { it.pinyin.length } > 4000) return@withLock null
      val readingKey = key(hint)
      val metadata = metadata()
      val phones = lookup(hint, readingKey, metadata) ?: return@withLock null
      if (!validPhones(phones)) return@withLock null
      currentCoroutineContext().ensureActive()
      val model = prepareModel(metadata)
      // First add the original G2P boundary phones, then apply add_blank exactly once.
      val size = 2 * (phones.phoneIds.size + 2) + 1
      val phoneIds = LongArray(size)
      val tones = LongArray(size)
      phones.phoneIds.indices.forEach { index ->
        phoneIds[2 * index + 3] = phones.phoneIds[index]
        tones[2 * index + 3] = phones.tones[index]
      }
      val samples = runInference(model, phoneIds, tones) ?: return@withLock null
      currentCoroutineContext().ensureActive()
      onChunk(SpeechAudioChunk(SAMPLE_RATE, samples))
      currentCoroutineContext().ensureActive()
      SpeechAudio(SAMPLE_RATE, listOf(samples))
    }
  }

  private fun metadata(): JSONObject = manifest ?: readJson("chinese-speech/manifest.json", METADATA_LIMIT).also {
    require(it.getInt("schemaVersion") == 1 && it.getString("modelRevision") == MODEL_REVISION)
    require(it.getString("modelPath") == "chinese-speech/model.onnx")
    require(it.getString("modelSha256") == MODEL_SHA256 && it.getLong("modelBytes") == MODEL_BYTES)
    require(it.getString("sourceWordlistSha256") == WORDLIST_SHA256)
    require(it.getInt("sampleRate") == SAMPLE_RATE && it.getInt("speakerId") == 1)
    require(it.getDouble("speed").toFloat() == SPEED && it.getDouble("noiseScale").toFloat() == .6f &&
      it.getDouble("noiseScaleW").toFloat() == .8f)
    require(it.getBoolean("addBlank") && it.getBoolean("padBoundary") && it.getInt("maxTokenCount") == MAX_PHONES)
    require(it.getString("phonemesPath") == "chinese-speech/phonemes.json")
    require(it.getString("phonemesSha256").matches(SHA256))
    it.getJSONObject("shards")
    manifest = it
  }

  private fun preparePhonemes(metadata: JSONObject): MandarinPhonemes = phonemes ?: run {
    val bytes = app.assets.open("chinese-speech/phonemes.json").use { it.readBytesBounded(METADATA_LIMIT) }
    check(sha256(bytes) == metadata.getString("phonemesSha256"))
    val data = JSONObject(bytes.toString(Charsets.UTF_8))
    require(data.getInt("schemaVersion") == 1 && data.getInt("blankTokenId") == 0)
    MandarinPhonemes(data)
  }.also { phonemes = it }

  private fun lookup(hint: ChinesePronunciation, readingKey: String, metadata: JSONObject): MandarinPhones? {
    val name = "shard_${readingKey.take(2)}.json"
    val record = metadata.getJSONObject("shards").optJSONObject(name)
    if (record != null) {
      val entries = shards.getOrPut(name) {
        val expectedBytes = record.getInt("bytes")
        require(expectedBytes in 1..SHARD_LIMIT && record.getString("sha256").matches(SHA256))
        val bytes = app.assets.open("chinese-speech/$name").use { it.readBytesBounded(SHARD_LIMIT) }
        check(bytes.size == expectedBytes && sha256(bytes) == record.getString("sha256"))
        val shard = JSONObject(bytes.toString(Charsets.UTF_8))
        require(shard.getInt("schemaVersion") == 1)
        shard.getJSONObject("entries").also { check(it.length() == record.getInt("entries")) }
      }
      entries.optJSONObject(readingKey)?.let { entry ->
        check(entry.getString("text").trim() == hint.text.trim() &&
          canonicalPinyin(entry.getString("pinyin")) == canonicalPinyin(hint.pinyin))
        if (entry.has("unsupportedReason")) return null
        val chunks = entry.optJSONArray("chunks")
        check((chunks?.length() ?: 0) == hint.chunks.size)
        hint.chunks.forEachIndexed { index, chunk ->
          val stored = checkNotNull(chunks).getJSONObject(index)
          check(stored.getString("hanzi") == chunk.hanzi &&
            canonicalPinyin(stored.getString("pinyin")) == canonicalPinyin(chunk.pinyin))
        }
        val ids = entry.getJSONArray("phoneIds")
        val toneIds = entry.getJSONArray("tones")
        require(ids.length() in 1..MAX_PHONES && ids.length() == toneIds.length())
        return MandarinPhones(LongArray(ids.length()) { ids.getLong(it) }, LongArray(toneIds.length()) { toneIds.getLong(it) })
      }
    }
    return preparePhonemes(metadata).compile(hint)
  }

  private fun validPhones(phones: MandarinPhones): Boolean = phones.phoneIds.size in 1..MAX_PHONES &&
    phones.phoneIds.size == phones.tones.size && phones.phoneIds.all { it in 1L..111L } &&
    phones.tones.all { it in 0L..5L }

  private suspend fun prepareModel(metadata: JSONObject): OrtSession {
    session?.let { return it }
    val context = currentCoroutineContext()
    context.ensureActive()
    val buffer = app.assets.openFd("chinese-speech/model.onnx").use { descriptor ->
      check(descriptor.declaredLength == MODEL_BYTES)
      descriptor.createInputStream().use { input ->
        input.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
      }
    }
    check(buffer.isDirect && buffer.remaining().toLong() == MODEL_BYTES)
    check(sha256(buffer, context) == metadata.getString("modelSha256"))
    var created: OrtSession? = null
    try {
      val model = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        OrtEnvironment.getEnvironment().createSession(buffer.asReadOnlyBuffer(), options)
      }
      created = model
      check(model.inputNames == INPUTS && model.outputNames == setOf("y"))
      val properties = model.metadata.customMetadata
      check(properties["model_type"] == "melo-vits" && properties["speaker_id"] == "1" &&
        properties["sample_rate"] == "44100" && properties["n_speakers"] == "1" && properties["add_blank"] == "1" &&
        properties["lang_id"] == "3" && properties["tone_start"] == "0")
      context.ensureActive()
      modelBuffer = buffer
      session = model
      return model
    } catch (error: Exception) {
      created?.close()
      throw error
    }
  }

  private suspend fun runInference(model: OrtSession, ids: LongArray, tones: LongArray): FloatArray? {
    val env = OrtEnvironment.getEnvironment()
    val inputs = LinkedHashMap<String, OnnxTensor>(7)
    try {
      val shape = longArrayOf(1, ids.size.toLong())
      inputs["x"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
      inputs["tones"] = OnnxTensor.createTensor(env, LongBuffer.wrap(tones), shape)
      inputs["x_lengths"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1))
      inputs["sid"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(1)), longArrayOf(1))
      inputs["noise_scale"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(.6f)), longArrayOf(1))
      inputs["length_scale"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f / SPEED)), longArrayOf(1))
      inputs["noise_scale_w"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(.8f)), longArrayOf(1))
      val context = currentCoroutineContext()
      context.ensureActive()
      return suspendCancellableCoroutine { continuation ->
        val options = OrtSession.RunOptions()
        val guard = Any()
        var optionsClosed = false
        continuation.invokeOnCancellation {
          synchronized(guard) {
            if (!optionsClosed) runCatching { options.setTerminate(true) }
          }
        }
        try {
          if (continuation.isActive) {
            val audio = model.run(inputs, options).use { output ->
              val buffer = (output[0] as OnnxTensor).floatBuffer
              val count = buffer.remaining()
              if (count !in 1..SAMPLE_RATE * 60) null else {
                val samples = FloatArray(count)
                buffer.get(samples)
                audible(samples, context)
              }
            }
            if (continuation.isActive) continuation.resume(audio)
          }
        } catch (error: Exception) {
          if (continuation.isActive) continuation.resumeWithException(error)
        } finally {
          synchronized(guard) {
            optionsClosed = true
            options.close()
          }
        }
      }
    } finally {
      inputs.values.forEach { it.close() }
    }
  }

  private fun audible(samples: FloatArray, context: CoroutineContext): FloatArray? {
    var sum = 0.0
    var squares = 0.0
    var peak = 0f
    var clipped = 0
    samples.forEachIndexed { index, sample ->
      if (index % 16384 == 0) context.ensureActive()
      if (!sample.isFinite()) return null
      val absolute = abs(sample)
      peak = maxOf(peak, absolute)
      if (absolute > 1f) clipped++
      sum += sample.toDouble()
      squares += sample.toDouble() * sample
    }
    val mean = sum / samples.size
    if (sqrt(squares / samples.size) <= 1e-4 || peak <= 1e-3f ||
      squares / samples.size - mean * mean <= 1e-12 || clipped.toDouble() / samples.size >= .01) return null
    samples.indices.forEach { samples[it] = samples[it].coerceIn(-1f, 1f) }
    context.ensureActive()
    return samples
  }

  private fun readJson(path: String, limit: Int): JSONObject = app.assets.open(path).use {
    JSONObject(it.readBytesBounded(limit).toString(Charsets.UTF_8))
  }

  suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.IO) {
    lock.withLock {
      closed = true
      try { session?.close() } finally {
        session = null
        modelBuffer = null
        manifest = null
        phonemes = null
        shards.clear()
      }
    }
  }

  companion object {
    private const val MODEL_REVISION = "a0d5c6a264c0ef92d70d8661d8cc502d79627cd6"
    private const val MODEL_SHA256 = "bf30582eb1b012250a35b1a4a80e7dfbcf8485e7bb9de0d95efbbeef0e4ad86d"
    private const val MODEL_BYTES = 170429550L
    private const val WORDLIST_SHA256 = "21b2dbf3103513c8b1749a5f2cfc17a94f7df3f9ca63f89748d4f9718a9388a7"
    private const val SAMPLE_RATE = 44100
    private const val SPEED = .7f
    private const val MAX_PHONES = 510
    private const val METADATA_LIMIT = 512 * 1024
    private const val SHARD_LIMIT = 4 * 1024 * 1024
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val INPUTS = setOf("x", "x_lengths", "tones", "sid", "noise_scale", "length_scale", "noise_scale_w")

    fun key(hint: ChinesePronunciation): String = sha256((hint.text.trim() + "\n" + canonicalPinyin(hint.pinyin) + "\n" +
      hint.chunks.joinToString("\u001f") { it.hanzi + "\u001e" + canonicalPinyin(it.pinyin) }).toByteArray(Charsets.UTF_8))

    private fun canonicalPinyin(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFC)

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun sha256(buffer: ByteBuffer, context: CoroutineContext): String {
      val digest = MessageDigest.getInstance("SHA-256")
      val view = buffer.asReadOnlyBuffer()
      val block = ByteArray(64 * 1024)
      while (view.hasRemaining()) {
        context.ensureActive()
        val count = minOf(view.remaining(), block.size)
        view.get(block, 0, count)
        digest.update(block, 0, count)
      }
      return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
  }
}
