package com.example.chinese_flashcard.feature.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.example.chinese_flashcard.core.ui.EditableAvatar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.StudySnapshot
import com.example.chinese_flashcard.core.domain.TodaySummary
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.StageThemePreviewCase
import com.example.chinese_flashcard.core.ui.StageThemePreviewProvider
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.domain.WordlistItem
import com.example.chinese_flashcard.core.domain.WordlistStatus

private enum class HomeAction { RESUME, CARRYOVER, REVIEW, LEARN, LEARN_MORE, COLLECTIONS, MISTAKES }

private data class HomeRow(val title: String, val subtitle: String, val trailing: String, val done: Boolean,
  val enabled: Boolean, val action: HomeAction)

@Composable
fun TodayScreen(
  vm: StudyViewModel,
  onStart: (StudyKind) -> Unit,
  onResume: () -> Unit,
  onWriting: (String) -> Unit,
  onLearnMore: () -> Unit,
  onProfile: () -> Unit,
  onWordlist: () -> Unit,
  onSpeak: (String) -> Unit,
) {
  val state by vm.state.collectAsStateWithLifecycle()
  var showLearnMoreBlocker by rememberSaveable { mutableStateOf(false) }
  WritingNavigation(state, vm, onWriting)
  LaunchedEffect(state.today?.learnMoreBlocker) {
    if (state.today?.learnMoreBlocker == null) showLearnMoreBlocker = false
  }
  TodayContent(state, onStart, onResume, onLearnMore,
    onLearnMoreBlocked = { showLearnMoreBlocker = true }, onStage = vm::selectStage,
    onRetry = vm::retry,
    onShuffle = { vm.shuffleHomeWord()?.let { onSpeak(it.hanzi) } },
    onProfile = onProfile, onWordlist = onWordlist)
  state.today?.learnMoreBlocker?.takeIf { showLearnMoreBlocker }?.let { blocker ->
    AlertDialog(onDismissRequest = { if (!state.busy) showLearnMoreBlocker = false },
      shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.surface,
      tonalElevation = 0.dp, title = { Text("Finish today's plan") }, text = {
        Text(when (blocker) {
          StudyKind.REVIEW -> "Review the due words before learning more."
          StudyKind.CARRYOVER -> "Finish the unfinished words before learning more."
          else -> "Finish the current word before learning more."
        })
      }, confirmButton = {
        TextButton(onClick = { showLearnMoreBlocker = false; onStart(blocker) }, enabled = !state.busy) {
          Text(when (blocker) {
            StudyKind.REVIEW -> "Go to review"
            StudyKind.CARRYOVER -> "Go to continue"
            else -> "Continue learning"
          })
        }
      }, dismissButton = {
        TextButton(onClick = { showLearnMoreBlocker = false }, enabled = !state.busy) { Text("Back") }
      })
  }
  if (!showLearnMoreBlocker) DailyWritingInvitation(state, vm)
}

@Composable
private fun TodayContent(
  state: StudyUiState,
  onStart: (StudyKind) -> Unit,
  onResume: () -> Unit,
  onLearnMore: () -> Unit,
  onLearnMoreBlocked: () -> Unit,
  onStage: (VocabularyStage) -> Unit,
  onRetry: () -> Unit,
  onShuffle: () -> Unit,
  onProfile: () -> Unit,
  onWordlist: () -> Unit,
) {
  val today = state.today
  val stage = today?.stageProgress?.stage ?: VocabularyStage.PRIMARY
  BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    val panelMaxHeight = maxHeight * 0.48f
    Column(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
      Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EditableAvatar(state.settings.avatarId, size = 44.dp,
          accessibilityLabel = "Open profile", onClick = onProfile)
        StagePicker(stage, !state.busy && !state.loading, onStage)
      }
      HomeWordShowcase(state = state, stage = stage, onShuffle = onShuffle,
        modifier = Modifier.weight(1f).fillMaxWidth())
      Spacer(Modifier.height(12.dp))
      HomeStudyPanel(state = state, onStart = onStart, onResume = onResume,
        onLearnMore = onLearnMore, onLearnMoreBlocked = onLearnMoreBlocked,
        onRetry = onRetry, onWordlist = onWordlist,
        modifier = Modifier.fillMaxWidth().heightIn(max = panelMaxHeight))
    }
  }
}

@Composable
private fun HomeWordShowcase(
  state: StudyUiState,
  stage: VocabularyStage,
  onShuffle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val today = state.today
  Box(modifier) {
    Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp)
      .verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp)) {
      when {
        state.loading -> LoadingNotice()
        state.homeWord != null && state.homeWord.difficulty == stage.rarity -> {
          Text(state.homeWord.pinyin, style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
          Text(state.homeWord.hanzi, style = MaterialTheme.typography.displayMedium.copy(fontSize = 48.sp),
            color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
          Text(listOf(state.homeWord.partOfSpeech, state.homeWord.english)
            .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        state.homeWordError -> Text("Couldn't load a word. Tap Shuffle to retry.", textAlign = TextAlign.Center)
        today != null && state.homeWordStage != stage -> LoadingNotice()
        today != null -> Text("No words in this stage yet", color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center)
      }
    }
    TextButton(onClick = onShuffle, enabled = !state.loading && !state.busy &&
      (state.homeWord != null || state.homeWordError), modifier = Modifier.align(Alignment.BottomEnd)) {
      Text("Shuffle")
    }
  }
}

@Composable
private fun HomeStudyPanel(
  state: StudyUiState,
  onStart: (StudyKind) -> Unit,
  onResume: () -> Unit,
  onLearnMore: () -> Unit,
  onLearnMoreBlocked: () -> Unit,
  onRetry: () -> Unit,
  onWordlist: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val today = state.today
  Surface(modifier = modifier, shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
      if (today != null) {
        val resume = state.card?.takeIf { it.phase != CardPhase.FINISHED }
        val open: (HomeAction) -> Unit = { action ->
          if (!state.busy) when (action) {
            HomeAction.RESUME -> onResume()
            HomeAction.CARRYOVER -> onStart(StudyKind.CARRYOVER)
            HomeAction.REVIEW -> onStart(StudyKind.REVIEW)
            HomeAction.LEARN -> onStart(StudyKind.NEW)
            HomeAction.LEARN_MORE -> if (today.learnMoreBlocker != null) onLearnMoreBlocked() else onLearnMore()
            HomeAction.COLLECTIONS -> onStart(StudyKind.COLLECTION)
            HomeAction.MISTAKES -> onStart(StudyKind.MISTAKES)
          }
        }
        Text("Study", style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          DailyProgress(today.stageProgress.learned, today.stageProgress.total, Modifier.weight(1f))
          TextButton(onClick = onWordlist) {
            Text("${today.stageProgress.learned} / ${today.stageProgress.total} words",
              style = MaterialTheme.typography.labelMedium)
          }
        }
        val learn = when {
          resume?.kind == StudyKind.NEW || resume?.kind == StudyKind.CARRYOVER ->
            HomeRow("Learn", "Continue word", "", false, !state.busy, HomeAction.RESUME)
          today.carryoverCompleted < today.carryoverPlanned ->
            HomeRow("Learn", "Unfinished words", "${today.carryoverCompleted} / ${today.carryoverPlanned}",
              false, !state.busy, HomeAction.CARRYOVER)
          else -> learnRow(today, state.busy)
            ?: HomeRow("Learn", "No new words", "", false, false, HomeAction.LEARN)
        }
        val review = if (resume?.kind == StudyKind.REVIEW) {
          HomeRow("Review", "Continue word", "", false, !state.busy, HomeAction.RESUME)
        } else {
          val done = today.reviewPlanned > 0 && today.reviewCompleted >= today.reviewPlanned
          HomeRow("Review", "Nothing due", if (today.reviewPlanned > 0)
            "${today.reviewCompleted} / ${today.reviewPlanned}" else "", done,
            !state.busy && today.reviewCompleted < today.reviewPlanned, HomeAction.REVIEW)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          HomeActionButton(learn, Modifier.weight(1f)) { open(learn.action) }
          HomeActionButton(review, Modifier.weight(1f)) { open(review.action) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          practiceRows(today, state.busy).forEach { row ->
            val kind = if (row.action == HomeAction.COLLECTIONS) StudyKind.COLLECTION else StudyKind.MISTAKES
            val button = if (resume?.kind == kind) row.copy(enabled = !state.busy, action = HomeAction.RESUME) else row
            HomeActionButton(button, Modifier.weight(1f), primary = false) { open(button.action) }
          }
        }
      }
      state.error?.let { ErrorNotice(it, !state.busy, onRetry) }
    }
  }
}

@Composable
private fun HomeActionButton(row: HomeRow, modifier: Modifier, primary: Boolean = true, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Surface(onClick = onClick, enabled = row.enabled, shape = MaterialTheme.shapes.medium,
    modifier = modifier.heightIn(min = 76.dp),
    color = if (primary) colors.primary.copy(alpha = if (row.enabled) 1f else 0.12f) else colors.secondaryContainer,
    contentColor = if (primary && row.enabled) colors.onPrimary else colors.onSurfaceVariant) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(if (row.done) "${row.title} ✓" else row.title, style = MaterialTheme.typography.titleMedium,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(row.trailing.ifEmpty { row.subtitle },
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun StagePicker(stage: VocabularyStage, enabled: Boolean, onStage: (VocabularyStage) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    Row(Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button) { expanded = true },
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(stage.label, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
      Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Choose learning stage",
        modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      VocabularyStage.entries.forEach { entry ->
        DropdownMenuItem(text = {
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.label, style = MaterialTheme.typography.titleMedium)
            Text("Stage ${entry.rarity}", style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }, leadingIcon = if (entry == stage) {
          { Icon(Icons.Default.Check, contentDescription = "Selected stage", modifier = Modifier.size(20.dp)) }
        } else null, onClick = { expanded = false; onStage(entry) }, enabled = enabled)
      }
    }
  }
}

private fun learnRow(today: TodaySummary, busy: Boolean): HomeRow? {
  val base = if (today.stageProgress.lap > 1) "Stage words" else "New words"
  if (today.newCompleted < today.newPlanned) {
    val extra = today.newPlanned - today.dailyGoal
    return HomeRow("Learn", if (extra > 0) "$base · $extra extra" else base,
      "${today.newCompleted} / ${today.newPlanned}", false, !busy, HomeAction.LEARN)
  }
  if (today.learnMoreBlocker != null || today.availableNewWords > 0) {
    val subtitle = when (today.learnMoreBlocker) {
      StudyKind.REVIEW -> "Finish review first"
      StudyKind.CARRYOVER -> "Finish unfinished words first"
      null -> "Next ${minOf(5, today.availableNewWords)} words"
      else -> "Finish the current word first"
    }
    val trailing = if (today.newPlanned > 0) "${today.newCompleted} / ${today.newPlanned}" else ""
    return HomeRow("Learn more", subtitle, trailing, false, !busy, HomeAction.LEARN_MORE)
  }
  if (today.newPlanned > 0) return HomeRow("Learn", base, "${today.newCompleted} / ${today.newPlanned}",
    true, false, HomeAction.LEARN)
  return null
}

private fun practiceRows(today: TodaySummary, busy: Boolean) = listOf(
  HomeRow("Collections", if (today.collectionsAvailable > 0) "Next ${minOf(5, today.collectionsAvailable)} words"
    else "None saved", today.collectionsAvailable.toString(), false,
    !busy && today.collectionsAvailable > 0, HomeAction.COLLECTIONS),
  HomeRow("Mistakes", if (today.mistakesAvailable > 0) "Next ${minOf(5, today.mistakesAvailable)} words"
    else "None to review", today.mistakesAvailable.toString(), false,
    !busy && today.mistakesAvailable > 0, HomeAction.MISTAKES),
)

@Preview(name = "Home", widthDp = 360, heightDp = 760)
@Preview(name = "Home · compact", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Composable
private fun HomePreview(@PreviewParameter(StageThemePreviewProvider::class, limit = 1) theme: StageThemePreviewCase) {
  FlashcardTheme(darkTheme = theme.darkTheme, stage = theme.stage) {
    Surface(Modifier.fillMaxSize().flashcardBackground(), color = androidx.compose.ui.graphics.Color.Transparent,
      contentColor = MaterialTheme.colorScheme.onBackground) {
      val today = PreviewToday.copy(stageProgress = PreviewToday.stageProgress.copy(stage = theme.stage))
      val word = WordlistItem(PreviewWord.id, PreviewWord.hanzi, PreviewWord.pinyin,
        PreviewWord.meanings.first().english, "", theme.stage.rarity, WordlistStatus.UNLEARNED, 0, 4,
        partOfSpeech = PreviewWord.meanings.first().partOfSpeech)
      TodayContent(StudyUiState(loading = false, snapshot = StudySnapshot(today, null),
        homeWord = word, homeWordStage = theme.stage),
        onStart = {}, onResume = {}, onLearnMore = {}, onLearnMoreBlocked = {},
        onStage = {}, onRetry = {}, onShuffle = {}, onProfile = {}, onWordlist = {})
    }
  }
}
