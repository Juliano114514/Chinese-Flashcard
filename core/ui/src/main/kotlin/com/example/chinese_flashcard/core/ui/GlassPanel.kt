package com.example.chinese_flashcard.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Pass this to a Surface or Card along with glassPanel using that component's existing shape. */
@Composable
@ReadOnlyComposable
fun glassPanelColor(): Color = MaterialTheme.colorScheme.surface.copy(
  alpha = if (FlashcardStyle.darkTheme) .86f else .70f)

/** A translucent panel treatment that leaves measurement, touch targets, and content sharpness intact. */
@Composable
fun Modifier.glassPanel(shape: Shape = MaterialTheme.shapes.large): Modifier {
  val dark = FlashcardStyle.darkTheme
  val accent = MaterialTheme.colorScheme.primary
  val highlight = Color.White.copy(alpha = if (dark) .06f else .55f)
  val edge = Color.White.copy(alpha = if (dark) .13f else .70f)
  val panelEffect = remember(shape, dark, accent) { Modifier.shadow(3.dp, shape = shape, clip = false,
    ambientColor = Color.Black.copy(alpha = if (dark) .12f else .035f),
    spotColor = Color.Black.copy(alpha = if (dark) .18f else .07f))
    .drawWithCache {
      val outline = shape.createOutline(size, layoutDirection, this)
      val glow = Brush.linearGradient(listOf(highlight, Color.Transparent, accent.copy(alpha = .025f)),
        start = Offset.Zero, end = Offset(size.width, size.height))
      val rim = Brush.verticalGradient(listOf(edge, accent.copy(alpha = if (dark) .12f else .09f)))
      val stroke = Stroke(width = 1.dp.toPx())
      onDrawWithContent {
        // The highlight sits behind the translucent Surface; only its rim is drawn above content.
        drawOutline(outline, brush = glow)
        drawContent()
        drawOutline(outline, brush = rim, style = stroke)
      }
    }
  }
  return then(panelEffect)
}
