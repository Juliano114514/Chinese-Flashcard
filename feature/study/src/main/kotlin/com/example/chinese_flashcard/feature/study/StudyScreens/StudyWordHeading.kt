package com.example.chinese_flashcard.feature.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.ui.FlashcardDialog
import com.example.chinese_flashcard.core.ui.WordHeading
import com.example.chinese_flashcard.core.ui.WordUserActions

@Composable
private fun RoundProgress(card: StudyCard) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics {
    contentDescription = "Round ${card.round} of ${card.targetRounds}"
  }) {
    repeat(card.targetRounds) { index ->
      Spacer(Modifier.size(width = 10.dp, height = 3.dp).background(
        if (index < card.round) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
    }
  }
}

@Composable
internal fun StudyWordHeading(word: WordEntry, enabled: Boolean, onSpeak: () -> Unit, onWrite: (() -> Unit)?,
  card: StudyCard? = null, onCollection: (Boolean) -> Unit = {}, onSkip: (Boolean) -> Unit = {}) {
  var showActions by remember(word.id) { mutableStateOf(false) }
  val showRound = card != null && !card.reviewRecall &&
    card.phase in listOf(CardPhase.INTRO, CardPhase.QUESTION, CardPhase.FEEDBACK)
  val fontScale = LocalDensity.current.fontScale
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val actionsWidth = 36.dp + maxOf(36.dp, (40 * fontScale).dp) + (if (showRound) 14.dp else 0.dp)
    val actionsBelow = (word.hanzi.length * 40 * fontScale).dp + actionsWidth + 2.dp > maxWidth
    WordHeading(word, enabled, onSpeak, onLongClick = { showActions = true }, trailingOnNewLine = actionsBelow,
      trailingSpacing = 2.dp, trailing = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showRound) RoundProgress(checkNotNull(card))
        WordUserActions(isCollected = card?.isCollected ?: false, isSkipped = card?.isSkipped ?: false,
          enabled = enabled, onCollection = onCollection, onSkip = onSkip, wordId = word.id, wordLabel = word.hanzi,
          compact = true)
      }
    })
  }
  if (showActions) FlashcardDialog(onDismissRequest = { showActions = false },
    title = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(word.hanzi, style = MaterialTheme.typography.headlineSmall)
        Text(word.pinyin, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }, text = {
      Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button) {
          showActions = false; onSpeak()
        }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
          Text("Listen", style = MaterialTheme.typography.bodyLarge)
        }
        onWrite?.let { write ->
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button) {
            showActions = false; write()
          }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("Write", style = MaterialTheme.typography.bodyLarge)
          }
        }
      }
    }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { showActions = false }) { Text("Close") } })
}
