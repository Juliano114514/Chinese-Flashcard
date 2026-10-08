package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared dimensions for reading surfaces and controls. */
object FlashcardLayout {
  val pageInset = 20.dp
  val sectionGap = 20.dp
  val contentGap = 12.dp
  val rowHeight = 52.dp
}

@Composable
fun FlashcardToolbar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier,
  enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
  Row(modifier.fillMaxWidth().heightIn(min = 56.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    IconButton(onClick = onBack, enabled = enabled) {
      Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
    trailing?.invoke()
  }
}

/** Flat dialog surfaces and the same type hierarchy across information, forms and actions. */
@Composable
fun FlashcardDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
  text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
  dismissButton: (@Composable () -> Unit)? = null) {
  AlertDialog(onDismissRequest = onDismissRequest,
    shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
    title = { ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() } },
    text = { ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() } },
    confirmButton = confirmButton, dismissButton = dismissButton)
}
