package com.example.chinese_flashcard.feature.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.StudySnapshot
import com.example.chinese_flashcard.core.domain.PracticeProgress
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardToolbar
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.StageThemePreviewCase
import com.example.chinese_flashcard.core.ui.WordMeanings
import com.example.chinese_flashcard.core.ui.WordExplanation
import com.example.chinese_flashcard.core.ui.WordExamplePanel

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
  onSpeak: (String) -> Unit,
  onSubmit: (String, String?) -> Unit,
  onExplain: (String) -> Unit,
  onAdvance: () -> Unit,
  onWordWriting: (String) -> Unit,
  onCollection: (String, Boolean) -> Unit,
  onSkip: (String, Boolean) -> Unit,
  onRetry: () -> Unit,
) {
  val card = state.card
  val practice = state.snapshot?.practice?.takeIf { it.kind == card?.kind }
  val completed = practice?.completed ?: state.today?.completed ?: 0
  val planned = practice?.planned ?: state.today?.planned ?: 0
  val largeText = LocalDensity.current.fontScale >= 1.25f
  Box(Modifier.fillMaxSize().flashcardBackground()) {
  Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
    Column {
    FlashcardToolbar(when (card?.kind) {
        StudyKind.NEW -> "Learn"
        StudyKind.REVIEW -> "Review"
        StudyKind.CARRYOVER -> "Continue"
        StudyKind.COLLECTION -> "Collections"
        StudyKind.MISTAKES -> "Mistakes"
        null -> "Study"
      }, onBack, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), enabled = !state.busy, trailing = {
      if (card != null && card.phase != CardPhase.FINISHED && !largeText) {
        if (state.snapshot != null) {
          Text("Progress $completed / $planned", modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.labelLarge, color = FlashcardStyle.colors.gradientSecondaryInk)
        }
      }
    })
    if (card != null && card.phase != CardPhase.FINISHED && state.snapshot != null && largeText) {
      Text("Progress $completed / $planned", modifier = Modifier.align(Alignment.End).padding(horizontal = 20.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = FlashcardStyle.colors.gradientSecondaryInk)
    }
    if (card != null && card.phase != CardPhase.FINISHED && state.snapshot != null) {
      DailyProgress(completed, planned, Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp))
    }
    }
  }, bottomBar = {
    if (card != null && card.phase != CardPhase.FINISHED) {
      Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        when (card.phase) {
          CardPhase.QUESTION -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              OutlinedButton(onClick = {
                onSubmit(card.id, null)
              }, enabled = !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
                shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp,
                  if (answerChosen && selectedOption == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { selected = answerChosen && selectedOption == null }) { Text("I don't know") }
              Button(onClick = { onSubmit(card.id, selectedOption) }, enabled = answerChosen && !state.busy,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Next") }
            }
          }
          CardPhase.FEEDBACK -> {
            if (card.correct == true && feedbackFinished && !feedbackPlaybackSucceeded) {
              Text("Audio unavailable. Tap Next.", style = MaterialTheme.typography.bodySmall,
                color = FlashcardStyle.colors.gradientSecondaryInk)
            }
            Button(onClick = { onExplain(card.id) }, enabled = !state.busy && feedbackFinished,
              shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Next") }
          }
          CardPhase.INTRO, CardPhase.EXPLANATION -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              OutlinedButton(onClick = { onWordWriting(card.word.id) }, enabled = !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
                border = BorderStroke(1.dp, FlashcardStyle.colors.gradientAction),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Write") }
              Button(onClick = onAdvance, enabled = !state.busy && card.phase != CardPhase.FEEDBACK, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Text(if (card.phase == CardPhase.INTRO) "Continue" else "Next word")
              }
            }
          }
          else -> Unit
        }
      }
    }
  }) { padding ->
    PageColumn(scrollKey = "${card?.id}/${card?.phase}", modifier = Modifier.padding(padding), studyPage = true) {
      when {
        state.loading || (state.busy && card == null) -> LoadingNotice(studyPage = true)
        card == null -> {
          Text("No active session", style = MaterialTheme.typography.headlineMedium)
          Text("Choose Learn or Review from Home.", color = FlashcardStyle.colors.gradientSecondaryInk)
          Button(onClick = onBack, enabled = !state.busy, shape = MaterialTheme.shapes.medium) { Text("Back to Home") }
        }
        card.phase == CardPhase.FINISHED -> {
          Spacer(Modifier.height(32.dp))
          val unfinishedDaily = state.today?.let { today -> when (card.kind) {
            StudyKind.NEW -> today.newCompleted < today.newPlanned
            StudyKind.REVIEW -> today.reviewCompleted < today.reviewPlanned
            StudyKind.CARRYOVER -> today.carryoverCompleted < today.carryoverPlanned
            else -> false
          } } == true
          val paused = practice?.paused == true || (practice == null && (card.isSkipped || unfinishedDaily))
          Text(if (paused) "Session paused" else "Session complete", style = MaterialTheme.typography.headlineLarge)
          if (paused) Text("Open this session from Home to continue. Skipped words stay paused until you unskip them.",
            color = FlashcardStyle.colors.gradientSecondaryInk)
          if (practice != null) {
            CompletionCount(if (practice.kind == StudyKind.COLLECTION) "Collections" else "Mistakes", completed, planned)
          } else state.today?.let { today ->
            Text("${today.completed} / ${today.planned} words today")
            CompletionCount("Learn", today.newCompleted, today.newPlanned)
            CompletionCount("Reviews", today.reviewCompleted, today.reviewPlanned)
            if (today.carryoverPlanned > 0) CompletionCount("Continued", today.carryoverCompleted, today.carryoverPlanned)
            today.nextReviewDate?.let { date ->
              Text("Next review: $date", style = MaterialTheme.typography.titleMedium,
                color = FlashcardStyle.colors.gradientAction)
            }
          }
          Button(onClick = onBack, enabled = !state.busy, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Done") }
        }
        else -> {
          StudyWordHeading(card.word, enabled = !state.busy && card.phase != CardPhase.FEEDBACK,
            onSpeak = { onSpeak(card.word.hanzi) },
            onWrite = if (card.phase in listOf(CardPhase.INTRO, CardPhase.EXPLANATION))
              ({ onWordWriting(card.word.id) }) else null, card = card,
            onCollection = { onCollection(card.word.id, it) }, onSkip = { onSkip(card.word.id, it) })
          when (card.phase) {
            CardPhase.INTRO, CardPhase.EXPLANATION -> {
              WordMeanings(card.word)
              if (card.correct != null) {
                AnswerFeedback(card.correct == true,
                  card.options.firstOrNull { it.id == card.selectedOptionId }?.english ?: "I don't know")
              }
              WordExplanation(card.word, onSpeak, compact = true)
            }
            CardPhase.QUESTION, CardPhase.FEEDBACK -> {
              val feedback = card.phase == CardPhase.FEEDBACK
              card.contextExample()?.let { example ->
                WordExamplePanel(example, onSpeak, enabled = !feedback && !state.busy, compact = true)
              }
              Text("Choose the meaning", style = MaterialTheme.typography.bodyMedium,
                color = FlashcardStyle.colors.gradientSecondaryInk)
              card.options.forEachIndexed { index, option ->
                val selected = if (feedback) card.selectedOptionId == option.id else answerChosen && selectedOption == option.id
                val correct = card.word.meanings.any { it.id == option.id }
                val revealed = feedback && (selected || (card.correct == false && correct && correctAnswerRevealed))
                MeaningOption(index, option.english, selected, enabled = !state.busy && !feedback,
                  onClick = {
                    onSubmit(card.id, option.id)
                  },
                  result = when {
                    feedback && selected && !correct -> false
                    feedback && correct && revealed -> true
                    else -> null
                  }, hanzi = if (revealed) option.hanzi else "",
                  pinyin = if (revealed) option.pinyin else "",
                  dimmed = feedback && !revealed)
              }
              if (feedback) AnswerFeedback(card.correct == true,
                card.options.firstOrNull { it.id == card.selectedOptionId }?.english ?: "I don't know")
            }
            CardPhase.WRITING -> {
              Text("Writing", style = MaterialTheme.typography.titleLarge)
              card.writingSessionId?.let { id ->
                Button(onClick = { onWriting(id) }, enabled = !state.busy, shape = MaterialTheme.shapes.medium) { Text("Continue writing") }
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
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(if (correct) "Correct" else "Incorrect · Round resets to 1",
      style = MaterialTheme.typography.bodySmall,
      color = if (correct) FlashcardStyle.colors.gradientSuccess else FlashcardStyle.colors.gradientError)
    if (!correct) {
      Text("Your answer: $selectedMeaning", style = MaterialTheme.typography.bodySmall,
        color = FlashcardStyle.colors.gradientSecondaryInk)
    }
  }
}

@Composable
private fun MeaningOption(index: Int, meaning: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
  result: Boolean? = null, hanzi: String = "", pinyin: String = "", dimmed: Boolean = false) {
  val colors = FlashcardStyle.colors
  val contentColor = when (result) {
    true -> colors.onSuccessContainer
    false -> MaterialTheme.colorScheme.onErrorContainer
    null -> if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
  }
  Surface(modifier = Modifier.fillMaxWidth().alpha(if (dimmed) .45f else 1f).semantics { this.selected = selected },
    shape = MaterialTheme.shapes.medium,
    color = when (result) {
      true -> colors.successContainer
      false -> MaterialTheme.colorScheme.errorContainer
      null -> if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface.copy(alpha = FlashcardStyle.opacity.choicePanel)
    }, contentColor = contentColor,
    border = BorderStroke(1.dp, when (result) {
      true -> colors.success
      false -> MaterialTheme.colorScheme.error
      null -> if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    }),
    onClick = onClick, enabled = enabled) {
    Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(('A' + index).toString(), style = MaterialTheme.typography.labelLarge,
        color = if (result != null || selected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hanzi.isNotBlank()) Text(buildAnnotatedString {
          withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium)) { append(hanzi) }
          if (pinyin.isNotBlank()) withStyle(SpanStyle(fontSize = 13.sp)) { append("  $pinyin") }
        }, style = MaterialTheme.typography.titleMedium)
        Text(meaning, style = if (hanzi.isBlank()) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
      }
      if (result == true) Icon(Icons.Default.Check, contentDescription = "Correct answer", modifier = Modifier.size(20.dp))
    }
  }
}

@Composable
private fun CompletionCount(label: String, completed: Int, planned: Int) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
      color = FlashcardStyle.colors.gradientSecondaryInk)
    Text("$completed / $planned", style = MaterialTheme.typography.titleMedium)
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

@Preview(name = "Explanation", widthDp = 360, heightDp = 640)
@Composable
private fun ExplanationPreview() {
  StudyPagePreview(StageThemePreviewCase(VocabularyStage.PRIMARY, false),
    PreviewCard.copy(phase = CardPhase.EXPLANATION, correct = true,
      selectedOptionId = "thank-you", isCollected = true))
}
