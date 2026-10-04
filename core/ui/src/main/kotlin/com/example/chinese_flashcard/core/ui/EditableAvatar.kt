package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun EditableAvatar(
  avatarId: String,
  size: Dp,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(MaterialTheme.shapes.small)
    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    .semantics(mergeDescendants = true) { contentDescription = "Change avatar" },
    contentAlignment = Alignment.Center) {
    Box(Modifier.size(size)) {
      Surface(Modifier.fillMaxSize(), shape = CircleShape, color = colors.surface,
        border = BorderStroke(1.5.dp, colors.primary)) {
        Box(Modifier.padding(3.5.dp).clip(CircleShape)) {
          PresetAvatar(avatarId, Modifier.fillMaxSize().clearAndSetSemantics { })
        }
      }
      Surface(Modifier.align(Alignment.BottomEnd).size(18.dp), shape = CircleShape,
        color = colors.primary, border = BorderStroke(1.5.dp, colors.surface)) {
        Box(contentAlignment = Alignment.Center) {
          Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(12.dp),
            tint = colors.onPrimary)
        }
      }
    }
  }
}

@Preview(name = "Editable avatars - light", showBackground = true)
@Composable
private fun EditableAvatarsLightPreview() {
  EditableAvatarsPreview(darkTheme = false)
}

@Preview(name = "Editable avatars - dark", showBackground = true)
@Composable
private fun EditableAvatarsDarkPreview() {
  EditableAvatarsPreview(darkTheme = true)
}

@Composable
private fun EditableAvatarsPreview(darkTheme: Boolean) {
  FlashcardTheme(darkTheme = darkTheme) {
    Surface(color = MaterialTheme.colorScheme.background) {
      Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        EditableAvatar(DEFAULT_AVATAR_ID, size = 44.dp, onClick = {})
        EditableAvatar(DEFAULT_AVATAR_ID, size = 48.dp, onClick = {})
      }
    }
  }
}
