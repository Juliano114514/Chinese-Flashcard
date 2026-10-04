package com.example.chinese_flashcard.core.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Apricot - light", widthDp = 360, heightDp = 640)
@Preview(name = "Apricot - dark", widthDp = 360, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Apricot - compact", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Composable
private fun ThemePreview() {
  FlashcardTheme {
    val scheme = MaterialTheme.colorScheme
    val colors = FlashcardStyle.colors
    Surface(color = scheme.background) {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Chinese Flashcard", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf(scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer,
            scheme.surfaceContainerHigh, scheme.surfaceContainerHighest).forEach { color ->
            Surface(Modifier.size(40.dp), color = color, shape = MaterialTheme.shapes.small) {}
          }
        }
        OutlinedTextField("Alexander", onValueChange = {}, label = { Text("Your name") },
          modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Button(onClick = {}) { Text("Next") }
          OutlinedButton(onClick = {}) { Text("Cancel") }
        }
        Surface(color = colors.successContainer, contentColor = colors.onSuccessContainer,
          shape = MaterialTheme.shapes.medium) {
          Text("Correct", Modifier.fillMaxWidth().padding(12.dp))
        }
        Surface(color = scheme.errorContainer, contentColor = scheme.onErrorContainer,
          shape = MaterialTheme.shapes.medium) {
          Text("Try again", Modifier.fillMaxWidth().padding(12.dp))
        }
        Box(Modifier.fillMaxWidth().background(studyBackgroundBrush(), MaterialTheme.shapes.medium).padding(16.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = scheme.surface.copy(alpha = FlashcardStyle.opacity.explanationPanel),
              shape = MaterialTheme.shapes.medium) {
              Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("学习", style = MaterialTheme.typography.headlineLarge)
                Text("xué xí · to study", color = scheme.onSurfaceVariant)
              }
            }
            Text("Listen", color = colors.gradientAction)
            Text("Round 1 / 4", color = colors.gradientSecondaryInk, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
  }
}
