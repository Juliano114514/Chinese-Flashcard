package com.example.chinese_flashcard.feature.study

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chinese_flashcard.core.domain.AnswerOption
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.splitEnglishMeanings
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.WordUserActions

/** The target word is the question; answer choices use the learner's known language. */
@Composable
internal fun QuestionHeading(card: StudyCard, enabled: Boolean, onSpeak: (SpeechRequest) -> Unit,
  ipaResolver: (String) -> String?, onCollection: (Boolean) -> Unit, onSkip: (Boolean) -> Unit) {
  val prompt = card.promptSpeech()
  if (card.learningLanguage == AppLanguage.CN) {
    StudyWordHeading(card.word, enabled, onSpeak = { onSpeak(prompt) }, onWrite = null,
      card = card, onCollection = onCollection, onSkip = onSkip)
    return
  }
  val prompts = splitEnglishMeanings(prompt.text)
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    prompts.forEachIndexed { index, text ->
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        if (index == 0) {
          if (!card.reviewRecall && card.phase in listOf(CardPhase.INTRO, CardPhase.QUESTION, CardPhase.FEEDBACK)) RoundProgress(card)
          WordUserActions(card.isCollected, card.isSkipped, enabled, onCollection, onSkip,
            wordId = card.word.id, wordLabel = prompt.text, compact = true)
        }
      }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val pronunciation = ipaResolver(text).orEmpty()
        Text(pronunciation, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
          color = FlashcardStyle.colors.gradientSecondaryInk)
        IconButton(onClick = { onSpeak(SpeechRequest(text, prompt.language)) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Default.PlayArrow, contentDescription = uiString(UiR.string.ui_listen_to_question), modifier = Modifier.size(20.dp))
        }
      }
    }
  }
}

@Composable
internal fun ChineseTranslation(word: WordEntry, enabled: Boolean, onSpeak: (SpeechRequest) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(word.hanzi, style = MaterialTheme.typography.bodyLarge)
      Text(word.pinyin, style = MaterialTheme.typography.bodySmall,
        color = FlashcardStyle.colors.gradientSecondaryInk)
    }
    IconButton(onClick = { onSpeak(SpeechRequest(word.hanzi, AppLanguage.CN)) }, enabled = enabled,
      modifier = Modifier.size(48.dp)) {
      Icon(Icons.Default.PlayArrow, contentDescription = uiString(UiR.string.ui_listen_to_word), modifier = Modifier.size(20.dp))
    }
  }
}

@Composable
internal fun QuestionExample(example: ExampleSentence, language: AppLanguage,
  onSpeak: (SpeechRequest) -> Unit, enabled: Boolean) {
  val request = SpeechRequest(if (language == AppLanguage.CN) example.hanzi else example.english, language)
  Surface(shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface.copy(alpha = FlashcardStyle.opacity.explanationPanel)) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(uiString(UiR.string.ui_example), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(request.text, modifier = Modifier.weight(1f), style = if (language == AppLanguage.CN)
          MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
          else MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onSpeak(request) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Default.PlayArrow, contentDescription = uiString(UiR.string.ui_listen_to_example), modifier = Modifier.size(20.dp))
        }
      }
      if (language == AppLanguage.CN) Text(example.pinyin, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
internal fun LanguageOption(index: Int, option: AnswerOption, learningLanguage: AppLanguage, selected: Boolean,
  enabled: Boolean, onClick: () -> Unit, result: Boolean? = null, revealBilingual: Boolean = false,
  dimmed: Boolean = false) {
  val colors = FlashcardStyle.colors
  val contentColor = when (result) {
    true -> colors.onSuccessContainer
    false -> MaterialTheme.colorScheme.onErrorContainer
    null -> if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
  }
  Surface(modifier = Modifier.fillMaxWidth().alpha(if (dimmed) .45f else 1f).semantics { this.selected = selected },
    shape = MaterialTheme.shapes.medium, color = when (result) {
      true -> colors.successContainer
      false -> MaterialTheme.colorScheme.errorContainer
      null -> if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface.copy(alpha = FlashcardStyle.opacity.choicePanel)
    }, contentColor = contentColor, border = BorderStroke(1.dp, when (result) {
      true -> colors.success
      false -> MaterialTheme.colorScheme.error
      null -> if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    }), onClick = onClick, enabled = enabled) {
    Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(('A' + index).toString(), style = MaterialTheme.typography.labelLarge,
        color = if (result != null || selected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val showChinese = learningLanguage == AppLanguage.EN || revealBilingual
        if (showChinese) Text(buildAnnotatedString {
          withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium)) { append(option.hanzi) }
          if (option.pinyin.isNotBlank()) withStyle(SpanStyle(fontSize = 13.sp)) { append("  ${option.pinyin}") }
        }, style = MaterialTheme.typography.titleMedium)
        if (learningLanguage == AppLanguage.CN || revealBilingual) {
          Text(option.english, style = if (showChinese) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
        }
      }
      if (result == true) Icon(Icons.Default.Check, contentDescription = uiString(UiR.string.ui_correct_answer), modifier = Modifier.size(20.dp))
    }
  }
}
