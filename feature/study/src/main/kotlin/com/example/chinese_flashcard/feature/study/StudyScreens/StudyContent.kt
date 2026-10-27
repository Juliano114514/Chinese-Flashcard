package com.example.chinese_flashcard.feature.study

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.StudySnapshot
import com.example.chinese_flashcard.core.domain.PracticeProgress
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.ChinesePronunciation
import com.example.chinese_flashcard.core.ui.AppLanguageProvider
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardToolbar
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.StageThemePreviewCase
import com.example.chinese_flashcard.core.ui.WordMeanings
import com.example.chinese_flashcard.core.ui.WordExplanation
import com.example.chinese_flashcard.core.ui.WordUserActions
import com.example.chinese_flashcard.core.ui.FlashcardIcons
import com.example.chinese_flashcard.core.ui.glassPanel
import com.example.chinese_flashcard.core.ui.glassPanelColor
import com.example.chinese_flashcard.core.ui.rememberSpeechSourceId

@Composable
internal fun StudyContent(
  state: StudyUiState,
  selectedOption: String?,
  answerChosen: Boolean,
  correctAnswerRevealed: Boolean,
  feedbackFinished: Boolean,
  feedbackPlaybackSucceeded: Boolean,
  onBack: () -> Unit,
  onWriting: (String) -> Unit,
  onSpeak: (SpeechRequest) -> Unit,
  onSubmit: (String, String?) -> Unit,
  onExplain: (String) -> Unit,
  onAdvance: () -> Unit,
  onWordWriting: (String) -> Unit,
  onCollection: (String, Boolean) -> Unit,
  onSkip: (String, Boolean) -> Unit,
  onRetry: () -> Unit,
  ipaResolver: (String) -> String? = { null },
  voiceLoading: Boolean = false,
  onReviewMore: () -> Unit = {},
) {
  val card = state.card
  val headingSourceId = rememberSpeechSourceId(Triple(card?.id, card?.phase, state.directionGeneration))
  val practice = state.snapshot?.practice?.takeIf { it.kind == card?.kind }
  val completed = practice?.completed ?: state.today?.let { today -> when (card?.kind) {
    StudyKind.NEW, StudyKind.CARRYOVER -> today.newCompleted + today.carryoverCompleted
    StudyKind.REVIEW -> today.reviewCompleted
    else -> today.completed
  } } ?: 0
  val planned = practice?.planned ?: state.today?.let { today -> when (card?.kind) {
    StudyKind.NEW, StudyKind.CARRYOVER -> today.newPlanned + today.carryoverPlanned
    StudyKind.REVIEW -> today.reviewPlanned
    else -> today.planned
  } } ?: 0
  val largeText = LocalDensity.current.fontScale >= 1.25f
  Box(Modifier.fillMaxSize().flashcardBackground()) {
  Scaffold(containerColor = Color.Transparent,
    contentWindowInsets = if (card != null && card.phase != CardPhase.FINISHED) WindowInsets(0, 0, 0, 0)
      else WindowInsets.navigationBars.only(WindowInsetsSides.Bottom), topBar = {
    Column {
    FlashcardToolbar(when (card?.kind) {
        StudyKind.NEW -> uiString(UiR.string.ui_learn_2118cb)
        StudyKind.REVIEW -> uiString(UiR.string.ui_review)
        StudyKind.CARRYOVER -> uiString(UiR.string.ui_continue)
        StudyKind.COLLECTION -> uiString(UiR.string.ui_collections)
        StudyKind.MISTAKES -> uiString(UiR.string.ui_mistakes_3bd9fb)
        null -> uiString(UiR.string.ui_study)
      }, onBack, Modifier.padding(horizontal = 12.dp, vertical = 3.dp), enabled = !state.busy, trailing = {
      if (card != null && card.phase != CardPhase.FINISHED) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          val showRound = !card.reviewRecall &&
            card.phase in listOf(CardPhase.INTRO, CardPhase.QUESTION, CardPhase.FEEDBACK)
          if (showRound) RoundProgress(card)
          WordUserActions(isCollected = card.isCollected, isSkipped = card.isSkipped,
            enabled = !state.busy && card.phase != CardPhase.FEEDBACK,
            onCollection = { onCollection(card.word.id, it) }, onSkip = { onSkip(card.word.id, it) },
            wordId = card.word.id,
            wordLabel = if (card.learningLanguage == AppLanguage.CN) card.word.hanzi else card.promptSpeech().text,
            compact = true)
        }
      }
    }, subtitle = {
      if (voiceLoading && card?.phase in listOf(CardPhase.INTRO, CardPhase.QUESTION, CardPhase.FEEDBACK, CardPhase.EXPLANATION)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(uiString(UiR.string.ui_loading_voice), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        }
      }
    })
    if (card != null && card.phase != CardPhase.FINISHED && state.snapshot != null) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DailyProgress(completed, planned)
        Text(uiString(UiR.string.ui_progress_count, completed, planned), modifier = Modifier.align(Alignment.End),
          style = MaterialTheme.typography.labelLarge, color = FlashcardStyle.colors.gradientSecondaryInk)
      }
    }
    }
  }, bottomBar = {
    if (card != null && card.phase != CardPhase.FINISHED) {
      val barShape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
      Surface(color = glassPanelColor(), tonalElevation = 0.dp, shadowElevation = 0.dp,
        modifier = Modifier.glassPanel(barShape), shape = barShape) {
      Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        when (card.phase) {
          CardPhase.QUESTION -> {
            if (!card.canAnswer) {
              Text(uiString(UiR.string.ui_this_word_has_too_few_distinct_choices_you_can_skip_it_for_now),
                style = MaterialTheme.typography.bodySmall)
              OutlinedButton(onClick = { onSkip(card.word.id, true) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(uiString(UiR.string.ui_skip_word)) }
            } else {
            StudyActionPair(stacked = largeText) { buttonModifier ->
              OutlinedButton(onClick = {
                onSubmit(card.id, null)
              }, enabled = !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
                shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp,
                  if (answerChosen && selectedOption == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = buttonModifier.heightIn(min = 52.dp).semantics { selected = answerChosen && selectedOption == null }) { Text(uiString(UiR.string.ui_i_don_t_know)) }
              Button(onClick = { onSubmit(card.id, selectedOption) }, enabled = answerChosen && !state.busy,
                shape = MaterialTheme.shapes.medium, modifier = buttonModifier.heightIn(min = 52.dp)) { Text(uiString(UiR.string.ui_next)) }
            }
            }
          }
          CardPhase.FEEDBACK -> {
            if (card.correct == true && feedbackFinished && !feedbackPlaybackSucceeded) {
              Text(uiString(UiR.string.ui_audio_unavailable_tap_next), style = MaterialTheme.typography.bodySmall,
                color = FlashcardStyle.colors.gradientSecondaryInk)
            }
            Button(onClick = { onExplain(card.id) }, enabled = !state.busy &&
              (feedbackFinished || (card.correct == true && !state.settings.waitForCorrectAnswerAudio)),
              shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(uiString(UiR.string.ui_next)) }
          }
          CardPhase.INTRO, CardPhase.EXPLANATION -> {
            StudyActionPair(stacked = largeText) { buttonModifier ->
              if (card.learningLanguage == AppLanguage.CN) OutlinedButton(onClick = { onWordWriting(card.word.id) }, enabled = !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
                border = BorderStroke(1.dp, FlashcardStyle.colors.gradientAction),
                shape = MaterialTheme.shapes.medium, modifier = buttonModifier.heightIn(min = 52.dp)) { Text(uiString(UiR.string.ui_write)) }
              Button(onClick = onAdvance, enabled = !state.busy && card.phase != CardPhase.FEEDBACK, shape = MaterialTheme.shapes.medium,
                modifier = buttonModifier.heightIn(min = 52.dp)) {
                Text(if (card.phase == CardPhase.INTRO) uiString(UiR.string.ui_continue) else uiString(UiR.string.ui_next_word))
              }
            }
          }
          else -> Unit
        }
      }
      }
    }
  }) { padding ->
    PageColumn(scrollKey = "${card?.id}/${card?.phase}", modifier = Modifier.padding(padding), studyPage = true) {
      when {
        state.loading || (state.busy && card == null) -> LoadingNotice(studyPage = true)
        card == null -> {
          Text(uiString(UiR.string.ui_no_active_session), style = MaterialTheme.typography.headlineMedium)
          Text(uiString(UiR.string.ui_choose_learn_or_review_from_home), color = FlashcardStyle.colors.gradientSecondaryInk)
          Button(onClick = onBack, enabled = !state.busy, shape = MaterialTheme.shapes.medium) { Text(uiString(UiR.string.ui_back_to_home)) }
        }
        card.phase == CardPhase.FINISHED -> {
          Spacer(Modifier.height(32.dp))
          val unfinishedDaily = state.today?.let { today -> when (card.kind) {
            StudyKind.NEW, StudyKind.CARRYOVER -> !today.learningComplete
            StudyKind.REVIEW -> today.reviewCompleted < today.reviewPlanned
            else -> false
          } } == true
          val paused = practice?.paused == true || (practice == null && (card.isSkipped || unfinishedDaily))
          if (card.canAnswer && !paused) {
            Icon(FlashcardIcons.Complete, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(56.dp))
            Text(uiString(UiR.string.ui_completion_encouragement), style = MaterialTheme.typography.titleMedium)
          }
          Text(if (!card.canAnswer) uiString(UiR.string.ui_viewing_complete) else if (paused) uiString(UiR.string.ui_session_paused) else uiString(UiR.string.ui_session_complete), style = MaterialTheme.typography.headlineLarge)
          if (!card.canAnswer) Text(uiString(UiR.string.ui_no_reliable_question_is_available_for_this_word_no_learning_progress_was_recorded),
            color = FlashcardStyle.colors.gradientSecondaryInk)
          if (paused && card.canAnswer) Text(uiString(UiR.string.ui_open_this_session_from_home_to_continue_skipped_words_stay_paused_until_you_unskip_them),
            color = FlashcardStyle.colors.gradientSecondaryInk)
          if (practice != null) {
            CompletionCount(if (practice.kind == StudyKind.COLLECTION) uiString(UiR.string.ui_collections) else uiString(UiR.string.ui_mistakes_3bd9fb), completed, planned)
          } else state.today?.let { today ->
            Text(uiString(UiR.string.ui_progress_today, today.dailyLearningCompleted, today.dailyGoal))
            if (today.dailyGoalReached) Text(uiString(UiR.string.ui_daily_goal_complete),
              color = FlashcardStyle.colors.success, style = MaterialTheme.typography.titleMedium)
            CompletionCount(uiString(UiR.string.ui_learn_2118cb), today.newCompleted + today.carryoverCompleted,
              today.newPlanned + today.carryoverPlanned)
            CompletionCount(uiString(UiR.string.ui_reviews), today.reviewCompleted, today.reviewPlanned)
            if (today.carryoverPlanned > 0) CompletionCount(uiString(UiR.string.ui_continued), today.carryoverCompleted, today.carryoverPlanned)
            today.nextReviewDate?.let { date ->
              Text(uiString(UiR.string.ui_next_review_1_s, date), style = MaterialTheme.typography.titleMedium,
                color = FlashcardStyle.colors.gradientAction)
            }
          }
          if (card.kind == StudyKind.REVIEW && card.canAnswer && !paused && state.today?.canReviewMore == true) {
            Button(onClick = onReviewMore, enabled = !state.busy, shape = MaterialTheme.shapes.medium,
              modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(uiString(UiR.string.ui_continue_review)) }
          }
          Button(onClick = onBack, enabled = !state.busy, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(uiString(UiR.string.ui_done)) }
        }
        else -> {
          if (card.phase in listOf(CardPhase.QUESTION, CardPhase.FEEDBACK) ||
            (card.learningLanguage == AppLanguage.EN && card.phase in listOf(CardPhase.INTRO, CardPhase.EXPLANATION))) {
            QuestionHeading(card, enabled = !state.busy && card.phase != CardPhase.FEEDBACK,
              onSpeak = onSpeak, ipaResolver = ipaResolver)
          } else StudyWordHeading(card.word, enabled = !state.busy && card.phase != CardPhase.FEEDBACK,
            onSpeak = { onSpeak(SpeechRequest(card.word.hanzi, AppLanguage.CN, counterpart = card.questionMeaning(),
              chinesePronunciation = ChinesePronunciation(card.word.hanzi, card.word.pinyin), sourceId = headingSourceId)) },
            onWrite = if (card.learningLanguage == AppLanguage.CN && card.phase in listOf(CardPhase.INTRO, CardPhase.EXPLANATION))
              ({ onWordWriting(card.word.id) }) else null, sourceId = headingSourceId)
          when (card.phase) {
            CardPhase.INTRO, CardPhase.EXPLANATION -> {
              if (card.correct != null) {
                AnswerFeedback(card.correct == true,
                  card.options.firstOrNull { it.id == card.selectedOptionId }?.let { card.optionLabel(it) } ?: uiString(UiR.string.ui_i_don_t_know))
              }
              Surface(shape = MaterialTheme.shapes.large, color = glassPanelColor(),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().glassPanel(shape = MaterialTheme.shapes.large)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                  if (card.learningLanguage == AppLanguage.EN) ChineseTranslation(card.word, !state.busy, onSpeak)
                  else WordMeanings(card.word, onSpeak, ipaResolver, enabled = !state.busy)
                }
              }
              if (!card.canAnswer) Text(uiString(UiR.string.ui_no_reliable_question_is_available_for_this_word_viewing_it_does_not_count_as_learned),
                style = MaterialTheme.typography.bodySmall, color = FlashcardStyle.colors.gradientSecondaryInk)
              WordExplanation(card.word, onSpeak, compact = false, enabled = !state.busy, progressive = true)
            }
            CardPhase.QUESTION, CardPhase.FEEDBACK -> {
              val feedback = card.phase == CardPhase.FEEDBACK
              card.contextExample()?.let { example ->
                QuestionExample(example, card.promptSpeech().language, onSpeak, enabled = !feedback && !state.busy)
              }
              Text(if (card.learningLanguage == AppLanguage.CN) uiString(UiR.string.ui_choose_the_meaning) else uiString(UiR.string.ui_choose_the_chinese_word), style = MaterialTheme.typography.bodyMedium,
                color = FlashcardStyle.colors.gradientSecondaryInk)
              Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              card.options.forEachIndexed { index, option ->
                val selected = if (feedback) card.selectedOptionId == option.id else answerChosen && selectedOption == option.id
                val correct = card.questionMeaningId == option.id
                val revealed = feedback && (selected || (card.correct == false && correct && correctAnswerRevealed))
                LanguageOption(index, option, card.learningLanguage, selected, enabled = !state.busy && !feedback && card.canAnswer,
                  onClick = {
                    onSubmit(card.id, option.id)
                  },
                  result = when {
                    feedback && selected && !correct -> false
                    feedback && correct && revealed -> true
                    else -> null
                  }, revealBilingual = revealed, dimmed = feedback && !revealed)
              }
              }
              if (feedback) AnswerFeedback(card.correct == true,
                card.options.firstOrNull { it.id == card.selectedOptionId }?.let { card.optionLabel(it) } ?: uiString(UiR.string.ui_i_don_t_know))
            }
            CardPhase.WRITING -> {
              Text(uiString(UiR.string.ui_writing), style = MaterialTheme.typography.titleLarge)
              card.writingSessionId?.let { id ->
                Button(onClick = { onWriting(id) }, enabled = !state.busy, shape = MaterialTheme.shapes.medium) { Text(uiString(UiR.string.ui_continue_writing)) }
              }
            }
            CardPhase.FINISHED -> Unit
          }
        }
      }
      state.error?.let { ErrorNotice(it, !state.busy, onRetry, studyPage = true) }
    }
  }
  }
}

@Composable
private fun AnswerFeedback(correct: Boolean, selectedMeaning: String) {
  Surface(shape = MaterialTheme.shapes.large,
    modifier = Modifier.fillMaxWidth().glassPanel(shape = MaterialTheme.shapes.large),
    color = if (correct) FlashcardStyle.colors.successContainer else MaterialTheme.colorScheme.errorContainer,
    contentColor = if (correct) FlashcardStyle.colors.onSuccessContainer else MaterialTheme.colorScheme.onErrorContainer) {
  Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(if (correct) FlashcardIcons.Check else FlashcardIcons.Close, contentDescription = null,
        modifier = Modifier.size(24.dp))
      Text(uiString(if (correct) UiR.string.ui_feedback_success else UiR.string.ui_feedback_retry),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    Text(if (correct) uiString(UiR.string.ui_correct) else uiString(UiR.string.ui_incorrect_round_resets_to_1),
      style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    if (!correct) {
      Text(uiString(UiR.string.ui_your_answer_1_s, selectedMeaning), style = MaterialTheme.typography.bodySmall)
    }
  }
  }
}

@Composable
private fun CompletionCount(label: String, completed: Int, planned: Int) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
      color = FlashcardStyle.colors.gradientSecondaryInk)
    Text(uiString(UiR.string.ui_fraction, completed, planned), style = MaterialTheme.typography.titleMedium)
  }
}

@Composable
private fun StudyPagePreview(theme: StageThemePreviewCase, card: StudyCard, selectedOption: String? = null,
  revealCorrect: Boolean = false, feedbackFinished: Boolean = false) {
  FlashcardTheme(darkTheme = theme.darkTheme, stage = theme.stage) {
    val practice = if (card.kind in listOf(StudyKind.COLLECTION, StudyKind.MISTAKES)) PracticeProgress(card.kind, 2, 5) else null
    StudyContent(StudyUiState(loading = false, snapshot = StudySnapshot(PreviewToday, card, practice)),
      selectedOption = selectedOption, answerChosen = selectedOption != null,
      correctAnswerRevealed = revealCorrect, feedbackFinished = feedbackFinished,
      feedbackPlaybackSucceeded = feedbackFinished, onBack = {}, onWriting = {}, onSpeak = {},
      onSubmit = { _, _ -> }, onExplain = {}, onAdvance = {}, onWordWriting = {},
      onCollection = { _, _ -> }, onSkip = { _, _ -> }, onRetry = {})
  }
}

@Preview(name = "Question", widthDp = 360, heightDp = 640)
@Composable
private fun QuestionPreview() {
  StudyPagePreview(StageThemePreviewCase(VocabularyStage.PRIMARY, false), PreviewCard)
}

@Preview(name = "Wrong answer revealed", widthDp = 360, heightDp = 640)
@Composable
private fun WrongAnswerPreview() {
  StudyPagePreview(StageThemePreviewCase(VocabularyStage.PRIMARY, false),
    PreviewCard.copy(phase = CardPhase.FEEDBACK, selectedOptionId = "morning", correct = false),
    selectedOption = "morning", revealCorrect = true, feedbackFinished = true)
}

@Preview(name = "English question · Chinese interface", widthDp = 360, heightDp = 640)
@Composable
private fun EnglishQuestionPreview() {
  AppLanguageProvider(AppLanguage.CN) {
    StudyPagePreview(StageThemePreviewCase(VocabularyStage.PRIMARY, false),
      PreviewCard.copy(learningLanguage = AppLanguage.EN))
  }
}

@Preview(name = "Explanation", widthDp = 360, heightDp = 640)
@Composable
private fun ExplanationPreview() {
  StudyPagePreview(StageThemePreviewCase(VocabularyStage.PRIMARY, false),
    PreviewCard.copy(phase = CardPhase.EXPLANATION, correct = true,
      selectedOptionId = "thank-you", isCollected = true))
}
