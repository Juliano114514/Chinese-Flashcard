package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.WordTag
import com.example.chinese_flashcard.core.domain.WordTagFilter
import com.example.chinese_flashcard.core.domain.WordTagGroup
import com.example.chinese_flashcard.core.domain.WordTagProgress
import com.example.chinese_flashcard.core.ui.R as UiR

@Composable
fun WordTag.localizedTagLabel(): String =
  if (LocalAppLanguage.current == AppLanguage.CN) labelZh else labelEn

@Composable
fun tagFilterLabel(filter: WordTagFilter, catalog: List<WordTag>): String {
  val selected = catalog.filter { it.id in filter.selectedIds }
  if (selected.isEmpty()) return uiString(if (filter.isTotal) UiR.string.ui_tag_in_total else UiR.string.ui_tag_saved_filter)
  val first = selected.first().localizedTagLabel()
  return if (filter.selectedIds.size == 1) first else uiString(UiR.string.ui_tag_selection_summary, first, filter.selectedIds.size - 1)
}

@Composable
fun WordTagGrid(catalog: List<WordTag>, selected: Set<String>, counts: Map<String, WordTagProgress>?,
  onSelectionChange: (Set<String>) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
  multiple: Boolean = false, totalProgress: WordTagProgress? = null) {
  val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 148.dp * fontScale),
    modifier = modifier,
    contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    WordTagGroup.entries.forEach { group ->
      val tags = catalog.filter { it.group == group }
      if (tags.isNotEmpty()) {
        item(key = "group-${group.name}", span = { GridItemSpan(maxLineSpan) }) {
          Text(uiString(when (group) {
            WordTagGroup.TOPIC -> UiR.string.ui_tag_group_topic
            WordTagGroup.USAGE -> UiR.string.ui_tag_group_usage
            WordTagGroup.FORM -> UiR.string.ui_tag_group_form
          }), modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (group == WordTagGroup.TOPIC) item(key = "in-total", contentType = "tag") {
          WordTagCard(uiString(UiR.string.ui_tag_in_total), "🌐", selected.isEmpty(), totalProgress,
            enabled, multiple, onClick = { onSelectionChange(emptySet()) })
        }
        items(tags, key = { it.id }, contentType = { "tag" }) { tag ->
          val checked = tag.id in selected
          WordTagCard(tag.localizedTagLabel(), tag.emoji(), checked, counts?.get(tag.id),
            enabled, multiple, onClick = {
              onSelectionChange(if (!multiple) setOf(tag.id) else if (checked) selected - tag.id else selected + tag.id)
            })
        }
      }
    }
  }
}

@Composable
private fun WordTagCard(label: String, emoji: String, checked: Boolean, progress: WordTagProgress?,
  enabled: Boolean, multiple: Boolean, onClick: () -> Unit) {
  Surface(shape = MaterialTheme.shapes.large,
    border = if (progress?.complete == true) BorderStroke(1.5.dp, FlashcardStyle.colors.success) else null,
    color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
    val selection = if (multiple) Modifier.toggleable(checked, enabled = enabled,
      role = Role.Checkbox, onValueChange = { onClick() })
    else Modifier.selectable(checked, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    Column(Modifier.fillMaxWidth().heightIn(min = 102.dp).then(selection)
      .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(emoji, modifier = Modifier.weight(1f).clearAndSetSemantics { }, style = MaterialTheme.typography.titleLarge)
        progress?.let {
          Text(uiString(UiR.string.ui_fraction, it.learned, it.total), style = MaterialTheme.typography.labelMedium,
            color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (checked) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
      }
      Text(label, style = MaterialTheme.typography.labelLarge, minLines = 2)
    }
  }
}

@Composable
fun TagSelectionModeSwitch(multiple: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
  val label = uiString(UiR.string.ui_tag_selection_mode)
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(uiString(if (multiple) UiR.string.ui_tag_multiple else UiR.string.ui_tag_single),
      style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Switch(checked = multiple, onCheckedChange = onChange, enabled = enabled,
      modifier = Modifier.heightIn(max = 36.dp).semantics { contentDescription = label })
  }
}

/** The background reaches the window edge; ancestor-consumed insets are not applied twice. */
@Composable
fun TagFilterBottomBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()
      .padding(horizontal = FlashcardLayout.pageInset, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
      content()
    }
  }
}

@Composable
fun WordTagFilterLayout(subtitle: String, catalog: List<WordTag>, selected: Set<String>,
  counts: Map<String, WordTagProgress>?, totalProgress: WordTagProgress?, multiple: Boolean,
  onSelectionChange: (Set<String>) -> Unit, onModeChange: (Boolean) -> Unit, onBack: () -> Unit,
  enabled: Boolean = true, footer: @Composable () -> Unit) {
  Column(Modifier.fillMaxSize()) {
    FlashcardToolbar(uiString(UiR.string.ui_tag_filter_title), onBack, enabled = enabled,
      modifier = Modifier.padding(horizontal = FlashcardLayout.pageInset),
      subtitle = { Text(subtitle, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant) },
      trailing = { TagSelectionModeSwitch(multiple, enabled, onModeChange) })
    WordTagGrid(catalog, selected, counts, onSelectionChange,
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = FlashcardLayout.pageInset),
      enabled = enabled, multiple = multiple, totalProgress = totalProgress)
    TagFilterBottomBar(content = footer)
  }
}

// Presentation only: stable tag IDs keep filtering independent of emoji or language.
private fun WordTag.emoji(): String = when (id) {
  "topic_family" -> "🏡"
  "topic_people" -> "👥"
  "topic_body_health" -> "🩺"
  "topic_emotions" -> "😊"
  "topic_home" -> "🏠"
  "topic_furniture" -> "🛋️"
  "topic_appliances" -> "🔌"
  "topic_food" -> "🍜"
  "topic_clothing" -> "👕"
  "topic_study" -> "🎒"
  "topic_work" -> "💼"
  "topic_money" -> "🛍️"
  "topic_travel_transport" -> "🚆"
  "topic_time_number" -> "🕒"
  "topic_space" -> "🧭"
  "topic_nature_weather" -> "🌤️"
  "topic_animals" -> "🐾"
  "topic_plants" -> "🌱"
  "topic_sport_leisure" -> "⚽"
  "topic_communication" -> "💬"
  "topic_thinking" -> "💡"
  "topic_society" -> "🏙️"
  "topic_law" -> "⚖️"
  "topic_science_technology" -> "🔬"
  "topic_history" -> "🏺"
  "topic_culture" -> "🎨"
  "topic_religion" -> "🕯️"
  "topic_geography" -> "🌍"
  "topic_daily_actions" -> "🚶"
  "topic_tools" -> "🧰"
  "topic_properties" -> "🎛️"
  "topic_actions_processes" -> "🔄"
  "topic_abstract_relations" -> "🔗"
  "topic_grammar" -> "🔤"
  "topic_objects" -> "🧺"
  "usage_everyday" -> "🗣️"
  "usage_written" -> "✍️"
  "usage_classical" -> "📜"
  "form_idiom" -> "📚"
  "form_fixed_expression" -> "🧩"
  "form_noun" -> "🏷️"
  "form_verb" -> "🏃"
  "form_adjective" -> "✨"
  "form_adverb" -> "🎚️"
  "form_number_measure" -> "🔢"
  "form_pronoun" -> "🙋"
  "form_function" -> "🪄"
  "form_sound_interjection" -> "🔊"
  "form_phrase" -> "📝"
  "form_morpheme" -> "🧱"
  "form_abbreviation" -> "🔠"
  "form_symbol" -> "🔣"
  else -> "📦"
}
