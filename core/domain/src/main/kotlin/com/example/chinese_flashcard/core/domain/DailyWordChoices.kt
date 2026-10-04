package com.example.chinese_flashcard.core.domain

/** Choices for new daily-word settings; stored legacy values remain readable. */
object DailyWordChoices {
  const val MIN = 5
  const val MAX = 50
  const val STEP = 5

  fun normalize(value: Int): Int = ((value.coerceIn(MIN, MAX) + STEP / 2) / STEP) * STEP

  fun isAllowed(value: Int): Boolean = value in MIN..MAX && value % STEP == 0
}
