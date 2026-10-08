package com.example.chinese_flashcard.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.chinese_flashcard.core.domain.StudyGrowth

@Composable
internal fun LevelBadge(growth: StudyGrowth) {
  Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "Level ${growth.level}" },
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    for ((count, drawable) in listOf(growth.crowns to R.drawable.ic_level_crown,
        growth.suns to R.drawable.ic_level_sun, growth.moons to R.drawable.ic_level_moon,
        growth.stars to R.drawable.ic_level_star)) {
      repeat(if (count > 3) 1 else count) {
        Icon(painterResource(drawable), contentDescription = null,
          modifier = Modifier.size(18.dp), tint = Color.Unspecified)
      }
      if (count > 3) Text("×$count", style = MaterialTheme.typography.labelSmall)
    }
  }
}
