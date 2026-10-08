package com.example.chinese_flashcard.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.example.chinese_flashcard.core.domain.VocabularyStage

/** Five independent hue families, each with a light and dark reading surface. */
@Immutable
internal data class StagePalette(
  val lightAccent: Color, val darkAccent: Color,
  val lightStart: Color, val lightMiddle: Color, val lightEnd: Color,
  val darkStart: Color, val darkMiddle: Color, val darkEnd: Color,
) {
  fun scheme(base: ColorScheme, dark: Boolean): ColorScheme {
    val accent = if (dark) darkAccent else lightAccent
    val background = if (dark) Color(0xFF17181B) else Color(0xFFF7F8FA)
    val tint = lerp(background, accent, if (dark) .14f else .08f)
    val ink = if (dark) Color(0xFFEDEFF1) else Color(0xFF232529)
    val secondaryInk = if (dark) Color(0xFFBCC1C6) else Color(0xFF56595F)
    val surface = if (dark) Color(0xFF212226) else Color.White
    val container = if (dark) lerp(background, accent, .19f) else tint
    val onAccent = if (dark) background else Color.White
    return base.copy(
      primary = accent, onPrimary = onAccent,
      primaryContainer = container, onPrimaryContainer = if (dark) darkAccent else ink,
      inversePrimary = if (dark) lightAccent else darkAccent,
      secondary = if (dark) lerp(darkAccent, ink, .30f) else lerp(lightAccent, secondaryInk, .50f),
      onSecondary = onAccent,
      secondaryContainer = if (dark) lerp(surface, accent, .10f) else lerp(surface, accent, .06f),
      onSecondaryContainer = ink,
      tertiary = accent, onTertiary = onAccent,
      tertiaryContainer = container, onTertiaryContainer = if (dark) darkAccent else ink,
      background = background, onBackground = ink,
      surface = surface, onSurface = ink, surfaceTint = accent,
      surfaceVariant = if (dark) Color(0xFF2D2E33) else Color(0xFFF0F1F4),
      onSurfaceVariant = secondaryInk,
      inverseSurface = if (dark) lightMiddle else darkMiddle,
      inverseOnSurface = if (dark) Color(0xFF232529) else Color(0xFFEDEFF1),
      surfaceBright = if (dark) lerp(background, ink, .12f) else surface,
      surfaceDim = if (dark) background else lerp(surface, tint, .80f),
      surfaceContainerLowest = if (dark) lerp(background, Color.Black, .20f) else Color.White,
      surfaceContainerLow = surface,
      surfaceContainer = if (dark) Color(0xFF25262B) else Color(0xFFF2F3F5),
      surfaceContainerHigh = if (dark) Color(0xFF2A2B30) else Color(0xFFEBEDF0),
      surfaceContainerHighest = if (dark) Color(0xFF303137) else Color(0xFFE5E7EB),
      outline = if (dark) Color(0xFF959AA0) else Color(0xFF74777B),
      outlineVariant = if (dark) Color(0xFF3B3D43) else Color(0xFFE1E3E8),
    )
  }
}

internal val StagePalettes = mapOf(
  VocabularyStage.PRIMARY to StagePalette(
    Color(0xFF216647), Color(0xFF9BDBB5),
    Color(0xFFEEF7F0), Color(0xFFFAFCF8), Color(0xFFDEEFE4),
    Color(0xFF15281D), Color(0xFF101C17), Color(0xFF1B3225)),
  VocabularyStage.MIDDLE to StagePalette(
    Color(0xFF285FA8), Color(0xFFA9CFFF),
    Color(0xFFEDF4FC), Color(0xFFF8FAFD), Color(0xFFDFEBFA),
    Color(0xFF16243A), Color(0xFF121A27), Color(0xFF1C304C)),
  VocabularyStage.HIGH to StagePalette(
    Color(0xFF6742AA), Color(0xFFD2BAFF),
    Color(0xFFF3EEFB), Color(0xFFFCFAFD), Color(0xFFEADFF8),
    Color(0xFF271D3A), Color(0xFF1B1724), Color(0xFF35264B)),
  VocabularyStage.UNIVERSITY to StagePalette(
    Color(0xFF9C303F), Color(0xFFFFB5B9),
    Color(0xFFFBEFF0), Color(0xFFFFF9F8), Color(0xFFF5DFE2),
    Color(0xFF341F25), Color(0xFF21161A), Color(0xFF42282F)),
  VocabularyStage.CHINESE_STUDIES to StagePalette(
    Color(0xFF944610), Color(0xFFFFC395),
    Color(0xFFFFF2E7), Color(0xFFFFFBF5), Color(0xFFF8E3CB),
    Color(0xFF352719), Color(0xFF211B14), Color(0xFF44321F)),
)
