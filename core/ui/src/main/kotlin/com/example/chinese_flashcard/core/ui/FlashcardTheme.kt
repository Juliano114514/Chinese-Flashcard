package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
  primary = Color(0xFF147D6C), onPrimary = Color.White,
  primaryContainer = Color(0xFFE1F2EC), onPrimaryContainer = Color(0xFF145346),
  secondary = Color(0xFF506863), onSecondary = Color.White,
  secondaryContainer = Color(0xFFE8EFEB), onSecondaryContainer = Color(0xFF172923),
  background = Color(0xFFF6F7F6), surface = Color.White,
  surfaceContainer = Color(0xFFECF1EE), surfaceContainerLow = Color(0xFFF1F5F3),
  onSurface = Color(0xFF182321), onBackground = Color(0xFF182321), onSurfaceVariant = Color(0xFF536261),
  outline = Color(0xFF78867E), outlineVariant = Color(0xFFDDE5E0))
private val Dark = darkColorScheme(
  primary = Color(0xFF7AD8BE), onPrimary = Color(0xFF063B30),
  primaryContainer = Color(0xFF204E42), onPrimaryContainer = Color(0xFFBCF0DE),
  secondary = Color(0xFFADC8BD), onSecondary = Color(0xFF213C31),
  secondaryContainer = Color(0xFF30463B), onSecondaryContainer = Color(0xFFD8E9DF),
  background = Color(0xFF151C19), surface = Color(0xFF19221E),
  surfaceContainer = Color(0xFF26312B), surfaceContainerLow = Color(0xFF202A24),
  onSurface = Color(0xFFEDF3EF), onBackground = Color(0xFFEDF3EF), onSurfaceVariant = Color(0xFFB1C0B6),
  outline = Color(0xFF819188), outlineVariant = Color(0xFF3B4B41))
private val Type = Typography(
  displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 56.sp, lineHeight = 68.sp, fontWeight = FontWeight.Medium),
  headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
  titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
  titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
  bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 24.sp),
  bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
  bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp))
private val Corners = Shapes(
  extraSmall = RoundedCornerShape(4.dp),
  small = RoundedCornerShape(6.dp),
  medium = RoundedCornerShape(8.dp),
  large = RoundedCornerShape(12.dp),
  extraLarge = RoundedCornerShape(16.dp))

@Composable
fun FlashcardTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light,
    typography = Type, shapes = Corners, content = content)
}

/** Restrict the reference palette to learning surfaces; utility pages stay neutral. */
@Composable
fun studyBackgroundBrush(): Brush = if (isSystemInDarkTheme()) {
  Brush.verticalGradient(0f to Color(0xFF16272C), .4f to Color(0xFF1B2829), 1f to Color(0xFF23434B))
} else {
  Brush.verticalGradient(0f to Color(0xFFC5E8F1), .4f to Color(0xFFECF1EC), 1f to Color(0xFF85B2BE))
}

fun Throwable.flashcardMessage(): String = when (this) {
  is com.example.chinese_flashcard.core.domain.NewStudyDayException -> message.orEmpty()
  else -> "Local data could not be read or saved. Please retry. Your saved progress has not been cleared."
}
