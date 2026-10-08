package com.example.chinese_flashcard.core.domain

import kotlin.math.sqrt

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Start at level 1; each growth tier n requires n² + 4n accumulated growth days. */
data class StudyGrowth(val days: Long = 0, val learnedCompletions: Long = 0,
  val clearedMistakes: Long = 0) {
  val level: Int get() = (sqrt(days.coerceAtLeast(0).toDouble() + 4) - 2).toInt() + 1
  val floor: Long get() = requiredDays(level)
  val span: Long get() = requiredDays(level + 1) - floor
  val earned: Long get() = days - floor
  val remaining: Long get() = span - earned
  val fraction: Float get() = (earned.toDouble() / span).toFloat().coerceIn(0f, 1f)
  val crowns: Int get() = level / 64
  val suns: Int get() = level % 64 / 16
  val moons: Int get() = level % 16 / 4
  val stars: Int get() = level % 4

  private fun requiredDays(level: Int): Long {
    val tier = (level - 1).toLong()
    return tier * tier + 4L * tier
  }
}
