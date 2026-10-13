package com.example.chinese_flashcard.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.chinese_flashcard.core.domain.ExplanationOptions

/** Shared by learning explanations and vocabulary details. */
val LocalExplanationOptions = staticCompositionLocalOf { ExplanationOptions() }
