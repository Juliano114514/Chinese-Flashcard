package com.example.chinese_flashcard.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp

/** Static corner details grow with the selected stage; paths and brushes are cached by size/theme. */
@Composable
fun Modifier.flashcardBackground(): Modifier {
  val gradient = studyBackgroundBrush()
  val accent = MaterialTheme.colorScheme.primary
  val stage = FlashcardStyle.stage.rarity
  val dark = FlashcardStyle.darkTheme
  return drawWithCache {
    val span = size.minDimension
    val top = Offset(size.width * 1.03f, size.height * .13f)
    val bottom = Offset(-size.width * .04f, size.height * .88f)
    val haloRadius = span * .85f
    val halo = Brush.radialGradient(
      colors = listOf(accent.copy(alpha = if (dark) .045f else .035f), Color.Transparent),
      center = top, radius = haloRadius.coerceAtLeast(1f))
    val ornament = accent.copy(alpha = if (dark) .09f else .055f)
    val stroke = Stroke(width = .85.dp.toPx())
    val arcs = mutableListOf<Path>()
    fun arc(center: Offset, radius: Float, start: Float) {
      arcs += Path().apply {
        addArc(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius), start, 180f)
      }
    }
    if (stage >= 1) {
      arc(top, span * .52f, 90f)
      arc(bottom, span * .48f, -90f)
    }
    if (stage >= 2) {
      arc(top, span * .46f, 90f)
      arc(bottom, span * .42f, -90f)
    }
    if (stage >= 4) {
      arc(top, span * .59f, 90f)
      arc(bottom, span * .55f, -90f)
    }
    val dots = if (stage >= 2) List(8) { index ->
      if (index < 4) Offset(size.width * (.91f + index * .02f), size.height * (.28f + index * .035f))
      else Offset(size.width * (.03f + (index - 4) * .02f), size.height * (.58f + (index - 4) * .035f))
    } else emptyList()
    val diamonds = if (stage >= 3) List(8) { index ->
      val center = if (index < 4) Offset(size.width * .91f, size.height * (.06f + index * .04f))
        else Offset(size.width * .09f, size.height * (.76f + (index - 4) * .04f))
      diamond(center, 3.dp.toPx())
    } else emptyList()
    val stars = if (stage >= 4) listOf(
      star(Offset(size.width * .87f, size.height * .24f), 7.dp.toPx()),
      star(Offset(size.width * .13f, size.height * .70f), 7.dp.toPx()),
      star(Offset(size.width * .96f, size.height * .44f), 4.dp.toPx()),
      star(Offset(size.width * .04f, size.height * .52f), 4.dp.toPx()),
    ) else emptyList()
    val dotRadius = 1.dp.toPx()
    fun DrawScope.drawOrnaments() {
      arcs.forEach { drawPath(it, ornament, style = stroke) }
      dots.forEach { drawCircle(ornament, radius = dotRadius, center = it) }
      diamonds.forEach { drawPath(it, ornament, style = stroke) }
      stars.forEach { drawPath(it, ornament) }
    }
    onDrawBehind {
      drawRect(gradient)
      drawRect(halo)
      // Keep the central reading/writing area free of patterned strokes.
      clipRect(right = size.width * .18f) { drawOrnaments() }
      clipRect(left = size.width * .82f) { drawOrnaments() }
    }
  }
}

private fun diamond(center: Offset, radius: Float) = Path().apply {
  moveTo(center.x, center.y - radius)
  lineTo(center.x + radius, center.y)
  lineTo(center.x, center.y + radius)
  lineTo(center.x - radius, center.y)
  close()
}

private fun star(center: Offset, radius: Float) = Path().apply {
  val waist = radius * .20f
  moveTo(center.x, center.y - radius)
  lineTo(center.x + waist, center.y - waist)
  lineTo(center.x + radius, center.y)
  lineTo(center.x + waist, center.y + waist)
  lineTo(center.x, center.y + radius)
  lineTo(center.x - waist, center.y + waist)
  lineTo(center.x - radius, center.y)
  lineTo(center.x - waist, center.y - waist)
  close()
}
