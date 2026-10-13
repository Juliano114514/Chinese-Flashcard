package com.example.chinese_flashcard.core.ui

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// The same 24 x 24 path is supplied in assets/icons/collection-{filled,unfilled}.svg.
private const val StarPath = "M12 3 L14.78 8.63 L21 9.54 L16.5 13.93 L17.56 20.13 L12 17.2 L6.44 20.13 L7.5 13.93 L3 9.54 L9.22 8.63 Z"
private fun collectionVector(filled: Boolean) = ImageVector.Builder(
  name = if (filled) "CollectionFilled" else "CollectionUnfilled", defaultWidth = 24.dp,
  defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
  addPath(addPathNodes(StarPath), fill = if (filled) SolidColor(Color.Black) else null,
    stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}.build()
private val FilledCollection = collectionVector(true)
private val UnfilledCollection = collectionVector(false)

@Composable
fun CollectionIcon(filled: Boolean, modifier: Modifier = Modifier, contentDescription: String? = null) {
  Icon(if (filled) FilledCollection else UnfilledCollection, contentDescription,
    modifier = modifier.size(24.dp),
    tint = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun WordUserActions(isCollected: Boolean, isSkipped: Boolean, enabled: Boolean,
  onCollection: (Boolean) -> Unit, onSkip: (Boolean) -> Unit, wordId: String? = null,
  wordLabel: String = "", compact: Boolean = false) {
  var confirming by remember(wordId) { mutableStateOf<Boolean?>(null) }
  val actionWidth = if (compact) 36.dp else 48.dp
  // Compact layout keeps the platform's expanded touch targets; only horizontal visual space shrinks.
  CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides actionWidth) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
      IconButton(onClick = { onCollection(!isCollected) }, enabled = enabled,
        modifier = Modifier.size(width = actionWidth, height = 48.dp)) {
        CollectionIcon(isCollected, contentDescription = uiString(if (isCollected) UiR.string.ui_remove_from_my_collection else UiR.string.ui_add_to_my_collection))
      }
      TextButton(shape = MaterialTheme.shapes.small, onClick = { confirming = !isSkipped }, enabled = enabled,
        modifier = Modifier.defaultMinSize(minWidth = actionWidth).heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 2.dp else 4.dp)) {
        Text(uiString(if (isSkipped) UiR.string.ui_unskip else UiR.string.ui_skip), style = MaterialTheme.typography.labelMedium,
          color = if (enabled) FlashcardStyle.colors.gradientSecondaryInk else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
      }
    }
  }
  confirming?.let { target ->
    SkipConfirmationDialog(target, wordLabel, enabled,
      onDismiss = { confirming = null }, onConfirm = { confirming = null; onSkip(target) })
  }
}

@Composable
fun SkipConfirmationDialog(skip: Boolean, wordLabel: String, enabled: Boolean,
  onDismiss: () -> Unit, onConfirm: () -> Unit) {
  FlashcardDialog(onDismissRequest = { if (enabled) onDismiss() },
    title = { Text(uiString(if (skip) UiR.string.ui_skip_word_confirm else UiR.string.ui_unskip_word_confirm, wordLabel.ifBlank { uiString(UiR.string.ui_this_word) })) },
    text = { Text(uiString(if (skip) UiR.string.ui_this_word_will_pause_in_learn_review_collections_and_mistakes_until_you_unskip_it_your_progress_will_be_kept else UiR.string.ui_this_word_will_be_available_again_the_next_time_you_open_its_study_session)) },
    confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onConfirm, enabled = enabled) { Text(uiString(if (skip) UiR.string.ui_skip_fc50a9 else UiR.string.ui_unskip_35cff7)) } },
    dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss, enabled = enabled) { Text(uiString(UiR.string.ui_cancel)) } })
}

@Preview(name = "Word actions · light", showBackground = true)
@Composable
private fun WordUserActionsPreview() = FlashcardTheme {
  Surface(color = MaterialTheme.colorScheme.background) {
    Row {
      WordUserActions(false, false, true, {}, {})
      WordUserActions(true, true, true, {}, {})
    }
  }
}

@Preview(name = "Skip confirmation", widthDp = 320, heightDp = 500, fontScale = 1.3f)
@Composable
private fun SkipConfirmationPreview() = FlashcardTheme {
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(Modifier.fillMaxSize()) { SkipConfirmationDialog(true, "掰开", true, {}, {}) }
  }
}

@Preview(name = "Unskip confirmation", widthDp = 320, heightDp = 500, fontScale = 1.3f)
@Composable
private fun UnskipConfirmationPreview() = FlashcardTheme {
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(Modifier.fillMaxSize()) { SkipConfirmationDialog(false, "掰开", true, {}, {}) }
  }
}
