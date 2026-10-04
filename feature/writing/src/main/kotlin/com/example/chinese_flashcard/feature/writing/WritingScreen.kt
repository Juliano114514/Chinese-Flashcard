package com.example.chinese_flashcard.feature.writing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import android.content.res.Configuration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.StrokeMatcher
import com.example.chinese_flashcard.core.domain.StrokePoint
import com.example.chinese_flashcard.core.domain.TracingItem
import com.example.chinese_flashcard.core.domain.Meaning
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.WritingReason
import com.example.chinese_flashcard.core.domain.WritingSnapshot
import com.example.chinese_flashcard.core.domain.WritingStatus
import com.example.chinese_flashcard.core.ui.studyBackgroundBrush
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

@Composable
fun WritingScreen(vm: WritingViewModel, onBack: () -> Unit, onFinished: () -> Unit, onSpeak: (String) -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val snapshot = state.snapshot
  val latestFinished by rememberUpdatedState(onFinished)
  LaunchedEffect(snapshot?.id, snapshot?.status) {
    if (snapshot?.status == WritingStatus.SKIPPED) latestFinished()
  }
  BackHandler(enabled = state.busy) { /* Finish the Room write before leaving. */ }
  Column(Modifier.fillMaxSize().background(studyBackgroundBrush())
    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
      Text("Writing", modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.width(48.dp))
    }
    when {
      state.loading -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      state.error != null -> WritingError(state.error!!, "Try again") { vm.onAction(WritingAction.Retry) }
      snapshot != null && snapshot.status == WritingStatus.COMPLETED -> {
        Spacer(Modifier.height(24.dp))
        Text("Writing complete", style = MaterialTheme.typography.headlineMedium)
        Text("${snapshot.totalWords} ${if (snapshot.totalWords == 1) "word" else "words"} practised.",
          color = FlashcardStyle.colors.gradientSecondaryInk)
        Button(onClick = onFinished, shape = RoundedCornerShape(8.dp),
          modifier = Modifier.fillMaxWidth()) { Text("Done") }
      }
      snapshot != null && snapshot.status == WritingStatus.ACTIVE -> {
        ActiveWriting(snapshot, state, vm::onAction, onSpeak)
        if (state.saveError != null) WritingError(state.saveError!!, "Retry save") { vm.onAction(WritingAction.RetrySave) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (snapshot.reason == WritingReason.FIRST_ENCOUNTER || snapshot.reason == WritingReason.REVIEW_ERROR) {
          TextButton(onClick = { vm.onAction(WritingAction.Skip) },
            colors = ButtonDefaults.textButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
            enabled = !state.busy && state.saveError == null, modifier = Modifier.fillMaxWidth()) {
            Text("Skip writing")
          }
        } else {
          TextButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = FlashcardStyle.colors.gradientAction)) {
            Text("Continue later")
          }
        }
      }
    }
  }
}

@Composable
private fun ActiveWriting(snapshot: WritingSnapshot, state: WritingUiState,
  onAction: (WritingAction) -> Unit, onSpeak: (String) -> Unit) {
  val item = snapshot.item
  val glyphs = remember(snapshot.word.hanzi) {
    snapshot.word.hanzi.codePoints().toArray().map { String(Character.toChars(it)) }
  }
  val currentGlyph = glyphs.getOrNull(snapshot.characterIndex)
  // Position, rather than glyph text, distinguishes repeated characters such as 谢谢.
  val positionKey = "${snapshot.id}/${snapshot.wordIndex}/${snapshot.characterIndex}"
  val key = "${snapshot.id}/${snapshot.wordIndex}/${snapshot.characterIndex}/${item?.revision}"
  val latestSpeak by rememberUpdatedState(onSpeak)
  var spokenPosition by rememberSaveable(snapshot.id) { mutableStateOf<String?>(null) }
  var playing by rememberSaveable(key) { mutableStateOf(false) }
  var frame by rememberSaveable(key) { mutableFloatStateOf(0f) }
  var guideShown by rememberSaveable(key) { mutableStateOf(false) }
  val owner = LocalLifecycleOwner.current
  var resumed by remember(owner) {
    mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
  }
  DisposableEffect(owner, key) {
    val observer = LifecycleEventObserver { _, event ->
      resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
      if (event == Lifecycle.Event.ON_STOP) playing = false
    }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer); playing = false }
  }
  LaunchedEffect(positionKey, resumed, state.busy) {
    if (resumed && !state.busy && currentGlyph != null && spokenPosition != positionKey) {
      spokenPosition = positionKey
      latestSpeak(currentGlyph)
    }
  }
  LaunchedEffect(playing, key) {
    if (playing && item != null) {
      while (playing && frame < item.paths.size) {
        delay(24)
        frame = (frame + .025f).coerceAtMost(item.paths.size.toFloat())
      }
      playing = false
    }
  }
  Text("Word ${snapshot.wordIndex + 1} / ${snapshot.totalWords}  ·  Character ${snapshot.characterIndex + 1} / ${glyphs.size}",
    style = MaterialTheme.typography.labelLarge, color = FlashcardStyle.colors.gradientSecondaryInk)
  WritingPaper(snapshot, glyphs, key, guideShown, frame,
    listenEnabled = !state.busy && currentGlyph != null,
    strokeEnabled = !playing && !state.busy && state.saveError == null && snapshot.accepted.size < (item?.paths?.size ?: 0),
    onListen = { currentGlyph?.let(onSpeak) }, onStroke = { onAction(WritingAction.Stroke(it)) })
  if (item != null && item.paths.isNotEmpty()) {
    WritingToolbar(playing = playing, canGuide = !state.busy && state.saveError == null,
      canUndo = !state.busy && state.saveError == null && snapshot.accepted.isNotEmpty(),
      onPlay = {
        guideShown = true
        if (frame >= item.paths.size) frame = 0f
        playing = !playing
      }, onNext = {
        guideShown = true
        playing = false
        frame = (floor(frame) + 1f).coerceAtMost(item.paths.size.toFloat())
      }, onReplay = { guideShown = true; frame = 0f; playing = true },
      onUndo = { playing = false; onAction(WritingAction.Undo) }, onRestart = {
        playing = false; guideShown = false; frame = 0f; onAction(WritingAction.Restart)
      })
    if (guideShown) Text("${ceil(frame).toInt()} / ${item.paths.size} strokes shown",
      style = MaterialTheme.typography.bodySmall, color = FlashcardStyle.colors.gradientSecondaryInk)
  }
  snapshot.feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
  Text("Follow the highlighted stroke.",
    style = MaterialTheme.typography.bodySmall, color = FlashcardStyle.colors.gradientSecondaryInk)
  item?.let { Text(it.attribution, style = MaterialTheme.typography.bodySmall,
    color = FlashcardStyle.colors.gradientSecondaryInk) }
}

@Composable
private fun WritingPaper(snapshot: WritingSnapshot, glyphs: List<String>, drawingKey: String,
  guideShown: Boolean, frame: Float, listenEnabled: Boolean, strokeEnabled: Boolean,
  onListen: () -> Unit, onStroke: (List<StrokePoint>) -> Unit) {
  val colors = FlashcardStyle.colors
  val item = snapshot.item
  Surface(color = colors.writingPaper, contentColor = colors.writingInk,
    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(buildAnnotatedString {
        glyphs.forEachIndexed { index, glyph ->
          withStyle(SpanStyle(color = if (index == snapshot.characterIndex) colors.writingActive else colors.writingInk)) { append(glyph) }
        }
      }, fontSize = 36.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
      Text(snapshot.word.pinyin, color = colors.writingSecondaryInk, textAlign = TextAlign.Center)
      TextButton(onClick = onListen, enabled = listenEnabled, colors = ButtonDefaults.textButtonColors(
        contentColor = colors.writingActive,
        disabledContentColor = colors.writingActive.copy(alpha = FlashcardStyle.opacity.disabledContent))) { Text("Listen") }
      if (item != null && item.paths.isNotEmpty()) {
        StrokeCanvas(item, snapshot.accepted, drawingKey, guideShown, frame,
          enabled = strokeEnabled, onStroke = onStroke)
        Text("${snapshot.accepted.size} / ${item.paths.size} strokes  ·  ${snapshot.mistakes} ${if (snapshot.mistakes == 1) "retry" else "retries"}",
          color = colors.writingSecondaryInk, style = MaterialTheme.typography.bodySmall)
      } else {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
          Text("No stroke data for this character.", color = colors.writingInk,
            textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
        }
      }
    }
  }
}

@Composable
private fun WritingToolbar(playing: Boolean, canGuide: Boolean, canUndo: Boolean,
  onPlay: () -> Unit, onNext: () -> Unit, onReplay: () -> Unit, onUndo: () -> Unit, onRestart: () -> Unit) {
  Row(Modifier.fillMaxWidth()) {
    WritingTool(if (playing) "Pause" else "Play", if (playing) WritingIcons.Pause else WritingIcons.Play,
      enabled = canGuide, modifier = Modifier.weight(1f), onClick = onPlay)
    WritingTool("Next", WritingIcons.Next, enabled = canGuide, modifier = Modifier.weight(1f), onClick = onNext)
    WritingTool("Replay", WritingIcons.Replay, enabled = canGuide, modifier = Modifier.weight(1f), onClick = onReplay)
    WritingTool("Undo", WritingIcons.Undo, enabled = canUndo, modifier = Modifier.weight(1f), onClick = onUndo)
    WritingTool("Restart", WritingIcons.Restart, enabled = canGuide, modifier = Modifier.weight(1f), onClick = onRestart)
  }
}

@Composable
private fun WritingTool(label: String, icon: ImageVector, enabled: Boolean,
  modifier: Modifier = Modifier, onClick: () -> Unit) {
  TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp),
    colors = ButtonDefaults.textButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp), shape = RoundedCornerShape(6.dp)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Icon(icon, contentDescription = if (label == "Next") "Next stroke" else label)
      Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Preview(name = "Writing · light", widthDp = 360, heightDp = 760)
@Preview(name = "Writing · dark", widthDp = 360, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Writing · compact", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Preview(name = "Writing · compact dark", widthDp = 320, heightDp = 760, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WritingPreview() {
  val word = WordEntry("preview-ten", "十", "shí", listOf(Meaning("ten", "ten")),
    emptyList(), emptyList(), "", emptyList())
  val item = TracingItem("preview-ten", "十",
    paths = listOf("M240 500H784V580H240Z", "M472 760H552V160H472Z"),
    medians = listOf(listOf(StrokePoint(260f, 540f), StrokePoint(764f, 540f)),
      listOf(StrokePoint(512f, 740f), StrokePoint(512f, 180f))),
    revision = "preview", attribution = "Preview stroke guide")
  val snapshot = WritingSnapshot("preview", word, wordIndex = 0, totalWords = 1,
    characterIndex = 0, item = item, accepted = emptyList(), mistakes = 0,
    status = WritingStatus.ACTIVE, reason = WritingReason.MANUAL)
  FlashcardTheme {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(Modifier.fillMaxSize().background(studyBackgroundBrush()).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Writing", style = MaterialTheme.typography.titleMedium)
        Text("Word 1 / 1 · Character 1 / 1", color = FlashcardStyle.colors.gradientSecondaryInk,
          style = MaterialTheme.typography.labelLarge)
        WritingPaper(snapshot, listOf("十"), "preview", guideShown = false, frame = 0f,
          listenEnabled = true, strokeEnabled = false, onListen = {}, onStroke = {})
        WritingToolbar(playing = false, canGuide = true, canUndo = false,
          onPlay = {}, onNext = {}, onReplay = {}, onUndo = {}, onRestart = {})
        Text("Follow the highlighted stroke.", style = MaterialTheme.typography.bodySmall,
          color = FlashcardStyle.colors.gradientSecondaryInk)
        TextButton(onClick = {}, colors = ButtonDefaults.textButtonColors(contentColor = FlashcardStyle.colors.gradientAction)) {
          Text("Continue later")
        }
      }
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
private fun WritingError(message: String, button: String, onRetry: () -> Unit) {
  Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
      TextButton(onClick = onRetry) { Text(button) }
    }
  }
}

/** The horizontal/vertical grid is a 田字格. Coordinates match the stored stroke geometry. */
@Composable
private fun StrokeCanvas(item: TracingItem, accepted: List<List<StrokePoint>>, drawingKey: String,
  guideShown: Boolean, frame: Float, enabled: Boolean, onStroke: (List<StrokePoint>) -> Unit) {
  val colors = FlashcardStyle.colors
  val paths = remember(item.id, item.revision) { item.paths.map { PathParser().parsePathString(it).toPath() } }
  val medians = remember(item.id, item.revision) { item.medians.map { StrokeMatcher.resample(it, 80) } }
  var drawing by remember(drawingKey) { mutableStateOf(emptyList<StrokePoint>()) }
  val latestStroke by rememberUpdatedState(onStroke)
  Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))
    .semantics { contentDescription = "Four-square tracing grid; draw one stroke at a time" }
    .pointerInput(drawingKey, enabled) {
      if (enabled) detectDragGestures(
        onDragStart = { offset ->
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
      if (guideShown) paths.forEachIndexed { index, path ->
        when {
          frame >= index + 1 -> drawPath(path, colors.writingActive)
          frame > index -> {
            val points = medians[index].take((medians[index].size * (frame - index)).toInt().coerceAtLeast(1))
            val medianPath = Path().apply {
              moveTo(points.first().x, points.first().y)
              points.drop(1).forEach { lineTo(it.x, it.y) }
            }
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
    (accepted + listOf(drawing)).filter { it.isNotEmpty() }.forEach { points ->
      val path = Path().apply {
        moveTo(inset + points.first().x * side, inset + points.first().y * side)
        points.drop(1).forEach { lineTo(inset + it.x * side, inset + it.y * side) }
      }
      drawPath(path, colors.writingActive, style = Stroke(side * .035f, cap = StrokeCap.Round))
    }
  }
}
