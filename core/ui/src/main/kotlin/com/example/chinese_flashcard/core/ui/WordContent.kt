package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.sp
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.WordEntry

/** Presentation only: callers own playback, writing, and any long-press menu. */
@Composable
fun WordHeading(word: WordEntry, enabled: Boolean, onSpeak: () -> Unit, onLongClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(word.hanzi, fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.weight(1f, fill = false).combinedClickable(enabled = enabled, onClick = onSpeak,
          onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) "Word actions" else null))
      trailing?.invoke()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(word.pinyin, fontSize = 18.sp, lineHeight = 26.sp, color = FlashcardStyle.colors.gradientSecondaryInk)
      IconButton(onClick = onSpeak, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Listen to word", modifier = Modifier.size(20.dp))
      }
    }
  }
}

@Composable
fun WordMeanings(word: WordEntry) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    word.meanings.forEach { meaning ->
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (meaning.partOfSpeech.isNotBlank()) Text(meaning.partOfSpeech,
          style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(meaning.english, style = MaterialTheme.typography.bodyLarge)
      }
    }
  }
}

@Composable
fun WordExplanation(word: WordEntry, onSpeak: (String) -> Unit) {
  if (word.literalExplanations.isNotEmpty() || word.figurativeExplanations.isNotEmpty()) {
    WordPanel {
      if (word.literalExplanations.isNotEmpty()) {
        PanelLabel(if (word.meanings.any { it.partOfSpeech == "idiom" }) "Literal meaning" else "Meanings")
        ExplanationMeanings(word.literalExplanations)
      }
      if (word.figurativeExplanations.isNotEmpty()) {
        if (word.literalExplanations.isNotEmpty()) PanelDivider()
        PanelLabel(if (word.meanings.any { it.partOfSpeech == "idiom" }) "Figurative meaning" else "Extended use")
        ExplanationMeanings(word.figurativeExplanations)
      }
    }
  }
  if (word.examples.isNotEmpty()) {
    WordPanel {
      word.examples.forEachIndexed { index, example ->
        if (index > 0) PanelDivider()
        ExampleContent(example, onSpeak)
      }
    }
  }
  if (word.parts.isNotEmpty()) {
    WordPanel {
      PanelLabel("Word breakdown")
      word.parts.forEach { part ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(part.hanzi, style = MaterialTheme.typography.titleLarge)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(part.pinyin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(part.gloss, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
      word.meanings.firstOrNull()?.let { Text("Together: ${it.english}", style = MaterialTheme.typography.bodyMedium) }
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
fun WordExamplePanel(example: ExampleSentence, onSpeak: (String) -> Unit, enabled: Boolean = true) {
  WordPanel { ExampleContent(example, onSpeak, enabled) }
}

@Composable
private fun WordPanel(content: @Composable ColumnScope.() -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = FlashcardStyle.opacity.explanationPanel),
    contentColor = MaterialTheme.colorScheme.onSurface),
    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
  }
}

@Composable
private fun ExampleContent(example: ExampleSentence, onSpeak: (String) -> Unit, enabled: Boolean = true) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      PanelLabel("Example")
      Spacer(Modifier.weight(1f))
      IconButton(onClick = { onSpeak(example.hanzi) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Listen to example", modifier = Modifier.size(20.dp))
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(exampleLine(example.chunks.map { it.hanzi }, example.hanzi),
        fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
      Text(exampleLine(example.chunks.map { it.pinyin }, example.pinyin),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (example.chunks.isNotEmpty()) Text(exampleLine(example.chunks.map { it.gloss }, ""),
        style = MaterialTheme.typography.bodyMedium.copy(localeList = LocaleList("en"), hyphens = Hyphens.None),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    PanelDivider()
    Text(example.english, style = MaterialTheme.typography.bodyLarge.copy(
      localeList = LocaleList("en"), hyphens = Hyphens.None))
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
