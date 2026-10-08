package com.example.chinese_flashcard.core.ui

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.example.chinese_flashcard.core.domain.VocabularyStage

data class StageThemePreviewCase(val stage: VocabularyStage, val darkTheme: Boolean) {
  override fun toString() = "Stage ${stage.rarity} · ${if (darkTheme) "dark" else "light"}"
}

/** Full theme gallery; screen previews use limit = 1 to avoid repeating all ten themes. */
class StageThemePreviewProvider : PreviewParameterProvider<StageThemePreviewCase> {
  override val values = VocabularyStage.entries.asSequence().flatMap { stage ->
    sequenceOf(StageThemePreviewCase(stage, false), StageThemePreviewCase(stage, true))
  }
}
