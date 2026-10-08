package com.example.chinese_flashcard.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.DailyWordChoices
import com.example.chinese_flashcard.core.domain.ThemeMode
import com.example.chinese_flashcard.core.domain.WordlistLoadProgress
import com.example.chinese_flashcard.core.ui.FlashcardDialog
import com.example.chinese_flashcard.core.ui.FlashcardToolbar
import com.example.chinese_flashcard.core.ui.FlashcardLayout
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.ui.WordlistLoadingProgress
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

private enum class SettingEditor { DAILY_WORDS, ROUNDS, REVIEW_DAYS, THEME }

@Composable
fun SettingScreen(vm: ProfileViewModel, onBack: () -> Unit, onImportCsv: () -> Unit, onLicenses: () -> Unit,
  defaultWordlistProgress: StateFlow<WordlistLoadProgress?>, csvImportProgress: StateFlow<WordlistLoadProgress?>,
  defaultWordlistLoading: Boolean = false, defaultWordlistError: String? = null,
  onRetryDefaultWordlist: () -> Unit = {}) {
  val state by vm.state.collectAsStateWithLifecycle()
  val csv by vm.importState.collectAsStateWithLifecycle()
  var editor by rememberSaveable { mutableStateOf<SettingEditor?>(null) }
  var appliedSaveRevision by rememberSaveable { mutableLongStateOf(state.savedRevision) }
  val value = state.stored
  val enabled = !state.loading && !state.saving && !csv.open && !defaultWordlistLoading
  fun openEditor(next: SettingEditor) { vm.beginEdit(); editor = next }
  fun closeEditor() { if (!state.saving) { editor = null; vm.beginEdit() } }
  LaunchedEffect(state.savedRevision) {
    if (state.savedRevision != appliedSaveRevision) {
      editor = null
      appliedSaveRevision = state.savedRevision
    }
  }
  Column(Modifier.fillMaxSize().flashcardBackground()
    .verticalScroll(rememberScrollState()).padding(FlashcardLayout.pageInset),
    verticalArrangement = Arrangement.spacedBy(FlashcardLayout.sectionGap)) {
    FlashcardToolbar("Settings", onBack, enabled = !state.saving && !csv.busy)
    if (state.loading) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Appearance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
          SettingsRow("Theme", value.themeMode.label(), enabled) { openEditor(SettingEditor.THEME) }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Learning", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
          Column(Modifier.fillMaxWidth()) {
            SettingsRow("Daily words", value.dailyWords.toString(), enabled) { openEditor(SettingEditor.DAILY_WORDS) }
            SettingsDivider()
            SettingsRow("Correct rounds", value.rounds.toString(), enabled) { openEditor(SettingEditor.ROUNDS) }
            SettingsDivider()
            SettingsRow("Review days", value.reviewDays.joinToString(" / "), enabled) { openEditor(SettingEditor.REVIEW_DAYS) }
          }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Data", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
          Column(Modifier.fillMaxWidth()) {
            SettingsRow("Import CSV", enabled = enabled, onClick = onImportCsv)
            SettingsDivider()
            SettingsRow("Data & licenses", enabled = enabled, onClick = onLicenses)
          }
        }
      }
      if (defaultWordlistLoading || defaultWordlistError != null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Default wordlist", style = MaterialTheme.typography.titleSmall)
          if (defaultWordlistLoading) WordlistLoadingProgress(defaultWordlistProgress)
          else {
            Text(defaultWordlistError.orEmpty(), textAlign = TextAlign.Center,
              color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(shape = MaterialTheme.shapes.small, onClick = onRetryDefaultWordlist, enabled = enabled && editor == null) { Text("Retry") }
          }
        }
      }
      if (state.error != null && editor == null) {
        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        TextButton(shape = MaterialTheme.shapes.small, onClick = vm::retry, enabled = enabled) { Text("Try again") }
      }
    }
  }
  if (csv.open) CsvImportDialog(csv, csvImportProgress, vm::confirmCsvImport, vm::dismissCsvImport)
  when (editor) {
    SettingEditor.DAILY_WORDS -> DailyWordsDialog(value.dailyWords, state.saving, state.error,
      onDismiss = ::closeEditor, onConfirm = { vm.save(ProfileUpdate(dailyWords = it)) })
    SettingEditor.ROUNDS -> RoundsDialog(value.rounds, state.saving, state.error,
      onDismiss = ::closeEditor, onConfirm = { vm.save(ProfileUpdate(rounds = it)) })
    SettingEditor.REVIEW_DAYS -> ReviewDaysDialog(value.reviewDays, state.saving, state.error,
      onDismiss = ::closeEditor, onConfirm = { vm.save(ProfileUpdate(reviewDays = it)) })
    SettingEditor.THEME -> ThemeDialog(value.themeMode, state.saving, state.error,
      onDismiss = ::closeEditor, onConfirm = { vm.save(ProfileUpdate(themeMode = it)) })
    null -> Unit
  }
}

private fun ThemeMode.label(): String = when (this) {
  ThemeMode.LIGHT -> "Light"
  ThemeMode.DARK -> "Dark"
  ThemeMode.SYSTEM -> "Follow system"
}

@Composable
private fun ThemeDialog(initial: ThemeMode, saving: Boolean, error: String?, onDismiss: () -> Unit,
  onConfirm: (ThemeMode) -> Unit) {
  var selected by rememberSaveable { mutableStateOf(initial) }
  SettingDialog("Theme", saving, error, true, onDismiss, { onConfirm(selected) }) {
    Column(Modifier.selectableGroup()) {
      ThemeMode.entries.forEach { mode ->
        Row(Modifier.fillMaxWidth().heightIn(min = FlashcardLayout.rowHeight)
          .selectable(selected = selected == mode, enabled = !saving, role = Role.RadioButton,
            onClick = { selected = mode }).padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          RadioButton(selected = selected == mode, onClick = null, enabled = !saving)
          Text(mode.label(), style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}

@Composable
private fun SettingsDivider() {
  HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = FlashcardStyle.opacity.divider))
}

@Composable
private fun SettingsRow(title: String, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
  val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = FlashcardStyle.opacity.disabledContent)
  Row(Modifier.fillMaxWidth().heightIn(min = FlashcardLayout.rowHeight)
    .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = color)
    value?.let {
      Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color)
    }
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, Modifier.size(20.dp),
      tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color)
  }
}

@Composable
internal fun NameDialog(initial: String, saving: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
  var text by rememberSaveable { mutableStateOf(initial) }
  SettingDialog("Your name", saving, error, text.trim().isNotEmpty(), onDismiss, { onConfirm(text.trim()) }) {
    OutlinedTextField(text, onValueChange = { if (it.length <= 40 && it.none(Char::isISOControl)) text = it },
      Modifier.fillMaxWidth(), singleLine = true, enabled = !saving, shape = MaterialTheme.shapes.medium,
      textStyle = MaterialTheme.typography.bodyLarge,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { if (!saving && text.trim().isNotEmpty()) onConfirm(text.trim()) }),
      label = { Text("Name") })
  }
}

@Composable
private fun DailyWordsDialog(initial: Int, saving: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
  var count by rememberSaveable { mutableIntStateOf(DailyWordChoices.normalize(initial)) }
  SettingDialog("Words per day", saving, error, DailyWordChoices.isAllowed(count), onDismiss, { onConfirm(count) }) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(count.toString(), style = MaterialTheme.typography.headlineLarge)
      Slider(count.toFloat(), onValueChange = { count = DailyWordChoices.normalize(it.roundToInt()) },
        modifier = Modifier.fillMaxWidth(), enabled = !saving,
        valueRange = DailyWordChoices.MIN.toFloat()..DailyWordChoices.MAX.toFloat(), steps = 8,
        colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(DailyWordChoices.MIN.toString(), style = MaterialTheme.typography.bodySmall)
        Text(DailyWordChoices.MAX.toString(), style = MaterialTheme.typography.bodySmall)
      }
      Text("Changes apply to the next daily plan.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun RoundsDialog(initial: Int, saving: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
  var count by rememberSaveable { mutableIntStateOf(initial) }
  SettingDialog("Correct rounds", saving, error, true, onDismiss, { onConfirm(count) }) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(shape = MaterialTheme.shapes.small, onClick = { count-- }, enabled = !saving && count > 2, modifier = Modifier.size(48.dp)) { Text("−") }
        Text(count.toString(), style = MaterialTheme.typography.headlineLarge)
        TextButton(shape = MaterialTheme.shapes.small, onClick = { count++ }, enabled = !saving && count < 8, modifier = Modifier.size(48.dp)) { Text("+") }
      }
      Text("Consecutive correct answers. A wrong answer resets the round.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("Changes apply to new learning cycles.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun ReviewDaysDialog(initial: List<Int>, saving: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: (List<Int>) -> Unit) {
  var selected by rememberSaveable { mutableStateOf(initial) }
  SettingDialog("Review days", saving, error, selected.isNotEmpty(), onDismiss, { onConfirm(selected.sorted()) }) {
    Column {
      Text("Days after passing a word", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
      listOf(1, 3, 5, 7, 14, 30).forEach { day ->
        val checked = day in selected
        Row(Modifier.fillMaxWidth().heightIn(min = FlashcardLayout.rowHeight)
          .toggleable(value = checked, enabled = !saving, role = Role.Checkbox,
            onValueChange = { selected = if (it) (selected + day).distinct().sorted() else selected - day }),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          Box(Modifier.size(22.dp).background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            MaterialTheme.shapes.extraSmall).border(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.onPrimary)
          }
          Text(if (day == 1) "1 day later" else "$day days later", style = MaterialTheme.typography.bodyMedium)
        }
      }
      Text("Changes apply to new learning cycles.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun CsvImportDialog(state: CsvImportUiState, progress: StateFlow<WordlistLoadProgress?>,
  onImport: () -> Unit, onDismiss: () -> Unit) {
  val preview = state.preview
  val report = state.report
  val issues = preview?.issues?.take(100).orEmpty()
  FlashcardDialog(onDismissRequest = { if (state.canDismiss) onDismiss() },
    title = { Text("CSV import") }, text = {
      Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.busy) {
          if (state.stage == CsvImportStage.READING || state.stage == CsvImportStage.IMPORTING)
            WordlistLoadingProgress(progress)
          else Text("Closing preview…", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall)
        }
        preview?.let {
          Text("${it.totalRows} rows · ${it.newWords} new · ${it.duplicateWords} duplicates")
          when {
            it.errorCount > 0 -> {
              Text("${it.errorCount} errors. Fix the CSV and choose it again. No words imported.",
                color = MaterialTheme.colorScheme.error)
              issues.forEach { issue ->
                val location = if (issue.line > 0) "Line ${issue.line}" else "File"
                Text("$location · ${issue.field}: ${issue.message}",
                  style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
              }
              if (it.errorCount > issues.size) {
                Text("Showing the first ${issues.size} errors.", style = MaterialTheme.typography.bodySmall)
              }
            }
            it.newWords == 0 -> Text("All words are already in your wordbook.")
            else -> Text("Existing words and progress will be kept.")
          }
        }
        report?.let { Text("Added ${it.addedWords} words. Skipped ${it.skippedWords} duplicates.") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    }, confirmButton = {
      if (preview?.canImport == true && preview.newWords > 0) {
        TextButton(shape = MaterialTheme.shapes.small, onClick = onImport, enabled = !state.busy) { Text("Import ${preview.newWords} words") }
      } else if (!state.busy) {
        TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("Done") }
      }
    }, dismissButton = {
      if (report == null && state.error == null && (preview == null || preview.newWords > 0 && preview.errorCount == 0)) {
        TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss, enabled = state.canDismiss) { Text("Cancel") }
      }
    })
}

@Composable
private fun SettingDialog(
  title: String,
  saving: Boolean,
  error: String?,
  valid: Boolean,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
  content: @Composable () -> Unit,
) {
  FlashcardDialog(onDismissRequest = { if (!saving) onDismiss() },
    title = { Text(title) }, text = {
      Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    }, confirmButton = {
      TextButton(shape = MaterialTheme.shapes.small, onClick = onConfirm, enabled = !saving && valid) { Text(if (saving) "Saving…" else "Done") }
    }, dismissButton = {
      TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss, enabled = !saving) { Text("Cancel") }
    })
}
