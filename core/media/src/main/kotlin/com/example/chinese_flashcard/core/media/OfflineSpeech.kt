package com.example.chinese_flashcard.core.media

import com.example.chinese_flashcard.core.domain.UiMessageKey
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** Offline Mandarin, bundled English, and installed offline English for imported text. */
class OfflineSpeech(context: Context) : AutoCloseable {
  private val main = Handler(Looper.getMainLooper())
  private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val english = EnglishSpeech(context)
  private val mutable = MutableStateFlow<UiMessageKey?>(null)
  val message = mutable.asStateFlow()
  private var closed = false
  private var initialized = false
  private val voices = mutableMapOf<AppLanguage, Voice>()
  private class Utterance(val speech: SpeechRequest, val waiter: CancellableContinuation<Boolean>? = null) {
    val id = "flashcard-${System.nanoTime()}"
    val deadline = SystemClock.elapsedRealtime() + 30_000L
  }
  private var pending: Utterance? = null
  @Volatile private var current: Utterance? = null
  private var englishJob: Job? = null
  private var preloadJob: Job? = null
  @Volatile private var playingTrack: AudioTrack? = null
  private var tts: TextToSpeech? = null
  private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
  private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    .setAudioAttributes(attributes).setOnAudioFocusChangeListener { if (it < 0) stop() }.build()

  init {
    try {
      tts = TextToSpeech(context.applicationContext) { status -> main.post { initialize(status) } }
      tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) { main.post { finish(utteranceId, true) } }
        @Deprecated("Android callback")
        override fun onError(utteranceId: String?) { main.post { finish(utteranceId, false, true) } }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { finish(utteranceId, false) } }
      })
    } catch (_: RuntimeException) {
      initialized = true
      try { tts?.shutdown() } catch (_: RuntimeException) { }
      tts = null
    }
  }

  private fun initialize(status: Int) {
    if (closed) return
    initialized = true
    try {
      val installed = if (status == TextToSpeech.SUCCESS) tts?.voices.orEmpty().filter {
        !it.isNetworkConnectionRequired && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
      } else emptyList()
      installed.filter { it.locale.language == "zh" && it.locale.country in listOf("CN", "", "SG") }
        .minByOrNull { if (it.locale.country == "CN") 0 else 1 }?.let { voices[AppLanguage.CN] = it }
      installed.filter { it.locale.language == "en" }
        .minByOrNull { if (it.locale.country == "US") 0 else 1 }?.let { voices[AppLanguage.EN] = it }
      tts?.setAudioAttributes(attributes)
    } catch (_: RuntimeException) { voices.clear() }
    val request = pending
    pending = null
    if (request != null) startSystem(request)
  }

  fun speak(text: String) = speak(SpeechRequest(text, AppLanguage.CN))
  fun speak(request: SpeechRequest) { onMain { requestSpeech(Utterance(request.copy(text = request.text.take(2000)))) } }
  /** Silent and cancellable: call on entering a speech page or when its English card text changes. */
  fun preloadEnglish(texts: List<String> = emptyList()) { onMain {
    if (closed || current != null || pending != null) return@onMain
    preloadJob?.cancel()
    val candidates = texts.asSequence().map { it.trim() }.filter { it.isNotEmpty() && it.length <= 2000 }
      .distinct().take(3).toList()
    preloadJob = scope.launch {
      try {
        withTimeout(15_000L) { withContext(Dispatchers.IO) {
          english.prepare()
          for (text in candidates) english.synthesize(text)
        } }
      } catch (error: CancellationException) { throw error }
      catch (_: Exception) { /* Actual playback can still use the installed offline voice. */ }
      catch (_: LinkageError) { /* Native runtime unavailable; leave fallback to playback. */ }
    }
  } }
  suspend fun speakAndWait(request: SpeechRequest): Boolean = withTimeoutOrNull(30_000L) {
    suspendCancellableCoroutine { waiter ->
      val utterance = Utterance(request.copy(text = request.text.take(2000)), waiter)
      waiter.invokeOnCancellation { onMain { if (current === utterance || pending === utterance) stopOnMain() } }
      onMain { if (waiter.isActive) requestSpeech(utterance) }
    }
  } ?: false

  private fun requestSpeech(utterance: Utterance) {
    if (closed || utterance.speech.text.isBlank()) { complete(utterance, false); return }
    stopOnMain()
    if (utterance.speech.language == AppLanguage.EN) startEnglish(utterance) else startSystem(utterance)
  }

  private fun startEnglish(utterance: Utterance) {
    current = utterance
    englishJob = scope.launch {
      var playbackStarted = false
      try {
        val completed = withTimeout((utterance.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)) {
          playEnglish(utterance) { playbackStarted = true }
        }
        if (current !== utterance || closed) return@launch
        if (completed == null) {
          current = null
          startSystem(utterance)
          return@launch
        }
        finish(utterance.id, completed)
      } catch (_: TimeoutCancellationException) { finish(utterance.id, false, true) }
      catch (error: CancellationException) { throw error }
      catch (_: Exception) { if (playbackStarted) finish(utterance.id, false, true) else fallbackToSystem(utterance) }
      catch (_: LinkageError) { if (playbackStarted) finish(utterance.id, false, true) else fallbackToSystem(utterance) }
    }
  }

  private suspend fun playEnglish(utterance: Utterance, onStarted: () -> Unit): Boolean? = coroutineScope {
    // One queued chunk bounds streaming memory while synthesis and playback overlap.
    val chunks = Channel<EnglishAudioChunk>(1)
    val producer = launch(Dispatchers.IO) {
      try { english.synthesize(utterance.speech.text) { chunks.send(it) } }
      finally { chunks.close() }
    }
    try {
      val first = chunks.receiveCatching().getOrNull() ?: return@coroutineScope null
      currentCoroutineContext().ensureActive()
      if (current !== utterance || closed) throw CancellationException()
      if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        mutable.value = UiMessageKey.AUDIO_IS_CURRENTLY_UNAVAILABLE_YOU_CAN_CONTINUE_STUDYING
        return@coroutineScope false
      }
      mutable.value = null
      withContext(Dispatchers.IO) { play(first, chunks, onStarted) }
    } finally {
      producer.cancel()
      chunks.cancel()
    }
  }

  private fun fallbackToSystem(utterance: Utterance) {
    if (current === utterance && !closed) {
      releaseFocus()
      current = null
      startSystem(utterance)
    }
  }

  private suspend fun play(first: EnglishAudioChunk, chunks: ReceiveChannel<EnglishAudioChunk>, onStarted: () -> Unit): Boolean {
    currentCoroutineContext().ensureActive()
    val minimum = AudioTrack.getMinBufferSize(first.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
    require(minimum > 0)
    val track = AudioTrack.Builder().setAudioAttributes(attributes)
      .setAudioFormat(AudioFormat.Builder().setSampleRate(first.sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
      .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, first.sampleRate / 5 * 4)).build()
    try {
      currentCoroutineContext().ensureActive()
      playingTrack = track
      track.play()
      var frames = 0L
      var chunk: EnglishAudioChunk? = first
      while (chunk != null) {
        require(chunk.sampleRate == first.sampleRate)
        val samples = chunk.samples
        var offset = 0
        while (offset < samples.size) {
          currentCoroutineContext().ensureActive()
          val count = track.write(samples, offset, minOf(2048, samples.size - offset), AudioTrack.WRITE_BLOCKING)
          check(count > 0)
          if (frames == 0L) onStarted()
          offset += count
          frames += count
        }
        chunk = chunks.receiveCatching().getOrNull()
      }
      while ((track.playbackHeadPosition.toLong() and 0xffffffffL) < frames) {
        currentCoroutineContext().ensureActive()
        delay(20)
      }
      return true
    } finally {
      if (playingTrack === track) playingTrack = null
      try { track.stop() } catch (_: RuntimeException) { }
      track.release()
    }
  }

  private fun startSystem(utterance: Utterance) {
    if (closed || utterance.waiter?.isActive == false) { complete(utterance, false); return }
    if (!initialized) {
      pending = utterance
      mutable.value = UiMessageKey.PREPARING_OFFLINE_SPEECH
      return
    }
    val voice = voices[utterance.speech.language]
    if (voice == null) {
      mutable.value = if (utterance.speech.language == AppLanguage.CN)
        UiMessageKey.NO_OFFLINE_MANDARIN_VOICE_IS_AVAILABLE_ON_THIS_DEVICE_YOU_CAN_CONTINUE_STUDYING
      else UiMessageKey.NO_OFFLINE_ENGLISH_VOICE_IS_AVAILABLE_ON_THIS_DEVICE_YOU_CAN_CONTINUE_STUDYING
      complete(utterance, false)
      return
    }
    try {
      if (tts?.setVoice(voice) != TextToSpeech.SUCCESS) throw IllegalStateException(UiMessageKey.OFFLINE_VOICE_UNAVAILABLE.name)
      val rate = if (utterance.speech.language == AppLanguage.EN) 0.8f * ENGLISH_SPEED_MULTIPLIER else 0.8f
      if (tts?.setSpeechRate(rate) != TextToSpeech.SUCCESS) throw IllegalStateException(UiMessageKey.OFFLINE_VOICE_UNAVAILABLE.name)
      if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        mutable.value = UiMessageKey.AUDIO_IS_CURRENTLY_UNAVAILABLE_YOU_CAN_CONTINUE_STUDYING
        complete(utterance, false)
        return
      }
      mutable.value = null
      current = utterance
      if (tts?.speak(utterance.speech.text, TextToSpeech.QUEUE_FLUSH, null, utterance.id) == TextToSpeech.SUCCESS) return
    } catch (_: RuntimeException) { }
    current = null
    mutable.value = UiMessageKey.SPEECH_COULD_NOT_BE_PLAYED_YOU_CAN_CONTINUE_STUDYING
    releaseFocus()
    complete(utterance, false)
  }

  private fun finish(id: String?, completed: Boolean, failed: Boolean = false) {
    val utterance = current ?: return
    if (id != utterance.id) return
    current = null
    if (failed) mutable.value = UiMessageKey.SPEECH_COULD_NOT_BE_PLAYED_YOU_CAN_CONTINUE_STUDYING
    releaseFocus()
    complete(utterance, completed)
  }
  private fun complete(utterance: Utterance, completed: Boolean) {
    val waiter = utterance.waiter ?: return
    main.post { if (waiter.isActive) waiter.resume(completed) }
  }
  private fun onMain(action: () -> Unit) {
    if (Looper.myLooper() == main.looper) action() else main.post { action() }
  }
  private fun releaseFocus() {
    try { manager.abandonAudioFocusRequest(focus) } catch (_: RuntimeException) { }
  }
  fun dismissMessage() { mutable.value = null }
  fun stop() { onMain(::stopOnMain) }
  private fun stopOnMain() {
    val previousPending = pending; pending = null
    val previous = current; current = null
    englishJob?.cancel(); englishJob = null
    preloadJob?.cancel(); preloadJob = null
    try { playingTrack?.pause(); playingTrack?.flush() } catch (_: RuntimeException) { }
    try { tts?.stop() } catch (_: RuntimeException) { }
    releaseFocus()
    previousPending?.let { complete(it, false) }
    previous?.let { complete(it, false) }
  }
  override fun close() { onMain {
    if (!closed) {
      closed = true
      stopOnMain()
      try { tts?.shutdown() } catch (_: RuntimeException) { }
      tts = null
      scope.launch { try { withContext(Dispatchers.IO) { english.close() } } finally { scope.cancel() } }
    }
  } }
}
