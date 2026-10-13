package com.example.chinese_flashcard.core.domain

enum class SpeechScene {
  HOME_WORD, STUDY_PROMPT, STUDY_CONTEXT, STUDY_EXPLANATION, ANSWER_FEEDBACK,
  WORD_DETAIL, WORD_PRONUNCIATION, MEANING_PRONUNCIATION, EXAMPLE_PRONUNCIATION, WRITING_WORD,
}

enum class SpeechMode {
  NONE, ONLY_EN, ONLY_CN, BOTH;

  fun allows(language: AppLanguage): Boolean = when (this) {
    NONE -> false
    ONLY_EN -> language == AppLanguage.EN
    ONLY_CN -> language == AppLanguage.CN
    BOTH -> true
  }

  companion object {
    fun forLanguage(language: AppLanguage) = if (language == AppLanguage.EN) ONLY_EN else ONLY_CN
  }
}

/** Automatic scenes default to the learning language; manual scenes use their displayed content. */
data class SpeechOptions(val modes: Map<SpeechScene, SpeechMode> = emptyMap()) {
  fun mode(scene: SpeechScene, defaultLanguage: AppLanguage): SpeechMode =
    modes[scene] ?: when (scene) {
      SpeechScene.WORD_PRONUNCIATION, SpeechScene.WRITING_WORD -> SpeechMode.ONLY_CN
      SpeechScene.MEANING_PRONUNCIATION -> SpeechMode.ONLY_EN
      SpeechScene.EXAMPLE_PRONUNCIATION -> SpeechMode.BOTH
      else -> SpeechMode.forLanguage(defaultLanguage)
    }

  fun withMode(scene: SpeechScene, mode: SpeechMode) = copy(modes = modes + (scene to mode))

  fun requests(request: SpeechRequest): List<SpeechRequest> {
    val mode = mode(request.scene, request.language)
    val otherLanguage = if (request.language == AppLanguage.CN) AppLanguage.EN else AppLanguage.CN
    val candidates = listOfNotNull(request, request.counterpart?.takeIf(String::isNotBlank)?.let {
      SpeechRequest(it, otherLanguage, request.scene)
    })
    return candidates.filter { mode.allows(it.language) && it.text.isNotBlank() }
      .map { it.copy(counterpart = null) }
  }
}

data class ExplanationOptions(val original: Boolean = true, val pinyin: Boolean = true,
  val literal: Boolean = true, val translation: Boolean = true) {
  val allSelected: Boolean get() = original && pinyin && literal && translation
  val mask: Int get() = (if (original) 1 else 0) or (if (pinyin) 2 else 0) or
    (if (literal) 4 else 0) or (if (translation) 8 else 0)

  companion object {
    fun fromMask(mask: Int) = ExplanationOptions(mask and 1 != 0, mask and 2 != 0,
      mask and 4 != 0, mask and 8 != 0)
  }
}
