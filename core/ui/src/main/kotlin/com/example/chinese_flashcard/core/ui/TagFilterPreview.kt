package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.WordTag
import com.example.chinese_flashcard.core.domain.WordTagGroup
import com.example.chinese_flashcard.core.domain.WordTagProgress

@Preview(name = "Tags · single · 360", device = "spec:width=360dp,height=760dp,dpi=320", showSystemUi = true)
@Preview(name = "Tags · compact · large text", device = "spec:width=320dp,height=640dp,dpi=320", fontScale = 1.3f, showSystemUi = true)
@Composable
private fun TagFilterPreview() = TagFilterPreviewContent(false, false)

@Preview(name = "Tags · multiple · dark", device = "spec:width=360dp,height=760dp,dpi=320", showSystemUi = true)
@Composable
private fun DarkTagFilterPreview() = TagFilterPreviewContent(true, true)

@Composable
private fun TagFilterPreviewContent(dark: Boolean, multiple: Boolean) {
  val tags = listOf(
    WordTag("topic_family", WordTagGroup.TOPIC, "Family & relatives", "家庭与亲属"),
    WordTag("topic_science_technology", WordTagGroup.TOPIC, "Science & technology", "科学与技术"),
    WordTag("topic_daily_actions", WordTagGroup.TOPIC, "Daily actions", "日常行为"),
    WordTag("usage_everyday", WordTagGroup.USAGE, "Everyday", "日常用语"))
  val counts = mapOf("topic_family" to WordTagProgress(35, 35),
    "topic_science_technology" to WordTagProgress(12, 103), "topic_daily_actions" to WordTagProgress(0, 0),
    "usage_everyday" to WordTagProgress(18, 79))
  AppLanguageProvider(AppLanguage.EN) {
    FlashcardTheme(darkTheme = dark) {
      FlashcardBackground {
        Scaffold(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground,
          contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) { padding ->
          Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            WordTagFilterLayout("Primary school", tags,
              if (multiple) setOf("topic_family", "usage_everyday") else emptySet(), counts,
              WordTagProgress(47, 219), multiple, {}, {}, {}) {
              Text("219 words · 47 learned")
              Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Apply filter") }
            }
          }
        }
      }
    }
  }
}
