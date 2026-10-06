package com.example.chinese_flashcard.core.domain

enum class VocabularyStage(val rarity: Int, val label: String) {
  PRIMARY(0, "Primary school"),
  MIDDLE(1, "Middle school"),
  HIGH(2, "High school"),
  UNIVERSITY(3, "University"),
  CHINESE_STUDIES(4, "Specialist");

  companion object {
    val rarityRange: IntRange = PRIMARY.rarity..CHINESE_STUDIES.rarity
    fun fromRarity(rarity: Int): VocabularyStage? = entries.firstOrNull { it.rarity == rarity }
  }
}

data class StageProgress(val stage: VocabularyStage = VocabularyStage.PRIMARY,
  val learned: Int = 0, val total: Int = 0, val lap: Int = 1)
