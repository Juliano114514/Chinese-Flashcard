package com.example.chinese_flashcard.core.domain

enum class SpeechEngine { AI, SYSTEM }

data class SpeechEngines(val chinese: SpeechEngine = SpeechEngine.AI,
  val english: SpeechEngine = SpeechEngine.AI) {
  fun forLanguage(language: AppLanguage): SpeechEngine = if (language == AppLanguage.CN) chinese else english
}

object SpeechPreview {
  const val CHINESE = "我能吞下玻璃而不伤身体"
  const val PINYIN = "wǒ néng tūn xià bō li ér bù shāng shēn tǐ"
  const val ENGLISH = "I can swallow glass without hurting myself."
  fun request(language: AppLanguage): SpeechRequest = if (language == AppLanguage.CN)
    SpeechRequest(CHINESE, language, chinesePronunciation = ChinesePronunciation(CHINESE, PINYIN),
      sourceId = "settings-voice-cn")
  else SpeechRequest(ENGLISH, language, sourceId = "settings-voice-en")
}
