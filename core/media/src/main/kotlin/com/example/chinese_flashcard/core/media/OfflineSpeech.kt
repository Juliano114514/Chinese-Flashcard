package com.example.chinese_flashcard.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Device-provided, offline Chinese speech only; no recording or network fallback. */
class OfflineSpeech(context: Context) : AutoCloseable {
  private val main = Handler(Looper.getMainLooper())
  private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
  private val mutable = MutableStateFlow<String?>(null)
  val message = mutable.asStateFlow()
  private var closed = false
  private var ready = false
  private var initialized = false
  private class SpeechRequest(val text: String, val waiter: CancellableContinuation<Boolean>? = null) {
    val id = "flashcard-${System.nanoTime()}"
  }
  private var pendingRequest: SpeechRequest? = null
  private var currentRequest: SpeechRequest? = null
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
        override fun onDone(utteranceId: String?) { main.post {
          finish(utteranceId, completed = true)
        } }
        @Deprecated("Android callback")
        override fun onError(utteranceId: String?) { main.post {
          finish(utteranceId, completed = false, failed = true)
        } }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post {
          finish(utteranceId, completed = false)
        } }
      })
    } catch (_: RuntimeException) {
      initialized = true
      ready = false
      try { tts?.shutdown() } catch (_: RuntimeException) { }
      tts = null
    }
  }
  private fun initialize(status: Int) {
    if (closed) return
    initialized = true
    try {
      val voice = if (status == TextToSpeech.SUCCESS) tts?.voices?.filter {
        it.locale.language == "zh" && !it.isNetworkConnectionRequired &&
          it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true &&
          it.locale.country in listOf("CN", "", "SG")
      }?.sortedBy { if (it.locale.country == "CN") 0 else 1 }?.firstOrNull() else null
      ready = voice != null && tts?.setVoice(voice) == TextToSpeech.SUCCESS
      if (ready) {
        tts?.setAudioAttributes(attributes)
        tts?.setSpeechRate(0.8f)
      }
    } catch (_: RuntimeException) { ready = false }
    val pending = pendingRequest; pendingRequest = null
    if (pending != null) startSpeech(pending)
  }
  fun speak(text: String) {
    onMain { requestSpeech(SpeechRequest(text.take(2000))) }
  }
  /** True only after playback finishes; unavailable/interrupted speech returns false.
   * Caller cancellation propagates and stops only its own request. A missing callback is bounded to 30 seconds.
   */
  suspend fun speakAndWait(text: String): Boolean = withTimeoutOrNull(30_000L) {
    suspendCancellableCoroutine { waiter ->
      val request = SpeechRequest(text.take(2000), waiter)
      waiter.invokeOnCancellation { onMain { cancelRequest(request) } }
      onMain { if (waiter.isActive) requestSpeech(request) }
    }
  } ?: false
  private fun requestSpeech(request: SpeechRequest) {
    if (closed || request.text.isBlank()) { complete(request, false); return }
    stopOnMain()
    if (!initialized) {
      pendingRequest = request
      mutable.value = "Preparing offline speech…"
      return
    }
    startSpeech(request)
  }
  private fun startSpeech(request: SpeechRequest) {
    if (closed || request.waiter?.isActive == false) { complete(request, false); return }
    if (!ready) {
      mutable.value = "No offline Mandarin voice is available on this device. You can continue studying."
      complete(request, false)
      return
    }
    try {
      if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        mutable.value = "Audio is currently unavailable. You can continue studying."
        complete(request, false)
        return
      }
      mutable.value = null
      currentRequest = request
      if (tts?.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.id) == TextToSpeech.SUCCESS) return
    } catch (_: RuntimeException) {
      ready = false
    }
    currentRequest = null
    mutable.value = "Speech could not be played. You can continue studying."
    releaseFocus()
    complete(request, false)
  }
  private fun finish(utteranceId: String?, completed: Boolean, failed: Boolean = false) {
    val request = currentRequest ?: return
    if (utteranceId != request.id) return
    currentRequest = null
    if (failed) mutable.value = "Speech could not be played. You can continue studying."
    releaseFocus()
    complete(request, completed)
  }
  private fun complete(request: SpeechRequest, completed: Boolean) {
    val waiter = request.waiter ?: return
    // Finish state/focus changes before the waiting coroutine can start another utterance.
    main.post { if (waiter.isActive) waiter.resume(completed) }
  }
  private fun cancelRequest(request: SpeechRequest) {
    if (pendingRequest === request) pendingRequest = null
    if (currentRequest === request) stopOnMain()
  }
  private fun onMain(action: () -> Unit) {
    if (Looper.myLooper() == main.looper) action() else main.post { action() }
  }
  private fun releaseFocus() {
    try { manager.abandonAudioFocusRequest(focus) } catch (_: RuntimeException) { }
  }
  fun dismissMessage() { mutable.value = null }
  fun stop() { onMain { stopOnMain() } }
  private fun stopOnMain() {
    val pending = pendingRequest; pendingRequest = null
    val current = currentRequest; currentRequest = null
    try { tts?.stop() } catch (_: RuntimeException) { }
    releaseFocus()
    if (pending != null) complete(pending, false)
    if (current != null) complete(current, false)
  }
  override fun close() { onMain {
    if (!closed) {
      closed = true
      stopOnMain()
      try { tts?.shutdown() } catch (_: RuntimeException) { }
      tts = null
    }
  } }
}
