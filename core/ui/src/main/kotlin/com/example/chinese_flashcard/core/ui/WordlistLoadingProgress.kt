package com.example.chinese_flashcard.core.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.WordlistLoadProgress
import com.example.chinese_flashcard.core.domain.WordlistLoadStage
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.floor

/** Collect progress here so word-by-word updates do not recompose the parent screen. */
@Composable
fun WordlistLoadingProgress(progress: StateFlow<WordlistLoadProgress?>, modifier: Modifier = Modifier) {
  val current by progress.collectAsStateWithLifecycle()
  WordlistLoadingProgress(current, modifier)
}

@Composable
private fun WordlistLoadingProgress(progress: WordlistLoadProgress?, modifier: Modifier = Modifier) {
  val fraction = if (progress?.stage == WordlistLoadStage.COMPLETE) 1f else
    progress?.fraction?.takeIf { it.isFinite() }?.coerceIn(0f, .99f) ?: 0f
  val percent = floor(fraction * 100).toInt()
  val entry = progress?.currentEntry?.takeIf { it.isNotBlank() }
  val stageLabel = when (progress?.stage) {
    WordlistLoadStage.CHECKING -> "Checking"
    WordlistLoadStage.STROKES -> "Stroke data"
    WordlistLoadStage.SAVING -> "Saving"
    WordlistLoadStage.COMMITTING -> "Finishing"
    WordlistLoadStage.COMPLETE -> "Ready"
    WordlistLoadStage.PREPARING, null -> "Preparing"
  }
  val message = when {
    progress?.stage == WordlistLoadStage.COMPLETE -> "Wordlist ready"
    progress?.stage == WordlistLoadStage.COMMITTING -> "Finishing…"
    entry != null -> "$stageLabel · Now loading $entry…"
    else -> when (progress?.stage) {
      WordlistLoadStage.CHECKING -> "Checking words…"
      WordlistLoadStage.STROKES -> "Loading stroke data…"
      WordlistLoadStage.SAVING -> "Saving words…"
      WordlistLoadStage.COMMITTING -> "Finishing…"
      WordlistLoadStage.COMPLETE -> "Wordlist ready"
      WordlistLoadStage.PREPARING, null -> "Preparing wordlist…"
    }
  }
  val density = LocalDensity.current
  val percentWidth = with(density) { 48.sp.toDp() }
  Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(.75f), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f).height(4.dp),
        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
      Text("$percent%", modifier = Modifier.width(percentWidth),
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text(message, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Preview(name = "Wordlist loading - light", widthDp = 320)
@Preview(name = "Wordlist loading - dark", widthDp = 320, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Wordlist loading - large text", widthDp = 320, fontScale = 1.5f)
@Preview(name = "Wordlist loading - dark large text", widthDp = 320, fontScale = 1.5f,
  uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WordlistLoadingProgressPreview() {
  FlashcardTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
      WordlistLoadingProgress(WordlistLoadProgress(stage = WordlistLoadStage.SAVING,
        processedWords = 6648, totalWords = 6648, currentEntry = "学习", fraction = 0.92f),
        Modifier.padding(24.dp))
    }
  }
}
