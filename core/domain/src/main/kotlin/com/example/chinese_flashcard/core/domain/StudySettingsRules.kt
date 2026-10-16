package com.example.chinese_flashcard.core.domain

/** Product settings shared by editors and validation; persisted migration defaults stay frozen. */
object StudySettingsRules {
  // Existing databases may contain daily goals outside the current editor's DailyWordChoices.
  val storedDailyWords: IntRange = 1..100
  val rounds: IntRange = 2..8
  val reviewDays: List<Int> = listOf(1, 3, 5, 7, 14, 30)
  val speechPreloadWords: IntRange = 0..20
  val speechCacheMiB: IntRange = 64..2048
  val homeShuffleQueueSize: IntRange = 1..20

  const val DEFAULT_ROUNDS = 4
  val defaultReviewDays: List<Int> = listOf(1, 3, 7)
  const val DEFAULT_SPEECH_PRELOAD_WORDS = 5
  const val DEFAULT_SPEECH_CACHE_MIB = 256
  const val DEFAULT_HOME_SHUFFLE_QUEUE_SIZE = 5
}
