package com.example.chinese_flashcard.feature.study

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.localizedLabel
import com.example.chinese_flashcard.core.ui.localizedStudyGuidance
import com.example.chinese_flashcard.core.ui.localizedPartOfSpeech
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.example.chinese_flashcard.core.ui.FlashcardDialog
import com.example.chinese_flashcard.core.ui.tagFilterLabel
import com.example.chinese_flashcard.core.ui.FlashcardLayout
import com.example.chinese_flashcard.core.ui.EditableAvatar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.StudySnapshot
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.UiMessageKey
import com.example.chinese_flashcard.core.domain.TodaySummary
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.StageThemePreviewCase
import com.example.chinese_flashcard.core.ui.StageThemePreviewProvider
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.domain.WordlistItem
import com.example.chinese_flashcard.core.domain.WordlistStatus
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.ui.rememberSpeechSourceId
import com.example.chinese_flashcard.core.ui.isSpeechPreparing
import com.example.chinese_flashcard.core.ui.SpeechPreparingIndicator

private enum class HomeAction { RESUME, CARRYOVER, REVIEW, REVIEW_MORE, LEARN, LEARN_MORE, COLLECTIONS, MISTAKES }

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
  onSpeak: (SpeechRequest) -> Unit,
  onLanguages: () -> Unit = {},
  onReviewMore: () -> Unit = {},
  onTagFilter: () -> Unit = {},
) {
  val state by vm.state.collectAsStateWithLifecycle()
  val latestSpeak by androidx.compose.runtime.rememberUpdatedState(onSpeak)
  LaunchedEffect(state.homeSpeechEvent?.token) {
    state.homeSpeechEvent?.let { event ->
      vm.consumeHomeSpeech(event.token)
      if (event.request.language == vm.state.value.settings.learningLanguage && !vm.state.value.busy)
        latestSpeak(event.request)
    }
  }
  var showLearnMoreBlocker by rememberSaveable { mutableStateOf(false) }
  WritingNavigation(state, vm, onWriting)
  LaunchedEffect(state.today?.learnMoreBlocker) {
    if (state.today?.learnMoreBlocker == null) showLearnMoreBlocker = false
  }
  TodayContent(state, onStart, onResume, onLearnMore,
    onReviewMore = onReviewMore,
    onLearnMoreBlocked = { showLearnMoreBlocker = true }, onStage = vm::selectStage,
    onRetry = vm::retry, onReplay = { sourceId -> vm.state.value.homeReplayRequest(sourceId)?.let(latestSpeak) },
    onShuffle = vm::shuffleHomeWord, onProfile = onProfile, onWordlist = onWordlist, onLanguages = onLanguages,
    onTagFilter = onTagFilter)
  state.today?.learnMoreBlocker?.takeIf { showLearnMoreBlocker }?.let { blocker ->
    FlashcardDialog(onDismissRequest = { if (!state.busy) showLearnMoreBlocker = false },
      title = { Text(uiString(UiR.string.ui_finish_today_s_plan)) }, text = {
        Text(when (blocker) {
          StudyKind.REVIEW -> uiString(UiR.string.ui_review_the_due_words_before_learning_more)
          StudyKind.CARRYOVER -> uiString(UiR.string.ui_finish_the_unfinished_words_before_learning_more)
          else -> uiString(UiR.string.ui_finish_the_current_word_before_learning_more)
        })
      }, confirmButton = {
        TextButton(shape = MaterialTheme.shapes.small, onClick = { showLearnMoreBlocker = false; onStart(blocker) }, enabled = !state.busy) {
          Text(when (blocker) {
            StudyKind.REVIEW -> uiString(UiR.string.ui_go_to_review)
            StudyKind.CARRYOVER -> uiString(UiR.string.ui_go_to_continue)
            else -> uiString(UiR.string.ui_continue_learning)
          })
        }
      }, dismissButton = {
        TextButton(shape = MaterialTheme.shapes.small, onClick = { showLearnMoreBlocker = false }, enabled = !state.busy) { Text(uiString(UiR.string.ui_back)) }
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
  onReplay: (String) -> Unit,
  onShuffle: () -> Unit,
  onProfile: () -> Unit,
  onWordlist: () -> Unit,
  onLanguages: () -> Unit = {},
  onReviewMore: () -> Unit = {},
  onTagFilter: () -> Unit = {},
) {
  val today = state.today
  val stage = today?.stageProgress?.stage ?: VocabularyStage.PRIMARY
  val replaySourceId = rememberSpeechSourceId(state.homeWord?.id to state.settings.learningLanguage)
  BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = FlashcardLayout.pageInset)) {
    val panelMaxHeight = maxHeight * 0.54f
    Column(Modifier.fillMaxSize().padding(bottom = 20.dp)) {
      Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EditableAvatar(state.settings.avatarId, size = 44.dp,
          accessibilityLabel = uiString(UiR.string.ui_open_profile), onClick = onProfile)
        StagePicker(stage, !state.busy && !state.loading, onStage, Modifier.weight(1f))
        val languageDescription = uiString(UiR.string.ui_use_1_s_learn_2_s,
          state.settings.uiLanguage.localizedLabel(), state.settings.learningLanguage.localizedLabel())
        TextButton(onClick = onLanguages, enabled = !state.busy && !state.loading,
          contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.widthIn(min = 88.dp)) {
          Text("${state.settings.uiLanguage.name} → ${state.settings.learningLanguage.name}",
            modifier = Modifier.clearAndSetSemantics { contentDescription = languageDescription },
            style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        }
      }
      HomeWordShowcase(word = state.homeWord.takeIf { state.homeWordContextVersion == state.directionGeneration },
        wordError = state.homeWordError,
        wordStage = state.homeWordStage.takeIf { state.homeWordContextVersion == state.directionGeneration },
        hasToday = today != null, loading = state.loading, busy = state.busy, stage = stage, onShuffle = onShuffle,
        onReplay = { onReplay(replaySourceId) }, replayEnabled = state.homeReplayRequest(replaySourceId) != null,
        replayPreparing = isSpeechPreparing(replaySourceId),
        shuffleWaiting = state.homeShuffleWaiting || isSpeechPreparing(HOME_SHUFFLE_SOURCE_ID),
        shuffleFailed = state.homeShuffleFailed,
        shuffleReady = state.homeShuffleReady,
        filtered = today?.tagFilter?.isTotal == false, onTagFilter = onTagFilter,
        modifier = Modifier.weight(1f).fillMaxWidth())
      Spacer(Modifier.height(16.dp))
      HomeStudyPanel(today = today, card = state.card, busy = state.busy, error = state.error,
        reviewBatchSize = state.settings.reviewBatchSize, onReviewMore = onReviewMore,
        onStart = onStart, onResume = onResume,
        onLearnMore = onLearnMore, onLearnMoreBlocked = onLearnMoreBlocked,
        onRetry = onRetry, onWordlist = onWordlist, onTagFilter = onTagFilter,
        modifier = Modifier.fillMaxWidth().heightIn(max = panelMaxHeight))
    }
  }
}

@Composable
private fun HomeWordShowcase(
  word: WordlistItem?,
  wordError: Boolean,
  wordStage: VocabularyStage?,
  hasToday: Boolean,
  loading: Boolean,
  busy: Boolean,
  stage: VocabularyStage,
  onReplay: () -> Unit,
  replayEnabled: Boolean,
  onShuffle: () -> Unit,
  replayPreparing: Boolean = false,
  shuffleWaiting: Boolean = false,
  shuffleFailed: Boolean = false,
  shuffleReady: Int = 0,
  filtered: Boolean = false,
  onTagFilter: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Box(modifier) {
    Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp)
      .verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
      when {
        loading -> LoadingNotice()
        word != null && word.difficulty == stage.rarity -> {
          Text(word.pinyin, style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
          Text(word.hanzi, style = MaterialTheme.typography.displayMedium.copy(fontSize = 48.sp),
            color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
          Text(listOf(word.partOfSpeech.localizedPartOfSpeech(), word.english)
            .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        wordError -> Text(uiString(UiR.string.ui_couldn_t_load_a_word_tap_shuffle_to_retry), textAlign = TextAlign.Center)
        hasToday && wordStage != stage -> LoadingNotice()
        hasToday -> {
          Text(uiString(if (filtered) UiR.string.ui_tag_no_matching_words else UiR.string.ui_no_words_in_this_stage_yet),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
          if (filtered) TextButton(onClick = onTagFilter, enabled = !busy) {
            Text(uiString(UiR.string.ui_tag_edit_filter))
          }
        }
      }
      if (shuffleFailed) Text(uiString(UiR.string.ui_speech_could_not_be_played_you_can_continue_studying),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center)
    }
    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(shape = MaterialTheme.shapes.small, onClick = onReplay,
        enabled = replayEnabled && !shuffleWaiting && !replayPreparing,
        modifier = Modifier.alignByBaseline().widthIn(min = 88.dp).heightIn(min = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          if (replayPreparing) SpeechPreparingIndicator()
          Text(uiString(UiR.string.ui_replay))
        }
      }
      TextButton(shape = MaterialTheme.shapes.small, onClick = onShuffle, enabled = !loading && !busy && !shuffleWaiting &&
        (word != null || wordError), modifier = Modifier.alignByBaseline().widthIn(min = 88.dp).heightIn(min = 48.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (shuffleWaiting) SpeechPreparingIndicator()
            Text(uiString(UiR.string.ui_shuffle))
          }
          Text(uiString(UiR.string.ui_shuffle_ready, shuffleReady),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

private fun StudyUiState.homeReplayRequest(sourceId: String): SpeechRequest? {
  val stage = today?.stageProgress?.stage ?: return null
  val word = homeWord?.takeIf { it.difficulty == stage.rarity } ?: return null
  if (loading || busy || homeShuffleWaiting || homeWordStage != stage ||
    homeWordContextVersion != directionGeneration || today?.tagFilter?.matches(word.tags, today?.tagCatalog.orEmpty()) != true) return null
  val request = word.homeSpeechRequest(settings.learningLanguage, sourceId)
  return request.takeIf { settings.speechOptions.requests(it).isNotEmpty() }
}

@Composable
private fun HomeStudyPanel(
  today: TodaySummary?,
  card: StudyCard?,
  busy: Boolean,
  error: UiMessageKey?,
  reviewBatchSize: Int,
  onStart: (StudyKind) -> Unit,
  onResume: () -> Unit,
  onLearnMore: () -> Unit,
  onReviewMore: () -> Unit,
  onLearnMoreBlocked: () -> Unit,
  onRetry: () -> Unit,
  onWordlist: () -> Unit,
  onTagFilter: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
      .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 12.dp)) {
      if (today != null) {
        val resume = card?.takeIf { it.phase != CardPhase.FINISHED }
        val open: (HomeAction) -> Unit = { action ->
          if (!busy) when (action) {
            HomeAction.RESUME -> onResume()
            HomeAction.CARRYOVER -> onStart(StudyKind.CARRYOVER)
            HomeAction.REVIEW -> onStart(StudyKind.REVIEW)
            HomeAction.REVIEW_MORE -> onReviewMore()
            HomeAction.LEARN -> onStart(StudyKind.NEW)
            HomeAction.LEARN_MORE -> if (today.learnMoreBlocker != null) onLearnMoreBlocked() else onLearnMore()
            HomeAction.COLLECTIONS -> onStart(StudyKind.COLLECTION)
            HomeAction.MISTAKES -> onStart(StudyKind.MISTAKES)
          }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(uiString(UiR.string.ui_study), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
          TextButton(onClick = onTagFilter, enabled = !busy,
            contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.widthIn(max = 210.dp)) {
            Text(tagFilterLabel(today.tagFilter, today.tagCatalog), maxLines = 1,
              overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
              style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = uiString(UiR.string.ui_tag_filter_title),
              modifier = Modifier.size(20.dp))
          }
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          HomeLabeledProgress(uiString(UiR.string.ui_today), today.dailyLearningCompleted, today.dailyGoal)
          HomeLabeledProgress(uiString(UiR.string.ui_review), today.reviewCompleted, today.reviewPlanned)
          HomeLabeledProgress(uiString(UiR.string.ui_words), today.stageProgress.learned, today.stageProgress.total, onWordlist)
        }
        val learn = when {
          today.dailyGoalReached && !today.learningExtra -> learnRow(today, busy)
            ?: HomeRow(uiString(UiR.string.ui_learn_2118cb), uiString(UiR.string.ui_daily_goal_complete), "", true, false, HomeAction.LEARN)
          resume?.kind == StudyKind.NEW || resume?.kind == StudyKind.CARRYOVER ->
            HomeRow(uiString(UiR.string.ui_learn_2118cb), uiString(UiR.string.ui_continue_word), "", false, !busy, HomeAction.RESUME)
          today.carryoverCompleted < today.carryoverPlanned ->
            HomeRow(uiString(UiR.string.ui_learn_2118cb), uiString(UiR.string.ui_unfinished_words), uiString(UiR.string.ui_fraction, today.carryoverCompleted, today.carryoverPlanned),
              false, !busy, HomeAction.CARRYOVER)
          else -> learnRow(today, busy)
            ?: HomeRow(uiString(UiR.string.ui_learn_2118cb), uiString(UiR.string.ui_no_new_words), "", false, false, HomeAction.LEARN)
        }
        val review = when {
          resume?.kind == StudyKind.REVIEW ->
            HomeRow(uiString(UiR.string.ui_review), uiString(UiR.string.ui_continue_word), "", false, !busy, HomeAction.RESUME)
          today.canReviewMore ->
            HomeRow(uiString(UiR.string.ui_review),
              uiString(UiR.string.ui_review_more_count, minOf(reviewBatchSize, today.availableReviewWords)),
              "", false, !busy, HomeAction.REVIEW_MORE)
          else -> {
            val done = today.reviewPlanned > 0 && today.reviewCompleted >= today.reviewPlanned
            HomeRow(uiString(UiR.string.ui_review), uiString(UiR.string.ui_nothing_due), if (today.reviewPlanned > 0)
              uiString(UiR.string.ui_fraction, today.reviewCompleted, today.reviewPlanned) else "", done,
              !busy && today.reviewCompleted < today.reviewPlanned, HomeAction.REVIEW)
          }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          HomeActionButton(learn, Modifier.weight(1f)) { open(learn.action) }
          HomeActionButton(review, Modifier.weight(1f)) { open(review.action) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          practiceRows(today, busy).forEach { row ->
            val kind = if (row.action == HomeAction.COLLECTIONS) StudyKind.COLLECTION else StudyKind.MISTAKES
            val button = if (resume?.kind == kind) row.copy(enabled = !busy, action = HomeAction.RESUME) else row
            HomeActionButton(button, Modifier.weight(1f), primary = false) { open(button.action) }
          }
        }
      }
      error?.let {
        if (today != null) Spacer(Modifier.height(12.dp))
        ErrorNotice(it, !busy, onRetry)
      }
    }
  }
}

@Composable
private fun HomeLabeledProgress(label: String, completed: Int, total: Int, onClick: (() -> Unit)? = null) {
  Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(uiString(UiR.string.ui_fraction, completed, total), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DailyProgress(completed, total)
  }
}

@Composable
private fun HomeActionButton(row: HomeRow, modifier: Modifier, primary: Boolean = true, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Surface(onClick = onClick, enabled = row.enabled, shape = MaterialTheme.shapes.large,
    modifier = modifier.heightIn(min = 72.dp),
    color = if (primary) colors.primary.copy(alpha = if (row.enabled) 1f else 0.12f) else colors.primaryContainer,
    contentColor = if (primary && row.enabled) colors.onPrimary else colors.onSurfaceVariant) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(if (row.done) "${row.title} ✓" else row.title, style = MaterialTheme.typography.titleMedium,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(row.trailing.ifEmpty { row.subtitle },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun StagePicker(stage: VocabularyStage, enabled: Boolean, onStage: (VocabularyStage) -> Unit,
  modifier: Modifier = Modifier) {
  var expanded by remember { mutableStateOf(false) }
  Box(modifier) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button) { expanded = true },
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(stage.localizedLabel(), modifier = Modifier.weight(1f, fill = false).widthIn(max = 120.dp),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
      Icon(Icons.Default.KeyboardArrowDown, contentDescription = uiString(UiR.string.ui_choose_learning_stage),
        modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
      modifier = Modifier.widthIn(min = 260.dp), shape = MaterialTheme.shapes.medium,
      containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 4.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
      VocabularyStage.entries.forEach { entry ->
        DropdownMenuItem(text = {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(entry.localizedLabel(), style = MaterialTheme.typography.bodyMedium)
              Text(entry.localizedStudyGuidance(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(uiString(UiR.string.ui_stage_number, entry.rarity), style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }, leadingIcon = {
          if (entry == stage) Icon(Icons.Default.Check, contentDescription = uiString(UiR.string.ui_selected_stage),
            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
          else Spacer(Modifier.size(20.dp))
        }, contentPadding = PaddingValues(horizontal = 12.dp),
          onClick = { expanded = false; onStage(entry) }, enabled = enabled)
      }
    }
  }
}

@Composable
private fun learnRow(today: TodaySummary, busy: Boolean): HomeRow? {
  val base = if (today.stageProgress.lap > 1) uiString(UiR.string.ui_stage_words) else uiString(UiR.string.ui_new_words)
  if (today.dailyGoalReached && today.showLearnMore) return HomeRow(uiString(UiR.string.ui_learn_more),
    uiString(UiR.string.ui_daily_goal_complete), "", true, !busy && today.canLearnMore, HomeAction.LEARN_MORE)
  if (today.newCompleted < today.newPlanned) {
    val extra = today.newPlanned - today.dailyGoal
    return HomeRow(uiString(UiR.string.ui_learn_2118cb), if (extra > 0) uiString(UiR.string.ui_learning_extra, base, extra) else base,
      uiString(UiR.string.ui_fraction, today.newCompleted, today.newPlanned), false, !busy, HomeAction.LEARN)
  }
  if (!today.tagFilter.isTotal && today.availableNewWords == 0 && today.learnMoreBlocker != StudyKind.NEW) {
    val empty = today.stageProgress.total == 0
    return HomeRow(uiString(UiR.string.ui_learn_2118cb),
      uiString(if (empty) UiR.string.ui_tag_no_matching_words else UiR.string.ui_tag_filter_completed),
      "", !empty, false, HomeAction.LEARN)
  }
  if (today.learnMoreBlocker != null || today.availableNewWords > 0) {
    val subtitle = when (today.learnMoreBlocker) {
      StudyKind.REVIEW -> uiString(UiR.string.ui_finish_review_first)
      StudyKind.CARRYOVER -> uiString(UiR.string.ui_finish_unfinished_words_first)
      null -> uiString(UiR.string.ui_next_word_count, minOf(5, today.availableNewWords))
      else -> uiString(UiR.string.ui_finish_the_current_word_first)
    }
    val trailing = if (today.newPlanned > 0) uiString(UiR.string.ui_fraction, today.newCompleted, today.newPlanned) else ""
    return HomeRow(uiString(UiR.string.ui_learn_more), subtitle, trailing, false, !busy, HomeAction.LEARN_MORE)
  }
  if (today.newPlanned > 0) return HomeRow(uiString(UiR.string.ui_learn_2118cb), base, uiString(UiR.string.ui_fraction, today.newCompleted, today.newPlanned),
    true, false, HomeAction.LEARN)
  return null
}

@Composable
private fun practiceRows(today: TodaySummary, busy: Boolean) = listOf(
  HomeRow(uiString(UiR.string.ui_collections), if (today.collectionsAvailable > 0) uiString(UiR.string.ui_next_word_count, minOf(5, today.collectionsAvailable))
    else uiString(UiR.string.ui_none_saved), today.collectionsAvailable.toString(), false,
    !busy && today.collectionsAvailable > 0, HomeAction.COLLECTIONS),
  HomeRow(uiString(UiR.string.ui_mistakes_3bd9fb), if (today.mistakesAvailable > 0) uiString(UiR.string.ui_next_word_count, minOf(5, today.mistakesAvailable))
    else uiString(UiR.string.ui_none_to_review), today.mistakesAvailable.toString(), false,
    !busy && today.mistakesAvailable > 0, HomeAction.MISTAKES),
)

@Preview(name = "Home", widthDp = 360, heightDp = 760)
@Preview(name = "Home · compact", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Composable
private fun HomePreview(@PreviewParameter(StageThemePreviewProvider::class, limit = 1) theme: StageThemePreviewCase) {
  HomePreviewContent(theme, PreviewToday)
}

@Preview(name = "Home · complete · dark", widthDp = 360, heightDp = 760)
@Preview(name = "Home · complete · compact", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Composable
private fun HomeCompletePreview() {
  HomePreviewContent(StageThemePreviewCase(VocabularyStage.PRIMARY, true),
    PreviewToday.copy(dailyLearningCompleted = 13, newCompleted = 10, carryoverCompleted = 2))
}

@Composable
private fun HomePreviewContent(theme: StageThemePreviewCase, summary: TodaySummary) {
  FlashcardTheme(darkTheme = theme.darkTheme, stage = theme.stage) {
    Surface(Modifier.fillMaxSize().flashcardBackground(), color = androidx.compose.ui.graphics.Color.Transparent,
      contentColor = MaterialTheme.colorScheme.onBackground) {
      val today = summary.copy(stageProgress = summary.stageProgress.copy(stage = theme.stage))
      val word = WordlistItem(PreviewWord.id, PreviewWord.hanzi, PreviewWord.pinyin,
        PreviewWord.meanings.first().english, "", theme.stage.rarity, WordlistStatus.UNLEARNED, 0, 4,
        partOfSpeech = PreviewWord.meanings.first().partOfSpeech)
      TodayContent(StudyUiState(loading = false, snapshot = StudySnapshot(today, null),
        homeWord = word, homeWordStage = theme.stage, homeWordContextVersion = 0),
        onStart = {}, onResume = {}, onLearnMore = {}, onLearnMoreBlocked = {},
        onStage = {}, onRetry = {}, onReplay = {}, onShuffle = {}, onProfile = {}, onWordlist = {})
    }
  }
}
