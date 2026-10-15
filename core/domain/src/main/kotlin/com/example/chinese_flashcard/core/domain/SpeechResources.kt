package com.example.chinese_flashcard.core.domain

/** Complete playable word content, independent of automatic speech preferences. */
fun wordSpeechResources(word: WordEntry): List<SpeechRequest> =
  (wordSpeechBasics(word) + wordSpeechExamples(word)).distinctBy { Triple(it.language, it.text, it.chinesePronunciation) }

fun wordSpeechBasics(word: WordEntry): List<SpeechRequest> = buildList<SpeechRequest> {
  add(SpeechRequest(word.hanzi, AppLanguage.CN,
    chinesePronunciation = ChinesePronunciation(word.hanzi, word.pinyin)))
  word.meanings.forEach { meaning ->
    add(SpeechRequest(meaning.english, AppLanguage.EN, SpeechScene.MEANING_PRONUNCIATION))
    splitEnglishMeanings(meaning.english).forEach { text ->
      add(SpeechRequest(text, AppLanguage.EN, SpeechScene.MEANING_PRONUNCIATION))
    }
  }
}.filter { it.text.isNotBlank() }.distinctBy { Triple(it.language, it.text, it.chinesePronunciation) }

fun wordSpeechExamples(word: WordEntry): List<SpeechRequest> = buildList<SpeechRequest> {
  word.examples.forEach { example ->
    add(SpeechRequest(example.hanzi, AppLanguage.CN, SpeechScene.EXAMPLE_PRONUNCIATION,
      chinesePronunciation = ChinesePronunciation(example.hanzi, example.pinyin, example.chunks)))
    add(SpeechRequest(example.english, AppLanguage.EN, SpeechScene.EXAMPLE_PRONUNCIATION))
  }
}.filter { it.text.isNotBlank() }.distinctBy { Triple(it.language, it.text, it.chinesePronunciation) }
