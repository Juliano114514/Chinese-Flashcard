package com.example.chinese_flashcard.core.media

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.Locale

internal data class EnglishAudio(val sampleRate: Int, val chunks: List<FloatArray>)

/** Fixed, locally bundled text-to-token data; no phonemizer, Python, recording or network at runtime. */
internal class EnglishSpeech(context: Context) {
  private val app = context.applicationContext
  private val lock = Mutex()
  private var session: OrtSession? = null
  private var manifest: JSONObject? = null
  private var styles: FloatArray? = null
  private var closed = false
  private val shards = object : LinkedHashMap<String, JSONObject>(8, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean = size > 8
  }

  suspend fun synthesize(text: String): EnglishAudio? = lock.withLock {
    check(!closed)
    val trimmed = text.trim()
    require(trimmed.length <= 2000)
    val metadata = manifest ?: readJson("english-speech/manifest.json").also {
      require(it.getInt("schemaVersion") == 1)
      manifest = it
    }
    val hash = sha256(trimmed.toByteArray(Charsets.UTF_8))
    val entries = shards.getOrPut(hash.take(2)) {
      val name = "shard_${hash.take(2)}.json"
      val bytes = app.assets.open("english-speech/$name").use { it.readBytesBounded(2 * 1024 * 1024) }
      check(sha256(bytes) == metadata.getJSONObject("shards").getJSONObject(name).getString("sha256"))
      JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("entries")
    }
    val entry = entries.optJSONObject(hash) ?: return@withLock null
    check(entry.getString("text") == trimmed)
    val rows = metadata.getInt("styleRows")
    val columns = metadata.getInt("styleColumns")
    val sampleRate = metadata.getInt("sampleRate")
    require(rows in 1..1000 && columns == 256 && sampleRate == 24000)
    val voice = styles ?: readStyle(metadata, rows, columns).also { styles = it }
    val env = OrtEnvironment.getEnvironment()
    val model = session ?: OrtSession.SessionOptions().use { options ->
      options.setIntraOpNumThreads(2)
      env.createSession(modelFile(metadata).absolutePath, options)
    }.also { session = it }
    val chunks = entry.getJSONArray("chunks")
    require(chunks.length() in 1..32)
    var totalSamples = 0
    val audio = ArrayList<FloatArray>(chunks.length())
    for (index in 0 until chunks.length()) {
      currentCoroutineContext().ensureActive()
      val chunk = chunks.getJSONObject(index)
      val ids = chunk.getJSONArray("tokenIds")
      require(ids.length() in 3..510)
      val tokenIds = LongArray(ids.length()) { ids.getLong(it).also { id -> require(id in 0..255) } }
      val row = chunk.getInt("styleRow")
      require(row in 0 until rows)
      val style = voice.copyOfRange(row * columns, (row + 1) * columns)
      OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), longArrayOf(1, tokenIds.size.toLong())).use { input ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, columns.toLong())).use { embedding ->
          // Kitten's voice prior already sets 0.8; never apply the old system-TTS rate a second time.
          OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(metadata.getDouble("speedPrior").toFloat())), longArrayOf(1)).use { speed ->
            model.run(mapOf("input_ids" to input, "style" to embedding, "speed" to speed)).use { output ->
              currentCoroutineContext().ensureActive()
              val buffer = (output[0] as OnnxTensor).floatBuffer
              val count = buffer.remaining() - metadata.getInt("trimTailSamples")
              require(count > 0 && count <= sampleRate * 60 && totalSamples + count <= sampleRate * 60)
              val samples = FloatArray(count)
              buffer.get(samples)
              require(samples.all(Float::isFinite))
              samples.indices.forEach { i -> samples[i] = samples[i].coerceIn(-1f, 1f) }
              totalSamples += count
              audio.add(samples)
            }
          }
        }
      }
    }
    EnglishAudio(sampleRate, audio)
  }

  private fun modelFile(metadata: JSONObject): File {
    val expected = metadata.getString("modelSha256")
    require(expected.matches(Regex("[0-9a-f]{64}")))
    val target = File(app.cacheDir, "kitten-$expected.onnx")
    if (target.isFile && target.inputStream().use(::sha256) == expected) return target
    val temporary = File.createTempFile("kitten-", ".part", app.cacheDir)
    try {
      app.assets.open(metadata.getString("modelPath").also { require(it == "english-speech/kitten-nano-0.8-int8.onnx") }).use { input ->
        temporary.outputStream().use { output ->
          val buffer = ByteArray(64 * 1024)
          var bytes = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            require(bytes <= 64L * 1024 * 1024)
            output.write(buffer, 0, count)
          }
        }
      }
      check(temporary.inputStream().use(::sha256) == expected)
      check((!target.exists() || target.delete()) && temporary.renameTo(target))
      return target
    } finally { temporary.delete() }
  }

  private fun readStyle(metadata: JSONObject, rows: Int, columns: Int): FloatArray {
    val bytes = app.assets.open(metadata.getString("stylePath").also { require(it == "english-speech/bella.f32") }).use {
      it.readBytesBounded(1024 * 1024)
    }
    require(bytes.size == rows * columns * 4 && sha256(bytes) == metadata.getString("styleSha256"))
    return FloatArray(rows * columns).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
  }

  private fun readJson(path: String): JSONObject = app.assets.open(path).use {
    JSONObject(it.readBytesBounded(2 * 1024 * 1024).toString(Charsets.UTF_8))
  }

  suspend fun close() = lock.withLock {
    closed = true
    session?.close()
    session = null
    styles = null
    shards.clear()
  }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
  val output = java.io.ByteArrayOutputStream()
  val buffer = ByteArray(8192)
  while (true) {
    val count = read(buffer)
    if (count < 0) break
    require(output.size() + count <= limit)
    output.write(buffer, 0, count)
  }
  return output.toByteArray()
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
  .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

private fun sha256(input: java.io.InputStream): String {
  val digest = MessageDigest.getInstance("SHA-256")
  val buffer = ByteArray(64 * 1024)
  while (true) {
    val count = input.read(buffer)
    if (count < 0) break
    digest.update(buffer, 0, count)
  }
  return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
