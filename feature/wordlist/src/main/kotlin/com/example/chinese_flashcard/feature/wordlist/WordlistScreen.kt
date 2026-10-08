package com.example.chinese_flashcard.feature.wordlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.WordlistItem
import com.example.chinese_flashcard.core.domain.WordlistStatus
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardLayout
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.CollectionIcon
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.ui.StageThemePreviewCase
import com.example.chinese_flashcard.core.ui.StageThemePreviewProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun WordlistScreen(vm: WordlistViewModel, onWord: (String) -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val listState = rememberLazyListState(vm.scrollIndex, vm.scrollOffset)
  val scope = rememberCoroutineScope()
  LaunchedEffect(vm, listState) {
    snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
      .collect { (index, offset) -> if (!vm.state.value.loading) vm.rememberScroll(index, offset) }
  }
  DisposableEffect(vm, listState) {
    onDispose { vm.rememberScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
  }
  val resetScroll = { scope.launch { listState.scrollToItem(0) }; Unit }
  WordlistContent(state, listState,
    onQuery = { vm.setQuery(it); resetScroll() },
    onStatus = { vm.setStatus(it); resetScroll() },
    onDifficulty = { vm.toggleDifficulty(it); resetScroll() },
    onCollection = { vm.setMyCollection(it); resetScroll() },
    onRetry = vm::retry, onWord = onWord)
}

@Composable
private fun WordlistContent(state: WordlistUiState, listState: LazyListState,
  onQuery: (String) -> Unit, onStatus: (WordlistStatus?) -> Unit, onDifficulty: (Int?) -> Unit,
  onCollection: (Boolean) -> Unit, onRetry: () -> Unit, onWord: (String) -> Unit) {
  Surface(Modifier.fillMaxSize().flashcardBackground(), color = Color.Transparent) {
    LazyColumn(state = listState, contentPadding = PaddingValues(FlashcardLayout.pageInset)) {
      item(key = "header") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Wordlist", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            Text("${state.total} words", style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WordCount(state.learned, "Learned", Modifier.weight(1f))
                WordCount(state.learning, "Learning", Modifier.weight(1f))
                WordCount(state.unlearned, "Not started", Modifier.weight(1f))
              }
              LinearProgressIndicator(progress = {
                if (state.total == 0) 0f else (state.learned.toFloat() / state.total).coerceIn(0f, 1f)
              }, modifier = Modifier.fillMaxWidth().height(4.dp))
            }
          }
          OutlinedTextField(value = state.query, onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { Text("Hanzi, pinyin or meaning", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (state.query.isNotEmpty()) {
              { IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } }
            } else null)
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              WordlistFilterChip(selected = state.status == null, onClick = { onStatus(null) }, label = "All")
              WordlistStatus.entries.forEach { status ->
                WordlistFilterChip(selected = state.status == status, onClick = { onStatus(status) }, label = status.label())
              }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              WordlistFilterChip(selected = state.difficulties.isEmpty(), onClick = { onDifficulty(null) }, label = "All")
              VocabularyStage.rarityRange.forEach { level ->
                WordlistFilterChip(selected = level in state.difficulties,
                  modifier = Modifier.semantics { contentDescription = VocabularyStage.fromRarity(level)?.label.orEmpty() },
                  onClick = { onDifficulty(level) }, label = level.toString())
              }
            }
          }
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(buildString {
              append("${state.entries.size} ${if (state.entries.size == 1) "word" else "words"}")
              if (state.skipped > 0) append(" · ${state.skipped} skipped")
            }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
            WordlistFilterChip(selected = state.myCollectionOnly,
              onClick = { onCollection(!state.myCollectionOnly) }, label = "My collection")
          }
          state.error?.let { message ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(message, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
              TextButton(shape = MaterialTheme.shapes.small, onClick = onRetry) { Text("Try again") }
            }
          }
          if (state.loading) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
          }
        }
      }
      if (!state.loading && state.error == null && state.entries.isEmpty()) {
        item(key = "empty") {
          Text(if (state.total == 0) "No words in your wordlist." else "No matching words.",
            modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      items(state.entries, key = { "word/${it.id}" }) { entry ->
        WordlistRow(entry, onClick = { onWord(entry.id) })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = FlashcardStyle.opacity.divider))
      }
    }
  }
}

@Composable
private fun WordlistFilterChip(selected: Boolean, onClick: () -> Unit, label: String,
  modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = modifier,
    shape = MaterialTheme.shapes.small,
    colors = FilterChipDefaults.filterChipColors(
      containerColor = colors.surface, labelColor = colors.onSurfaceVariant,
      selectedContainerColor = colors.primary, selectedLabelColor = colors.onPrimary),
    border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant))
}

@Composable
private fun WordCount(count: Int, label: String, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(count.toString(), style = MaterialTheme.typography.titleLarge)
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun WordlistRow(entry: WordlistItem, onClick: () -> Unit) {
  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
    .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically) {
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(entry.hanzi, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (entry.isCollected) CollectionIcon(true, contentDescription = "In my collection")
      }
      Text("Stage ${entry.difficulty}", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(entry.pinyin, style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(entry.english, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(if (entry.status == WordlistStatus.LEARNING && entry.targetRounds > 0)
        "Learning · ${entry.correctRounds} / ${entry.targetRounds}" else entry.status.label(),
        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
        color = when (entry.status) {
          WordlistStatus.LEARNED -> FlashcardStyle.colors.success
          WordlistStatus.LEARNING -> MaterialTheme.colorScheme.primary
          WordlistStatus.UNLEARNED -> MaterialTheme.colorScheme.onSurfaceVariant
          WordlistStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        })
    }
  }
}

private fun WordlistStatus.label(): String = when (this) {
  WordlistStatus.UNLEARNED -> "Not started"
  WordlistStatus.LEARNING -> "Learning"
  WordlistStatus.LEARNED -> "Learned"
  WordlistStatus.SKIPPED -> "Skipped"
}

@Preview(name = "Wordlist", widthDp = 360, heightDp = 760)
@Preview(name = "Wordlist · compact", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Composable
private fun WordlistPreview(@PreviewParameter(StageThemePreviewProvider::class, limit = 2) theme: StageThemePreviewCase) {
  val entries = listOf(
    WordlistItem("hello", "你好", "nǐ hǎo", "hello", "hello", 0, WordlistStatus.LEARNED, 0, 0, isCollected = true),
    WordlistItem("thanks", "谢谢", "xièxie", "thank you", "thank you", 1, WordlistStatus.LEARNING, 2, 4),
    WordlistItem("tomorrow", "明天", "míngtiān", "tomorrow", "tomorrow", 2, WordlistStatus.SKIPPED, 0, 0,
      isCollected = true, isSkipped = true, learningStatus = WordlistStatus.UNLEARNED),
  )
  FlashcardTheme(darkTheme = theme.darkTheme, stage = theme.stage) {
    WordlistContent(WordlistUiState(loading = false, entries = entries, total = 3, learned = 1, learning = 1, unlearned = 1),
      rememberLazyListState(), onQuery = {}, onStatus = {}, onDifficulty = {}, onCollection = {}, onRetry = {}, onWord = {})
  }
}
