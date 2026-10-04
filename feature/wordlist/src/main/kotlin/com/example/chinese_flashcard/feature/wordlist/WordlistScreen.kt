package com.example.chinese_flashcard.feature.wordlist

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.WordlistItem
import com.example.chinese_flashcard.core.domain.WordlistStatus
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardTheme
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
    onRetry = vm::retry, onWord = onWord)
}

@Composable
private fun WordlistContent(state: WordlistUiState, listState: LazyListState,
  onQuery: (String) -> Unit, onStatus: (WordlistStatus?) -> Unit, onDifficulty: (Int?) -> Unit,
  onRetry: () -> Unit, onWord: (String) -> Unit) {
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp)) {
      item(key = "header") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 12.dp)) {
          Text("Wordlist", style = MaterialTheme.typography.headlineLarge)
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${state.learned} / ${state.total}", style = MaterialTheme.typography.headlineMedium,
              fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = {
              if (state.total == 0) 0f else (state.learned.toFloat() / state.total).coerceIn(0f, 1f)
            }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              WordCount(state.learned, "Learned", Modifier.weight(1f))
              WordCount(state.learning, "Learning", Modifier.weight(1f))
              WordCount(state.unlearned, "Unlearned", Modifier.weight(1f))
            }
          }
          OutlinedTextField(value = state.query, onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(8.dp),
            placeholder = { Text("Hanzi, pinyin or meaning", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (state.query.isNotEmpty()) {
              { IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } }
            } else null)
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(selected = state.status == null, onClick = { onStatus(null) }, label = { Text("All") })
              WordlistStatus.entries.forEach { status ->
                FilterChip(selected = state.status == status, onClick = { onStatus(status) }, label = { Text(status.label()) })
              }
            }
            Text("Difficulty", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(selected = state.difficulties.isEmpty(), onClick = { onDifficulty(null) }, label = { Text("All") })
              (0..3).forEach { level ->
                FilterChip(selected = level in state.difficulties, onClick = { onDifficulty(level) }, label = { Text(level.toString()) })
              }
            }
          }
          Text("${state.entries.size} ${if (state.entries.size == 1) "word" else "words"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          state.error?.let { message ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(message, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
              TextButton(onClick = onRetry) { Text("Try again") }
            }
          }
          if (state.loading) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
private fun WordCount(count: Int, label: String, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(count.toString(), style = MaterialTheme.typography.titleLarge)
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun WordlistRow(entry: WordlistItem, onClick: () -> Unit) {
  Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick)
    .padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(entry.hanzi, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(entry.pinyin, modifier = Modifier.widthIn(max = 140.dp), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text(entry.english, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(if (entry.status == WordlistStatus.LEARNING && entry.targetRounds > 0)
        "Learning · ${entry.correctRounds} / ${entry.targetRounds}" else entry.status.label(),
        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
        color = when (entry.status) {
          WordlistStatus.LEARNED -> FlashcardStyle.colors.success
          WordlistStatus.LEARNING -> MaterialTheme.colorScheme.primary
          WordlistStatus.UNLEARNED -> MaterialTheme.colorScheme.onSurfaceVariant
        })
      Text("Difficulty ${entry.difficulty}", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

private fun WordlistStatus.label(): String = when (this) {
  WordlistStatus.UNLEARNED -> "Unlearned"
  WordlistStatus.LEARNING -> "Learning"
  WordlistStatus.LEARNED -> "Learned"
}

@Preview(name = "Wordlist · light", widthDp = 360, heightDp = 900)
@Preview(name = "Wordlist · dark", widthDp = 360, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Wordlist · compact", widthDp = 320, heightDp = 900, fontScale = 1.3f)
@Preview(name = "Wordlist · compact dark", widthDp = 320, heightDp = 900, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WordlistPreview() {
  val entries = listOf(
    WordlistItem("hello", "你好", "nǐ hǎo", "hello", "hello", 0, WordlistStatus.LEARNED, 0, 0),
    WordlistItem("thanks", "谢谢", "xièxie", "thank you", "thank you", 1, WordlistStatus.LEARNING, 2, 4),
    WordlistItem("tomorrow", "明天", "míngtiān", "tomorrow", "tomorrow", 2, WordlistStatus.UNLEARNED, 0, 0),
  )
  FlashcardTheme {
    WordlistContent(WordlistUiState(loading = false, entries = entries, total = 3, learned = 1, learning = 1, unlearned = 1),
      rememberLazyListState(), onQuery = {}, onStatus = {}, onDifficulty = {}, onRetry = {}, onWord = {})
  }
}
