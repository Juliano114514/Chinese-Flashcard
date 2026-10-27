package com.example.chinese_flashcard

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.*
import com.example.chinese_flashcard.core.data.FlashcardRepositories
import com.example.chinese_flashcard.core.data.PresetWritingResume
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.CsvSource
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.SpeechScene
import com.example.chinese_flashcard.core.domain.SpeechPlaybackPhase
import com.example.chinese_flashcard.core.domain.SpeechPlaybackState
import com.example.chinese_flashcard.core.media.EnglishPronunciations
import com.example.chinese_flashcard.core.ui.AppLanguageProvider
import com.example.chinese_flashcard.core.ui.LocalExplanationOptions
import com.example.chinese_flashcard.core.ui.LocalSpeechPlaybackState
import com.example.chinese_flashcard.core.ui.uiText
import com.example.chinese_flashcard.core.ui.localizedLabel
import com.example.chinese_flashcard.core.ui.WordlistLoadingProgress
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.FlashcardBackground
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.glassPanel
import com.example.chinese_flashcard.core.ui.glassPanelColor
import com.example.chinese_flashcard.feature.profile.ProfileScreen
import com.example.chinese_flashcard.feature.profile.ProfileViewModel
import com.example.chinese_flashcard.feature.profile.SettingScreen
import com.example.chinese_flashcard.feature.profile.LanguagesDialog
import com.example.chinese_flashcard.feature.profile.ReadingOptionsScreen
import com.example.chinese_flashcard.feature.profile.ExplanationOptionsScreen
import com.example.chinese_flashcard.feature.study.*
import com.example.chinese_flashcard.feature.wordlist.*
import com.example.chinese_flashcard.feature.writing.WritingScreen
import com.example.chinese_flashcard.feature.writing.WritingViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

private fun <T : ViewModel> factory(create: () -> T) = object : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}

private class NavigationJob { var job: Job? = null }

private class SpeechOperation {
  private var serial = 0L
  var state by mutableStateOf(SpeechPlaybackState())
    private set
  fun begin(sourceId: String?): Long = (++serial).also {
    state = SpeechPlaybackState(it, sourceId, SpeechPlaybackPhase.PREPARING)
  }
  fun started(id: Long) {
    if (state.operationId == id) state = state.copy(phase = SpeechPlaybackPhase.PLAYING)
  }
  fun finish(id: Long) { if (state.operationId == id) state = SpeechPlaybackState(++serial) }
  fun cancel() { state = SpeechPlaybackState(++serial) }
}

@Composable
fun FlashcardApp(repositories: FlashcardRepositories) {
  val startup: StartupViewModel = viewModel(factory = factory { StartupViewModel(repositories) })
  val boot by startup.state.collectAsStateWithLifecycle()
  AppLanguageProvider(boot.settings.uiLanguage) {
  FlashcardTheme(themeMode = boot.settings.themeMode) {
  if (!boot.ready || !boot.settings.welcomed) FlashcardSystemBars()
  Surface(Modifier.fillMaxSize()) {
    when {
      !boot.ready -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(uiString(UiR.string.ui_app_name), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
          style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        if (boot.error != null) {
          Text(uiString(boot.error!!), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
          Button(onClick = startup::prepare, enabled = !boot.busy) { Text(uiString(UiR.string.ui_retry)) }
        } else {
          WordlistLoadingProgress(repositories.defaultWordlistProgress)
        }
      }
      !boot.settings.welcomed -> Box(Modifier.safeDrawingPadding()) {
        WelcomeScreen(boot.settings, boot.busy, boot.error, startup::welcome)
      }
      else -> AppNavigation(repositories, boot.settings)
    }
  }
  }
  }
}

@Composable
private fun FlashcardSystemBars() {
  val view = LocalView.current
  val activity = LocalActivity.current ?: return
  val dark = FlashcardStyle.darkTheme
  DisposableEffect(view, activity, dark) {
    if (!view.isInEditMode) {
    WindowCompat.getInsetsController(activity.window, view).apply {
      isAppearanceLightStatusBars = !dark
      isAppearanceLightNavigationBars = !dark
    }
    if (android.os.Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = false
    }
    onDispose { }
  }
}

@Composable
private fun AppNavigation(repositories: FlashcardRepositories, initialSettings: StudySettings) {
  val settings by repositories.settings.settings.collectAsStateWithLifecycle(initialValue = initialSettings)
  val nav = rememberNavController()
  val navigationScope = rememberCoroutineScope()
  val defaultWordlist by repositories.defaultWordlistState.collectAsStateWithLifecycle()
  val defaultWordlistRetry = remember { NavigationJob() }
  val context = LocalContext.current
  val application = context.applicationContext as FlashcardApplication
  val speech = application.speech
  val speechCache = speech.cache
  val study: StudyViewModel = viewModel(factory = factory {
    StudyViewModel(repositories.study, repositories.settings, repositories.wordState, repositories.wordlist,
      speechCache = speechCache)
  })
  val profile: ProfileViewModel = viewModel(factory = factory { ProfileViewModel(repositories.settings, repositories.study, repositories.csvImport) })
  val sharedViewModelOwner = requireNotNull(LocalViewModelStoreOwner.current)
  val resolver = context.applicationContext.contentResolver
  val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let { selected -> profile.readCsv(CsvSource {
      requireNotNull(resolver.openInputStream(selected)) { "The selected CSV couldn't be opened." }
    }) }
  }
  val latestSpeechOptions by rememberUpdatedState(settings.speechOptions)
  val latestSpeechLanguage by rememberUpdatedState(settings.learningLanguage)
  val manualPlayback = remember { NavigationJob() }
  val playbackOperation = remember { SpeechOperation() }
  val configuredWait: suspend (SpeechRequest) -> Boolean = configuredWait@ { request ->
    if (request.scene == SpeechScene.HOME_WORD && request.language != latestSpeechLanguage) return@configuredWait false
    val options = latestSpeechOptions
    val id = playbackOperation.begin(request.sourceId)
    var started = false
    try {
      var succeeded = true
      for (part in options.requests(request)) {
        currentCoroutineContext().ensureActive()
        if (latestSpeechOptions != options || playbackOperation.state.operationId != id) return@configuredWait false
        if (!speech.speakAndWait(part) {
          navigationScope.launch {
            if (!started) { started = true; playbackOperation.started(id) }
          }
        }) succeeded = false
      }
      succeeded // Intentionally disabled speech must not block feedback or navigation.
    } finally {
      playbackOperation.finish(id)
    }
  }
  val configuredSpeak: (SpeechRequest) -> Unit = { request ->
    manualPlayback.job?.cancel()
    speech.stop()
    playbackOperation.cancel()
    manualPlayback.job = navigationScope.launch { configuredWait(request) }
  }
  LaunchedEffect(settings.speechOptions) {
    manualPlayback.job?.cancel()
    speech.stop()
    playbackOperation.cancel()
  }
  LaunchedEffect(settings.speechCacheMiB) { speechCache.configure(settings.speechCacheMiB) }
  val pronunciations by produceState<EnglishPronunciations?>(null, context.applicationContext) {
    value = withContext(Dispatchers.IO) {
      try { EnglishPronunciations.load(context.applicationContext) }
      catch (error: CancellationException) { throw error }
      catch (_: Exception) { null }
    }
  }
  var openLanguages by rememberSaveable { mutableStateOf(false) }
  var appliedLearningLanguage by rememberSaveable { mutableStateOf(settings.learningLanguage) }
  val owner = LocalLifecycleOwner.current
  DisposableEffect(owner, speech, speechCache) {
    speechCache.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START) speechCache.setForeground(true)
      if (event == Lifecycle.Event.ON_STOP) {
        manualPlayback.job?.cancel()
        speech.stop(); speechCache.setForeground(false); playbackOperation.cancel()
      }
      if (event == Lifecycle.Event.ON_RESUME) study.refresh()
    }
    owner.lifecycle.addObserver(observer)
    onDispose {
      owner.lifecycle.removeObserver(observer); manualPlayback.job?.cancel()
      speech.stop(); speechCache.setForeground(false); playbackOperation.cancel()
    }
  }
  val snackbar = remember { SnackbarHostState() }
  val speechMessage by speech.message.collectAsStateWithLifecycle()
  LaunchedEffect(speechMessage, settings.uiLanguage) {
    speechMessage?.let { snackbar.showSnackbar(uiText(context, it, settings.uiLanguage)); speech.dismissMessage() }
  }
  val entry by nav.currentBackStackEntryAsState()
  val route = entry?.destination?.route
  DisposableEffect(entry, speech) { onDispose {
    manualPlayback.job?.cancel(); speech.stop(); playbackOperation.cancel()
  } }
  LaunchedEffect(route, owner) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      study.setHomeSpeechActive(route == "today")
      try { awaitCancellation() }
      finally { study.setHomeSpeechActive(false) }
    }
  }
  val studyState = study.state.collectAsStateWithLifecycle()
  val studyBusy by remember(studyState) { derivedStateOf { studyState.value.busy } }
  val stage by remember(studyState) { derivedStateOf { studyState.value.today?.stageProgress?.stage ?: VocabularyStage.PRIMARY } }
  var learnMoreRequest by rememberSaveable { mutableStateOf<Triple<String, Int, Int>?>(null) }
  var reviewMoreRequest by rememberSaveable { mutableStateOf<Triple<String, Int, Long>?>(null) }
  StudyNavigationEffects(studyState, route, learnMoreRequest, reviewMoreRequest,
    onReviewMoreHandled = { reviewMoreRequest = null },
    onRequestHandled = { learnMoreRequest = null }, onStudy = { nav.navigate("study") { launchSingleTop = true } })
  BackHandler(enabled = route == "study" && studyBusy) { }
  val openWriting: (String) -> Unit = { id ->
    if (nav.currentDestination?.route != "writing/{id}") nav.navigate("writing/$id")
  }
  var presetWritingRequest by remember { mutableStateOf<PresetWritingResume?>(null) }
  var presetWritingRecoveryId by rememberSaveable { mutableStateOf<String?>(null) }
  LaunchedEffect(settings.learningLanguage) {
    if (appliedLearningLanguage != settings.learningLanguage) {
      speech.stop()
      playbackOperation.cancel()
      learnMoreRequest = null
      reviewMoreRequest = null
      study.refresh()
      nav.navigate("today") { popUpTo("today"); launchSingleTop = true }
      appliedLearningLanguage = settings.learningLanguage
    }
  }
  LaunchedEffect(presetWritingRequest?.sessionId, settings.learningLanguage, appliedLearningLanguage) {
    val request = presetWritingRequest ?: return@LaunchedEffect
    try {
      if (settings.learningLanguage != request.language) {
        repositories.settings.save(settings.copy(learningLanguage = request.language))
        return@LaunchedEffect
      }
      // The existing language effect saves/restores the proper learning context and
      // returns Home first. Open the saved writing only after that navigation settles.
      if (appliedLearningLanguage != request.language) return@LaunchedEffect
      presetWritingRecoveryId = request.sessionId
      presetWritingRequest = null
      openWriting(request.sessionId)
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) {
      presetWritingRequest = null
      snackbar.showSnackbar(uiText(context,
        com.example.chinese_flashcard.core.domain.UiMessageKey.THIS_STEP_COULDN_T_BE_COMPLETED_TRY_AGAIN,
        settings.uiLanguage))
    }
  }
  AppLanguageProvider(settings.uiLanguage) {
  CompositionLocalProvider(LocalExplanationOptions provides settings.explanationOptions,
    LocalSpeechPlaybackState provides playbackOperation.state) {
  FlashcardTheme(stage = stage, themeMode = settings.themeMode) {
  FlashcardSystemBars()
  FlashcardBackground {
  Scaffold(containerColor = Color.Transparent,
    contentWindowInsets = if (route == "study" || route == "word/{wordId}")
      WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
      else ScaffoldDefaults.contentWindowInsets,
    contentColor = MaterialTheme.colorScheme.onBackground,
    snackbarHost = { SnackbarHost(snackbar, modifier = if (route == "study" || route == "word/{wordId}")
      Modifier.navigationBarsPadding() else Modifier) }, bottomBar = {
    if (route in listOf("today", "profile", "wordlist")) Column(Modifier
      .glassPanel(androidx.compose.ui.graphics.RectangleShape).background(glassPanelColor())) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
      NavigationBar(modifier = Modifier.windowInsetsPadding(NavigationBarDefaults.windowInsets).height(72.dp),
        windowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent, tonalElevation = 0.dp) {
        listOf("today" to UiR.string.ui_home, "profile" to UiR.string.ui_profile, "wordlist" to UiR.string.ui_wordlist).forEach { (destination, label) ->
          NavigationBarItem(selected = route == destination, onClick = {
            if (nav.currentDestination?.route == destination) return@NavigationBarItem
            if (destination == "today") {
              if (route != "today" && !nav.popBackStack("today", inclusive = false)) {
                nav.navigate("today") { launchSingleTop = true }
              }
            } else {
              nav.navigate(destination) {
                popUpTo("today") { saveState = true }
                launchSingleTop = true
                restoreState = true
              }
            }
          }, icon = { Icon(when (destination) {
            "today" -> Icons.Default.Home
            "profile" -> Icons.Default.Person
            else -> Icons.AutoMirrored.Filled.List
          }, uiString(label)) }, label = {
            Text(uiString(label), style = MaterialTheme.typography.labelSmall)
          },
            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
              selectedIconColor = MaterialTheme.colorScheme.primary,
              selectedTextColor = MaterialTheme.colorScheme.primary,
              unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
              unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
      }
    }
  }) { padding ->
    NavHost(nav, startDestination = "today", modifier = Modifier.padding(padding).consumeWindowInsets(padding),
      enterTransition = { fadeIn(tween(180)) }, exitTransition = { fadeOut(tween(120)) },
      popEnterTransition = { fadeIn(tween(180)) }, popExitTransition = { fadeOut(tween(120)) }) {
      composable("today") {
        TodayScreen(study, onStart = { kind -> study.start(kind); nav.navigate("study") { launchSingleTop = true } },
          onResume = { study.refresh(); nav.navigate("study") { launchSingleTop = true } }, onWriting = openWriting,
          onLearnMore = {
            val before = study.state.value.today
            if (before != null && !study.state.value.busy) {
              learnMoreRequest = Triple(before.date, before.newPlanned, before.stageProgress.stage.rarity)
              study.learnMore()
            }
          }, onReviewMore = {
            val before = study.state.value.today
            if (before != null && !study.state.value.busy) {
              reviewMoreRequest = Triple(before.date, before.reviewPlanned, before.contextVersion)
              study.reviewMore()
            }
          }, onProfile = { nav.navigate("profile") { launchSingleTop = true } },
          onWordlist = { nav.navigate("wordlist") { launchSingleTop = true } }, onSpeak = configuredSpeak,
          onLanguages = { profile.beginEdit(); openLanguages = true },
          onTagFilter = { nav.navigate("tag-filter") { launchSingleTop = true } })
      }
      composable("tag-filter") { TagFilterScreen(study) { nav.popBackStack() } }
      composable("profile") { ProfileScreen(profile, onSettings = { nav.navigate("settings") { launchSingleTop = true } }) }
      composable("settings") {
        val speechCacheUsage by speechCache.usage.collectAsStateWithLifecycle()
        SettingScreen(profile, onBack = { nav.popBackStack() },
        onImportCsv = { csvPicker.launch(arrayOf("text/*", "application/csv", "application/x-csv",
          "application/octet-stream", "application/vnd.ms-excel")) },
        onLicenses = { nav.navigate("licenses") },
        onReadingOptions = { profile.beginEdit(); nav.navigate("reading-options") },
        onExplanationOptions = { profile.beginEdit(); nav.navigate("explanation-options") },
        defaultWordlistProgress = repositories.defaultWordlistProgress,
        csvImportProgress = repositories.csvImport.progress,
        defaultWordlistLoading = defaultWordlist.loading,
        defaultWordlistError = defaultWordlist.error,
        defaultWordlistResumeWritingLabel = defaultWordlist.writingConflict?.let { conflict ->
          uiString(UiR.string.ui_continue_writing) + " · " + conflict.hanzi + " · " + conflict.language.localizedLabel()
        },
        onResumeDefaultWordlistWriting = { presetWritingRequest = defaultWordlist.writingConflict },
        languageSwitchEnabled = !studyBusy,
        cacheUsedBytes = speechCacheUsage.usedBytes,
        onClearSpeechCache = speechCache::clear,
        onRetryDefaultWordlist = {
          if (defaultWordlistRetry.job?.isActive != true) {
            defaultWordlistRetry.job = navigationScope.launch {
              try { repositories.retryDefaultWordlist() }
              catch (error: CancellationException) { throw error }
              catch (_: Exception) { /* The repository exposes this failure in defaultWordlistState. */ }
            }
          }
        }) }
      composable("reading-options") { ReadingOptionsScreen(profile) { nav.popBackStack() } }
      composable("explanation-options") { ExplanationOptionsScreen(profile) { nav.popBackStack() } }
      composable("wordlist") {
        val wordlist: WordlistViewModel = viewModel(viewModelStoreOwner = sharedViewModelOwner,
          factory = factory { WordlistViewModel(repositories.wordlist) })
        WordlistScreen(wordlist, onWord = { id -> nav.navigate("word/$id") { launchSingleTop = true } })
      }
      composable("word/{wordId}") { detailEntry ->
        val wordId = requireNotNull(detailEntry.arguments?.getString("wordId"))
        val vm: WordDetailViewModel = viewModel(detailEntry, key = "$wordId:${settings.learningLanguage}",
          factory = factory {
            WordDetailViewModel(wordId, repositories.wordlist, repositories.study, repositories.wordState,
              settings.learningLanguage, speechCache)
          })
        WordDetailScreen(vm, onBack = { speech.stop(); nav.popBackStack() }, onWriting = { sessionId ->
          if (nav.currentBackStackEntry == detailEntry) {
            nav.navigate("word-writing/$sessionId") { launchSingleTop = true }
          }
        }, onSpeak = configuredSpeak, ipaResolver = { pronunciations?.ipa(it) })
      }
      composable("word-writing/{id}") { writingEntry ->
        val id = requireNotNull(writingEntry.arguments?.getString("id"))
        val vm: WritingViewModel = viewModel(writingEntry, key = id,
          factory = factory { WritingViewModel(id, repositories.writing) })
        val returnToWord: () -> Unit = {
          if (nav.currentBackStackEntry == writingEntry) { speech.stop(); nav.popBackStack() }
        }
        WritingScreen(vm, onBack = returnToWord, onFinished = returnToWord, onSpeak = configuredSpeak)
      }
      composable("study") {
        val playbackPreparing by speech.playbackPreparing.collectAsStateWithLifecycle()
        StudyScreen(study, onBack = {
          if (!study.state.value.busy) { speech.stop(); nav.popBackStack(); study.refresh() }
        }, onWriting = openWriting, onSpeakAndWait = configuredWait, ipaResolver = { pronunciations?.ipa(it) },
          playbackPreparing = playbackPreparing)
      }
      composable("writing/{id}") { writingEntry ->
        val id = requireNotNull(writingEntry.arguments?.getString("id"))
        val vm: WritingViewModel = viewModel(writingEntry, key = id,
          factory = factory { WritingViewModel(id, repositories.writing) })
        WritingScreen(vm, onBack = { speech.stop(); nav.popBackStack(); study.refresh() }, onFinished = {
          speech.stop()
          if (presetWritingRecoveryId == id) presetWritingRecoveryId = null
          navigationScope.launch {
            // Resume refresh may still be saving the snapshot. Keep this completion until it is idle.
            study.state.first { !it.busy }
            if (nav.currentBackStackEntry == writingEntry) {
              val card = study.state.value.card
              if (card?.phase == CardPhase.WRITING && card.writingSessionId == id) study.advance() else study.refresh()
              nav.popBackStack()
            }
          }
        }, onSpeak = configuredSpeak,
          allowSkip = presetWritingRecoveryId == id || defaultWordlist.writingConflict?.sessionId == id)
      }
      composable("licenses") { LicenseScreen { nav.popBackStack() } }
    }
  }
  }
  if (openLanguages && route == "today") LanguagesDialog(profile, initial = settings,
    enabled = !studyBusy && !defaultWordlist.loading,
    onDismiss = { openLanguages = false; profile.beginEdit() })
  }
  }
  }
}

/** Read detailed study updates here so card/shuffle changes do not rebuild the navigation host. */
@Composable
private fun StudyNavigationEffects(studyState: State<StudyUiState>,
  route: String?,
  learnMoreRequest: Triple<String, Int, Int>?, reviewMoreRequest: Triple<String, Int, Long>?,
  onReviewMoreHandled: () -> Unit, onRequestHandled: () -> Unit, onStudy: () -> Unit) {
  val state by studyState
  LaunchedEffect(learnMoreRequest, state.busy, state.snapshot, state.error, route) {
    val request = learnMoreRequest ?: return@LaunchedEffect
    if (route != "today" || state.today?.date != request.first ||
      state.today?.stageProgress?.stage?.rarity != request.third) {
      onRequestHandled()
    } else if (!state.busy) {
      if ((state.today?.newPlanned ?: 0) > request.second && state.card?.kind == StudyKind.NEW &&
        state.card?.phase != CardPhase.FINISHED) {
        onRequestHandled()
        onStudy()
      } else if (state.error == null) onRequestHandled()
      // A failed request stays on Home; its existing Retry can complete this same request.
    }
  }
  LaunchedEffect(reviewMoreRequest, state.busy, state.snapshot, state.error, route) {
    val request = reviewMoreRequest ?: return@LaunchedEffect
    if (route != "today" || state.today?.date != request.first || state.directionGeneration != request.third) {
      onReviewMoreHandled()
    } else if (!state.busy) {
      if ((state.today?.reviewPlanned ?: 0) > request.second && state.card?.kind == StudyKind.REVIEW &&
        state.card?.phase != CardPhase.FINISHED) {
        onReviewMoreHandled()
        onStudy()
      } else if (state.error == null) onReviewMoreHandled()
    }
  }
}
