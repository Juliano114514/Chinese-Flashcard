package com.example.chinese_flashcard.feature.wordlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chinese_flashcard.core.domain.WordTagFilter
import com.example.chinese_flashcard.core.domain.WordTagProgress
import com.example.chinese_flashcard.core.domain.WordlistStatus
import com.example.chinese_flashcard.core.ui.WordTagFilterLayout
import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val WordlistTagSelectionSaver = listSaver<Set<String>, String>(
  save = { it.sorted() }, restore = { it.toSet() })

@Composable
internal fun WordlistTagFilterScreen(state: WordlistUiState, onBack: () -> Unit,
  onApply: (WordTagFilter) -> Unit) {
  var selected by rememberSaveable(stateSaver = WordlistTagSelectionSaver) {
    mutableStateOf(state.tagFilter.selectedIds)
  }
  var multiple by rememberSaveable { mutableStateOf(selected.size > 1) }
  var lastSelected by rememberSaveable { mutableStateOf<String?>(null) }
  var counts by remember { mutableStateOf<Map<String, WordTagProgress>?>(null) }
  var totalProgress by remember { mutableStateOf<WordTagProgress?>(null) }
  var matching by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(state.allEntries, state.tagCatalog) {
    val progress = withContext(Dispatchers.Default) {
      state.tagCatalog.associate { tag ->
        val words = state.allEntries.filter { tag.id in it.tags }
        tag.id to WordTagProgress(words.count { it.learningStatus == WordlistStatus.LEARNED }, words.size)
      } to WordTagProgress(state.allEntries.count { it.learningStatus == WordlistStatus.LEARNED }, state.allEntries.size)
    }
    counts = progress.first
    totalProgress = progress.second
  }
  LaunchedEffect(selected, state.allEntries, state.tagCatalog) {
    matching = null
    matching = withContext(Dispatchers.Default) {
      val matches = WordTagFilter(selected).matcher(state.tagCatalog)
      state.allEntries.count { matches(it.tags) }
    }
  }
  BackHandler(onBack = onBack)
  WordTagFilterLayout(subtitle = uiString(UiR.string.ui_wordlist), catalog = state.tagCatalog,
    selected = selected, counts = counts, totalProgress = totalProgress, multiple = multiple,
    onBack = onBack, onSelectionChange = { next ->
      lastSelected = (next - selected).firstOrNull() ?: lastSelected?.takeIf { it in next }
      selected = next
    }, onModeChange = { value ->
      multiple = value
      if (!value && selected.size > 1) selected = setOf(lastSelected?.takeIf { it in selected }
        ?: state.tagCatalog.firstOrNull { it.id in selected }?.id ?: selected.sorted().first())
    }) {
    Text(matching?.let { uiString(UiR.string.ui_word_count, it) } ?: uiString(UiR.string.ui_loading),
      style = MaterialTheme.typography.titleSmall)
    if (matching == 0) Text(uiString(UiR.string.ui_no_matching_words),
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { onApply(WordTagFilter(selected)) }, enabled = matching != null,
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
      Text(uiString(UiR.string.ui_tag_apply))
    }
  }
}
