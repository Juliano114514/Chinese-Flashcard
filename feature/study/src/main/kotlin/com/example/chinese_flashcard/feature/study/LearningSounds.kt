package com.example.chinese_flashcard.feature.study

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal enum class LearningSound(val resource: Int) {
  CORRECT(R.raw.gentle_correct),
  TRY_AGAIN(R.raw.gentle_retry),
  COMPLETE(R.raw.gentle_complete),
}

/** Dedicated cue players; no speech focus changes or wall-clock guesses about playback completion. */
internal class LearningSounds(context: Context) : AutoCloseable {
  private val appContext = context.applicationContext
  private val debug = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
  private val audioManager = appContext.getSystemService(AudioManager::class.java)
  private val thread = HandlerThread("LearningSounds").apply { start() }
  private val handler = Handler(thread.looper)
  private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
  // MediaPlayer and its callbacks must always use the same Looper thread.
  private class Clip(val player: MediaPlayer, var ready: Boolean = false)
  private class Playback(val sound: LearningSound, val clip: Clip, val waiter: CancellableContinuation<Unit>)
  private val clips = mutableMapOf<LearningSound, Clip>()
  private var active: Playback? = null
  @Volatile private var closed = false

  /** Prepare bundled files without playing anything or blocking the UI thread. */
  suspend fun prewarm() {
    handler.post { if (!closed) LearningSound.entries.forEach(::prepare) }
  }

  private fun prepare(sound: LearningSound) {
    if (closed || clips.containsKey(sound)) return
    val player = runCatching { MediaPlayer() }.getOrNull() ?: return
    val clip = Clip(player)
    clips[sound] = clip
    runCatching {
      player.setAudioAttributes(attributes)
      appContext.resources.openRawResourceFd(sound.resource).use { file ->
        player.setDataSource(file.fileDescriptor, file.startOffset, file.length)
      }
      player.setVolume(.45f, .45f)
      player.isLooping = false
      player.setOnPreparedListener { if (!closed && clips[sound] === clip) clip.ready = true }
      player.setOnCompletionListener {
        if (active?.clip === clip) finishActive("completed")
      }
      player.setOnErrorListener { _, _, _ ->
        if (active?.clip === clip) finishActive("failed")
        discard(sound, clip)
        true
      }
      player.prepareAsync()
    }.onFailure { discard(sound, clip) }
  }

  suspend fun playAnswer(correct: Boolean) = play(if (correct) LearningSound.CORRECT else LearningSound.TRY_AGAIN)

  /** Cold, muted, failed or cancelled cues never hold up the learning flow. */
  suspend fun play(sound: LearningSound) {
    // The longest bundled cue is 720ms; a missing device callback must not lock feedback.
    withTimeoutOrNull(2_000L) {
      suspendCancellableCoroutine<Unit> { waiter ->
        waiter.invokeOnCancellation {
          handler.post { if (active?.waiter === waiter) stopOnPlayerThread() }
        }
        val accepted = handler.post {
          if (closed || !waiter.isActive) { complete(waiter); return@post }
          stopOnPlayerThread()
          prepare(sound)
          val clip = clips[sound]
          val audible = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0 }.getOrDefault(false)
          if (clip == null || !clip.ready || !audible) { complete(waiter); return@post }
          active = Playback(sound, clip, waiter)
          runCatching {
            // Prepared / PlaybackCompleted both start at the beginning, with no seek or stop cutoff.
            clip.player.start()
            if (debug) Log.d("LearningSounds", "player=MediaPlayer cue=$sound state=started")
          }.onFailure {
            finishActive("failed")
            discard(sound, clip)
          }
        }
        if (!accepted) complete(waiter)
      }
    }
  }

  private fun complete(waiter: CancellableContinuation<Unit>) {
    if (waiter.isActive) waiter.resume(Unit)
  }

  private fun finishActive(state: String) {
    val playback = active ?: return
    active = null
    if (debug) Log.d("LearningSounds", "player=MediaPlayer cue=${playback.sound} state=$state")
    complete(playback.waiter)
  }

  private fun discard(sound: LearningSound, clip: Clip) {
    if (clips[sound] !== clip) return
    clips.remove(sound)
    runCatching { clip.player.setOnPreparedListener(null) }
    runCatching { clip.player.setOnCompletionListener(null) }
    runCatching { clip.player.setOnErrorListener(null) }
    runCatching { clip.player.release() }
  }

  private fun stopOnPlayerThread() {
    val playback = active ?: return
    // Only cancellation discards a player. Normal completion keeps it prepared for the next cue.
    discard(playback.sound, playback.clip)
    finishActive("cancelled")
    if (!closed) prepare(playback.sound)
  }

  fun stop() { handler.post { stopOnPlayerThread() } }

  @Synchronized override fun close() {
    if (closed) return
    closed = true
    handler.post {
      stopOnPlayerThread()
      clips.toMap().forEach { (sound, clip) -> discard(sound, clip) }
      thread.quitSafely()
    }
  }
}
