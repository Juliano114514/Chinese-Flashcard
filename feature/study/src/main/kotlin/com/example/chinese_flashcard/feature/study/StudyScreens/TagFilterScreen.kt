package com.example.chinese_flashcard.feature.study

import com.example.chinese_flashcard.core.ui.WordTagFilterLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.TagFilterPreview
import com.example.chinese_flashcard.core.domain.WordTagFilter
import com.example.chinese_flashcard.core.ui.localizedLabel
import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val TagSelectionSaver = listSaver<Set<String>, String>(
  save = { it.sorted() }, restore = { it.toSet() })

@Composable
fun TagFilterScreen(vm: StudyViewModel, onBack: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val today = state.today
  val language = state.settings.learningLanguage
  val stage = today?.stageProgress?.stage
  val catalog = today?.tagCatalog.orEmpty()
  var selected by rememberSaveable(language.name, stage?.rarity, stateSaver = TagSelectionSaver) {
    mutableStateOf(today?.tagFilter?.selectedIds.orEmpty())
  }
  var multiple by rememberSaveable(language.name, stage?.rarity) { mutableStateOf(selected.size > 1) }
  var lastSelected by rememberSaveable(language.name, stage?.rarity) { mutableStateOf<String?>(null) }
  val filter = WordTagFilter(selected)
  var preview by remember { mutableStateOf<TagFilterPreview?>(null) }
  var previewFailed by remember { mutableStateOf(false) }
  var retry by remember { mutableStateOf(0) }
  var submitting by remember { mutableStateOf(false) }
  var applyFailed by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val enabled = !state.loading && !state.busy && !submitting
  BackHandler(enabled = state.busy || submitting) { }

  LaunchedEffect(filter, language, stage, state.directionGeneration, state.snapshot?.revision, retry) {
    preview = null
    previewFailed = false
    if (today == null) return@LaunchedEffect
    try {
      preview = vm.previewTagFilter(filter, language, state.directionGeneration)
      previewFailed = preview == null
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      previewFailed = true
    }
  }
  WordTagFilterLayout(subtitle = stage?.localizedLabel().orEmpty(), catalog = catalog,
    selected = selected, counts = preview?.tagCounts, totalProgress = preview?.totalProgress,
    multiple = multiple, enabled = enabled, onBack = onBack,
    onSelectionChange = { next ->
      lastSelected = (next - selected).firstOrNull() ?: lastSelected?.takeIf { it in next }
      selected = next
    }, onModeChange = { value ->
      multiple = value
      if (!value && selected.size > 1) selected = setOf(lastSelected?.takeIf { it in selected }
        ?: catalog.firstOrNull { it.id in selected }?.id ?: selected.sorted().first())
    }) {
    when {
      previewFailed -> {
        Text(uiString(UiR.string.ui_tag_preview_failed), color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { retry++ }, enabled = enabled) { Text(uiString(UiR.string.ui_retry)) }
      }
      preview == null -> Text(uiString(UiR.string.ui_loading), style = MaterialTheme.typography.bodyMedium)
      else -> {
        val result = requireNotNull(preview)
        Text(uiString(UiR.string.ui_tag_match_progress, result.total, result.learned),
          style = MaterialTheme.typography.titleSmall)
        DailyProgress(result.learned, result.total)
        if (result.total == 0) Text(uiString(UiR.string.ui_tag_no_matching_words),
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    if (state.error != null) Text(uiString(requireNotNull(state.error)),
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    else if (applyFailed) Text(uiString(UiR.string.ui_this_step_couldn_t_be_completed_try_again),
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    Button(onClick = {
      val target = filter.normalized(catalog)
      if (today?.tagFilter == target) onBack()
      else {
        submitting = true
        applyFailed = false
        scope.launch {
          try {
            if (!vm.applyTagFilter(target)) {
              applyFailed = true
              return@launch
            }
            // Read the StateFlow directly: even a write finishing between frames is observed.
            val result = vm.state.first { !it.busy }
            if (result.error == null && result.settings.learningLanguage == language && result.today?.tagFilter == target) onBack()
            else applyFailed = true
          } finally {
            // A stale context can return a different snapshot without a repository error.
            submitting = false
          }
        }
      }
    }, enabled = enabled && preview != null && !previewFailed,
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
      Text(uiString(if (submitting || state.busy) UiR.string.ui_loading else UiR.string.ui_tag_apply))
    }
  }
}
