package com.example.chinese_flashcard.core.ui

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.example.chinese_flashcard.core.domain.VocabularyStage

data class StageThemePreviewCase(val stage: VocabularyStage, val darkTheme: Boolean) {
  override fun toString() = "Stage ${stage.rarity} · ${if (darkTheme) "dark" else "light"}"
}

/** Every stage/appearance pair is available in both regular and compact screen previews. */
class StageThemePreviewProvider : PreviewParameterProvider<StageThemePreviewCase> {
  override val values = VocabularyStage.entries.asSequence().flatMap { stage ->
    sequenceOf(StageThemePreviewCase(stage, false), StageThemePreviewCase(stage, true))
  }
}
