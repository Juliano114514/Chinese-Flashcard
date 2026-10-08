package com.example.chinese_flashcard.feature.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.example.chinese_flashcard.core.domain.DailyWordChoices
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.ui.AvatarPickerDialog
import com.example.chinese_flashcard.core.ui.EditableAvatar
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import kotlin.math.roundToInt

@Composable
fun WelcomeScreen(
  settings: StudySettings,
  saving: Boolean,
  error: String?,
  onSave: (StudySettings) -> Unit,
) {
  var step by rememberSaveable { mutableIntStateOf(0) }
  var name by rememberSaveable { mutableStateOf(settings.displayName) }
  var avatarId by rememberSaveable { mutableStateOf(settings.avatarId) }
  var choosingAvatar by rememberSaveable { mutableStateOf(false) }
  var dailyWords by rememberSaveable { mutableIntStateOf(DailyWordChoices.normalize(settings.dailyWords)) }
  var reviewDays by rememberSaveable { mutableStateOf(settings.reviewDays) }
  val focus = LocalFocusManager.current
  val validName = name.isNotBlank()
  val canSave = validName && DailyWordChoices.isAllowed(dailyWords) && reviewDays.isNotEmpty() && !saving
  val save = {
    if (canSave) {
      focus.clearFocus()
      onSave(settings.copy(displayName = name.trim(), avatarId = avatarId,
        dailyWords = dailyWords, reviewDays = reviewDays, welcomed = true))
    }
  }
  BackHandler(enabled = step > 0 || saving) {
    if (!saving) { focus.clearFocus(); step = (step - 1).coerceAtLeast(0) }
  }
  Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp, vertical = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()
        .verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        when (step) {
          0 -> WelcomeNameFields(name = name, avatarId = avatarId, enabled = !saving,
            onNameChange = { value ->
              if (value.length <= 40 && value.none { it.isISOControl() }) name = value
            }, onAvatar = { focus.clearFocus(); choosingAvatar = true },
            onNext = { if (!saving && validName) { focus.clearFocus(); step = 1 } })
          1 -> {
            Text("Words per day", style = MaterialTheme.typography.headlineMedium,
              textAlign = TextAlign.Center)
            Text(dailyWords.toString(), fontSize = 52.sp, lineHeight = 60.sp,
              fontWeight = FontWeight.SemiBold)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Slider(value = dailyWords.toFloat(), onValueChange = {
                dailyWords = DailyWordChoices.normalize(it.roundToInt())
              }, valueRange = DailyWordChoices.MIN.toFloat()..DailyWordChoices.MAX.toFloat(),
                steps = (DailyWordChoices.MAX - DailyWordChoices.MIN) / DailyWordChoices.STEP - 1, enabled = !saving,
                colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(DailyWordChoices.MIN.toString(), style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(DailyWordChoices.MAX.toString(), style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
          2 -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("When should you review?", style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center)
              Text("Days after passing a new word", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Column(Modifier.fillMaxWidth()) {
              listOf(1, 3, 5, 7, 14, 30).forEach { day ->
                val checked = day in reviewDays
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                  .toggleable(value = checked, enabled = !saving, role = Role.Checkbox,
                    onValueChange = { selected ->
                      reviewDays = if (selected) (reviewDays + day).sorted() else reviewDays - day
                    }).padding(horizontal = 12.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                  Box(Modifier.size(22.dp).background(
                    if (checked) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.extraSmall)
                    .border(1.dp, if (checked) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.Center) {
                    if (checked) Icon(Icons.Default.Check, contentDescription = null,
                      tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                  }
                  Text("$day ${if (day == 1) "day" else "days"} later",
                    style = MaterialTheme.typography.bodyLarge)
                }
              }
            }
            if (reviewDays.isEmpty()) Text("Select at least one day", color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
      error?.let { ErrorNotice(it, canSave, save) }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics {
        contentDescription = "Setup page ${step + 1} of 3"
      }) {
        repeat(3) { index ->
          Spacer(Modifier.size(6.dp).background(if (step == index) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
      }
      Button(onClick = { if (step < 2) { focus.clearFocus(); step++ } else save() },
        enabled = !saving && if (step == 0) validName else if (step == 2) canSave else true,
        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(if (saving) "Saving…" else if (step < 2) "Next" else "Let's start")
      }
    }
  }
  if (choosingAvatar) AvatarPickerDialog(selectedId = avatarId,
    onDismiss = { choosingAvatar = false }, onConfirm = { avatarId = it; choosingAvatar = false })
}

@Composable
private fun WelcomeNameFields(
  name: String,
  avatarId: String,
  enabled: Boolean,
  onNameChange: (String) -> Unit,
  onAvatar: () -> Unit,
  onNext: () -> Unit,
) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(24.dp)) {
    EditableAvatar(avatarId, size = 48.dp, enabled = enabled, onClick = onAvatar)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Your name", style = MaterialTheme.typography.titleLarge)
      OutlinedTextField(value = name, onValueChange = onNameChange,
        modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        shape = RoundedCornerShape(8.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onNext() }))
    }
  }
}

@Preview(name = "Welcome · light", widthDp = 360, heightDp = 760)
@Composable
private fun WelcomePreview() {
  FlashcardTheme {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
      contentColor = MaterialTheme.colorScheme.onBackground) {
      Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 44.dp),
        contentAlignment = Alignment.Center) {
        WelcomeNameFields(name = "Alex", avatarId = "1", enabled = true,
          onNameChange = {}, onAvatar = {}, onNext = {})
      }
    }
  }
}
