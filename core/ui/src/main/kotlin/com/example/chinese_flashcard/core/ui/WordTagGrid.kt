package com.example.chinese_flashcard.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.WordTag
import com.example.chinese_flashcard.core.domain.WordTagFilter
import com.example.chinese_flashcard.core.domain.WordTagGroup
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
fun WordTagGrid(catalog: List<WordTag>, selected: Set<String>, counts: Map<String, Int>?,
  onSelectionChange: (Set<String>) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 152.dp * fontScale),
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
        items(tags, key = { it.id }, contentType = { "tag" }) { tag ->
          val checked = tag.id in selected
          Surface(shape = MaterialTheme.shapes.large,
            color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxWidth().heightIn(min = 102.dp).toggleable(checked,
              enabled = enabled, role = Role.Checkbox, onValueChange = { value ->
                onSelectionChange(if (value) selected + tag.id else selected - tag.id)
              }).padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tag.emoji(), modifier = Modifier.weight(1f).clearAndSetSemantics { },
                  style = MaterialTheme.typography.titleLarge)
                counts?.let { tagCounts ->
                  Text((tagCounts[tag.id] ?: 0).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (checked) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
              }
              Text(tag.localizedTagLabel(), style = MaterialTheme.typography.labelLarge, minLines = 2)
            }
          }
        }
      }
    }
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
