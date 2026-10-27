package com.example.chinese_flashcard.feature.wordlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.chinese_flashcard.core.ui.FlashcardLayout
import com.example.chinese_flashcard.core.ui.FlashcardToolbar
import com.example.chinese_flashcard.core.ui.WordTagGrid
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
  var counts by remember { mutableStateOf<Map<String, Int>?>(null) }
  var matching by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(state.allEntries) {
    counts = withContext(Dispatchers.Default) {
      state.allEntries.flatMap { it.tags }.groupingBy { it }.eachCount()
    }
  }
  LaunchedEffect(selected, state.allEntries, state.tagCatalog) {
    matching = null
    matching = withContext(Dispatchers.Default) {
      val matches = WordTagFilter(selected).matcher(state.tagCatalog)
      state.allEntries.count { matches(it.tags) }
    }
  }
  BackHandler(onBack = onBack)
  Column(Modifier.fillMaxSize().padding(horizontal = FlashcardLayout.pageInset)) {
    FlashcardToolbar(uiString(UiR.string.ui_tag_filter_title), onBack,
      subtitle = { Text(uiString(UiR.string.ui_wordlist), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant) },
      trailing = {
        TextButton(onClick = { selected = emptySet() }, enabled = selected.isNotEmpty()) {
          Text(uiString(UiR.string.ui_tag_clear))
        }
      })
    WordTagGrid(state.tagCatalog, selected, counts, { selected = it },
      modifier = Modifier.weight(1f).fillMaxWidth())
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
      modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
  }
}
