package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.WordEntry

/** Presentation only: callers own playback, writing, and any long-press menu. */
@Composable
fun WordHeading(word: WordEntry, enabled: Boolean, onSpeak: () -> Unit, onLongClick: (() -> Unit)? = null) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(word.hanzi, fontSize = 48.sp, lineHeight = 58.sp, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.combinedClickable(enabled = enabled, onClick = onSpeak, onLongClick = onLongClick,
        onLongClickLabel = if (onLongClick != null) "Word actions" else null))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(word.pinyin, fontSize = 18.sp, lineHeight = 26.sp, color = FlashcardStyle.colors.gradientSecondaryInk)
      IconButton(onClick = onSpeak, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Listen to word", modifier = Modifier.size(20.dp))
      }
    }
  }
}

@Composable
fun WordMeanings(word: WordEntry) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    word.meanings.forEach { meaning ->
      Text(listOf(meaning.partOfSpeech, meaning.english).filter(String::isNotBlank).joinToString(" · "),
        style = MaterialTheme.typography.bodyLarge)
    }
  }
}

@Composable
fun WordExplanation(word: WordEntry, onSpeak: (String) -> Unit) {
  if (word.examples.isNotEmpty()) {
    WordPanel {
      word.examples.forEachIndexed { index, example ->
        if (index > 0) PanelDivider()
        ExampleContent(example, onSpeak)
      }
    }
  }
  if (word.parts.isNotEmpty() || word.note.isNotBlank()) {
    WordPanel {
      if (word.parts.isNotEmpty()) {
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
      if (word.note.isNotBlank()) {
        if (word.parts.isNotEmpty()) PanelDivider()
        PanelLabel("Usage note")
        Text(word.note, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
fun WordExamplePanel(example: ExampleSentence, onSpeak: (String) -> Unit) {
  WordPanel { ExampleContent(example, onSpeak) }
}

@Composable
private fun WordPanel(content: @Composable ColumnScope.() -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = FlashcardStyle.opacity.explanationPanel),
    contentColor = MaterialTheme.colorScheme.onSurface),
    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
  }
}

@Composable
private fun ExampleContent(example: ExampleSentence, onSpeak: (String) -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(example.hanzi, fontSize = 18.sp, lineHeight = 27.sp)
      Text(example.pinyin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(example.english, style = MaterialTheme.typography.bodyMedium)
    }
    IconButton(onClick = { onSpeak(example.hanzi) }, modifier = Modifier.size(40.dp)) {
      Icon(Icons.Default.PlayArrow, contentDescription = "Listen to example", modifier = Modifier.size(20.dp))
    }
  }
}

@Composable
private fun PanelDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = FlashcardStyle.opacity.divider))

@Composable
private fun PanelLabel(value: String) = Text(value, style = MaterialTheme.typography.titleSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
