package com.example.chinese_flashcard.feature.writing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.StrokeMatcher
import com.example.chinese_flashcard.core.domain.StrokePoint
import com.example.chinese_flashcard.core.domain.TracingItem
import com.example.chinese_flashcard.core.domain.WritingReason
import com.example.chinese_flashcard.core.domain.WritingSnapshot
import com.example.chinese_flashcard.core.domain.WritingStatus
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

private val Paper = Color(0xFFFAF9F5)
private val ActiveRed = Color(0xFFCF3737)

@Composable
fun WritingScreen(vm: WritingViewModel, onBack: () -> Unit, onFinished: () -> Unit, onSpeak: (String) -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val snapshot = state.snapshot
  val latestFinished by rememberUpdatedState(onFinished)
  LaunchedEffect(snapshot?.id, snapshot?.status) {
    if (snapshot?.status == WritingStatus.SKIPPED) latestFinished()
  }
  BackHandler(enabled = state.busy) { /* Finish the Room write before leaving. */ }
  Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = onBack, enabled = !state.busy) { Text("Back") }
      Text("Write it out", modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.width(60.dp))
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
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Tracing is guided practice. Your vocabulary progress is saved separately.",
          style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text("Done") }
      }
      snapshot != null && snapshot.status == WritingStatus.ACTIVE -> {
        ActiveWriting(snapshot, state, vm::onAction, onSpeak)
        if (state.saveError != null) WritingError(state.saveError!!, "Retry save") { vm.onAction(WritingAction.RetrySave) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (snapshot.reason == WritingReason.FIRST_ENCOUNTER || snapshot.reason == WritingReason.REVIEW_ERROR) {
          TextButton(onClick = { vm.onAction(WritingAction.Skip) },
            enabled = !state.busy && state.saveError == null, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now")
          }
        } else {
          TextButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Continue later")
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveWriting(snapshot: WritingSnapshot, state: WritingUiState,
  onAction: (WritingAction) -> Unit, onSpeak: (String) -> Unit) {
  val item = snapshot.item
  val glyphs = remember(snapshot.word.hanzi) {
    snapshot.word.hanzi.codePoints().toArray().map { String(Character.toChars(it)) }
  }
  val key = "${snapshot.id}/${snapshot.wordIndex}/${snapshot.characterIndex}/${item?.revision}"
  var playing by rememberSaveable(key) { mutableStateOf(false) }
  var frame by rememberSaveable(key) { mutableFloatStateOf(0f) }
  var guideShown by rememberSaveable(key) { mutableStateOf(false) }
  val owner = LocalLifecycleOwner.current
  DisposableEffect(owner, key) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) playing = false }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer); playing = false }
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
    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Surface(color = Paper, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(buildAnnotatedString {
        glyphs.forEachIndexed { index, glyph ->
          withStyle(SpanStyle(color = if (index == snapshot.characterIndex) ActiveRed else Color.Black)) { append(glyph) }
        }
      }, fontSize = 36.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
      Text(snapshot.word.pinyin, color = Color.Black.copy(alpha = .65f), textAlign = TextAlign.Center)
      TextButton(onClick = { onSpeak(snapshot.word.hanzi) }, enabled = !state.busy) { Text("Listen to the word", color = ActiveRed) }
      if (item != null && item.paths.isNotEmpty()) {
        StrokeCanvas(item, snapshot.accepted, key, guideShown, frame,
          enabled = !playing && !state.busy && state.saveError == null && snapshot.accepted.size < item.paths.size,
          onStroke = { onAction(WritingAction.Stroke(it)) })
        Text("${snapshot.accepted.size} / ${item.paths.size} strokes  ·  ${snapshot.mistakes} ${if (snapshot.mistakes == 1) "retry" else "retries"}",
          color = Color.Black.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall)
      } else {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
          Text("Stroke data is unavailable for this character.", color = Color.Black,
            textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
        }
      }
    }
  }
  if (item != null && item.paths.isNotEmpty()) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      OutlinedButton(onClick = {
        guideShown = true
        if (frame >= item.paths.size) frame = 0f
        playing = !playing
      }, enabled = !state.busy && state.saveError == null) { Text(if (playing) "Pause" else "Play strokes") }
      OutlinedButton(onClick = {
        guideShown = true
        playing = false
        frame = (floor(frame) + 1f).coerceAtMost(item.paths.size.toFloat())
      }, enabled = !state.busy && state.saveError == null) { Text("Next stroke") }
      OutlinedButton(onClick = { guideShown = true; frame = 0f; playing = true },
        enabled = !state.busy && state.saveError == null) { Text("Replay") }
    }
    if (guideShown) Text("${ceil(frame).toInt()} / ${item.paths.size} strokes shown",
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { playing = false; onAction(WritingAction.Undo) },
        enabled = !state.busy && state.saveError == null && snapshot.accepted.isNotEmpty()) { Text("Undo") }
      OutlinedButton(onClick = {
        playing = false; guideShown = false; frame = 0f; onAction(WritingAction.Restart)
      }, enabled = !state.busy && state.saveError == null) { Text("Start again") }
    }
  }
  snapshot.feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
  Text("Follow the highlighted stroke. Each character continues automatically when all strokes are saved.",
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  item?.let { Text(it.attribution, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    drawRect(Paper)
    val inset = size.width * .05f
    val side = size.width * .9f
    val grid = Color.Black.copy(alpha = .16f)
    drawRect(grid, topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(side, side), style = Stroke(1.dp.toPx()))
    drawLine(grid, Offset(inset + side / 2, inset), Offset(inset + side / 2, inset + side), strokeWidth = 1.dp.toPx())
    drawLine(grid, Offset(inset, inset + side / 2), Offset(inset + side, inset + side / 2), strokeWidth = 1.dp.toPx())
    withTransform({ translate(inset, inset + 900f / 1024f * side); scale(side / 1024f, -side / 1024f, Offset.Zero) }) {
      paths.forEach { drawPath(it, Color.Black.copy(alpha = .09f)) }
      if (accepted.size < paths.size) drawPath(paths[accepted.size], ActiveRed.copy(alpha = .17f))
      if (guideShown) paths.forEachIndexed { index, path ->
        when {
          frame >= index + 1 -> drawPath(path, ActiveRed)
          frame > index -> {
            val points = medians[index].take((medians[index].size * (frame - index)).toInt().coerceAtLeast(1))
            val medianPath = Path().apply {
              moveTo(points.first().x, points.first().y)
              points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            clipPath(path) { drawPath(medianPath, ActiveRed, style = Stroke(130f, cap = StrokeCap.Round)) }
          }
        }
      }
      if (accepted.size < medians.size && !guideShown) {
        val points = medians[accepted.size]
        drawCircle(ActiveRed, 17f, Offset(points.first().x, points.first().y))
        drawCircle(ActiveRed.copy(alpha = .5f), 10f, Offset(points.last().x, points.last().y))
      }
    }
    (accepted + listOf(drawing)).filter { it.isNotEmpty() }.forEach { points ->
      val path = Path().apply {
        moveTo(inset + points.first().x * side, inset + points.first().y * side)
        points.drop(1).forEach { lineTo(inset + it.x * side, inset + it.y * side) }
      }
      drawPath(path, ActiveRed, style = Stroke(side * .035f, cap = StrokeCap.Round))
    }
  }
}
