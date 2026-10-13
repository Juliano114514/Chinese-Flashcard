package com.example.chinese_flashcard.core.ui

import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

private val LocalBackgroundDrawn = staticCompositionLocalOf { false }

/** Navigation destinations share one stationary decoration, including during transitions. */
@Composable
fun FlashcardBackground(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().flashcardBackground()) {
    CompositionLocalProvider(LocalBackgroundDrawn provides true, content = content)
  }
}

/** Stage-tinted background. Only the cached decorative layer is blurred, never the content. */
@Composable
fun Modifier.flashcardBackground(): Modifier {
  if (LocalBackgroundDrawn.current) return this
  val accent = MaterialTheme.colorScheme.primary
  val tint = accent.copy(alpha = if (FlashcardStyle.darkTheme) .07f else .09f)
  val decorationModifier = remember(tint) { Modifier.drawWithCache {
    val top = Offset(size.width * .12f, size.height * .14f)
    val bottom = Offset(size.width * .92f, size.height * .80f)
    val radius = size.minDimension * .72f
    val topGlow = Brush.radialGradient(listOf(tint, Color.Transparent), top, radius.coerceAtLeast(1f))
    val bottomGlow = Brush.radialGradient(listOf(tint, Color.Transparent), bottom, radius.coerceAtLeast(1f))
    val decoration = if (Build.VERSION.SDK_INT >= 31) BackgroundBlurApi31.record(
      size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1),
      top, bottom, radius.coerceAtLeast(1f), 48.dp.toPx(), tint.toArgb()) else null
    onDrawWithContent {
      drawIntoCanvas { canvas ->
        if (Build.VERSION.SDK_INT >= 31 && canvas.nativeCanvas.isHardwareAccelerated && decoration != null) {
          canvas.nativeCanvas.drawRenderNode(decoration)
        } else {
          // Older Android and software previews keep the same soft gradient, without a blur pass.
          drawRect(topGlow)
          drawRect(bottomGlow)
        }
      }
      drawContent()
    }
  } }
  return background(studyBackgroundBrush()).then(decorationModifier)
}

/** Recorded only when background size, density, or palette changes; no screenshot capture. */
@RequiresApi(31)
private object BackgroundBlurApi31 {
  fun record(width: Int, height: Int, top: Offset, bottom: Offset, radius: Float,
    blurRadius: Float, tint: Int): RenderNode {
    val node = RenderNode("Flashcard background glow")
    node.setPosition(0, 0, width, height)
    val canvas = node.beginRecording(width, height)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    listOf(top, bottom).forEach { center ->
      paint.shader = RadialGradient(center.x, center.y, radius,
        tint, android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
      canvas.drawCircle(center.x, center.y, radius, paint)
    }
    node.endRecording()
    node.setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.DECAL))
    return node
  }
}
