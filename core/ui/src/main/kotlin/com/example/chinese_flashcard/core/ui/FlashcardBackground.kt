package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A quiet reading surface; stage identity stays in accents and controls. */
@Composable
fun Modifier.flashcardBackground(): Modifier = background(MaterialTheme.colorScheme.background)
