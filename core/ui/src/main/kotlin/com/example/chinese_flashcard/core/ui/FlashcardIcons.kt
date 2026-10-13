package com.example.chinese_flashcard.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Original 24 px icons. Matching editable SVG masters are in assets/icons. */
object FlashcardIcons {
  val Play: ImageVector by lazy {
    icon("Play", "M8.53 5.69C7.63 5.16 6.5 5.8 6.5 6.84V17.16C6.5 18.2 7.63 18.84 8.53 18.31L17.38 13.15C18.25 12.64 18.25 11.36 17.38 10.85L8.53 5.69Z", filled = true)
  }
  val Speaker: ImageVector by lazy {
    icon("Speaker", "M4.5 9H7.5L11.5 5.5V18.5L7.5 15H4.5C3.67 15 3 14.33 3 13.5V10.5C3 9.67 3.67 9 4.5 9ZM15 8.5C16 9.35 16.5 10.5 16.5 12C16.5 13.5 16 14.65 15 15.5M18 5.5C20 7.1 21 9.3 21 12C21 14.7 20 16.9 18 18.5")
  }
  val Check: ImageVector by lazy { icon("Check", "M5 12.5L9.5 17L19 7") }
  val Close: ImageVector by lazy { icon("Close", "M6 6L18 18M18 6L6 18") }
  val Complete: ImageVector by lazy {
    icon("Complete", "M12 3L14.4 9.6L21 12L14.4 14.4L12 21L9.6 14.4L3 12L9.6 9.6L12 3Z")
  }

  private fun icon(name: String, path: String, filled: Boolean = false): ImageVector =
    ImageVector.Builder(name = "Flashcard$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
      viewportWidth = 24f, viewportHeight = 24f)
      .addPath(pathData = addPathNodes(path), fill = if (filled) SolidColor(Color.Black) else null,
        stroke = if (filled) null else SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
      .build()
}
