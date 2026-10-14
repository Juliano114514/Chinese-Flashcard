package com.example.chinese_flashcard.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume

/** A single engine, serialized across silent synthesis and the last-resort audible path. */
internal class SystemOfflineSpeech(context: Context, private val attributes: AudioAttributes) {
  private val app = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  private val lock = Mutex()
  private val initialized = CompletableDeferred<Unit>()
  private val voices = mutableMapOf<AppLanguage, Voice>()
  private var engine: TextToSpeech? = null
  private var closed = false
  @Volatile private var active: Operation? = null
  private val fingerprints = mutableMapOf<AppLanguage, String>()

  suspend fun cleanupOrphans() = withContext(Dispatchers.IO) {
    app.cacheDir.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("speech--?[0-9]+\\.wav")) }
      .forEach { currentCoroutineContext().ensureActive(); it.delete() }
  }

  private data class Format(val rate: Int, val encoding: Int, val channels: Int) {
    val bytesPerSample: Int get() = when (encoding) {
      AudioFormat.ENCODING_PCM_8BIT -> 1
      AudioFormat.ENCODING_PCM_16BIT -> 2
      AudioFormat.ENCODING_PCM_FLOAT -> 4
      else -> 0
    }
    val frameBytes: Int get() = bytesPerSample * channels
    fun valid(): Boolean = rate in 8_000..192_000 && channels in 1..2 && bytesPerSample > 0
  }
  private class Operation(val id: String, val events: Channel<ByteArray>? = null,
    val waiter: CancellableContinuation<Boolean>? = null, val onStarted: () -> Unit = {}) {
    val guard = Any()
    var format: Format? = null
    var bytes = 0L
    var chunks = 0
    var finished = false
    var succeeded = false
  }

  init {
    try {
      engine = TextToSpeech(app) { status -> main.post { initialize(status) } }
      engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
          val operation = matching(utteranceId) ?: return
          if (operation.events == null) main.post {
            if (active === operation) operation.onStarted()
          }
        }
        override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
          val operation = matching(utteranceId) ?: return
          if (operation.events == null) return
          val format = Format(sampleRateInHz, audioFormat, channelCount)
          synchronized(operation.guard) {
            if (operation.finished) return
            if (!format.valid() || operation.format != null) fail(operation)
            else operation.format = format
          }
        }
        override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
          val operation = matching(utteranceId) ?: return
          val events = operation.events ?: return
          synchronized(operation.guard) {
            if (operation.finished) return
            val format = operation.format
            if (format == null || audio == null || audio.isEmpty() || audio.size > 1024 * 1024 ||
              operation.bytes + audio.size > minOf(96L * 1024 * 1024, format.rate.toLong() * format.frameBytes * 60) ||
              operation.chunks >= 4096) {
              fail(operation); return
            }
            operation.bytes += audio.size
            operation.chunks++
            if (!events.trySend(audio).isSuccess) fail(operation)
          }
        }
        override fun onDone(utteranceId: String?) {
          val operation = matching(utteranceId) ?: return
          synchronized(operation.guard) {
            if (operation.finished) return
            operation.finished = true; operation.succeeded = true
            operation.events?.close()
          }
          operation.waiter?.let { waiter -> main.post { if (waiter.isActive) waiter.resume(true) } }
        }
        @Deprecated("Android callback")
        override fun onError(utteranceId: String?) { matching(utteranceId)?.let(::fail) }
        override fun onError(utteranceId: String?, errorCode: Int) { matching(utteranceId)?.let(::fail) }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { matching(utteranceId)?.let(::fail) }
      })
    } catch (_: RuntimeException) {
      engine = null
      initialized.complete(Unit)
    }
  }

  private fun matching(id: String?): Operation? = active?.takeIf { it.id == id }
  private fun fail(operation: Operation) {
    synchronized(operation.guard) {
      if (operation.finished) return
      operation.finished = true
      operation.events?.close(IllegalStateException("Offline synthesis failed"))
    }
    main.post {
      if (active === operation) runCatching { engine?.stop() }
      operation.waiter?.let { if (it.isActive) it.resume(false) }
    }
  }

  private fun initialize(status: Int) {
    if (closed) { initialized.complete(Unit); return }
    try {
      val installed = if (status == TextToSpeech.SUCCESS) engine?.voices.orEmpty().filter {
        !it.isNetworkConnectionRequired && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
      } else emptyList()
      installed.filter { it.locale.language == "zh" && it.locale.country in listOf("CN", "", "SG") }
        .minByOrNull { if (it.locale.country == "CN") 0 else 1 }?.let { voices[AppLanguage.CN] = it }
      installed.filter { it.locale.language == "en" }
        .minByOrNull { if (it.locale.country == "US") 0 else 1 }?.let { voices[AppLanguage.EN] = it }
      engine?.setAudioAttributes(attributes)
      val packageName = engine?.defaultEngine.orEmpty()
      val version = runCatching { app.packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0)
      voices.forEach { (language, voice) ->
        val value = "$packageName/$version/${voice.name}/${voice.locale.toLanguageTag()}/${rate(language)}/pcm-v1"
        fingerprints[language] = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
      }
    } catch (_: RuntimeException) { voices.clear(); fingerprints.clear() }
    initialized.complete(Unit)
  }

  suspend fun fingerprint(language: AppLanguage): String? {
    if (withTimeoutOrNull(5_000L) { initialized.await(); true } != true) return null
    return withContext(Dispatchers.Main.immediate) { fingerprints[language] }
  }

  private fun rate(language: AppLanguage): Float = if (language == AppLanguage.EN) 0.8f * ENGLISH_SPEED_MULTIPLIER else 0.8f
  private fun configure(request: SpeechRequest): Boolean {
    if (closed) return false
    val voice = voices[request.language] ?: return false
    return engine?.setVoice(voice) == TextToSpeech.SUCCESS &&
      engine?.setSpeechRate(rate(request.language)) == TextToSpeech.SUCCESS
  }

  suspend fun synthesize(request: SpeechRequest, onChunk: suspend (SpeechAudioChunk) -> Unit,
    temporaryBudget: suspend (String, Long, Long) -> Boolean,
    releaseTemporary: suspend (String) -> Unit): SpeechAudio? = coroutineScope { lock.withLock {
    val fingerprint = fingerprint(request.language) ?: return@withLock null
    val events = Channel<ByteArray>(64)
    val operation = Operation("flashcard-cache-${System.nanoTime()}", events)
    var temporary: java.io.File? = null
    var monitor: kotlinx.coroutines.Job? = null
    try {
      if (!temporaryBudget(operation.id, 64, 0)) return@withLock null
      withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
        temporary = java.io.File.createTempFile("speech-", ".wav", app.cacheDir)
      }
      currentCoroutineContext().ensureActive()
      val outputFile = checkNotNull(temporary)
      monitor = launch(Dispatchers.IO) {
        while (isActive) {
          val format = synchronized(operation.guard) { operation.format }
          val bound = format?.let { it.rate.toLong() * it.frameBytes * 60 + 64 } ?: 96L * 1024 * 1024
          val bytes = outputFile.length()
          val reserved = if (format == null) maxOf(64L, bytes + 64 * 1024) else bound
          if (bytes > bound || !temporaryBudget(operation.id, reserved, bytes)) {
            fail(operation); break
          }
          if (synchronized(operation.guard) { operation.finished }) break
          delay(40)
        }
      }
      val queued = withContext(Dispatchers.Main.immediate) {
        if (!configure(request)) false else {
          active = operation
          engine?.synthesizeToFile(request.text, android.os.Bundle(), outputFile, operation.id) == TextToSpeech.SUCCESS
        }
      }
      if (!queued) return@withLock null
      val chunks = ArrayList<FloatArray>()
      var residue = ByteArray(0)
      var receivedBytes = 0L
      for (bytes in events) {
        currentCoroutineContext().ensureActive()
        val format = synchronized(operation.guard) { operation.format } ?: return@withLock null
        receivedBytes += bytes.size
        val maximumWavBytes = format.rate.toLong() * format.frameBytes * 60 + 64
        if (!temporaryBudget(operation.id, maximumWavBytes, maxOf(receivedBytes + 44, outputFile.length()))) return@withLock null
        val input = if (residue.isEmpty()) bytes else residue + bytes
        val aligned = input.size / format.frameBytes * format.frameBytes
        residue = input.copyOfRange(aligned, input.size)
        if (aligned == 0) continue
        val buffer = ByteBuffer.wrap(input, 0, aligned).order(ByteOrder.nativeOrder())
        val samples = FloatArray(aligned / format.bytesPerSample)
        for (index in samples.indices) {
          if (index % 16384 == 0) currentCoroutineContext().ensureActive()
          val sample = when (format.encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128f
            AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
            else -> buffer.float
          }
          require(sample.isFinite())
          samples[index] = sample.coerceIn(-1f, 1f)
        }
        chunks.add(samples)
        onChunk(SpeechAudioChunk(format.rate, samples, format.channels))
      }
      val format = synchronized(operation.guard) { operation.format }
      if (format != null) require(outputFile.length() <= format.rate.toLong() * format.frameBytes * 60 + 64)
      if (format == null || !operation.succeeded || residue.isNotEmpty() || chunks.isEmpty()) null
      else SpeechAudio(format.rate, chunks, format.channels, fingerprint).takeIf(SpeechAudio::isValid)
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }
    finally {
      withContext(kotlinx.coroutines.NonCancellable) { monitor?.cancel(); monitor?.join() }
      withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
        if (active === operation) { active = null; runCatching { engine?.stop() } }
        events.cancel()
      }
      withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { temporary?.delete() }
      withContext(kotlinx.coroutines.NonCancellable) { releaseTemporary(operation.id) }
    }
  } }

  suspend fun speakDirectAndWait(request: SpeechRequest, onStarted: () -> Unit): Boolean = lock.withLock {
    if (fingerprint(request.language) == null) return@withLock false
    try {
      suspendCancellableCoroutine { waiter ->
        val operation = Operation("flashcard-direct-${System.nanoTime()}", waiter = waiter, onStarted = onStarted)
        waiter.invokeOnCancellation { main.post {
          if (active === operation) { active = null; runCatching { engine?.stop() } }
        } }
        main.post {
          if (!waiter.isActive) return@post
          try {
            if (configure(request)) {
              active = operation
              if (engine?.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, operation.id) == TextToSpeech.SUCCESS) return@post
            }
          } catch (_: RuntimeException) { }
          if (active === operation) active = null
          if (waiter.isActive) waiter.resume(false)
        }
      }
    } finally {
      withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
        if (active?.events == null) { active = null; runCatching { engine?.stop() } }
      }
    }
  }

  suspend fun close() = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
    closed = true
    active?.let(::fail); active = null
    runCatching { engine?.stop(); engine?.shutdown() }
    engine = null
    initialized.complete(Unit)
  }
}
