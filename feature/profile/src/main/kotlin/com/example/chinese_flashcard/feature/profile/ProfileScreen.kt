package com.example.chinese_flashcard.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(vm: ProfileViewModel, onLicenses: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  var dailyText by remember(state.draft.dailyWords) { mutableStateOf(state.draft.dailyWords.toString()) }
  var showReviewDays by remember { mutableStateOf(false) }
  val dailyWords = dailyText.toIntOrNull()
  val validDaily = dailyWords != null && dailyWords in 1..100
  Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp)) {
    Text("YOUR STUDY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text("Profile & settings", style = MaterialTheme.typography.headlineLarge)
    if (state.loading) {
      CircularProgressIndicator(Modifier.size(28.dp))
    } else {
      state.today?.let { today ->
        Text("Wordbook progress", style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Metric(today.totalWords, "Total words")
          Metric(today.learnedWords, "Learned")
          Metric(today.remainingWords, "Not started")
        }
        Text("Today: ${today.completed} of ${today.planned} words completed", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      HorizontalDivider()
      Text("Daily plan", style = MaterialTheme.typography.titleLarge)
      OutlinedTextField(value = dailyText, onValueChange = { value ->
        if (value.length <= 3 && value.all(Char::isDigit)) {
          dailyText = value
          value.toIntOrNull()?.takeIf { it in 1..100 }?.let { vm.edit(state.draft.copy(dailyWords = it)) }
        }
      }, modifier = Modifier.fillMaxWidth(), label = { Text("New words per day") },
        supportingText = { Text("Choose 1–100. Reviews are added separately.") },
        enabled = !state.saving, singleLine = true, isError = !validDaily,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { dailyText = "10"; vm.edit(state.draft.copy(dailyWords = 10)) }, enabled = !state.saving) { Text("10 words") }
        OutlinedButton(onClick = { dailyText = "20"; vm.edit(state.draft.copy(dailyWords = 20)) }, enabled = !state.saving) { Text("20 words") }
      }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
          Text("Correct rounds", style = MaterialTheme.typography.titleMedium)
          Text("Consecutive correct answers to pass today", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { vm.edit(state.draft.copy(rounds = state.draft.rounds - 1)) },
          enabled = !state.saving && state.draft.rounds > 2) { Text("−") }
        Text(state.draft.rounds.toString(), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { vm.edit(state.draft.copy(rounds = state.draft.rounds + 1)) },
          enabled = !state.saving && state.draft.rounds < 8) { Text("+") }
      }
      Text("A wrong answer restarts the word from round 1. The first two rounds show a sentence, pinyin and translation.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedButton(onClick = { showReviewDays = true }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
        Text("Review after ${state.draft.reviewDays.joinToString(" / ")} days")
      }
      Text("Daily word goal applies tomorrow once today's plan has started. Rounds and review days apply to new learning or relearning cycles. Current cycles retain their settings.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Button(onClick = vm::save, enabled = !state.saving && validDaily && state.dirty, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.saving) "Saving…" else "Save settings")
      }
      if (state.saved) Text("Settings saved", color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium)
      HorizontalDivider()
      Text("Chinese Flashcard", style = MaterialTheme.typography.titleLarge)
      Text("Simplified Chinese · tone-marked pinyin · English explanations", color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("Offline study. Progress and handwriting are saved on this device.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      TextButton(onClick = onLicenses) { Text("Data & licenses") }
    }
    state.error?.let { error ->
      Text(error, color = MaterialTheme.colorScheme.error)
      TextButton(onClick = vm::retry, enabled = !state.saving && validDaily) { Text("Try again") }
    }
  }
  if (showReviewDays) {
    var selected by remember(state.draft.reviewDays) { mutableStateOf(state.draft.reviewDays.toSet()) }
    AlertDialog(onDismissRequest = { showReviewDays = false }, title = { Text("Review days") }, text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Review after learning a word. Choose at least one day.")
        listOf(1, 3, 7, 14, 30).forEach { day ->
          Row(Modifier.fillMaxWidth().toggleable(value = day in selected, role = Role.Checkbox,
            onValueChange = { checked -> selected = if (checked) selected + day else selected - day }),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = day in selected, onCheckedChange = null)
            Text("Day $day")
          }
        }
      }
    }, confirmButton = {
      TextButton(onClick = {
        vm.edit(state.draft.copy(reviewDays = selected.sorted()))
        showReviewDays = false
      }, enabled = selected.isNotEmpty()) { Text("Done") }
    }, dismissButton = { TextButton(onClick = { showReviewDays = false }) { Text("Cancel") } })
  }
}

@Composable
private fun Metric(count: Int, label: String) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
