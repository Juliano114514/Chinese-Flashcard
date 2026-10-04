package com.example.chinese_flashcard.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-provided, offline Chinese speech only; no recording or network fallback. */
class OfflineSpeech(context: Context) : AutoCloseable {
  private val main = Handler(Looper.getMainLooper())
  private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
  private val mutable = MutableStateFlow<String?>(null)
  val message = mutable.asStateFlow()
  private var closed = false
  private var ready = false
  private var initialized = false
  private var pendingText: String? = null
  private var currentUtterance: String? = null
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
          if (!closed && utteranceId != null && utteranceId == currentUtterance) {
            currentUtterance = null
            releaseFocus()
          }
        } }
        @Deprecated("Android callback")
        override fun onError(utteranceId: String?) { main.post {
          if (!closed && utteranceId != null && utteranceId == currentUtterance) {
            currentUtterance = null
            mutable.value = "Speech could not be played. You can continue studying."
            releaseFocus()
          }
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
      if (ready) tts?.setAudioAttributes(attributes)
    } catch (_: RuntimeException) { ready = false }
    val pending = pendingText; pendingText = null
    if (pending != null) speak(pending)
  }
  fun speak(text: String) {
    if (closed || text.isBlank()) return
    if (!initialized) { pendingText = text.take(2000); mutable.value = "Preparing offline speech…"; return }
    if (!ready) {
      mutable.value = "No offline Mandarin voice is available on this device. You can continue studying."
      return
    }
    try {
      if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        mutable.value = "Audio is currently unavailable. You can continue studying."; return
      }
      mutable.value = null
      val id = "flashcard-${System.nanoTime()}"
      currentUtterance = id
      if (tts?.speak(text.take(2000), TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS) return
    } catch (_: RuntimeException) {
      ready = false
    }
    currentUtterance = null
    mutable.value = "Speech could not be played. You can continue studying."
    releaseFocus()
  }
  private fun releaseFocus() {
    try { manager.abandonAudioFocusRequest(focus) } catch (_: RuntimeException) { }
  }
  fun dismissMessage() { mutable.value = null }
  fun stop() {
    pendingText = null
    currentUtterance = null
    try { tts?.stop() } catch (_: RuntimeException) { }
    releaseFocus()
  }
  override fun close() {
    if (!closed) {
      closed = true
      stop()
      try { tts?.shutdown() } catch (_: RuntimeException) { }
      tts = null
    }
  }
}
