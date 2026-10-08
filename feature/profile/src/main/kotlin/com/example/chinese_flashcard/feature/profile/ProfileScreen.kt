package com.example.chinese_flashcard.feature.profile

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.StudyGrowth
import com.example.chinese_flashcard.core.ui.FlashcardDialog
import com.example.chinese_flashcard.core.ui.FlashcardLayout
import com.example.chinese_flashcard.core.ui.AvatarPickerDialog
import com.example.chinese_flashcard.core.ui.EditableAvatar
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.flashcardBackground

private enum class ProfileEditor { NAME, AVATAR }

@Composable
fun ProfileScreen(vm: ProfileViewModel, onSettings: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  var editor by rememberSaveable { mutableStateOf<ProfileEditor?>(null) }
  var appliedSaveRevision by rememberSaveable { mutableLongStateOf(state.savedRevision) }
  val value = state.stored
  val enabled = !state.loading && !state.saving
  fun openEditor(next: ProfileEditor) { vm.beginEdit(); editor = next }
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("Profile", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
      IconButton(onClick = onSettings, enabled = !state.saving) {
        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
      }
    }
    if (state.loading) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
      }
    } else {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        EditableAvatar(value.avatarId, size = 44.dp, enabled = enabled,
          onClick = { openEditor(ProfileEditor.AVATAR) })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(value.displayName.ifBlank { "Your name" },
            Modifier.clickable(enabled = enabled, role = Role.Button, onClick = { openEditor(ProfileEditor.NAME) }),
            style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
          LevelBadge(state.today?.growth ?: StudyGrowth())
        }
      }
      state.today?.let { today ->
        Column(verticalArrangement = Arrangement.spacedBy(FlashcardLayout.contentGap)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric(today.totalWords, "Total", Modifier.weight(1f))
            Metric(today.learnedWords, "Learned", Modifier.weight(1f))
            Metric(today.remainingWords, "Not started", Modifier.weight(1f))
          }
          Text("Today · ${today.completed} / ${today.planned}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GrowthDetails(today.growth)
      }
      if (state.error != null && editor == null) {
        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        TextButton(shape = MaterialTheme.shapes.small, onClick = vm::retry, enabled = enabled) { Text("Try again") }
      }
    }
  }
  when (editor) {
    ProfileEditor.NAME -> NameDialog(value.displayName, state.saving, state.error,
      onDismiss = ::closeEditor, onConfirm = { vm.save(ProfileUpdate(displayName = it)) })
    ProfileEditor.AVATAR -> AvatarPickerDialog(value.avatarId, onDismiss = ::closeEditor,
      onConfirm = { vm.save(ProfileUpdate(avatarId = it)) }, saving = state.saving, error = state.error)
    null -> Unit
  }
}

@Composable
private fun GrowthDetails(growth: StudyGrowth) {
  var showInfo by rememberSaveable { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Level ${growth.level}", style = MaterialTheme.typography.titleMedium)
      IconButton(onClick = { showInfo = true }) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = "Growth information",
          modifier = Modifier.size(20.dp))
      }
    }
    LinearProgressIndicator(progress = { growth.fraction }, modifier = Modifier.fillMaxWidth())
    Text("${growth.earned} / ${growth.span} days · ${growth.remaining} days to next level",
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("${growth.days} growth days", style = MaterialTheme.typography.bodyMedium)
  }
  if (showInfo) GrowthInfoDialog(growth, onDismiss = { showInfo = false })
}

@Composable
private fun GrowthInfoDialog(growth: StudyGrowth, onDismiss: () -> Unit) {
  FlashcardDialog(onDismissRequest = onDismiss,
    title = { Text("Growth information") },
    text = {
      Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          GrowthRule("Open the app daily")
          GrowthRule("Finish today's Learn")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          GrowthRule("Complete 10 words", "Learn or Collection")
          GrowthRule("Clear 20 mistakes")
        }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
          Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Words", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text("${growth.learnedCompletions % 10} / 10", style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Mistakes", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text("${growth.clearedMistakes % 20} / 20", style = MaterialTheme.typography.titleMedium)
            }
          }
        }
      }
    },
    confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("OK") } })
}

@Composable
private fun GrowthRule(title: String, detail: String? = null) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
      detail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Text("+1", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
  }
}

@Composable
private fun Metric(count: Int, label: String, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Preview(name = "Profile growth", widthDp = 360)
@Composable
private fun GrowthPreview() {
  FlashcardTheme {
    Column(Modifier.flashcardBackground().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      LevelBadge(StudyGrowth(320, 27, 41))
      GrowthDetails(StudyGrowth(320, 27, 41))
    }
  }
}

@Preview(name = "Growth information", widthDp = 360, heightDp = 640)
@Preview(name = "Growth information · compact", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Composable
private fun GrowthInfoPreview() {
  FlashcardTheme { GrowthInfoDialog(StudyGrowth(320, 27, 41), onDismiss = {}) }
}
