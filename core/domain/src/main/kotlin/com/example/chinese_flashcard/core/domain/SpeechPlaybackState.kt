package com.example.chinese_flashcard.core.domain

enum class SpeechPlaybackPhase { IDLE, PREPARING, PLAYING }

/** Identifies the manual control waiting for audio; automatic playback has no source. */
data class SpeechPlaybackState(val operationId: Long = 0, val sourceId: String? = null,
  val phase: SpeechPlaybackPhase = SpeechPlaybackPhase.IDLE)
