package com.example.chinese_flashcard.feature.study

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.ui.FlashcardDialog
import com.example.chinese_flashcard.core.ui.FlashcardIcons
import com.example.chinese_flashcard.core.ui.glassPanel
import com.example.chinese_flashcard.core.ui.glassPanelColor
import com.example.chinese_flashcard.core.ui.WordHeading
import com.example.chinese_flashcard.core.ui.isSpeechPreparing
import com.example.chinese_flashcard.core.ui.SpeechPlayIcon

@Composable
internal fun RoundProgress(card: StudyCard) {
  val description = uiString(UiR.string.ui_round_of, card.round, card.targetRounds)
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics {
    contentDescription = description
  }) {
    repeat(card.targetRounds) { index ->
      Spacer(Modifier.size(width = 4.dp, height = 16.dp).background(
        if (index < card.round) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
    }
  }
}

@Composable
internal fun StudyWordHeading(word: WordEntry, enabled: Boolean, onSpeak: () -> Unit, onWrite: (() -> Unit)?,
  sourceId: String? = null) {
  val preparing = isSpeechPreparing(sourceId)
  var showActions by remember(word.id) { mutableStateOf(false) }
  Surface(shape = MaterialTheme.shapes.large, color = glassPanelColor(),
    contentColor = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.fillMaxWidth().glassPanel(shape = MaterialTheme.shapes.large)) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
    WordHeading(word, enabled, onSpeak, onLongClick = { showActions = true },
      trailingSpacing = 8.dp, playBesideWord = true, sourceId = sourceId)
  }
  }
  if (showActions) FlashcardDialog(onDismissRequest = { showActions = false },
    title = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(word.hanzi, style = MaterialTheme.typography.headlineSmall)
        Text(word.pinyin, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }, text = {
      Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled && !preparing, role = Role.Button) {
          showActions = false; onSpeak()
        }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          SpeechPlayIcon(sourceId, contentDescription = uiString(UiR.string.ui_listen_to_word), modifier = Modifier.size(20.dp))
          Text(uiString(UiR.string.ui_listen_66b5db), style = MaterialTheme.typography.bodyLarge)
        }
        onWrite?.let { write ->
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button) {
            showActions = false; write()
          }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(uiString(UiR.string.ui_write), style = MaterialTheme.typography.bodyLarge)
          }
        }
      }
    }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { showActions = false }) { Text(uiString(UiR.string.ui_close)) } })
}
