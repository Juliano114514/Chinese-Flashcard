package com.example.chinese_flashcard.feature.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chinese_flashcard.core.domain.MAX_PROGRESS_CODE_LENGTH
import com.example.chinese_flashcard.core.ui.FlashcardDialog
import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

@Composable
internal fun ProgressTransferDialog(state: ProgressTransferUiState, vm: ProfileViewModel) {
  val context = LocalContext.current
  var clipboardError by remember { mutableStateOf(false) }
  var copied by remember { mutableStateOf(false) }
  val title = uiString(if (state.exporting) UiR.string.ui_progress_export else UiR.string.ui_progress_import)
  FlashcardDialog(onDismissRequest = vm::closeProgressTransfer,
    title = { Text(title) },
    text = {
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiString(UiR.string.ui_progress_description), style = MaterialTheme.typography.bodySmall)
        if (state.busy) CircularProgressIndicator()
        if (state.exporting && state.code.isNotEmpty()) {
          Text(uiString(UiR.string.ui_progress_code_size, state.code.length))
          Text(state.code.take(100) + if (state.code.length > 100) "…" else "",
            style = MaterialTheme.typography.bodySmall)
        } else if (!state.exporting && !state.imported) {
          OutlinedTextField(value = state.code, onValueChange = vm::editProgressCode,
            modifier = Modifier.fillMaxWidth(), label = { Text(uiString(UiR.string.ui_progress_code)) },
            minLines = 2, maxLines = 4, enabled = !state.busy)
          TextButton(enabled = !state.busy, onClick = {
            clipboardError = false
            try {
              val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              val clip = clipboard.primaryClip
              val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
              if (text == null || text.length > MAX_PROGRESS_CODE_LENGTH) clipboardError = true
              else vm.editProgressCode(text.toString())
            } catch (_: Exception) { clipboardError = true }
          }) { Text(uiString(UiR.string.ui_progress_paste)) }
        }
        state.summary?.let { summary ->
          Text(uiString(UiR.string.ui_progress_summary, summary.chinese,
            summary.mistakes, summary.matched, summary.unknown))
        }
        if (state.imported) Text(uiString(UiR.string.ui_progress_imported))
        if (copied) Text(uiString(UiR.string.ui_progress_copied))
        if (state.error || clipboardError) Text(uiString(UiR.string.ui_progress_error), color = MaterialTheme.colorScheme.error)
      }
    },
    confirmButton = {
      if (state.exporting) {
        TextButton(enabled = !state.busy && state.code.isNotEmpty(), onClick = {
          clipboardError = false
          copied = false
          try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(title, state.code))
            copied = true
          } catch (_: Exception) { clipboardError = true }
        }) { Text(uiString(UiR.string.ui_progress_copy)) }
      } else if (!state.imported) {
        TextButton(enabled = !state.busy && state.code.isNotBlank() &&
          (state.summary == null || state.summary.matched > 0),
          onClick = { if (state.summary == null) vm.previewProgressCode() else vm.mergeProgressCode() }) {
          Text(uiString(if (state.summary == null) UiR.string.ui_progress_preview else UiR.string.ui_progress_merge))
        }
      }
    },
    dismissButton = {
      TextButton(enabled = !state.busy, onClick = vm::closeProgressTransfer) { Text(uiString(UiR.string.ui_close)) }
    })
}
