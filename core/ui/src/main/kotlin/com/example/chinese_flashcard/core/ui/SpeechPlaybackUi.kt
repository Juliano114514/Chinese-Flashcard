package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chinese_flashcard.core.domain.SpeechPlaybackPhase
import com.example.chinese_flashcard.core.domain.SpeechPlaybackState
import java.util.UUID

val LocalSpeechPlaybackState = compositionLocalOf { SpeechPlaybackState() }

/** An instance identity keeps two controls for the same text independent. */
@Composable
fun rememberSpeechSourceId(key: Any? = Unit): String = remember(key) { "speech/${UUID.randomUUID()}" }

@Composable
fun isSpeechPreparing(sourceId: String?): Boolean {
  val state = LocalSpeechPlaybackState.current
  return sourceId != null && state.sourceId == sourceId && state.phase == SpeechPlaybackPhase.PREPARING
}

@Composable
fun SpeechPreparingIndicator(modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
  val description = uiString(R.string.ui_preparing_offline_speech)
  CircularProgressIndicator(modifier.size(20.dp).semantics { contentDescription = description },
    color = color, strokeWidth = 2.dp)
}

@Composable
fun SpeechPlayIcon(sourceId: String?, contentDescription: String,
  modifier: Modifier = Modifier.size(20.dp), tint: Color = LocalContentColor.current) {
  if (isSpeechPreparing(sourceId)) SpeechPreparingIndicator(modifier, tint)
  else Icon(FlashcardIcons.Play, contentDescription = contentDescription, modifier = modifier, tint = tint)
}
