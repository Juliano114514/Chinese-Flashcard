package com.example.chinese_flashcard.core.ui

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.splitEnglishMeanings

/** Presentation only: callers own playback, writing, and any long-press menu. */
@Composable
fun WordHeading(word: WordEntry, enabled: Boolean, onSpeak: () -> Unit, onLongClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null, trailingOnNewLine: Boolean = false, trailingSpacing: Dp = 4.dp) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(trailingSpacing)) {
      Text(word.hanzi, fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.weight(1f, fill = false).combinedClickable(enabled = enabled, onClick = onSpeak,
          onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) uiString(UiR.string.ui_word_actions) else null))
      if (!trailingOnNewLine) trailing?.invoke()
    }
    if (trailingOnNewLine) trailing?.invoke()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(word.pinyin, modifier = Modifier.weight(1f), fontSize = 18.sp, lineHeight = 26.sp,
        color = FlashcardStyle.colors.gradientSecondaryInk)
      IconButton(onClick = onSpeak, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.PlayArrow, contentDescription = uiString(UiR.string.ui_listen_to_word), modifier = Modifier.size(20.dp))
      }
    }
  }
}

@Composable
fun WordMeanings(word: WordEntry, onSpeak: (SpeechRequest) -> Unit = {},
  ipaResolver: (String) -> String? = { null }, enabled: Boolean = true) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    word.meanings.forEach { meaning ->
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (meaning.partOfSpeech.isNotBlank()) Text(meaning.partOfSpeech,
          style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        splitEnglishMeanings(meaning.english).forEach { english ->
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(english, style = MaterialTheme.typography.bodyLarge)
              ipaResolver(english)?.takeIf { it.isNotBlank() }?.let { ipa ->
                Text(ipa, style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
            IconButton(onClick = { onSpeak(SpeechRequest(english, AppLanguage.EN)) }, enabled = enabled,
              modifier = Modifier.size(48.dp)) {
              Icon(Icons.Default.PlayArrow, contentDescription = uiString(UiR.string.ui_listen_to_english_meaning),
                modifier = Modifier.size(20.dp))
            }
          }
        }
      }
    }
  }
}

@Composable
fun WordExplanation(word: WordEntry, onSpeak: (SpeechRequest) -> Unit, compact: Boolean = false) {
  if (word.literalExplanations.isNotEmpty() || word.figurativeExplanations.isNotEmpty()) {
    WordPanel(compact) {
      if (word.literalExplanations.isNotEmpty()) {
        PanelLabel(if (word.meanings.any { it.partOfSpeech == "idiom" }) uiString(UiR.string.ui_literal_meaning) else uiString(UiR.string.ui_meanings))
        ExplanationMeanings(word.literalExplanations)
      }
      if (word.figurativeExplanations.isNotEmpty()) {
        if (word.literalExplanations.isNotEmpty()) PanelDivider()
        PanelLabel(if (word.meanings.any { it.partOfSpeech == "idiom" }) uiString(UiR.string.ui_figurative_meaning) else uiString(UiR.string.ui_extended_use))
        ExplanationMeanings(word.figurativeExplanations)
      }
    }
  }
  if (word.examples.isNotEmpty()) {
    WordPanel(compact) {
      word.examples.forEachIndexed { index, example ->
        if (index > 0) PanelDivider()
        ExampleContent(example, onSpeak)
      }
    }
  }
  if (word.parts.isNotEmpty()) {
    WordPanel(compact) {
      PanelLabel(uiString(UiR.string.ui_word_breakdown))
      word.parts.forEach { part ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(part.hanzi, style = MaterialTheme.typography.titleLarge)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(part.pinyin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(part.gloss, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
      word.meanings.firstOrNull()?.let { Text(uiString(UiR.string.ui_together_meaning, it.english), style = MaterialTheme.typography.bodyMedium) }
    }
  }
}

@Composable
private fun ExplanationMeanings(meanings: List<String>) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    meanings.forEachIndexed { index, meaning ->
      val number = if (index < 20) ('①'.code + index).toChar().toString() else "(${index + 1})"
      Text(if (meanings.size > 1) "$number $meaning" else meaning,
        style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
fun WordExamplePanel(example: ExampleSentence, onSpeak: (SpeechRequest) -> Unit, enabled: Boolean = true,
  compact: Boolean = false) {
  WordPanel(compact) { ExampleContent(example, onSpeak, enabled) }
}

@Composable
private fun WordPanel(compact: Boolean, content: @Composable ColumnScope.() -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = FlashcardStyle.opacity.explanationPanel),
    contentColor = MaterialTheme.colorScheme.onSurface),
    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(if (compact) 12.dp else 16.dp),
      verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp), content = content)
  }
}

@Composable
private fun ExampleContent(example: ExampleSentence, onSpeak: (SpeechRequest) -> Unit, enabled: Boolean = true) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      PanelLabel(uiString(UiR.string.ui_example))
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(exampleLine(example.chunks.map { it.hanzi }, example.hanzi), modifier = Modifier.weight(1f),
            fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
          IconButton(onClick = { onSpeak(SpeechRequest(example.hanzi, AppLanguage.CN)) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = uiString(UiR.string.ui_listen_to_chinese_example), modifier = Modifier.size(20.dp))
          }
        }
        Text(exampleLine(example.chunks.map { it.pinyin }, example.pinyin),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (example.chunks.isNotEmpty()) Text(exampleLine(example.chunks.map { it.gloss }, ""),
          style = MaterialTheme.typography.bodyMedium.copy(localeList = LocaleList("en"), hyphens = Hyphens.None),
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    PanelDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(example.english, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(
        localeList = LocaleList("en"), hyphens = Hyphens.None))
      IconButton(onClick = { onSpeak(SpeechRequest(example.english, AppLanguage.EN)) }, enabled = enabled,
        modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.PlayArrow, contentDescription = uiString(UiR.string.ui_listen_to_english_example),
          modifier = Modifier.size(20.dp))
      }
    }
  }
}

@Composable
private fun exampleLine(chunks: List<String>, fallback: String): androidx.compose.ui.text.AnnotatedString {
  val separatorColor = MaterialTheme.colorScheme.outline
  return buildAnnotatedString {
    if (chunks.isEmpty()) append(fallback)
    else chunks.forEachIndexed { index, chunk ->
      if (index > 0) withStyle(SpanStyle(color = separatorColor)) { append(" / ") }
      append(chunk)
    }
  }
}

@Composable
private fun PanelDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = FlashcardStyle.opacity.divider))

@Composable
private fun PanelLabel(value: String) = Text(value, style = MaterialTheme.typography.titleSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
