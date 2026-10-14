package com.example.chinese_flashcard.core.media

internal data class SpeechAudio(val sampleRate: Int, val chunks: List<FloatArray>, val channelCount: Int = 1,
  val systemVoiceFingerprint: String? = null) {
  val sampleCount: Long get() = chunks.sumOf { it.size.toLong() }
  val frameCount: Long get() = sampleCount / channelCount
  val pcmBytes: Long get() = sampleCount * 4
}
internal data class SpeechAudioChunk(val sampleRate: Int, val samples: FloatArray, val channelCount: Int = 1)

data class SpeechCacheUsage(val usedBytes: Long, val limitBytes: Long)

internal fun SpeechAudio.isValid(): Boolean = sampleRate in 8_000..192_000 && channelCount in 1..2 &&
  chunks.isNotEmpty() && chunks.size <= 4096 && sampleCount > 0 && sampleCount % channelCount == 0L &&
  frameCount <= sampleRate.toLong() * 60 && chunks.all { samples ->
    samples.isNotEmpty() && samples.size % channelCount == 0 && samples.all { it.isFinite() && it in -1f..1f }
  }
