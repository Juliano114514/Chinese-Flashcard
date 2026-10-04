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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.ui.AvatarPickerDialog
import com.example.chinese_flashcard.core.ui.EditableAvatar
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import kotlin.math.roundToInt

private enum class ProfileEditor { NAME, AVATAR, DAILY_WORDS, ROUNDS, REVIEW_DAYS }

@Composable
fun ProfileScreen(vm: ProfileViewModel, onImportCsv: () -> Unit, onLicenses: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val csv by vm.importState.collectAsStateWithLifecycle()
  var editor by rememberSaveable { mutableStateOf<ProfileEditor?>(null) }
  var appliedSaveRevision by rememberSaveable { mutableLongStateOf(state.savedRevision) }
  val value = state.stored
  val enabled = !state.loading && !state.saving && !csv.open
  fun openEditor(next: ProfileEditor) { vm.beginEdit(); editor = next }
  fun closeEditor() { if (!state.saving) { editor = null; vm.beginEdit() } }
  LaunchedEffect(state.savedRevision) {
    if (state.savedRevision != appliedSaveRevision) {
      editor = null
      appliedSaveRevision = state.savedRevision
    }
  }
  Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp)) {
    Text("Profile", style = MaterialTheme.typography.headlineLarge)
    if (state.loading) {
      CircularProgressIndicator(Modifier.size(28.dp))
    } else {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        EditableAvatar(value.avatarId, size = 44.dp, enabled = enabled,
          onClick = { openEditor(ProfileEditor.AVATAR) })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(value.displayName.ifBlank { "Your name" }, style = MaterialTheme.typography.titleLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text("Chinese Flashcard", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      state.today?.let { today ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric(today.totalWords, "Total", Modifier.weight(1f))
            Metric(today.learnedWords, "Learned", Modifier.weight(1f))
            Metric(today.remainingWords, "Not started", Modifier.weight(1f))
          }
          Text("Today · ${today.completed} / ${today.planned}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
          SettingsRow("Name", value.displayName.ifBlank { "Add" }, enabled) { openEditor(ProfileEditor.NAME) }
          SettingsDivider()
          SettingsRow("Daily words", value.dailyWords.toString(), enabled) { openEditor(ProfileEditor.DAILY_WORDS) }
          SettingsDivider()
          SettingsRow("Correct rounds", value.rounds.toString(), enabled) { openEditor(ProfileEditor.ROUNDS) }
          SettingsDivider()
          SettingsRow("Review days", value.reviewDays.joinToString(" / "), enabled) { openEditor(ProfileEditor.REVIEW_DAYS) }
        }
      }
      Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
          SettingsRow("Import CSV", enabled = enabled, onClick = onImportCsv)
          SettingsDivider()
          SettingsRow("Data & licenses", enabled = enabled, onClick = onLicenses)
        }
      }
      if (state.error != null && editor == null) {
        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = vm::retry, enabled = enabled) { Text("Try again") }
      }
    }
  }
  if (csv.open) CsvImportDialog(csv, vm::confirmCsvImport, vm::dismissCsvImport)
  when (editor) {
    ProfileEditor.NAME -> NameDialog(value.displayName, state.saving, state.error,
      onDismiss = ::closeEditor, onConfirm = { vm.save(ProfileUpdate(displayName = it)) })
    ProfileEditor.AVATAR -> AvatarPickerDialog(value.avatarId, onDismiss = ::closeEditor,
      onConfirm = { vm.save(ProfileUpdate(avatarId = it)) }, saving = state.saving, error = state.error)
    ProfileEditor.DAILY_WORDS -> DailyWordsDialog(value.dailyWords, state.saving, state.error,
      onDismiss = ::closeEditor, onConfirm = { vm.save(ProfileUpdate(dailyWords = it)) })
    ProfileEditor.ROUNDS -> RoundsDialog(value.rounds, state.saving, state.error,
      onDismiss = ::closeEditor, onConfirm = { vm.save(ProfileUpdate(rounds = it)) })
    ProfileEditor.REVIEW_DAYS -> ReviewDaysDialog(value.reviewDays, state.saving, state.error,
      onDismiss = ::closeEditor, onConfirm = { vm.save(ProfileUpdate(reviewDays = it)) })
    null -> Unit
  }
}

@Composable
private fun SettingsDivider() {
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = FlashcardStyle.opacity.divider))
}

@Composable
private fun SettingsRow(title: String, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
  val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = FlashcardStyle.opacity.disabledContent)
  Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
    .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = color)
    value?.let {
      Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color)
    }
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, Modifier.size(20.dp),
      tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color)
  }
}

@Composable
private fun NameDialog(initial: String, saving: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
  var text by rememberSaveable { mutableStateOf(initial) }
  SettingDialog("Your name", saving, error, text.trim().isNotEmpty(), onDismiss, { onConfirm(text.trim()) }) {
    OutlinedTextField(text, onValueChange = { if (it.length <= 40 && it.none(Char::isISOControl)) text = it },
      Modifier.fillMaxWidth(), singleLine = true, enabled = !saving, shape = MaterialTheme.shapes.medium,
      label = { Text("Name") })
  }
}

@Composable
private fun DailyWordsDialog(initial: Int, saving: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
  var count by rememberSaveable { mutableIntStateOf(initial) }
  SettingDialog("Words per day", saving, error, true, onDismiss, { onConfirm(count) }) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(count.toString(), style = MaterialTheme.typography.headlineLarge)
      Slider(count.coerceIn(5, 100).toFloat(), onValueChange = { count = it.roundToInt() },
        modifier = Modifier.fillMaxWidth(), enabled = !saving, valueRange = 5f..100f, steps = 94)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("5", style = MaterialTheme.typography.bodySmall)
        Text("100", style = MaterialTheme.typography.bodySmall)
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
        TextButton(onClick = { count-- }, enabled = !saving && count > 2, modifier = Modifier.size(48.dp)) { Text("−") }
        Text(count.toString(), style = MaterialTheme.typography.headlineLarge)
        TextButton(onClick = { count++ }, enabled = !saving && count < 8, modifier = Modifier.size(48.dp)) { Text("+") }
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
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
          .toggleable(value = checked, enabled = !saving, role = Role.Checkbox,
            onValueChange = { selected = if (it) (selected + day).distinct().sorted() else selected - day }),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          Box(Modifier.size(22.dp).background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            CircleShape).border(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.onPrimary)
          }
          Text(if (day == 1) "1 day later" else "$day days later", style = MaterialTheme.typography.bodyLarge)
        }
      }
      Text("Changes apply to new learning cycles.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun CsvImportDialog(state: CsvImportUiState, onImport: () -> Unit, onDismiss: () -> Unit) {
  val preview = state.preview
  val report = state.report
  val issues = preview?.issues?.take(100).orEmpty()
  AlertDialog(onDismissRequest = { if (state.canDismiss) onDismiss() }, shape = MaterialTheme.shapes.large,
    title = { Text("CSV import") }, text = {
      Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.busy) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(when (state.stage) {
              CsvImportStage.READING -> "Reading CSV…"
              CsvImportStage.IMPORTING -> "Importing words…"
              else -> "Closing preview…"
            })
          }
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
        TextButton(onClick = onImport, enabled = !state.busy) { Text("Import ${preview.newWords} words") }
      } else if (!state.busy) {
        TextButton(onClick = onDismiss) { Text("Done") }
      }
    }, dismissButton = {
      if (report == null && state.error == null && (preview == null || preview.newWords > 0 && preview.errorCount == 0)) {
        TextButton(onClick = onDismiss, enabled = state.canDismiss) { Text("Cancel") }
      }
    })
}

@Composable
private fun Metric(count: Int, label: String, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(count.toString(), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
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
  AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, shape = MaterialTheme.shapes.large,
    title = { Text(title) }, text = {
      Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    }, confirmButton = {
      TextButton(onClick = onConfirm, enabled = !saving && valid) { Text(if (saving) "Saving…" else "Done") }
    }, dismissButton = {
      TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
    })
}

@Preview(name = "Settings - light", widthDp = 360)
@Preview(name = "Settings - dark", widthDp = 360, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Settings - compact", widthDp = 320, fontScale = 1.3f)
@Composable
private fun SettingsPreview() {
  FlashcardTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
      Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
          EditableAvatar("1", size = 44.dp, onClick = {})
          Text("Profile", style = MaterialTheme.typography.titleLarge)
        }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
          Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SettingsRow("Name", "Alexander", onClick = {})
            SettingsDivider()
            SettingsRow("Daily words", "10", onClick = {})
            SettingsDivider()
            SettingsRow("Review days", "1 / 3 / 7", onClick = {})
            SettingsDivider()
            SettingsRow("Import CSV", enabled = false, onClick = {})
          }
        }
      }
    }
  }
}

@Preview(name = "Name form - light", widthDp = 360)
@Preview(name = "Name form - dark", widthDp = 360, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NameFormPreview() {
  FlashcardTheme {
    NameDialog("Alexander", saving = false, error = null, onDismiss = {}, onConfirm = {})
  }
}
