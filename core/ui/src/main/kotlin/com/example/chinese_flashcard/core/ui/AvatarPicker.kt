package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val avatarsById = avatarPresets.associateBy { it.id }

@Composable
fun PresetAvatar(avatarId: String, modifier: Modifier = Modifier) {
  val avatar = avatarsById[avatarId] ?: avatarsById.getValue(DEFAULT_AVATAR_ID)
  Image(painterResource(avatar.resourceId), contentDescription = "Avatar ${avatar.id}",
    modifier = modifier.size(40.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Fit)
}

@Composable
fun AvatarPickerDialog(
  selectedId: String,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
  saving: Boolean = false,
  error: String? = null,
) {
  var draftId by rememberSaveable { mutableStateOf(selectedId.takeIf(avatarsById::containsKey) ?: DEFAULT_AVATAR_ID) }
  AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, shape = MaterialTheme.shapes.large,
    title = { Text("Choose avatar") }, text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyVerticalGrid(columns = GridCells.Adaptive(56.dp),
          modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 360.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          items(avatarPresets, key = { it.id }) { avatar ->
            val isSelected = avatar.id == draftId
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
              .border(if (isSelected) 2.dp else 1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp))
              .clickable(enabled = !saving, role = Role.RadioButton) { draftId = avatar.id }
              .semantics { selected = isSelected }, contentAlignment = Alignment.Center) {
              PresetAvatar(avatar.id)
              if (isSelected) {
                Surface(Modifier.align(Alignment.BottomEnd).padding(2.dp),
                  shape = RoundedCornerShape(3.dp), color = MaterialTheme.colorScheme.primary) {
                  Icon(Icons.Default.Check, contentDescription = null, Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimary)
                }
              }
            }
          }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    }, confirmButton = {
      TextButton(onClick = { onConfirm(draftId) }, enabled = !saving) { Text(if (saving) "Saving…" else "Done") }
    }, dismissButton = {
      TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
    })
}
