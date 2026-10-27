package com.example.chinese_flashcard.feature.study

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun rememberLearningSounds(): LearningSounds {
  val appContext = LocalContext.current.applicationContext
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val sounds = remember(appContext, lifecycle) { LearningSounds(appContext) }
  LaunchedEffect(sounds) { sounds.prewarm() }
  DisposableEffect(sounds, lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) sounds.stop()
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer); sounds.close() }
  }
  return sounds
}

/** completed must exclude paused/skipped sessions and cards without a reliable question. */
@Composable
internal fun StudyCompletionEffects(cardId: String?, directionGeneration: Long, completed: Boolean,
  sounds: LearningSounds, modifier: Modifier = Modifier, soundEnabled: Boolean = true, small: Boolean = false) {
  var celebrated by rememberSaveable(cardId, directionGeneration) { mutableStateOf(false) }
  var progress by remember(cardId, directionGeneration) { mutableFloatStateOf(1f) }
  val particles = remember(cardId, directionGeneration) {
    List(if (small) 10 else 24) { List(4) { Random.nextFloat() } }
  }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(cardId, directionGeneration, completed, lifecycle) {
    if (!completed || cardId == null) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      if (celebrated) return@repeatOnLifecycle
      // Claim before any suspension, including across configuration restoration.
      celebrated = true
      val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
      try {
        coroutineScope {
          if (soundEnabled) launch { sounds.play(LearningSound.COMPLETE) }
          if (scale > 0f) {
            progress = 0f
            val start = withFrameNanos { it }
            val duration = ((if (small) 600_000_000L else 1_000_000_000L) * scale.coerceAtMost(2f)).toLong().coerceAtLeast(1L)
            while (progress < 1f) {
              progress = withFrameNanos { ((it - start).toDouble() / duration).toFloat().coerceIn(0f, 1f) }
            }
          }
        }
      } finally { progress = 1f }
    }
  }
  val palette = listOf(MaterialTheme.colorScheme.primary, FlashcardStyle.colors.success,
    MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
  Canvas(modifier) {
    val currentProgress = progress
    if (currentProgress >= 1f) return@Canvas
    val fade = ((1f - currentProgress) / .35f).coerceIn(0f, 1f) * .8f
    particles.forEachIndexed { index, particle ->
      val spread = particle[0] * 2f - 1f
      val x = size.width * .5f + spread * size.width * (.08f + (if (small) .25f else .42f) * currentProgress)
      val y = size.height * .16f + currentProgress * size.height * .26f -
        sin(PI * currentProgress).toFloat() * (24f + particle[1] * (if (small) 32f else 64f)) * density
      val center = Offset(x, y)
      val color = palette[index % palette.size].copy(alpha = fade)
      if (index % 3 == 0) drawCircle(color, radius = 2.5f * density, center = center)
      else rotate(particle[2] * 360f + currentProgress * (100f + particle[3] * 120f), pivot = center) {
        drawRoundRect(color, topLeft = Offset(x - 2f * density, y - 4f * density),
          size = Size(4f * density, 8f * density), cornerRadius = CornerRadius(2f * density))
      }
    }
  }
}
