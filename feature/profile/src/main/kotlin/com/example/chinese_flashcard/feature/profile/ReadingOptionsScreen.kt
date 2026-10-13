package com.example.chinese_flashcard.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.SpeechMode
import com.example.chinese_flashcard.core.domain.SpeechScene
import com.example.chinese_flashcard.core.ui.FlashcardLayout
import com.example.chinese_flashcard.core.ui.FlashcardToolbar
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

@Composable
fun ReadingOptionsScreen(vm: ProfileViewModel, onBack: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  OptionsPage(uiString(UiR.string.ui_reading_options), uiString(UiR.string.ui_reading_options_description), state, onBack, vm::retry) {
    Text(uiString(UiR.string.ui_reading_scene_count, SpeechScene.entries.size),
      style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SpeechScene.entries.forEach { scene ->
      Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(uiString(scene.labelId()), style = MaterialTheme.typography.titleMedium)
          val selected = state.stored.speechOptions.mode(scene, state.stored.learningLanguage)
          Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeechMode.entries.chunked(2).forEach { pair ->
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { mode ->
                  Row(Modifier.weight(1f).heightIn(min = 52.dp).clip(MaterialTheme.shapes.small)
                    .background(if (selected == mode) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceContainerLow).selectable(
                    selected = selected == mode, enabled = !state.loading && !state.saving,
                    role = Role.RadioButton, onClick = {
                      vm.save(ProfileUpdate(speechOptions = state.stored.speechOptions.withMode(scene, mode)))
                    }).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == mode, onClick = null, enabled = !state.saving,
                      modifier = Modifier.size(24.dp))
                    Text(uiString(mode.labelId()), Modifier.weight(1f).padding(start = 8.dp),
                      style = MaterialTheme.typography.bodySmall)
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun ExplanationOptionsScreen(vm: ProfileViewModel, onBack: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  OptionsPage(uiString(UiR.string.ui_explanation_options), uiString(UiR.string.ui_explanation_options_description), state, onBack, vm::retry) {
    val options = state.stored.explanationOptions
    val items = listOf(UiR.string.ui_explanation_original to options.original,
      UiR.string.ui_explanation_pinyin to options.pinyin, UiR.string.ui_explanation_literal to options.literal,
      UiR.string.ui_explanation_translation to options.translation)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
      Column {
        items.forEach { (label, checked) ->
          Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked,
            enabled = !state.loading && !state.saving, role = Role.Checkbox, onValueChange = { value ->
              val changed = when (label) {
                UiR.string.ui_explanation_original -> options.copy(original = value)
                UiR.string.ui_explanation_pinyin -> options.copy(pinyin = value)
                UiR.string.ui_explanation_literal -> options.copy(literal = value)
                else -> options.copy(translation = value)
              }
              vm.save(ProfileUpdate(explanationOptions = changed))
            }).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Checkbox(checked, onCheckedChange = null, enabled = !state.saving)
            Text(uiString(label), style = MaterialTheme.typography.bodyLarge)
          }
        }
      }
    }
  }
}

@Composable
private fun OptionsPage(title: String, description: String, state: ProfileUiState,
  onBack: () -> Unit, onRetry: () -> Unit, content: @Composable () -> Unit) {
  Column(Modifier.fillMaxSize().flashcardBackground().verticalScroll(rememberScrollState())
    .padding(FlashcardLayout.pageInset), verticalArrangement = Arrangement.spacedBy(FlashcardLayout.sectionGap)) {
    FlashcardToolbar(title, onBack, enabled = !state.saving)
    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.loading || state.saving) CircularProgressIndicator()
    state.error?.let {
      Text(uiString(it), color = MaterialTheme.colorScheme.error)
      TextButton(onClick = onRetry, enabled = !state.saving) { Text(uiString(UiR.string.ui_retry)) }
    }
    if (!state.loading) content()
  }
}

private fun SpeechMode.labelId(): Int = when (this) {
  SpeechMode.NONE -> UiR.string.ui_reading_none
  SpeechMode.ONLY_EN -> UiR.string.ui_reading_only_en
  SpeechMode.ONLY_CN -> UiR.string.ui_reading_only_cn
  SpeechMode.BOTH -> UiR.string.ui_reading_both
}

private fun SpeechScene.labelId(): Int = when (this) {
  SpeechScene.HOME_WORD -> UiR.string.ui_reading_home
  SpeechScene.STUDY_PROMPT -> UiR.string.ui_reading_study_prompt
  SpeechScene.STUDY_CONTEXT -> UiR.string.ui_reading_study_context
  SpeechScene.STUDY_EXPLANATION -> UiR.string.ui_reading_study_explanation
  SpeechScene.ANSWER_FEEDBACK -> UiR.string.ui_reading_answer_feedback
  SpeechScene.WORD_DETAIL -> UiR.string.ui_reading_word_detail
  SpeechScene.WORD_PRONUNCIATION -> UiR.string.ui_reading_word
  SpeechScene.MEANING_PRONUNCIATION -> UiR.string.ui_reading_meaning
  SpeechScene.EXAMPLE_PRONUNCIATION -> UiR.string.ui_reading_example
  SpeechScene.WRITING_WORD -> UiR.string.ui_reading_writing
}
