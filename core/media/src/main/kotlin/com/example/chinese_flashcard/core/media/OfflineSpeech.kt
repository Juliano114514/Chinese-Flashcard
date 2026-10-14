package com.example.chinese_flashcard.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.UiMessageKey
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Shared persistent resources and cancellable playback; model work never runs on the UI thread. */
class OfflineSpeech(context: Context) : AutoCloseable {
  private val main = Handler(Looper.getMainLooper())
  private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val mutable = MutableStateFlow<UiMessageKey?>(null)
  val message = mutable.asStateFlow()
  private val mutablePlaybackPreparing = MutableStateFlow(false)
  val playbackPreparing = mutablePlaybackPreparing.asStateFlow()
  private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
  private val system = SystemOfflineSpeech(context, attributes)
  private val resources = SpeechResources(context, ChineseSpeech(context), EnglishSpeech(context), system)
  val cacheUsage: StateFlow<SpeechCacheUsage> = resources.cacheUsage
  val resourceChanges: StateFlow<Long> = resources.changes
  private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    .setAudioAttributes(attributes).setOnAudioFocusChangeListener { if (it < 0) stop() }.build()
  private var closed = false
  @Volatile private var current: Utterance? = null
  @Volatile private var playingTrack: AudioTrack? = null
  private var playbackJob: Job? = null
  private var englishWarmJob: Job? = null
  private var chineseWarmJob: Job? = null

  private class Utterance(val speech: SpeechRequest, val waiter: CancellableContinuation<Boolean>? = null,
    val onStarted: () -> Unit = {}) {
    val deadline = SystemClock.elapsedRealtime() + 30_000L
    @Volatile var started = false
  }

  fun speak(text: String) = speak(SpeechRequest(text, AppLanguage.CN))
  fun speak(request: SpeechRequest) { onMain { requestSpeech(Utterance(request.copy(text = request.text.take(2000)))) } }

  suspend fun speakAndWait(request: SpeechRequest, onStarted: () -> Unit = {}): Boolean = withTimeoutOrNull(30_000L) {
    suspendCancellableCoroutine { waiter ->
      val utterance = Utterance(request.copy(text = request.text.take(2000)), waiter, onStarted)
      waiter.invokeOnCancellation { onMain { if (current === utterance) stopOnMain() } }
      onMain { if (waiter.isActive) requestSpeech(utterance) }
    }
  } ?: false

  /** Caller cancellation withdraws its interests, preserving another subscriber's foreground task. */
  suspend fun prefetch(requests: List<SpeechRequest>): Boolean = try { resources.prefetch(requests) }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { false }
    catch (_: LinkageError) { false }

  /** Checks completed resources only; this does not start synthesis or audible output. */
  suspend fun isReady(requests: List<SpeechRequest>): Boolean = try { resources.isReady(requests) }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { false }

  fun configureCache(maxMiB: Int) = resources.configureCache(maxMiB)
  fun clearCache() = resources.clearCache()
  fun pausePreloading() = resources.pausePreloading()
  fun resumePreloading() = resources.resumePreloading()

  /** Compatibility entry points; the page supplies its complete desired resource window. */
  fun preloadEnglish(texts: List<String> = emptyList()) { onMain {
    if (closed) return@onMain
    englishWarmJob?.cancel()
    englishWarmJob = scope.launch {
      delay(220)
      prefetch(texts.filter(String::isNotBlank).map { SpeechRequest(it, AppLanguage.EN) })
    }
  } }
  fun preloadChinese(requests: List<SpeechRequest>) { onMain {
    if (closed) return@onMain
    chineseWarmJob?.cancel()
    chineseWarmJob = scope.launch {
      delay(220)
      prefetch(requests.filter { it.language == AppLanguage.CN && it.text.isNotBlank() })
    }
  } }

  private fun requestSpeech(utterance: Utterance) {
    if (closed || utterance.speech.text.isBlank()) { complete(utterance, false); return }
    stopOnMain()
    current = utterance
    mutablePlaybackPreparing.value = true
    playbackJob = scope.launch {
      try {
        val completed = withTimeout((utterance.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)) {
          val resource = playResource(utterance)
          if (resource != null) resource else resources.withDirectPlayback {
            if (!requestFocus()) false else {
              mutable.value = null
              val played = system.speakDirectAndWait(utterance.speech) { started(utterance) }
              if (!played && current === utterance) reportUnavailable(utterance.speech)
              played
            }
          }
        }
        finish(utterance, completed, !completed && mutable.value == null)
      } catch (_: TimeoutCancellationException) { finish(utterance, false, true) }
      catch (error: CancellationException) { throw error }
      catch (_: Exception) { finish(utterance, false, true) }
      catch (_: LinkageError) { finish(utterance, false, true) }
    }
  }

  private suspend fun playResource(utterance: Utterance): Boolean? {
    val handle = resources.acquire(utterance.speech, true) ?: return null
    try {
      val initial = handle.progress.first { it.chunks.isNotEmpty() || it.finished }
      if (initial.chunks.isEmpty()) return null
      currentCoroutineContext().ensureActive()
      if (current !== utterance || closed) throw CancellationException()
      if (!requestFocus()) return false
      mutable.value = null
      return withContext(Dispatchers.IO) { play(handle, initial.chunks.first(), utterance) }
    } finally { handle.release() }
  }

  private suspend fun play(handle: SpeechResources.Handle, first: SpeechAudioChunk, utterance: Utterance): Boolean = coroutineScope {
    val mask = if (first.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    val minimum = AudioTrack.getMinBufferSize(first.sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
    require(minimum > 0 && first.channelCount in 1..2)
    val track = AudioTrack.Builder().setAudioAttributes(attributes)
      .setAudioFormat(AudioFormat.Builder().setSampleRate(first.sampleRate).setChannelMask(mask)
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setBufferSizeInBytes(maxOf(minimum, first.sampleRate / 5 * first.channelCount * 4)).build()
    var startObserver: Job? = null
    var writtenFrames = 0L
    try {
      currentCoroutineContext().ensureActive()
      check(track.state == AudioTrack.STATE_INITIALIZED)
      playingTrack = track
      if (Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(1)
      track.play()
      startObserver = launch {
        while (isActive && !utterance.started) {
          if ((track.playbackHeadPosition.toLong() and 0xffffffffL) > 0) {
            withContext(Dispatchers.Main.immediate) { started(utterance) }
            break
          }
          delay(10)
        }
      }
      var index = 0
      var success: Boolean
      while (true) {
        val progress = handle.progress.first { it.chunks.size > index || it.finished }
        while (index < progress.chunks.size) {
          val chunk = progress.chunks[index++]
          require(chunk.sampleRate == first.sampleRate && chunk.channelCount == first.channelCount)
          var offset = 0
          while (offset < chunk.samples.size) {
            currentCoroutineContext().ensureActive()
            val count = track.write(chunk.samples, offset, minOf(2048, chunk.samples.size - offset), AudioTrack.WRITE_BLOCKING)
            check(count > 0 && count % first.channelCount == 0)
            offset += count
            writtenFrames += count / first.channelCount
          }
        }
        if (progress.finished) { success = progress.succeeded; break }
      }
      // API 28-30 start only after their buffer fills; very short complete clips need playback-only padding.
      if (Build.VERSION.SDK_INT < 31 && writtenFrames < track.bufferSizeInFrames) {
        var remaining = (track.bufferSizeInFrames - writtenFrames.toInt()) * first.channelCount
        val silence = FloatArray(minOf(2048, remaining))
        while (remaining > 0) {
          currentCoroutineContext().ensureActive()
          val count = track.write(silence, 0, minOf(silence.size, remaining), AudioTrack.WRITE_BLOCKING)
          check(count > 0); remaining -= count
        }
      }
      while ((track.playbackHeadPosition.toLong() and 0xffffffffL) < writtenFrames) {
        currentCoroutineContext().ensureActive()
        delay(10)
      }
      if (writtenFrames > 0) withContext(Dispatchers.Main.immediate) { started(utterance) }
      success
    } finally {
      withContext(NonCancellable) { startObserver?.cancel(); startObserver?.join() }
      if (playingTrack === track) playingTrack = null
      runCatching { track.stop() }
      track.release()
    }
  }

  private fun requestFocus(): Boolean {
    val granted = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    if (!granted) mutable.value = UiMessageKey.AUDIO_IS_CURRENTLY_UNAVAILABLE_YOU_CAN_CONTINUE_STUDYING
    return granted
  }
  private fun started(utterance: Utterance) {
    if (current === utterance && !closed && !utterance.started) {
      utterance.started = true
      mutablePlaybackPreparing.value = false
      utterance.onStarted()
    }
  }
  private suspend fun reportUnavailable(request: SpeechRequest) {
    mutable.value = if (system.fingerprint(request.language) == null) {
      if (request.language == AppLanguage.CN)
        UiMessageKey.NO_OFFLINE_MANDARIN_VOICE_IS_AVAILABLE_ON_THIS_DEVICE_YOU_CAN_CONTINUE_STUDYING
      else UiMessageKey.NO_OFFLINE_ENGLISH_VOICE_IS_AVAILABLE_ON_THIS_DEVICE_YOU_CAN_CONTINUE_STUDYING
    } else UiMessageKey.SPEECH_COULD_NOT_BE_PLAYED_YOU_CAN_CONTINUE_STUDYING
  }
  private fun finish(utterance: Utterance, completed: Boolean, failed: Boolean = false) {
    if (current !== utterance) return
    current = null
    mutablePlaybackPreparing.value = false
    if (failed) mutable.value = UiMessageKey.SPEECH_COULD_NOT_BE_PLAYED_YOU_CAN_CONTINUE_STUDYING
    releaseFocus()
    complete(utterance, completed)
  }
  private fun complete(utterance: Utterance, completed: Boolean) {
    utterance.waiter?.let { if (it.isActive) it.resume(completed) }
  }
  private fun releaseFocus() { runCatching { manager.abandonAudioFocusRequest(focus) } }
  private fun onMain(action: () -> Unit) {
    if (Looper.myLooper() == main.looper) action() else main.post { action() }
  }
  fun dismissMessage() { mutable.value = null }
  fun stop() { onMain(::stopOnMain) }
  private fun stopOnMain() {
    val previous = current; current = null
    mutablePlaybackPreparing.value = false
    playbackJob?.cancel(); playbackJob = null
    runCatching { playingTrack?.pause(); playingTrack?.flush() }
    releaseFocus()
    previous?.let { complete(it, false) }
  }
  override fun close() { onMain {
    if (closed) return@onMain
    closed = true
    stopOnMain()
    englishWarmJob?.cancel(); chineseWarmJob?.cancel()
    scope.launch { try { resources.close() } finally { scope.cancel() } }
  } }
}
