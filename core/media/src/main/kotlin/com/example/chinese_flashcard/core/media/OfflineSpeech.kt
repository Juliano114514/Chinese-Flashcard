package com.example.chinese_flashcard.core.media

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.StrokeName
import com.example.chinese_flashcard.core.domain.UiMessageKey
import com.example.chinese_flashcard.core.domain.SpeechCacheController
import com.example.chinese_flashcard.core.domain.SpeechCacheUsage
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
  private val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
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
  private val strokes = StrokeSpeech(context.applicationContext.assets)
  private val cacheCoordinator = SpeechPreloadCoordinator(resources)
  val cache: SpeechCacheController = cacheCoordinator
  val cacheUsage: StateFlow<SpeechCacheUsage> = resources.cacheUsage
  val resourceChanges: StateFlow<Long> = resources.changes
  private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    .setAudioAttributes(attributes).setOnAudioFocusChangeListener { if (it < 0) stop() }.build()
  private var closed = false
  @Volatile private var current: Utterance? = null
  @Volatile private var playingTrack: AudioTrack? = null
  private var playbackJob: Job? = null

  private class Utterance(val speech: SpeechRequest, val waiter: CancellableContinuation<Boolean>? = null,
    val onStarted: () -> Unit = {}, val stroke: StrokeName? = null) {
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

  suspend fun speakStrokeAndWait(name: StrokeName): Boolean = withTimeoutOrNull(10_000L) {
    suspendCancellableCoroutine { waiter ->
      val utterance = Utterance(SpeechRequest(name.chinese, AppLanguage.CN), waiter, stroke = name)
      waiter.invokeOnCancellation { onMain { if (current === utterance) stopOnMain() } }
      onMain { if (waiter.isActive) requestSpeech(utterance) }
    }
  } ?: false

  private fun requestSpeech(utterance: Utterance) {
    if (closed || utterance.speech.text.isBlank()) { complete(utterance, false); return }
    stopOnMain()
    current = utterance
    mutablePlaybackPreparing.value = true
    playbackJob = scope.launch {
      try {
        val completed = withTimeout((utterance.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)) {
          if (utterance.stroke != null) return@withTimeout playStroke(utterance, utterance.stroke)
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

  private suspend fun playStroke(utterance: Utterance, name: StrokeName): Boolean {
    val audio = withContext(Dispatchers.IO) { strokes.audio(name) }
    currentCoroutineContext().ensureActive()
    if (current !== utterance || closed) throw CancellationException()
    if (!requestFocus()) return false
    mutable.value = null
    val chunks = audio.chunks.map { SpeechAudioChunk(audio.sampleRate, it) }
    return withContext(Dispatchers.IO) {
      play(MutableStateFlow(SpeechProgress(chunks, finished = true, succeeded = true)), chunks.first(), utterance)
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
      return withContext(Dispatchers.IO) { play(handle.progress, initial.chunks.first(), utterance) }
    } finally { handle.release() }
  }

  private suspend fun play(progress: StateFlow<SpeechProgress>, first: SpeechAudioChunk, utterance: Utterance): Boolean = coroutineScope {
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
        val available = progress.first { it.chunks.size > index || it.finished }
        while (index < available.chunks.size) {
          val chunk = available.chunks[index++]
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
        if (available.finished) { success = available.succeeded; break }
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
      if (debug) Log.d("SpeechCache", "playback=started language=${utterance.speech.language} scene=${utterance.speech.scene}")
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
    cacheCoordinator.close()
    scope.launch { try { resources.close() } finally { scope.cancel() } }
  } }
}
