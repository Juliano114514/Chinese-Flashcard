package com.example.chinese_flashcard.feature.profile

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.ui.FlashcardDialog
import com.example.chinese_flashcard.core.ui.FlashcardLayout

/** Home edits both language fields without leaving the current page. */
@Composable
fun LanguagesDialog(vm: ProfileViewModel, initial: StudySettings, enabled: Boolean, onDismiss: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val csv by vm.importState.collectAsStateWithLifecycle()
  var uiLanguage by rememberSaveable { mutableStateOf(initial.uiLanguage) }
  var learningLanguage by rememberSaveable { mutableStateOf(initial.learningLanguage) }
  val openingRevision by rememberSaveable { mutableLongStateOf(state.savedRevision) }
  val canSave = enabled && !state.loading && !state.saving && !csv.open
  LaunchedEffect(state.savedRevision) {
    if (state.savedRevision != openingRevision) onDismiss()
  }
  FlashcardDialog(onDismissRequest = { if (!state.saving) onDismiss() },
    title = { Text(uiString(UiR.string.ui_languages)) }, text = {
      Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LanguageChoices(uiString(UiR.string.ui_system_language), uiLanguage, canSave) { uiLanguage = it }
        LanguageChoices(uiString(UiR.string.ui_learning_language), learningLanguage, canSave) { learningLanguage = it }
        state.error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall) }
      }
    }, confirmButton = {
      TextButton(onClick = { vm.save(ProfileUpdate(uiLanguage = uiLanguage, learningLanguage = learningLanguage)) },
        enabled = canSave, shape = MaterialTheme.shapes.small) {
        Text(uiString(if (state.saving) UiR.string.ui_saving_ff509c else UiR.string.ui_done))
      }
    }, dismissButton = {
      TextButton(onClick = onDismiss, enabled = !state.saving, shape = MaterialTheme.shapes.small) { Text(uiString(UiR.string.ui_cancel)) }
    })
}

@Composable
private fun LanguageChoices(title: String, selected: AppLanguage, enabled: Boolean, onSelect: (AppLanguage) -> Unit) {
  Column(Modifier.selectableGroup()) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AppLanguage.entries.forEach { language ->
      Row(Modifier.fillMaxWidth().heightIn(min = FlashcardLayout.rowHeight)
        .selectable(selected = language == selected, enabled = enabled, role = Role.RadioButton,
          onClick = { onSelect(language) }).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = language == selected, onClick = null, enabled = enabled)
        Text(if (language == AppLanguage.EN) uiString(UiR.string.ui_english) + " · EN" else uiString(UiR.string.ui_chinese) + " · CN")
      }
    }
  }
}
