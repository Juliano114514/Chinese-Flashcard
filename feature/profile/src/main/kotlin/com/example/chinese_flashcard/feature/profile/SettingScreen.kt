package com.example.chinese_flashcard.feature.profile

import com.example.chinese_flashcard.core.domain.StudySettingsRules

import com.example.chinese_flashcard.core.ui.uiPlural
import com.example.chinese_flashcard.core.domain.UiMessageKey
import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.DailyWordChoices
import com.example.chinese_flashcard.core.domain.ThemeMode
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.WordlistLoadProgress
import com.example.chinese_flashcard.core.ui.FlashcardDialog
import com.example.chinese_flashcard.core.ui.FlashcardToolbar
import com.example.chinese_flashcard.core.ui.FlashcardLayout
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.ui.WordlistLoadingProgress
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

private enum class SettingEditor {
  DAILY_WORDS, ROUNDS, REVIEW_DAYS, REVIEW_BATCH_SIZE, THEME, UI_LANGUAGE, LEARNING_LANGUAGE,
  SPEECH_PRELOAD, SPEECH_CACHE, HOME_SHUFFLE_QUEUE,
}

@Composable
fun SettingScreen(vm: ProfileViewModel, onBack: () -> Unit, onImportCsv: () -> Unit, onLicenses: () -> Unit,
  defaultWordlistProgress: StateFlow<WordlistLoadProgress?>, csvImportProgress: StateFlow<WordlistLoadProgress?>,
  defaultWordlistLoading: Boolean = false, defaultWordlistError: UiMessageKey? = null,
  onRetryDefaultWordlist: () -> Unit = {}, languageSwitchEnabled: Boolean = true,
  defaultWordlistResumeWritingLabel: String? = null, onResumeDefaultWordlistWriting: () -> Unit = {},
  onReadingOptions: () -> Unit = {}, onExplanationOptions: () -> Unit = {},
  cacheUsedBytes: Long = 0L, onClearSpeechCache: () -> Unit = {}) {
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
    FlashcardToolbar(uiString(UiR.string.ui_settings), onBack, enabled = !state.saving && !csv.busy)
    if (state.loading) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiString(UiR.string.ui_languages), style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
          Column(Modifier.fillMaxWidth()) {
            SettingsRow(uiString(UiR.string.ui_system_language), value.uiLanguage.label(), enabled && languageSwitchEnabled) {
              openEditor(SettingEditor.UI_LANGUAGE)
            }
            SettingsDivider()
            SettingsRow(uiString(UiR.string.ui_learning_language), value.learningLanguage.label(), enabled && languageSwitchEnabled) {
              openEditor(SettingEditor.LEARNING_LANGUAGE)
            }
          }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiString(UiR.string.ui_appearance), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
          SettingsRow(uiString(UiR.string.ui_theme), value.themeMode.label(), enabled) { openEditor(SettingEditor.THEME) }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiString(UiR.string.ui_learning), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
          Column(Modifier.fillMaxWidth()) {
            SettingsRow(uiString(UiR.string.ui_daily_words), value.dailyWords.toString(), enabled) { openEditor(SettingEditor.DAILY_WORDS) }
            SettingsDivider()
            SettingsRow(uiString(UiR.string.ui_correct_rounds), value.rounds.toString(), enabled) { openEditor(SettingEditor.ROUNDS) }
            SettingsDivider()
            SettingsRow(uiString(UiR.string.ui_review_days), value.reviewDays.joinToString(" / "), enabled) { openEditor(SettingEditor.REVIEW_DAYS) }
            SettingsDivider()
            SettingsRow(uiString(UiR.string.ui_review_batch_size), value.reviewBatchSize.toString(), enabled) {
              openEditor(SettingEditor.REVIEW_BATCH_SIZE)
            }
            SettingsDivider()
            SettingsRow(uiString(UiR.string.ui_reading_options), uiString(UiR.string.ui_reading_options_summary), enabled, onReadingOptions)
            SettingsDivider()
            SettingsRow(uiString(UiR.string.ui_explanation_options), uiString(UiR.string.ui_explanation_options_summary), enabled, onExplanationOptions)
            SettingsDivider()
            Row(Modifier.fillMaxWidth().toggleable(value = value.waitForCorrectAnswerAudio,
              enabled = enabled, role = Role.Checkbox,
              onValueChange = { vm.save(ProfileUpdate(waitForCorrectAnswerAudio = it)) })
              .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(uiString(UiR.string.ui_wait_for_correct_answer_audio), style = MaterialTheme.typography.bodyLarge)
                Text(uiString(UiR.string.ui_wait_for_correct_answer_audio_description),
                  style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Checkbox(checked = value.waitForCorrectAnswerAudio, onCheckedChange = null, enabled = enabled)
            }
          }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiString(UiR.string.ui_speech_and_cache), style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
          Column(Modifier.fillMaxWidth()) {
            SettingsRow(uiString(UiR.string.ui_speech_preload_words), value.speechPreloadWords.toString(), enabled) {
              openEditor(SettingEditor.SPEECH_PRELOAD)
            }
            SettingsDivider()
            SettingsRow(uiString(UiR.string.ui_speech_cache_capacity),
              uiString(UiR.string.ui_speech_cache_capacity_value, value.speechCacheMiB), enabled) {
              openEditor(SettingEditor.SPEECH_CACHE)
            }
            SettingsDivider()
            SettingsRow(uiString(UiR.string.ui_home_shuffle_queue_size), value.homeShuffleQueueSize.toString(), enabled) {
              openEditor(SettingEditor.HOME_SHUFFLE_QUEUE)
            }
            SettingsDivider()
            SettingsRow(uiString(UiR.string.ui_clear_speech_cache),
              uiString(UiR.string.ui_speech_cache_used, cacheUsedBytes.coerceAtLeast(0L).toDouble() / 1_048_576.0,
                value.speechCacheMiB), enabled, onClearSpeechCache)
          }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiString(UiR.string.ui_data), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
          Column(Modifier.fillMaxWidth()) {
            SettingsRow(uiString(UiR.string.ui_import_csv), enabled = enabled, onClick = onImportCsv)
            SettingsDivider()
            SettingsRow(uiString(UiR.string.ui_data_licenses), enabled = enabled, onClick = onLicenses)
          }
        }
      }
      if (defaultWordlistLoading || defaultWordlistError != null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(uiString(UiR.string.ui_default_wordlist), style = MaterialTheme.typography.titleSmall)
          if (defaultWordlistLoading) WordlistLoadingProgress(defaultWordlistProgress)
          else {
            Text(uiString(defaultWordlistError!!), textAlign = TextAlign.Center,
              color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            defaultWordlistResumeWritingLabel?.let { label ->
              TextButton(shape = MaterialTheme.shapes.small, onClick = onResumeDefaultWordlistWriting,
                enabled = enabled && editor == null) {
                Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
              }
            }
            TextButton(shape = MaterialTheme.shapes.small, onClick = onRetryDefaultWordlist, enabled = enabled && editor == null) { Text(uiString(UiR.string.ui_retry)) }
          }
        }
      }
      if (state.error != null && editor == null) {
        Text(uiString(state.error!!), color = MaterialTheme.colorScheme.error)
        TextButton(shape = MaterialTheme.shapes.small, onClick = vm::retry, enabled = enabled) { Text(uiString(UiR.string.ui_try_again)) }
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
    SettingEditor.REVIEW_BATCH_SIZE -> NumberSettingDialog(uiString(UiR.string.ui_review_batch_size),
      value.reviewBatchSize, StudySettingsRules.reviewBatchSize, uiString(UiR.string.ui_review_batch_description), state.saving, state.error,
      ::closeEditor) { vm.save(ProfileUpdate(reviewBatchSize = it)) }
    SettingEditor.THEME -> ThemeDialog(value.themeMode, state.saving, state.error,
      onDismiss = ::closeEditor, onConfirm = { vm.save(ProfileUpdate(themeMode = it)) })
    SettingEditor.UI_LANGUAGE -> LanguageDialog(uiString(UiR.string.ui_system_language), value.uiLanguage,
      state.saving, state.error, ::closeEditor) { vm.save(ProfileUpdate(uiLanguage = it)) }
    SettingEditor.LEARNING_LANGUAGE -> LanguageDialog(uiString(UiR.string.ui_learning_language), value.learningLanguage,
      state.saving, state.error, ::closeEditor) { vm.save(ProfileUpdate(learningLanguage = it)) }
    SettingEditor.SPEECH_PRELOAD -> NumberSettingDialog(uiString(UiR.string.ui_speech_preload_words),
      value.speechPreloadWords, StudySettingsRules.speechPreloadWords, uiString(UiR.string.ui_speech_preload_description), state.saving, state.error,
      ::closeEditor) { vm.save(ProfileUpdate(speechPreloadWords = it)) }
    SettingEditor.SPEECH_CACHE -> NumberSettingDialog(uiString(UiR.string.ui_speech_cache_capacity),
      value.speechCacheMiB, StudySettingsRules.speechCacheMiB, uiString(UiR.string.ui_speech_cache_description), state.saving, state.error,
      ::closeEditor) { vm.save(ProfileUpdate(speechCacheMiB = it)) }
    SettingEditor.HOME_SHUFFLE_QUEUE -> NumberSettingDialog(uiString(UiR.string.ui_home_shuffle_queue_size),
      value.homeShuffleQueueSize, StudySettingsRules.homeShuffleQueueSize, uiString(UiR.string.ui_home_shuffle_queue_description), state.saving, state.error,
      ::closeEditor) { vm.save(ProfileUpdate(homeShuffleQueueSize = it)) }
    null -> Unit
  }
}

@Composable
private fun NumberSettingDialog(title: String, initial: Int, range: IntRange, description: String,
  saving: Boolean, error: UiMessageKey?, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
  var text by rememberSaveable(title, initial) { mutableStateOf(initial.toString()) }
  val number = text.toIntOrNull()
  val valid = number != null && number in range
  SettingDialog(title, saving, error, valid, onDismiss, { if (valid) onConfirm(number) }) {
    OutlinedTextField(text, onValueChange = { next ->
      if (next.length <= 4 && next.all { it in '0'..'9' }) text = next
    }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving,
      shape = MaterialTheme.shapes.medium, textStyle = MaterialTheme.typography.bodyLarge,
      isError = !valid,
      supportingText = { Text(uiString(UiR.string.ui_integer_range, range.first, range.last)) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { if (!saving && valid) onConfirm(number) }))
    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun AppLanguage.label(): String = when (this) {
  AppLanguage.EN -> uiString(UiR.string.ui_english) + " · EN"
  AppLanguage.CN -> uiString(UiR.string.ui_chinese) + " · CN"
}

@Composable
private fun LanguageDialog(title: String, initial: AppLanguage, saving: Boolean, error: UiMessageKey?,
  onDismiss: () -> Unit, onConfirm: (AppLanguage) -> Unit) {
  var selected by rememberSaveable(initial) { mutableStateOf(initial) }
  SettingDialog(title, saving, error, true, onDismiss, { onConfirm(selected) }) {
    Column(Modifier.selectableGroup()) {
      AppLanguage.entries.forEach { language ->
        Row(Modifier.fillMaxWidth().heightIn(min = FlashcardLayout.rowHeight)
          .selectable(selected = selected == language, enabled = !saving, role = Role.RadioButton,
            onClick = { selected = language }).padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          RadioButton(selected = selected == language, onClick = null, enabled = !saving)
          Text(language.label())
        }
      }
    }
  }
}

@Composable
private fun ThemeMode.label(): String = when (this) {
  ThemeMode.LIGHT -> uiString(UiR.string.ui_light)
  ThemeMode.DARK -> uiString(UiR.string.ui_dark)
  ThemeMode.SYSTEM -> uiString(UiR.string.ui_follow_system)
}

@Composable
private fun ThemeDialog(initial: ThemeMode, saving: Boolean, error: UiMessageKey?, onDismiss: () -> Unit,
  onConfirm: (ThemeMode) -> Unit) {
  var selected by rememberSaveable { mutableStateOf(initial) }
  SettingDialog(uiString(UiR.string.ui_theme), saving, error, true, onDismiss, { onConfirm(selected) }) {
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
  HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = FlashcardStyle.opacity.divider))
}

@Composable
private fun SettingsRow(title: String, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
  val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = FlashcardStyle.opacity.disabledContent)
  Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
    .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = color)
    value?.let {
      Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color)
    }
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, Modifier.size(20.dp),
      tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color)
  }
}

@Composable
internal fun NameDialog(initial: String, saving: Boolean, error: UiMessageKey?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
  var text by rememberSaveable { mutableStateOf(initial) }
  SettingDialog(uiString(UiR.string.ui_your_name), saving, error, text.trim().isNotEmpty(), onDismiss, { onConfirm(text.trim()) }) {
    OutlinedTextField(text, onValueChange = { if (it.length <= 40 && it.none(Char::isISOControl)) text = it },
      Modifier.fillMaxWidth(), singleLine = true, enabled = !saving, shape = MaterialTheme.shapes.medium,
      textStyle = MaterialTheme.typography.bodyLarge,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { if (!saving && text.trim().isNotEmpty()) onConfirm(text.trim()) }),
      label = { Text(uiString(UiR.string.ui_name)) })
  }
}

@Composable
private fun DailyWordsDialog(initial: Int, saving: Boolean, error: UiMessageKey?, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
  var count by rememberSaveable { mutableIntStateOf(DailyWordChoices.normalize(initial)) }
  SettingDialog(uiString(UiR.string.ui_words_per_day), saving, error, DailyWordChoices.isAllowed(count), onDismiss, { onConfirm(count) }) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(count.toString(), style = MaterialTheme.typography.headlineLarge)
      Slider(count.toFloat(), onValueChange = { count = DailyWordChoices.normalize(it.roundToInt()) },
        modifier = Modifier.fillMaxWidth(), enabled = !saving,
        valueRange = DailyWordChoices.MIN.toFloat()..DailyWordChoices.MAX.toFloat(),
        steps = (DailyWordChoices.MAX - DailyWordChoices.MIN) / DailyWordChoices.STEP - 1,
        colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(DailyWordChoices.MIN.toString(), style = MaterialTheme.typography.bodySmall)
        Text(DailyWordChoices.MAX.toString(), style = MaterialTheme.typography.bodySmall)
      }
      Text(uiString(UiR.string.ui_changes_apply_to_the_next_daily_plan), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun RoundsDialog(initial: Int, saving: Boolean, error: UiMessageKey?, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
  var count by rememberSaveable { mutableIntStateOf(initial) }
  SettingDialog(uiString(UiR.string.ui_correct_rounds), saving, error, true, onDismiss, { onConfirm(count) }) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(shape = MaterialTheme.shapes.small, onClick = { count-- }, enabled = !saving && count > StudySettingsRules.rounds.first, modifier = Modifier.size(48.dp)) { Text("−") }
        Text(count.toString(), style = MaterialTheme.typography.headlineLarge)
        TextButton(shape = MaterialTheme.shapes.small, onClick = { count++ }, enabled = !saving && count < StudySettingsRules.rounds.last, modifier = Modifier.size(48.dp)) { Text("+") }
      }
      Text(uiString(UiR.string.ui_consecutive_correct_answers_a_wrong_answer_resets_the_round),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(uiString(UiR.string.ui_changes_apply_to_new_learning_cycles), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun ReviewDaysDialog(initial: List<Int>, saving: Boolean, error: UiMessageKey?, onDismiss: () -> Unit, onConfirm: (List<Int>) -> Unit) {
  var selected by rememberSaveable { mutableStateOf(initial) }
  SettingDialog(uiString(UiR.string.ui_review_days), saving, error, selected.isNotEmpty(), onDismiss, { onConfirm(selected.sorted()) }) {
    Column {
      Text(uiString(UiR.string.ui_days_after_passing_a_word), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
      StudySettingsRules.reviewDays.forEach { day ->
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
          Text(uiPlural(UiR.plurals.ui_days_later_quantity, day, day), style = MaterialTheme.typography.bodyMedium)
        }
      }
      Text(uiString(UiR.string.ui_changes_apply_to_new_learning_cycles), style = MaterialTheme.typography.bodySmall,
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
    title = { Text(uiString(UiR.string.ui_csv_import)) }, text = {
      Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.busy) {
          if (state.stage == CsvImportStage.READING || state.stage == CsvImportStage.IMPORTING)
            WordlistLoadingProgress(progress)
          else Text(uiString(UiR.string.ui_closing_preview), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall)
        }
        preview?.let {
          Text(uiString(UiR.string.ui_import_summary, it.totalRows, it.newWords, it.duplicateWords))
          when {
            it.errorCount > 0 -> {
              Text(uiString(UiR.string.ui_import_errors, it.errorCount),
                color = MaterialTheme.colorScheme.error)
              issues.forEach { issue ->
                val location = if (issue.line > 0) uiString(UiR.string.ui_line_number, issue.line) else uiString(UiR.string.ui_file)
                val field = issue.fieldIndex?.let { index -> uiString(UiMessageKey.COLUMN_1_D, index) } ?: issue.field
                Text(uiString(UiR.string.ui_csv_issue_detail, location, field, uiString(issue.message, *issue.messageArguments.toTypedArray())),
                  style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
              }
              if (it.errorCount > issues.size) {
                Text(uiString(UiR.string.ui_show_first_errors, issues.size), style = MaterialTheme.typography.bodySmall)
              }
            }
            it.newWords == 0 -> Text(uiString(UiR.string.ui_all_words_are_already_in_your_wordbook))
            else -> Text(uiString(UiR.string.ui_existing_words_and_progress_will_be_kept))
          }
        }
        report?.let { Text(uiString(UiR.string.ui_import_added, it.addedWords, it.skippedWords)) }
        state.error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
      }
    }, confirmButton = {
      if (preview?.canImport == true && preview.newWords > 0) {
        TextButton(shape = MaterialTheme.shapes.small, onClick = onImport, enabled = !state.busy) { Text(uiString(UiR.string.ui_import_words, preview.newWords)) }
      } else if (!state.busy) {
        TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text(uiString(UiR.string.ui_done)) }
      }
    }, dismissButton = {
      if (report == null && state.error == null && (preview == null || preview.newWords > 0 && preview.errorCount == 0)) {
        TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss, enabled = state.canDismiss) { Text(uiString(UiR.string.ui_cancel)) }
      }
    })
}

@Composable
private fun SettingDialog(
  title: String,
  saving: Boolean,
  error: UiMessageKey?,
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
        error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    }, confirmButton = {
      TextButton(shape = MaterialTheme.shapes.small, onClick = onConfirm, enabled = !saving && valid) { Text(uiString(if (saving) UiR.string.ui_saving_ff509c else UiR.string.ui_done)) }
    }, dismissButton = {
      TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss, enabled = !saving) { Text(uiString(UiR.string.ui_cancel)) }
    })
}
