package com.example.chinese_flashcard.feature.writing

import com.example.chinese_flashcard.core.ui.uiPlural
import com.example.chinese_flashcard.core.domain.UiMessageKey
import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.StrokeMatcher
import com.example.chinese_flashcard.core.domain.StrokePoint
import com.example.chinese_flashcard.core.domain.TracingItem
import com.example.chinese_flashcard.core.domain.Meaning
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.WordPart
import com.example.chinese_flashcard.core.domain.StrokeName
import com.example.chinese_flashcard.core.domain.WritingReason
import com.example.chinese_flashcard.core.domain.WritingSnapshot
import com.example.chinese_flashcard.core.domain.WritingStatus
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardToolbar
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.StageThemePreviewCase
import com.example.chinese_flashcard.core.ui.StageThemePreviewProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

@Composable
fun WritingScreen(vm: WritingViewModel, onBack: () -> Unit, onFinished: () -> Unit,
  onSpeakStroke: suspend (StrokeName) -> Boolean, onStopSpeech: () -> Unit,
  allowSkip: Boolean = false) {
  val state by vm.state.collectAsStateWithLifecycle()
  val snapshot = state.snapshot
  val latestSpeakStroke by rememberUpdatedState(onSpeakStroke)
  val latestStopSpeech by rememberUpdatedState(onStopSpeech)
  var spokenCue by rememberSaveable(snapshot?.id) { mutableStateOf<Long?>(null) }
  val owner = LocalLifecycleOwner.current
  DisposableEffect(owner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) latestStopSpeech()
    }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer); latestStopSpeech() }
  }
  LaunchedEffect(state.strokeCue?.sequence) {
    state.strokeCue?.let { cue ->
      if (spokenCue != cue.sequence) {
        spokenCue = cue.sequence
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) latestSpeakStroke(cue.name)
      }
    }
  }
  val latestFinished by rememberUpdatedState(onFinished)
  LaunchedEffect(snapshot?.id, snapshot?.status) {
    if (snapshot?.status == WritingStatus.SKIPPED) latestFinished()
  }
  BackHandler(enabled = state.busy) { /* Finish the Room write before leaving. */ }
  Column(Modifier.fillMaxSize().flashcardBackground()
    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    FlashcardToolbar(uiString(UiR.string.ui_writing), onBack, enabled = !state.busy)
    when {
      state.loading -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      state.error != null -> WritingError(state.error!!, uiString(UiR.string.ui_try_again)) { vm.onAction(WritingAction.Retry) }
      snapshot != null && snapshot.status == WritingStatus.COMPLETED -> {
        Spacer(Modifier.height(24.dp))
        Text(uiString(UiR.string.ui_writing_complete), style = MaterialTheme.typography.headlineMedium)
        Text(uiPlural(UiR.plurals.ui_words_practised_quantity, snapshot.totalWords, snapshot.totalWords),
          color = FlashcardStyle.colors.gradientSecondaryInk)
        Button(onClick = onFinished, shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth()) { Text(uiString(UiR.string.ui_done)) }
      }
      snapshot != null && snapshot.status == WritingStatus.ACTIVE -> {
        ActiveWriting(snapshot, state, vm::onAction, onSpeakStroke, onStopSpeech)
        if (state.saveError != null) WritingError(state.saveError!!, uiString(UiR.string.ui_retry_save)) { vm.onAction(WritingAction.RetrySave) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (allowSkip || snapshot.reason == WritingReason.FIRST_ENCOUNTER || snapshot.reason == WritingReason.REVIEW_ERROR) {
          TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.onAction(WritingAction.Skip) },
            colors = ButtonDefaults.textButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
            enabled = !state.busy && state.saveError == null, modifier = Modifier.fillMaxWidth()) {
            Text(uiString(UiR.string.ui_skip_writing))
          }
        } else {
          TextButton(shape = MaterialTheme.shapes.small, onClick = onBack, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = FlashcardStyle.colors.gradientAction)) {
            Text(uiString(UiR.string.ui_continue_later))
          }
        }
      }
    }
  }
}

@Composable
private fun ActiveWriting(snapshot: WritingSnapshot, state: WritingUiState,
  onAction: (WritingAction) -> Unit, onSpeakStroke: suspend (StrokeName) -> Boolean, onStopSpeech: () -> Unit) {
  val item = snapshot.item
  val glyphs = remember(snapshot.word.hanzi) {
    snapshot.word.hanzi.codePoints().toArray().map { String(Character.toChars(it)) }
  }
  val key = "${snapshot.id}/${snapshot.wordIndex}/${snapshot.characterIndex}/${item?.revision}"
  val latestSpeak by rememberUpdatedState(onSpeakStroke)
  val scope = rememberCoroutineScope()
  var narrationJob by remember { mutableStateOf<Job?>(null) }
  var playing by rememberSaveable(key) { mutableStateOf(false) }
  val frame = rememberSaveable(key) { mutableFloatStateOf(0f) }
  var guideShown by rememberSaveable(key) { mutableStateOf(false) }
  var guideRun by remember(key) { mutableStateOf(0) }
  var shownStroke by rememberSaveable(key) { mutableStateOf(snapshot.accepted.size) }
  val owner = LocalLifecycleOwner.current
  var resumed by remember(owner) {
    mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
  }
  DisposableEffect(owner, key) {
    val observer = LifecycleEventObserver { _, event ->
      resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
      if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
        playing = false
        narrationJob?.cancel()
      }
    }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer); playing = false; narrationJob?.cancel() }
  }
  LaunchedEffect(playing, key, resumed, guideRun) {
    if (playing && item != null && resumed) {
      val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
      while (frame.floatValue < item.paths.size) {
        val index = floor(frame.floatValue).toInt()
        shownStroke = index
        val end = index + 1f
        if (scale <= 0f) frame.floatValue = end
        else {
          var previous = withFrameNanos { it }
          while (frame.floatValue < end) {
            val now = withFrameNanos { it }
            frame.floatValue = (frame.floatValue + (now - previous) / (960_000_000f * scale)).coerceAtMost(end)
            previous = now
          }
        }
        item.strokeNames.getOrNull(index)?.let { latestSpeak(it) }
        delay(180)
      }
      playing = false
    }
  }
  Text(uiString(UiR.string.ui_writing_word_character, uiString(UiR.string.ui_fraction, snapshot.wordIndex + 1, snapshot.totalWords), uiString(UiR.string.ui_fraction, snapshot.characterIndex + 1, glyphs.size)),
    style = MaterialTheme.typography.labelLarge, color = FlashcardStyle.colors.gradientSecondaryInk)
  WritingPaper(snapshot, key, guideShown, frame = { frame.floatValue },
    shownStroke = shownStroke,
    repeatEnabled = !state.busy && state.saveError == null,
    onRepeatStroke = { name ->
      playing = false; narrationJob?.cancel(); onStopSpeech()
      narrationJob = scope.launch { latestSpeak(name) }
    },
    strokeEnabled = !playing && !state.busy && state.saveError == null && snapshot.accepted.size < (item?.paths?.size ?: 0),
    onStrokeStart = { shownStroke = snapshot.accepted.size },
    onStroke = { onAction(WritingAction.Stroke(it)) })
  if (item != null && item.paths.isNotEmpty()) {
    WritingToolbar(playing = playing, canGuide = !state.busy && state.saveError == null,
      canUndo = !state.busy && state.saveError == null && snapshot.accepted.isNotEmpty(),
      onPlay = {
        narrationJob?.cancel(); onStopSpeech()
        guideShown = true
        if (frame.floatValue >= item.paths.size) frame.floatValue = 0f
        playing = !playing
      }, onNext = {
        narrationJob?.cancel(); onStopSpeech()
        guideShown = true
        playing = false
        shownStroke = floor(frame.floatValue).toInt().coerceAtMost(item.paths.lastIndex)
        frame.floatValue = (floor(frame.floatValue) + 1f).coerceAtMost(item.paths.size.toFloat())
        item.strokeNames.getOrNull(shownStroke)?.let { name -> narrationJob = scope.launch { latestSpeak(name) } }
      }, onReplay = {
        narrationJob?.cancel(); onStopSpeech(); guideRun++
        guideShown = true; frame.floatValue = 0f; playing = true
      }, onUndo = {
        narrationJob?.cancel(); onStopSpeech(); playing = false
        shownStroke = (snapshot.accepted.size - 1).coerceAtLeast(0); onAction(WritingAction.Undo)
      }, onRestart = {
        narrationJob?.cancel(); onStopSpeech()
        playing = false; guideShown = false; frame.floatValue = 0f; shownStroke = 0; onAction(WritingAction.Restart)
      })
    if (guideShown) StrokeGuideProgress(frame = { frame.floatValue }, total = item.paths.size)
  }
  snapshot.feedback?.let { Text(uiString(it), style = MaterialTheme.typography.bodyMedium) }
  Text(uiString(UiR.string.ui_follow_the_highlighted_stroke),
    style = MaterialTheme.typography.bodySmall, color = FlashcardStyle.colors.gradientSecondaryInk)
  item?.let { Text(it.attribution, style = MaterialTheme.typography.bodySmall,
    color = FlashcardStyle.colors.gradientSecondaryInk) }
}

@Composable
private fun StrokeGuideProgress(frame: () -> Float, total: Int) {
  val shown by remember(frame) { derivedStateOf { ceil(frame()).toInt() } }
  Text(uiString(UiR.string.ui_strokes_shown, uiString(UiR.string.ui_fraction, shown, total)),
    style = MaterialTheme.typography.bodySmall, color = FlashcardStyle.colors.gradientSecondaryInk)
}

@Composable
private fun WritingPaper(snapshot: WritingSnapshot, drawingKey: String,
  guideShown: Boolean, frame: () -> Float, shownStroke: Int, strokeEnabled: Boolean, repeatEnabled: Boolean,
  onRepeatStroke: (StrokeName) -> Unit, onStrokeStart: () -> Unit, onStroke: (List<StrokePoint>) -> Unit) {
  val colors = FlashcardStyle.colors
  val item = snapshot.item
  Surface(color = colors.writingPaper, contentColor = colors.writingInk,
    shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
      WritingWordHeader(snapshot.word, snapshot.characterIndex)
      if (item != null && item.paths.isNotEmpty()) {
        val index = shownStroke.coerceIn(item.paths.indices)
        val name = item.strokeNames.getOrNull(index)
        TextButton(onClick = { name?.let(onRepeatStroke) }, enabled = name != null && repeatEnabled,
          colors = ButtonDefaults.textButtonColors(contentColor = colors.writingActive,
            disabledContentColor = colors.writingSecondaryInk), shape = MaterialTheme.shapes.small) {
          Text(if (name != null) uiString(UiR.string.ui_writing_stroke_name, index + 1, name.chinese, name.english)
            else uiString(UiR.string.ui_writing_stroke_name_unavailable, index + 1), textAlign = TextAlign.Center)
        }
        StrokeCanvas(item, snapshot.accepted, drawingKey, guideShown, frame,
          enabled = strokeEnabled, onStrokeStart = onStrokeStart, onStroke = onStroke)
        Text(uiString(UiR.string.ui_strokes_retries, uiString(UiR.string.ui_fraction, snapshot.accepted.size, item.paths.size), snapshot.mistakes),
          color = colors.writingSecondaryInk, style = MaterialTheme.typography.bodySmall)
      } else {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
          Text(uiString(UiR.string.ui_no_stroke_data_for_this_character), color = colors.writingInk,
            textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
        }
      }
    }
  }
}

@Composable
private fun WritingWordHeader(word: WordEntry, characterIndex: Int) {
  val parts = remember(word) { writingDisplayParts(word) }
  val hasBreakdown = parts.size > 1 && parts.joinToString("") { it.hanzi } == word.hanzi
  val fontScale = LocalDensity.current.fontScale
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    Row(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth),
      horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.Top) {
      if (hasBreakdown) {
        var start = 0
        parts.forEachIndexed { index, part ->
          val end = start + part.hanzi.codePointCount(0, part.hanzi.length)
          WritingWordItem(part.hanzi, part.pinyin, part.gloss, fontScale,
            activeCharacterIndex = (characterIndex - start).takeIf { characterIndex in start until end })
          start = end
          WritingWordOperator(if (index == parts.lastIndex) "=" else "+", fontScale)
        }
      }
      WritingWordItem(word.hanzi, word.pinyin, word.meanings.firstOrNull()?.english.orEmpty(), fontScale,
        activeCharacterIndex = if (hasBreakdown) null else characterIndex)
    }
  }
}

@Composable
private fun WritingWordItem(hanzi: String, pinyin: String, meaning: String, fontScale: Float,
  activeCharacterIndex: Int?) {
  val colors = FlashcardStyle.colors
  val density = LocalDensity.current
  val textMeasurer = rememberTextMeasurer()
  val meaningStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp,
    localeList = LocaleList("en"), hyphens = Hyphens.None, lineBreak = LineBreak.Simple)
  val longestWordPixels = remember(meaning, meaningStyle, textMeasurer) {
    meaning.split(Regex("\\s+")).maxOfOrNull { token ->
      textMeasurer.measure(token, style = meaningStyle, softWrap = false, maxLines = 1).size.width
    } ?: 0
  }
  val glyphCount = hanzi.codePointCount(0, hanzi.length)
  val secondaryInk = if (glyphCount == 1 && activeCharacterIndex == 0) colors.writingActive else colors.writingSecondaryInk
  val itemWidth = maxOf((maxOf(62f, glyphCount * 32f + 12f, pinyin.length * 7.5f + 8f) * fontScale).dp,
    with(density) { longestWordPixels.toDp() } + 4.dp)
  Column(Modifier.width(itemWidth), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(pinyin, color = secondaryInk, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1,
      textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height((22f * fontScale).dp)
        .wrapContentHeight(Alignment.CenterVertically))
    Text(buildAnnotatedString {
      hanzi.codePoints().toArray().forEachIndexed { index, codePoint ->
        withStyle(SpanStyle(color = if (index == activeCharacterIndex) colors.writingActive else colors.writingInk)) {
          append(String(Character.toChars(codePoint)))
        }
      }
    }, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium,
      maxLines = 1, textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth().height((44f * fontScale).dp).wrapContentHeight(Alignment.CenterVertically))
    Text(meaning, color = secondaryInk, style = meaningStyle,
      textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
  }
}

@Composable
private fun WritingWordOperator(operator: String, fontScale: Float) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Spacer(Modifier.height((22f * fontScale).dp + 4.dp))
    Text(operator, color = FlashcardStyle.colors.writingSecondaryInk, fontSize = 24.sp, lineHeight = 40.sp,
      textAlign = TextAlign.Center, modifier = Modifier.width((16f * fontScale).dp)
        .height((44f * fontScale).dp).wrapContentHeight(Alignment.CenterVertically))
  }
}

/** Keep neutral tones from the word reading when its syllables align with the stored parts. */
private fun writingDisplayParts(word: WordEntry): List<WordPart> {
  fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
    .filterNot { it.isWhitespace() || it == '\'' || it == '’' }
  fun unaccented(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
  val pinyin = compact(word.pinyin)
  val readings = word.parts.map { compact(it.pinyin) }
  val combined = readings.joinToString("")
  if (readings.any(String::isBlank) || combined.length != pinyin.length ||
    !unaccented(combined).equals(unaccented(pinyin), ignoreCase = true)) return word.parts
  var offset = 0
  return word.parts.mapIndexed { index, part ->
    val end = offset + readings[index].length
    part.copy(pinyin = pinyin.substring(offset, end)).also { offset = end }
  }
}

@Composable
private fun WritingToolbar(playing: Boolean, canGuide: Boolean, canUndo: Boolean,
  onPlay: () -> Unit, onNext: () -> Unit, onReplay: () -> Unit, onUndo: () -> Unit, onRestart: () -> Unit) {
  Row(Modifier.fillMaxWidth()) {
    WritingTool(uiString(if (playing) UiR.string.ui_pause else UiR.string.ui_play), if (playing) WritingIcons.Pause else WritingIcons.Play,
      enabled = canGuide, modifier = Modifier.weight(1f), onClick = onPlay)
    WritingTool(uiString(UiR.string.ui_next), WritingIcons.Next, enabled = canGuide, modifier = Modifier.weight(1f), onClick = onNext)
    WritingTool(uiString(UiR.string.ui_replay), WritingIcons.Replay, enabled = canGuide, modifier = Modifier.weight(1f), onClick = onReplay)
    WritingTool(uiString(UiR.string.ui_undo), WritingIcons.Undo, enabled = canUndo, modifier = Modifier.weight(1f), onClick = onUndo)
    WritingTool(uiString(UiR.string.ui_restart), WritingIcons.Restart, enabled = canGuide, modifier = Modifier.weight(1f), onClick = onRestart)
  }
}

@Composable
private fun WritingTool(label: String, icon: ImageVector, enabled: Boolean,
  modifier: Modifier = Modifier, onClick: () -> Unit) {
  TextButton(shape = MaterialTheme.shapes.small, onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp),
    colors = ButtonDefaults.textButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Icon(icon, contentDescription = if (icon == WritingIcons.Next) uiString(UiR.string.ui_next_stroke) else label)
      Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Preview(name = "Writing", widthDp = 360, heightDp = 760)
@Composable
private fun WritingPreview(@PreviewParameter(StageThemePreviewProvider::class, limit = 1) theme: StageThemePreviewCase) {
  val word = WordEntry("preview-ten", "十", "shí", listOf(Meaning("ten", "ten")),
    emptyList(), emptyList(), emptyList())
  val item = TracingItem("preview-ten", "十",
    paths = listOf("M240 500H784V580H240Z", "M472 760H552V160H472Z"),
    medians = listOf(listOf(StrokePoint(260f, 540f), StrokePoint(764f, 540f)),
      listOf(StrokePoint(512f, 740f), StrokePoint(512f, 180f))),
    revision = "preview", attribution = "Preview stroke guide", strokeNames = listOf(StrokeName.HENG, StrokeName.SHU))
  val snapshot = WritingSnapshot("preview", word, wordIndex = 0, totalWords = 1,
    characterIndex = 0, item = item, accepted = emptyList(), mistakes = 0,
    status = WritingStatus.ACTIVE, reason = WritingReason.MANUAL)
  FlashcardTheme(darkTheme = theme.darkTheme, stage = theme.stage) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(Modifier.fillMaxSize().flashcardBackground().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiString(UiR.string.ui_writing), style = MaterialTheme.typography.titleMedium)
        Text(uiString(UiR.string.ui_writing_word_character, uiString(UiR.string.ui_fraction, 1, 1), uiString(UiR.string.ui_fraction, 1, 1)), color = FlashcardStyle.colors.gradientSecondaryInk,
          style = MaterialTheme.typography.labelLarge)
        WritingPaper(snapshot, "preview", guideShown = false, frame = { 0f },
          shownStroke = 0, strokeEnabled = false, repeatEnabled = true, onRepeatStroke = {}, onStrokeStart = {}, onStroke = {})
        WritingToolbar(playing = false, canGuide = true, canUndo = false,
          onPlay = {}, onNext = {}, onReplay = {}, onUndo = {}, onRestart = {})
        Text(uiString(UiR.string.ui_follow_the_highlighted_stroke), style = MaterialTheme.typography.bodySmall,
          color = FlashcardStyle.colors.gradientSecondaryInk)
        TextButton(shape = MaterialTheme.shapes.small, onClick = {}, colors = ButtonDefaults.textButtonColors(contentColor = FlashcardStyle.colors.gradientAction)) {
          Text(uiString(UiR.string.ui_continue_later))
        }
      }
    }
  }
}

@Preview(name = "Word breakdown · friend", widthDp = 360)
@Composable
private fun WritingWordHeaderPreview() {
  val word = WordEntry("preview-friend", "朋友", "péngyou", listOf(Meaning("friend", "friend")),
    emptyList(), listOf(WordPart("朋", "péng", "companion"), WordPart("友", "yǒu", "friend")), emptyList())
  FlashcardTheme {
    Surface(color = FlashcardStyle.colors.writingPaper) {
      Box(Modifier.padding(horizontal = 40.dp, vertical = 16.dp)) { WritingWordHeader(word, characterIndex = 0) }
    }
  }
}

@Preview(name = "Word breakdown · repeated character", widthDp = 360)
@Composable
private fun WritingRepeatedWordPreview() {
  val word = WordEntry("preview-thanks", "谢谢", "xièxie", listOf(Meaning("thanks", "thank you")),
    emptyList(), listOf(WordPart("谢", "xiè", "thank"), WordPart("谢", "xiè", "repeated for this expression")), emptyList())
  FlashcardTheme {
    Surface(color = FlashcardStyle.colors.writingPaper) {
      Box(Modifier.padding(horizontal = 40.dp, vertical = 16.dp)) { WritingWordHeader(word, characterIndex = 1) }
    }
  }
}

@Preview(name = "Word breakdown · grouped part", widthDp = 360)
@Composable
private fun WritingGroupedWordPreview() {
  val word = WordEntry("preview-grouped-friend", "朋友", "péng you", listOf(Meaning("friend", "friend")),
    emptyList(), listOf(WordPart("朋友", "péng you", "friend")), emptyList())
  FlashcardTheme {
    Surface(color = FlashcardStyle.colors.writingPaper) {
      Box(Modifier.padding(horizontal = 40.dp, vertical = 16.dp)) { WritingWordHeader(word, characterIndex = 1) }
    }
  }
}

private object WritingIcons {
  private fun vector(name: String, path: String): ImageVector = ImageVector.Builder(
    name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f
  ).addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black)).build()
  val Play = vector("Play", "M8 5v14l11-7z")
  val Pause = vector("Pause", "M6 5h4v14H6zM14 5h4v14h-4z")
  val Next = vector("Next stroke", "M7 5v14l10-7zM18 5h2v14h-2z")
  val Replay = vector("Replay", "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z")
  val Undo = vector("Undo", "M12.5 8c-2.6 0-4.97.99-6.76 2.61L2 7v9h9l-3.84-3.84C8.55 11 10.43 10.3 12.5 10.3c3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.16 17.15 8 12.5 8z")
  val Restart = vector("Restart", "M13 3a9 9 0 0 0-9 9H1l4 4 4-4H6a7 7 0 1 1 2.05 4.95l-1.42 1.42A9 9 0 1 0 13 3z")
}

@Composable
private fun WritingError(message: UiMessageKey, button: String, onRetry: () -> Unit) {
  Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
    Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(uiString(message), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onErrorContainer)
      TextButton(shape = MaterialTheme.shapes.small, onClick = onRetry) { Text(button) }
    }
  }
}

/** The horizontal/vertical grid is a 田字格. Coordinates match the stored stroke geometry. */
@Composable
private fun StrokeCanvas(item: TracingItem, accepted: List<List<StrokePoint>>, drawingKey: String,
  guideShown: Boolean, frame: () -> Float, enabled: Boolean, onStrokeStart: () -> Unit,
  onStroke: (List<StrokePoint>) -> Unit) {
  val colors = FlashcardStyle.colors
  val gridDescription = uiString(UiR.string.ui_four_square_tracing_grid_draw_one_stroke_at_a_time)
  val paths = remember(item.id, item.revision) { item.paths.map { PathParser().parsePathString(it).toPath() } }
  val medians = remember(item.id, item.revision) { item.medians.map { StrokeMatcher.resample(it, 80) } }
  val acceptedPaths = remember(accepted) { accepted.filter { it.isNotEmpty() }.map { points ->
    Path().apply {
      moveTo(points.first().x, points.first().y)
      for (index in 1 until points.size) lineTo(points[index].x, points[index].y)
    }
  } }
  val medianPath = remember(drawingKey) { Path() }
  val drawingPath = remember(drawingKey) { Path() }
  var drawing by remember(drawingKey) { mutableStateOf(emptyList<StrokePoint>()) }
  val latestStroke by rememberUpdatedState(onStroke)
  val latestStrokeStart by rememberUpdatedState(onStrokeStart)
  Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(2.dp))
    .semantics { contentDescription = gridDescription }
    .pointerInput(drawingKey, enabled) {
      if (enabled) detectDragGestures(
        onDragStart = { offset ->
          latestStrokeStart()
          val inset = size.width * .05f
          val side = size.width * .9f
          drawing = listOf(StrokePoint((offset.x - inset) / side, (offset.y - inset) / side))
        },
        onDrag = { change, _ ->
          change.consume()
          val inset = size.width * .05f
          val side = size.width * .9f
          val point = StrokePoint((change.position.x - inset) / side, (change.position.y - inset) / side)
          if (drawing.size < 1024 && (drawing.isEmpty() || hypot(point.x - drawing.last().x, point.y - drawing.last().y) > .002f))
            drawing = drawing + point
        },
        onDragEnd = { val finished = drawing; drawing = emptyList(); latestStroke(finished) },
        onDragCancel = { drawing = emptyList() })
    }) {
    drawRect(colors.writingPaper)
    val inset = size.width * .05f
    val side = size.width * .9f
    val grid = colors.writingGrid
    drawRect(grid, topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(side, side), style = Stroke(1.dp.toPx()))
    drawLine(grid, Offset(inset + side / 2, inset), Offset(inset + side / 2, inset + side), strokeWidth = 1.dp.toPx())
    drawLine(grid, Offset(inset, inset + side / 2), Offset(inset + side, inset + side / 2), strokeWidth = 1.dp.toPx())
    withTransform({ translate(inset, inset + 900f / 1024f * side); scale(side / 1024f, -side / 1024f, Offset.Zero) }) {
      paths.forEach { drawPath(it, colors.writingGhost) }
      if (accepted.size < paths.size) drawPath(paths[accepted.size], colors.writingHighlight)
      val progress = frame()
      if (guideShown) paths.forEachIndexed { index, path ->
        when {
          progress >= index + 1 -> drawPath(path, colors.writingActive)
          progress > index -> {
            val points = medians[index]
            val count = (points.size * (progress - index)).toInt().coerceIn(1, points.size)
            medianPath.reset()
            medianPath.moveTo(points.first().x, points.first().y)
            for (point in 1 until count) medianPath.lineTo(points[point].x, points[point].y)
            clipPath(path) { drawPath(medianPath, colors.writingActive, style = Stroke(130f, cap = StrokeCap.Round)) }
          }
        }
      }
      if (accepted.size < medians.size && !guideShown) {
        val points = medians[accepted.size]
        drawCircle(colors.writingActive, 17f, Offset(points.first().x, points.first().y))
        drawCircle(colors.writingGuide, 10f, Offset(points.last().x, points.last().y))
      }
    }
    withTransform({ translate(inset, inset); scale(side, side, Offset.Zero) }) {
      acceptedPaths.forEach { drawPath(it, colors.writingActive, style = Stroke(.035f, cap = StrokeCap.Round)) }
      val points = drawing
      if (points.isNotEmpty()) {
        drawingPath.reset()
        drawingPath.moveTo(points.first().x, points.first().y)
        for (point in 1 until points.size) drawingPath.lineTo(points[point].x, points[point].y)
        drawPath(drawingPath, colors.writingActive, style = Stroke(.035f, cap = StrokeCap.Round))
      }
    }
  }
}
