package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
  primary = Color(0xFFB65324), onPrimary = Color.White,
  primaryContainer = Color(0xFFFFE3D1), onPrimaryContainer = Color(0xFF5B250C),
  secondary = Color(0xFF655B53), background = Color(0xFFFAF9F6), surface = Color(0xFFFAF9F6),
  surfaceContainer = Color(0xFFF0EDE8), surfaceContainerLow = Color(0xFFF5F2EE),
  onSurface = Color(0xFF222222), onSurfaceVariant = Color(0xFF6B625B),
  outline = Color(0xFF85766C), outlineVariant = Color(0xFFE1DBD4))
private val Dark = darkColorScheme(
  primary = Color(0xFFFFB68D), onPrimary = Color(0xFF56230B),
  primaryContainer = Color(0xFF72381D), onPrimaryContainer = Color(0xFFFFDBC6),
  secondary = Color(0xFFD4C6BB), background = Color(0xFF191715), surface = Color(0xFF191715),
  surfaceContainer = Color(0xFF292521), surfaceContainerLow = Color(0xFF211E1B),
  onSurface = Color(0xFFF0EAE3), onSurfaceVariant = Color(0xFFC5B9AD),
  outlineVariant = Color(0xFF4C443D))
private val Type = Typography(
  displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 56.sp, lineHeight = 68.sp, fontWeight = FontWeight.Medium),
  headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold),
  titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
  bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 26.sp),
  bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 23.sp))

@Composable
fun FlashcardTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}

fun Throwable.flashcardMessage(): String = when (this) {
  is com.example.chinese_flashcard.core.domain.NewStudyDayException -> message.orEmpty()
  else -> "Local data could not be read or saved. Please retry. Your saved progress has not been cleared."
}
