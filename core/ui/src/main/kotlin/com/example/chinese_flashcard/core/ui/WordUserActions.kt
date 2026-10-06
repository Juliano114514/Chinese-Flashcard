package com.example.chinese_flashcard.core.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
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
        CollectionIcon(isCollected, contentDescription = if (isCollected) "Remove from my collection" else "Add to my collection")
      }
      TextButton(onClick = { confirming = !isSkipped }, enabled = enabled,
        modifier = Modifier.defaultMinSize(minWidth = actionWidth).heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = if (compact) 2.dp else 4.dp)) {
        Text(if (isSkipped) "unskip" else "skip", style = MaterialTheme.typography.labelMedium,
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
  AlertDialog(onDismissRequest = { if (enabled) onDismiss() }, shape = MaterialTheme.shapes.large,
    containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
    title = { Text(if (skip) "Skip ${wordLabel.ifBlank { "this word" }}?" else "Unskip ${wordLabel.ifBlank { "this word" }}?") },
    text = { Text(if (skip) "This word will pause in Learn, Review, Collections and Mistakes until you unskip it. Your progress will be kept."
      else "This word will be available again the next time you open its study session.") },
    confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(if (skip) "Skip" else "Unskip") } },
    dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text("Cancel") } })
}

@Preview(name = "Word actions · light", showBackground = true)
@Preview(name = "Word actions · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
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
@Preview(name = "Skip confirmation · dark", widthDp = 320, heightDp = 500, fontScale = 1.3f,
  uiMode = Configuration.UI_MODE_NIGHT_YES)
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
