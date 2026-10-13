package com.example.chinese_flashcard.feature.study

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
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
import com.example.chinese_flashcard.core.domain.SpeechScene
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.splitEnglishMeanings
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardIcons
import com.example.chinese_flashcard.core.ui.glassPanel
import com.example.chinese_flashcard.core.ui.glassPanelColor
import com.example.chinese_flashcard.core.ui.LocalExplanationOptions

/** The target word is the question; answer choices use the learner's known language. */
@Composable
internal fun QuestionHeading(card: StudyCard, enabled: Boolean, onSpeak: (SpeechRequest) -> Unit,
  ipaResolver: (String) -> String?) {
  val prompt = card.promptSpeech()
  if (card.learningLanguage == AppLanguage.CN) {
    StudyWordHeading(card.word, enabled, onSpeak = { onSpeak(prompt) }, onWrite = null)
    return
  }
  val prompts = splitEnglishMeanings(prompt.text)
  Surface(shape = MaterialTheme.shapes.large, color = glassPanelColor(),
    contentColor = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.fillMaxWidth().glassPanel(shape = MaterialTheme.shapes.large)) {
  Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    prompts.forEach { text ->
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, modifier = Modifier.weight(1f).clickable(enabled = enabled, role = Role.Button) {
          onSpeak(prompt.copy(text = text))
        }, style = MaterialTheme.typography.headlineMedium.copy(lineHeight = 38.sp), fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { onSpeak(prompt.copy(text = text)) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
          Icon(FlashcardIcons.Play, contentDescription = uiString(UiR.string.ui_listen_to_question), modifier = Modifier.size(20.dp))
        }
      }
      ipaResolver(text)?.takeIf { it.isNotBlank() }?.let { pronunciation ->
        Text(pronunciation, style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
  }
}

@Composable
internal fun ChineseTranslation(word: WordEntry, enabled: Boolean, onSpeak: (SpeechRequest) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(word.hanzi, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
      Text(word.pinyin, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    IconButton(onClick = { onSpeak(SpeechRequest(word.hanzi, AppLanguage.CN,
      scene = SpeechScene.WORD_PRONUNCIATION, counterpart = word.meanings.firstOrNull()?.english)) }, enabled = enabled,
      modifier = Modifier.size(48.dp)) {
      Icon(FlashcardIcons.Play, contentDescription = uiString(UiR.string.ui_listen_to_word), modifier = Modifier.size(20.dp))
    }
  }
}

@Composable
internal fun QuestionExample(example: ExampleSentence, language: AppLanguage,
  onSpeak: (SpeechRequest) -> Unit, enabled: Boolean) {
  val request = SpeechRequest(if (language == AppLanguage.CN) example.hanzi else example.english,
    language, scene = SpeechScene.STUDY_CONTEXT)
  val options = LocalExplanationOptions.current
  var expanded by remember(example, options, language) { mutableStateOf(false) }
  val expandable = !options.allSelected
  val toggleLabel = uiString(if (expanded) UiR.string.ui_hide_full_example else UiR.string.ui_show_full_example)
  val showSentence = expanded || if (language == AppLanguage.CN) options.original else options.translation
  val showPinyin = language == AppLanguage.CN && (expanded || options.pinyin)
  val panelModifier = if (expandable) Modifier.clickable(enabled = enabled, role = Role.Button,
    onClickLabel = toggleLabel) { expanded = !expanded }.semantics { stateDescription = toggleLabel } else Modifier
  // Expanding a question restores only its target-language lines, never the counterpart answer.
  Surface(shape = MaterialTheme.shapes.large,
    modifier = panelModifier.glassPanel(shape = MaterialTheme.shapes.large),
    color = glassPanelColor()) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(uiString(UiR.string.ui_example), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary)
      if (showSentence || showPinyin) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (showSentence) Text(request.text, style = if (language == AppLanguage.CN)
            MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
            else MaterialTheme.typography.bodyMedium.copy(lineHeight = 26.sp))
          if (showPinyin) Text(example.pinyin, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onSpeak(request) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
          Icon(FlashcardIcons.Play, contentDescription = uiString(UiR.string.ui_listen_to_example), modifier = Modifier.size(20.dp))
        }
      }
      }
    }
  }
}

@Composable
internal fun LanguageOption(index: Int, option: AnswerOption, learningLanguage: AppLanguage, selected: Boolean,
  enabled: Boolean, onClick: () -> Unit, result: Boolean? = null, revealBilingual: Boolean = false,
  dimmed: Boolean = false, modifier: Modifier = Modifier) {
  val colors = FlashcardStyle.colors
  val contentColor = when (result) {
    true -> colors.onSuccessContainer
    false -> MaterialTheme.colorScheme.onErrorContainer
    null -> if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
  }
  Surface(modifier = modifier.fillMaxWidth().alpha(if (dimmed) .60f else 1f)
    .glassPanel(shape = MaterialTheme.shapes.large).semantics { this.selected = selected },
    shape = MaterialTheme.shapes.large, color = when (result) {
      true -> colors.successContainer
      false -> MaterialTheme.colorScheme.errorContainer
      null -> if (selected) MaterialTheme.colorScheme.primaryContainer
        else glassPanelColor()
    }, contentColor = contentColor, border = BorderStroke(1.dp, when (result) {
      true -> colors.success
      false -> MaterialTheme.colorScheme.error
      null -> if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    }), onClick = onClick, enabled = enabled) {
    Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(('A' + index).toString(), style = MaterialTheme.typography.labelLarge,
        color = if (result != null || selected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant)
      OptionLabel(option, learningLanguage, revealBilingual, Modifier.weight(1f))
      OptionResultIcon(result)
    }
  }
}

@Composable
private fun OptionLabel(option: AnswerOption, learningLanguage: AppLanguage, revealBilingual: Boolean,
  modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    val showChinese = learningLanguage == AppLanguage.EN || revealBilingual
    if (showChinese) Text(buildAnnotatedString {
      withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium)) { append(option.hanzi) }
      if (option.pinyin.isNotBlank()) withStyle(SpanStyle(fontSize = 13.sp)) { append("  ${option.pinyin}") }
    }, style = MaterialTheme.typography.titleMedium)
    if (learningLanguage == AppLanguage.CN || revealBilingual) {
      Text(option.english, style = if (showChinese) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
    }
  }
}

@Composable
private fun OptionResultIcon(result: Boolean?) {
  if (result != null) Icon(if (result) FlashcardIcons.Check else FlashcardIcons.Close,
    contentDescription = uiString(if (result) UiR.string.ui_correct_answer else UiR.string.ui_incorrect_round_resets_to_1),
    modifier = Modifier.size(20.dp))
}
