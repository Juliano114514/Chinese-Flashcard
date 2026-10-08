package com.example.chinese_flashcard.feature.study

import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.AnswerOption
import com.example.chinese_flashcard.core.domain.ExampleChunk
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.Meaning
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.TodaySummary
import com.example.chinese_flashcard.core.domain.StageProgress
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.domain.WordEntry

internal val PreviewWord = WordEntry(
  id = "preview-thank-you", hanzi = "谢谢", pinyin = "xièxie",
  meanings = listOf(Meaning("thank-you", "thank you", "expression")),
  examples = listOf(ExampleSentence("谢谢你的帮助。", "Xièxie nǐ de bāngzhù.", "Thank you for your help.",
    listOf(ExampleChunk("谢谢", "xièxie", "thank"), ExampleChunk("你", "nǐ", "you"),
      ExampleChunk("的", "de", "DE"), ExampleChunk("帮助。", "bāngzhù.", "help")))),
  parts = emptyList(), distractorMeaningIds = emptyList(), literalExplanations = listOf("Express thanks."),
)

internal val PreviewCard = StudyCard("preview", PreviewWord, StudyKind.NEW, CardPhase.QUESTION, 2, 4, false,
  listOf(AnswerOption("thank-you", "thank you", "谢谢", "xièxie"),
    AnswerOption("morning", "good morning", "早上好", "zǎoshang hǎo"),
    AnswerOption("welcome", "you're welcome", "不客气", "bú kèqi"),
    AnswerOption("tomorrow", "see you tomorrow", "明天见", "míngtiān jiàn")))

internal val PreviewToday = TodaySummary(date = "2026-10-06", dailyGoal = 10, totalWords = 7723,
  remainingWords = 7483, learnedWords = 240, newPlanned = 10, newCompleted = 6,
  reviewPlanned = 3, reviewCompleted = 1, carryoverPlanned = 2, carryoverCompleted = 0,
  availableNewWords = 50, collectionsAvailable = 12, mistakesAvailable = 3,
  stageProgress = StageProgress(VocabularyStage.PRIMARY, learned = 240, total = 1545))
