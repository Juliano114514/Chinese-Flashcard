package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chinese_flashcard.core.domain.VocabularyStage

private val Light = lightColorScheme(
  primary = Color(0xFF9C4C16), onPrimary = Color.White,
  primaryContainer = Color(0xFFFFDCC2), onPrimaryContainer = Color(0xFF3A1904),
  inversePrimary = Color(0xFFFFB77D),
  secondary = Color(0xFF775F4D), onSecondary = Color.White,
  secondaryContainer = Color(0xFFF6DFC9), onSecondaryContainer = Color(0xFF2A1A10),
  tertiary = Color(0xFF756322), onTertiary = Color.White,
  tertiaryContainer = Color(0xFFF3E5AB), onTertiaryContainer = Color(0xFF241A00),
  background = Color(0xFFFFF8F2), onBackground = Color(0xFF2B211A),
  surface = Color(0xFFFFFCF9), onSurface = Color(0xFF2B211A),
  surfaceVariant = Color(0xFFF0DCCC), onSurfaceVariant = Color(0xFF6F5C4C),
  surfaceTint = Color(0xFF9C4C16),
  inverseSurface = Color(0xFF382F29), inverseOnSurface = Color(0xFFFFF4EA),
  surfaceBright = Color(0xFFFFFCF9), surfaceDim = Color(0xFFF0DCCC),
  surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF4EA),
  surfaceContainer = Color(0xFFFBEDE0), surfaceContainerHigh = Color(0xFFF6E4D5),
  surfaceContainerHighest = Color(0xFFF0DCCC),
  outline = Color(0xFF8A7768), outlineVariant = Color(0xFFDDCBBD),
  error = Color(0xFFB3261E), onError = Color.White,
  errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
  scrim = Color.Black,
)
private val Dark = darkColorScheme(
  primary = Color(0xFFFFB77D), onPrimary = Color(0xFF4F2508),
  primaryContainer = Color(0xFF713710), onPrimaryContainer = Color(0xFFFFDCC2),
  inversePrimary = Color(0xFF9C4C16),
  secondary = Color(0xFFE5C2AA), onSecondary = Color(0xFF412C1F),
  secondaryContainer = Color(0xFF584333), onSecondaryContainer = Color(0xFFFFE8D6),
  tertiary = Color(0xFFD8CA91), onTertiary = Color(0xFF3D3300),
  tertiaryContainer = Color(0xFF554A11), onTertiaryContainer = Color(0xFFF3E5AB),
  background = Color(0xFF1B1612), onBackground = Color(0xFFF4E4D7),
  surface = Color(0xFF221C17), onSurface = Color(0xFFF4E4D7),
  surfaceVariant = Color(0xFF403229), onSurfaceVariant = Color(0xFFCBB6A4),
  surfaceTint = Color(0xFFFFB77D),
  inverseSurface = Color(0xFFF1E0D2), inverseOnSurface = Color(0xFF382F29),
  surfaceBright = Color(0xFF403229), surfaceDim = Color(0xFF1B1612),
  surfaceContainerLowest = Color(0xFF15100D), surfaceContainerLow = Color(0xFF241D17),
  surfaceContainer = Color(0xFF2C231C), surfaceContainerHigh = Color(0xFF352A22),
  surfaceContainerHighest = Color(0xFF403229),
  outline = Color(0xFFA38E7C), outlineVariant = Color(0xFF524338),
  error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
  errorContainer = Color(0xFF5E2923), onErrorContainer = Color(0xFFFFDAD6),
  scrim = Color.Black,
)

/** App-specific semantic colors; writing overlays include their theme-specific alpha. */
@Immutable
data class FlashcardColors(
  val success: Color,
  val onSuccess: Color,
  val successContainer: Color,
  val onSuccessContainer: Color,
  val gradientStart: Color,
  val gradientMiddle: Color,
  val gradientEnd: Color,
  val gradientAction: Color,
  val gradientSecondaryInk: Color,
  val gradientSuccess: Color,
  val gradientError: Color,
  val writingPaper: Color,
  val writingInk: Color,
  val writingSecondaryInk: Color,
  val writingGrid: Color,
  val writingGhost: Color,
  val writingActive: Color,
  val writingHighlight: Color,
  val writingGuide: Color,
)

@Immutable
data class FlashcardOpacity(
  val explanationPanel: Float,
  val choicePanel: Float,
  val divider: Float = .65f,
  val disabledContent: Float = .38f,
  val disabledContainer: Float = .12f,
)

private val LightColors = FlashcardColors(
  success = Color(0xFF2F6B43), onSuccess = Color.White,
  successContainer = Color(0xFFE3F1E6), onSuccessContainer = Color(0xFF153B21),
  gradientStart = Color(0xFFFBE2C7), gradientMiddle = Color(0xFFFFF8F2), gradientEnd = Color(0xFFEFC8A3),
  gradientAction = Color(0xFF713710), gradientSecondaryInk = Color(0xFF5A493D),
  gradientSuccess = Color(0xFF245334), gradientError = Color(0xFF8C1E17),
  writingPaper = Light.surface, writingInk = Light.onSurface, writingSecondaryInk = Light.onSurfaceVariant,
  writingGrid = Light.onSurface.copy(alpha = .16f), writingGhost = Light.onSurface.copy(alpha = .09f),
  writingActive = Color(0xFFC13737), writingHighlight = Color(0xFFC13737).copy(alpha = .17f),
  writingGuide = Color(0xFFC13737).copy(alpha = .50f),
)
private val DarkColors = FlashcardColors(
  success = Color(0xFF96D5A2), onSuccess = Color(0xFF12301A),
  successContainer = Color(0xFF183B24), onSuccessContainer = Color(0xFFC7ECCE),
  gradientStart = Color(0xFF2A1F16), gradientMiddle = Dark.background, gradientEnd = Color(0xFF3B291B),
  gradientAction = Dark.primary, gradientSecondaryInk = Dark.onSurfaceVariant,
  gradientSuccess = Color(0xFF96D5A2), gradientError = Dark.error,
  writingPaper = Dark.surfaceContainerLow, writingInk = Dark.onSurface, writingSecondaryInk = Dark.onSurfaceVariant,
  writingGrid = Dark.onSurface.copy(alpha = .22f), writingGhost = Dark.onSurface.copy(alpha = .14f),
  writingActive = Color(0xFFF17D76), writingHighlight = Color(0xFFF17D76).copy(alpha = .22f),
  writingGuide = Color(0xFFF17D76).copy(alpha = .65f),
)
private val LightOpacity = FlashcardOpacity(explanationPanel = .76f, choicePanel = .86f)
private val DarkOpacity = FlashcardOpacity(explanationPanel = .92f, choicePanel = .96f)
private val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalOpacity = staticCompositionLocalOf { LightOpacity }
private val LocalStage = staticCompositionLocalOf { VocabularyStage.PRIMARY }
private val LocalDarkTheme = staticCompositionLocalOf { false }

object FlashcardStyle {
  val stage: VocabularyStage
    @Composable @ReadOnlyComposable get() = LocalStage.current
  val darkTheme: Boolean
    @Composable @ReadOnlyComposable get() = LocalDarkTheme.current
  val colors: FlashcardColors
    @Composable @ReadOnlyComposable get() = LocalColors.current
  val opacity: FlashcardOpacity
    @Composable @ReadOnlyComposable get() = LocalOpacity.current
}
private val Type = Typography(
  displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 56.sp, lineHeight = 68.sp, fontWeight = FontWeight.Medium),
  headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
  headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
  headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
  titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
  titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
  titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
  labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
  labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
  labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
  bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
  bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
  bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp))
private val Corners = Shapes(
  extraSmall = RoundedCornerShape(4.dp),
  small = RoundedCornerShape(6.dp),
  medium = RoundedCornerShape(8.dp),
  large = RoundedCornerShape(12.dp),
  extraLarge = RoundedCornerShape(12.dp))

@Composable
fun FlashcardTheme(darkTheme: Boolean = isSystemInDarkTheme(),
  stage: VocabularyStage = VocabularyStage.PRIMARY, content: @Composable () -> Unit) {
  val palette = StagePalettes.getValue(stage)
  val scheme = remember(stage, darkTheme) { palette.scheme(if (darkTheme) Dark else Light, darkTheme) }
  val colors = remember(stage, darkTheme, scheme) {
    val base = if (darkTheme) DarkColors else LightColors
    base.copy(
      gradientStart = if (darkTheme) palette.darkStart else palette.lightStart,
      gradientMiddle = scheme.background,
      gradientEnd = if (darkTheme) palette.darkEnd else palette.lightEnd,
      gradientAction = scheme.primary,
      gradientSecondaryInk = scheme.onSurfaceVariant,
    )
  }
  CompositionLocalProvider(
    LocalColors provides colors,
    LocalOpacity provides if (darkTheme) DarkOpacity else LightOpacity,
    LocalStage provides stage,
    LocalDarkTheme provides darkTheme,
  ) {
    MaterialTheme(colorScheme = scheme,
      typography = Type, shapes = Corners, content = content)
  }
}

/** Learning surfaces share the same theme selection as all Material components. */
@Composable
fun studyBackgroundBrush(): Brush {
  val colors = FlashcardStyle.colors
  return remember(colors) {
    Brush.verticalGradient(0f to colors.gradientStart, .4f to colors.gradientMiddle, 1f to colors.gradientEnd)
  }
}

fun Throwable.flashcardMessage(): String = when (this) {
  is com.example.chinese_flashcard.core.domain.NewStudyDayException -> message.orEmpty()
  else -> "Local data could not be read or saved. Please retry. Your saved progress has not been cleared."
}
