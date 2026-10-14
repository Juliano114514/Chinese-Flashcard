package com.example.chinese_flashcard.core.ui

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.ChinesePronunciation
import com.example.chinese_flashcard.core.domain.SpeechScene
import com.example.chinese_flashcard.core.domain.ExplanationOptions
import com.example.chinese_flashcard.core.domain.splitEnglishMeanings

/** Presentation only: callers own playback, writing, and any long-press menu. */
@Composable
fun WordHeading(word: WordEntry, enabled: Boolean, onSpeak: () -> Unit, onLongClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null, trailingOnNewLine: Boolean = false, trailingSpacing: Dp = 4.dp,
  playBesideWord: Boolean = false, sourceId: String? = null) {
  val speakEnabled = enabled && !isSpeechPreparing(sourceId)
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(trailingSpacing)) {
      Text(word.hanzi, fontSize = 44.sp, lineHeight = 56.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f, fill = false).combinedClickable(enabled = speakEnabled, onClick = onSpeak,
          onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) uiString(UiR.string.ui_word_actions) else null))
      if (playBesideWord) WordPlayButton(speakEnabled, onSpeak, sourceId)
      if (!trailingOnNewLine) trailing?.invoke()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(word.pinyin, modifier = Modifier.weight(1f), fontSize = 18.sp, lineHeight = 28.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (!playBesideWord) WordPlayButton(speakEnabled, onSpeak, sourceId)
    }
    if (trailingOnNewLine) trailing?.invoke()
  }
}

@Composable
private fun WordPlayButton(enabled: Boolean, onSpeak: () -> Unit, sourceId: String?) {
  IconButton(onClick = onSpeak, enabled = enabled,
    modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)) {
    SpeechPlayIcon(sourceId, contentDescription = uiString(UiR.string.ui_listen_to_word), modifier = Modifier.size(20.dp),
      tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
  }
}

@Composable
fun WordMeanings(word: WordEntry, onSpeak: (SpeechRequest) -> Unit = {},
  ipaResolver: (String) -> String? = { null }, enabled: Boolean = true) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    word.meanings.forEach { meaning ->
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (meaning.partOfSpeech.isNotBlank()) Text(meaning.partOfSpeech.localizedPartOfSpeech(),
          style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.primary)
        splitEnglishMeanings(meaning.english).forEach { english ->
          val sourceId = rememberSpeechSourceId("${word.id}/${meaning.id}/$english")
          val preparing = isSpeechPreparing(sourceId)
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(english, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
              ipaResolver(english)?.takeIf { it.isNotBlank() }?.let { ipa ->
                Text(ipa, style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
            IconButton(onClick = { onSpeak(SpeechRequest(english, AppLanguage.EN,
              scene = SpeechScene.MEANING_PRONUNCIATION, counterpart = word.hanzi,
              chinesePronunciation = ChinesePronunciation(word.hanzi, word.pinyin), sourceId = sourceId)) }, enabled = enabled && !preparing,
              modifier = Modifier.size(48.dp)) {
              SpeechPlayIcon(sourceId, contentDescription = uiString(UiR.string.ui_listen_to_english_meaning),
                modifier = Modifier.size(20.dp))
            }
          }
        }
      }
    }
  }
}

@Composable
fun WordExplanation(word: WordEntry, onSpeak: (SpeechRequest) -> Unit, compact: Boolean = false,
  enabled: Boolean = true, progressive: Boolean = false) {
  val hasMeanings = word.literalExplanations.isNotEmpty() || word.figurativeExplanations.isNotEmpty()
  val sections = buildList {
    if (word.examples.isNotEmpty()) add(0)
    if (word.parts.isNotEmpty()) add(2)
  }
  var selectedSection by rememberSaveable(word.id) { mutableStateOf(sections.firstOrNull() ?: 0) }
  var selectedExample by rememberSaveable(word.id) { mutableStateOf(0) }
  val activeSection = selectedSection.takeIf { it in sections } ?: sections.firstOrNull()
  if (hasMeanings) {
    WordPanel(compact) {
      if (word.literalExplanations.isNotEmpty()) {
        PanelLabel(if (word.meanings.any { it.partOfSpeech == "idiom" }) uiString(UiR.string.ui_literal_meaning) else uiString(UiR.string.ui_meanings))
        ExplanationMeanings(word.literalExplanations)
      }
      if (word.figurativeExplanations.isNotEmpty()) {
        if (word.literalExplanations.isNotEmpty()) PanelDivider()
        PanelLabel(if (word.meanings.any { it.partOfSpeech == "idiom" }) uiString(UiR.string.ui_figurative_meaning) else uiString(UiR.string.ui_extended_use))
        ExplanationMeanings(word.figurativeExplanations)
      }
    }
  }
  if (progressive && sections.size > 1) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      sections.forEach { section ->
        FilterChip(selected = section == activeSection, enabled = enabled, onClick = { selectedSection = section },
          label = { Text(uiString(if (section == 0) UiR.string.ui_example else UiR.string.ui_word_breakdown)) })
      }
    }
  }
  if (word.examples.isNotEmpty() && (!progressive || activeSection == 0)) {
    if (progressive) {
      val activeExample = selectedExample.coerceIn(word.examples.indices)
      val exampleHeader: (@Composable () -> Unit)? = if (word.examples.size > 1) {
        {
          Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            word.examples.indices.forEach { index ->
              FilterChip(selected = index == activeExample, enabled = enabled, onClick = { selectedExample = index },
                label = { Text(uiString(UiR.string.ui_example_number, index + 1)) })
            }
          }
        }
      } else null
      WordExamplePanel(word.examples[activeExample], onSpeak, enabled, compact, header = exampleHeader)
    } else word.examples.forEach { example -> WordExamplePanel(example, onSpeak, enabled, compact) }
  }
  if (word.parts.isNotEmpty() && (!progressive || activeSection == 2)) {
    WordPanel(compact) {
      PanelLabel(uiString(UiR.string.ui_word_breakdown))
      word.parts.forEach { part ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(part.hanzi, style = MaterialTheme.typography.titleLarge)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(part.pinyin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(part.gloss, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
      word.meanings.firstOrNull()?.let { Text(uiString(UiR.string.ui_together_meaning, it.english), style = MaterialTheme.typography.bodyMedium) }
    }
  }
}

@Composable
private fun ExplanationMeanings(meanings: List<String>) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    meanings.forEachIndexed { index, meaning ->
      val number = if (index < 20) ('①'.code + index).toChar().toString() else "(${index + 1})"
      Text(if (meanings.size > 1) "$number $meaning" else meaning,
        style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
fun WordExamplePanel(example: ExampleSentence, onSpeak: (SpeechRequest) -> Unit, enabled: Boolean = true,
  compact: Boolean = false, header: (@Composable () -> Unit)? = null) {
  val options = LocalExplanationOptions.current
  var expanded by remember(example, options) { mutableStateOf(false) }
  val expandable = !options.allSelected
  val toggleLabel = uiString(if (expanded) UiR.string.ui_hide_full_example else UiR.string.ui_show_full_example)
  val panelModifier = if (expandable) Modifier.clickable(enabled = enabled, role = Role.Button,
    onClickLabel = toggleLabel) { expanded = !expanded }.semantics { stateDescription = toggleLabel } else Modifier
  WordPanel(compact, panelModifier) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      if (header == null) Text(uiString(UiR.string.ui_example), modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
      else Box(Modifier.weight(1f)) { header() }
      if (expandable) Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
    ExampleContent(example, onSpeak, enabled, if (expanded) ExplanationOptions() else options,
      splitOriginal = options.literal)
  }
}

@Composable
private fun WordPanel(compact: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = glassPanelColor(),
    contentColor = MaterialTheme.colorScheme.onSurface),
    shape = MaterialTheme.shapes.large,
    modifier = modifier.fillMaxWidth().glassPanel(shape = MaterialTheme.shapes.large)) {
    Column(Modifier.padding(if (compact) 12.dp else 14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
  }
}

@Composable
private fun ExampleContent(example: ExampleSentence, onSpeak: (SpeechRequest) -> Unit, enabled: Boolean,
  visible: ExplanationOptions, splitOriginal: Boolean) {
  val chineseSourceId = rememberSpeechSourceId(example to AppLanguage.CN)
  val englishSourceId = rememberSpeechSourceId(example to AppLanguage.EN)
  val preparingChinese = isSpeechPreparing(chineseSourceId)
  val preparingEnglish = isSpeechPreparing(englishSourceId)
  val hasOriginal = visible.original || visible.pinyin || (visible.literal && example.chunks.isNotEmpty())
  if (hasOriginal) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (visible.original) Text(exampleLine(if (splitOriginal) example.chunks.map { it.hanzi } else emptyList(), example.hanzi),
            fontSize = 20.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
          if (visible.pinyin) Text(exampleLine(if (splitOriginal) example.chunks.map { it.pinyin } else emptyList(), example.pinyin),
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (visible.literal && example.chunks.isNotEmpty()) Text(exampleLine(example.chunks.map { it.gloss }, ""),
            style = MaterialTheme.typography.bodySmall.copy(localeList = LocaleList("en"), hyphens = Hyphens.None, lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onSpeak(SpeechRequest(example.hanzi, AppLanguage.CN,
          scene = SpeechScene.EXAMPLE_PRONUNCIATION, counterpart = example.english,
          chinesePronunciation = ChinesePronunciation(example.hanzi, example.pinyin, example.chunks),
          sourceId = chineseSourceId)) }, enabled = enabled && !preparingChinese, modifier = Modifier.size(48.dp)) {
          SpeechPlayIcon(chineseSourceId, contentDescription = uiString(UiR.string.ui_listen_to_chinese_example), modifier = Modifier.size(20.dp))
        }
      }
    }
  }
  if (visible.translation) {
    if (hasOriginal) PanelDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(example.english, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(
        localeList = LocaleList("en"), hyphens = Hyphens.None, lineHeight = 26.sp))
      IconButton(onClick = { onSpeak(SpeechRequest(example.english, AppLanguage.EN,
        scene = SpeechScene.EXAMPLE_PRONUNCIATION, counterpart = example.hanzi,
        chinesePronunciation = ChinesePronunciation(example.hanzi, example.pinyin, example.chunks),
        sourceId = englishSourceId)) }, enabled = enabled && !preparingEnglish,
        modifier = Modifier.size(48.dp)) {
        SpeechPlayIcon(englishSourceId, contentDescription = uiString(UiR.string.ui_listen_to_english_example),
          modifier = Modifier.size(20.dp))
      }
    }
  }
}

@Composable
private fun exampleLine(chunks: List<String>, fallback: String): androidx.compose.ui.text.AnnotatedString {
  val separatorColor = MaterialTheme.colorScheme.outline
  return buildAnnotatedString {
    if (chunks.isEmpty()) append(fallback)
    else chunks.forEachIndexed { index, chunk ->
      if (index > 0) withStyle(SpanStyle(color = separatorColor)) { append(" / ") }
      append(chunk)
    }
  }
}

@Composable
private fun PanelDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = FlashcardStyle.opacity.divider))

@Composable
private fun PanelLabel(value: String) = Text(value, style = MaterialTheme.typography.titleSmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant)
