package com.example.chinese_flashcard.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

enum class LearningSound { CORRECT, TRY_AGAIN, COMPLETE }

/** Original, softly enveloped sine notes; no downloaded recording, permission or network. */
class LearningSounds(context: Context) : AutoCloseable {
  private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
  private val lock = Any()
  private var activeTrack: AudioTrack? = null
  private var closed = false

  /** Call after speech completes, before advancing to the next speaking page. */
  suspend fun playAnswer(correct: Boolean) = play(if (correct) LearningSound.CORRECT else LearningSound.TRY_AGAIN)

  suspend fun play(sound: LearningSound) = withContext(Dispatchers.IO) {
    val audible = runCatching {
      audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
    }.getOrDefault(false)
    if (!audible) return@withContext
    val samples = synthesize(sound)
    currentCoroutineContext().ensureActive()
    val track = synchronized(lock) {
      if (closed) return@withContext
      releaseActive()
      try {
        val created = AudioTrack.Builder()
          .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
          .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
          .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
        activeTrack = created
        if (created.state != AudioTrack.STATE_INITIALIZED || created.write(samples, 0, samples.size) != samples.size) {
          releaseActive()
          null
        } else {
          created.setVolume(.22f)
          created.play()
          created
        }
      } catch (_: Exception) {
        releaseActive()
        null
      }
    } ?: return@withContext
    try { delay(samples.size * 1000L / SAMPLE_RATE + 25L) }
    finally { synchronized(lock) { if (activeTrack === track) releaseActive() } }
  }

  fun stop() = synchronized(lock) { releaseActive() }

  override fun close() = synchronized(lock) { closed = true; releaseActive() }

  private fun releaseActive() {
    val track = activeTrack ?: return
    activeTrack = null
    runCatching { track.stop() }
    runCatching { track.release() }
  }

  private fun synthesize(sound: LearningSound): ShortArray {
    val notes = when (sound) {
      LearningSound.CORRECT -> doubleArrayOf(659.25, 880.0)
      LearningSound.TRY_AGAIN -> doubleArrayOf(349.23, 329.63)
      LearningSound.COMPLETE -> doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
    }
    val noteMs = if (sound == LearningSound.COMPLETE) 95 else 70
    val frames = SAMPLE_RATE * noteMs / 1000
    return ShortArray(frames * notes.size) { frame ->
      val position = frame % frames
      val progress = position.toDouble() / frames
      // Zero at each note boundary prevents clicks; the quiet harmonic warms the tone.
      val envelope = sin(PI * progress) * sin(PI * progress)
      val angle = 2.0 * PI * notes[frame / frames] * position / SAMPLE_RATE
      ((sin(angle) + .12 * sin(angle * 2.0)) * envelope * 14000.0).toInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
  }

  private companion object { const val SAMPLE_RATE = 24000 }
}
