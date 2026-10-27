package com.example.chinese_flashcard.core.domain

enum class WordTagGroup { TOPIC, USAGE, FORM }

data class WordTag(val id: String, val group: WordTagGroup, val labelEn: String, val labelZh: String,
  val descriptionEn: String = "", val descriptionZh: String = "")

/** A word matches any selected tag within each group, and every selected group. */
data class WordTagFilter(val selectedIds: Set<String> = emptySet()) {
  val isTotal: Boolean get() = selectedIds.isEmpty()

  fun normalized(catalog: List<WordTag>): WordTagFilter {
    val ordered = catalog.filter { it.id in selectedIds }.mapTo(linkedSetOf()) { it.id }
    ordered.addAll(selectedIds.filter { it !in ordered }.sorted())
    return WordTagFilter(ordered)
  }

  fun matches(wordTagIds: Set<String>, catalog: List<WordTag>): Boolean = matcher(catalog)(wordTagIds)

  /** Compile once when filtering a full library instead of grouping the catalog for every word. */
  fun matcher(catalog: List<WordTag>): (Set<String>) -> Boolean {
    if (isTotal) return { true }
    val selected = catalog.filter { it.id in selectedIds }
    // Unknown selections must not silently widen a filtered result to all words.
    if (selected.size != selectedIds.size) return { false }
    val groups = selected.groupBy { it.group }.values.map { tags -> tags.map { it.id } }
    return { wordTagIds -> groups.all { group -> group.any { it in wordTagIds } } }
  }
}

/** Read-only counts for a draft filter; previewing never creates or changes a learning plan. */
data class WordTagProgress(val learned: Int, val total: Int) {
  val complete: Boolean get() = total > 0 && learned >= total
}

data class TagFilterPreview(val stage: VocabularyStage, val filter: WordTagFilter,
  val total: Int, val learned: Int, val tagCounts: Map<String, WordTagProgress> = emptyMap(),
  val totalProgress: WordTagProgress = WordTagProgress(0, 0),
  val learningLanguage: AppLanguage = AppLanguage.CN, val contextVersion: Long = 0)
