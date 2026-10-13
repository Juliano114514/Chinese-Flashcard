package com.example.chinese_flashcard

import com.example.chinese_flashcard.core.domain.UiMessageKey
import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.viewModelScope
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.example.chinese_flashcard.core.data.FlashcardRepositories
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.CsvSource
import com.example.chinese_flashcard.core.domain.DailyWordChoices
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.domain.ThemeMode
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.SpeechScene
import com.example.chinese_flashcard.core.media.EnglishPronunciations
import com.example.chinese_flashcard.core.ui.AppLanguageProvider
import com.example.chinese_flashcard.core.ui.LocalExplanationOptions
import com.example.chinese_flashcard.core.ui.uiText
import com.example.chinese_flashcard.core.media.OfflineSpeech
import com.example.chinese_flashcard.core.ui.WordlistLoadingProgress
import com.example.chinese_flashcard.core.ui.flashcardMessage
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardToolbar
import com.example.chinese_flashcard.core.ui.flashcardBackground
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun <T : ViewModel> factory(create: () -> T) = object : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}

private class NavigationJob { var job: Job? = null }

data class StartupState(val ready: Boolean = false, val busy: Boolean = false,
  val settings: StudySettings = StudySettings(), val error: UiMessageKey? = null)
class StartupViewModel(private val repositories: FlashcardRepositories) : ViewModel() {
  private val mutable = MutableStateFlow(StartupState())
  val state = mutable.asStateFlow()
  init { prepare() }
  fun prepare() = run {
    repositories.prepare()
    repositories.settings.settings.first()
  }
  fun welcome(value: StudySettings) = run {
    val saved = value.copy(welcomed = true)
    require(DailyWordChoices.isAllowed(saved.dailyWords))
    saved.validate()
    repositories.settings.save(saved)
    saved
  }
  private fun run(operation: suspend () -> StudySettings) {
    if (mutable.value.busy) return
    mutable.value = mutable.value.copy(busy = true, error = null)
    viewModelScope.launch {
      try {
        val value = withContext(Dispatchers.IO) { operation() }
        mutable.value = StartupState(ready = true, settings = value)
      } catch (error: CancellationException) { throw error }
      catch (error: Exception) {
        val message = if (!mutable.value.ready) repositories.defaultWordlistState.value.error
          ?: error.flashcardMessage() else error.flashcardMessage()
        mutable.value = mutable.value.copy(busy = false, error = message)
      }
    }
  }
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
  val study: StudyViewModel = viewModel(factory = factory {
    StudyViewModel(repositories.study, repositories.settings, repositories.wordState, repositories.wordlist)
  })
  val profile: ProfileViewModel = viewModel(factory = factory { ProfileViewModel(repositories.settings, repositories.study, repositories.csvImport) })
  val wordlist: WordlistViewModel = viewModel(factory = factory { WordlistViewModel(repositories.wordlist) })
  val context = LocalContext.current
  val resolver = context.applicationContext.contentResolver
  val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let { selected -> profile.readCsv(CsvSource {
      requireNotNull(resolver.openInputStream(selected)) { "The selected CSV couldn't be opened." }
    }) }
  }
  val speech = remember { OfflineSpeech(context) }
  val latestSpeechOptions by rememberUpdatedState(settings.speechOptions)
  val manualPlayback = remember { NavigationJob() }
  val configuredWait: suspend (SpeechRequest) -> Boolean = configuredWait@ { request ->
    val options = latestSpeechOptions
    var succeeded = true
    for (part in options.requests(request)) {
      if (latestSpeechOptions != options) return@configuredWait false
      if (!speech.speakAndWait(part)) succeeded = false
    }
    succeeded // Intentionally disabled speech must not block feedback or navigation.
  }
  val configuredSpeak: (SpeechRequest) -> Unit = { request ->
    manualPlayback.job?.cancel()
    speech.stop()
    manualPlayback.job = navigationScope.launch { configuredWait(request) }
  }
  LaunchedEffect(settings.speechOptions) {
    manualPlayback.job?.cancel()
    speech.stop()
  }
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
  DisposableEffect(owner, speech) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) { manualPlayback.job?.cancel(); speech.stop() }
      if (event == Lifecycle.Event.ON_RESUME) study.refresh()
    }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer); manualPlayback.job?.cancel(); speech.close() }
  }
  val snackbar = remember { SnackbarHostState() }
  val speechMessage by speech.message.collectAsStateWithLifecycle()
  LaunchedEffect(speechMessage, settings.uiLanguage) {
    speechMessage?.let { snackbar.showSnackbar(uiText(context, it, settings.uiLanguage)); speech.dismissMessage() }
  }
  val entry by nav.currentBackStackEntryAsState()
  val route = entry?.destination?.route
  DisposableEffect(entry, speech) { onDispose { manualPlayback.job?.cancel(); speech.stop() } }
  val state by study.state.collectAsStateWithLifecycle()
  LaunchedEffect(speech, entry, state.card?.id, state.homeWord?.id, settings.speechOptions) {
    if (route !in listOf("today", "study", "word/{wordId}", "writing/{id}", "word-writing/{id}")) return@LaunchedEffect
    val word = state.card?.word
    val candidates = listOfNotNull(state.homeWord?.english, word?.meanings?.firstOrNull()?.english,
      word?.examples?.firstOrNull()?.english)
    speech.preloadEnglish(candidates)
  }
  var learnMoreRequest by rememberSaveable { mutableStateOf<Triple<String, Int, Int>?>(null) }
  LaunchedEffect(learnMoreRequest, state.busy, state.snapshot, state.error, route) {
    val request = learnMoreRequest ?: return@LaunchedEffect
    if (route != "today" || state.today?.date != request.first ||
      state.today?.stageProgress?.stage?.rarity != request.third) {
      learnMoreRequest = null
    } else if (!state.busy) {
      if ((state.today?.newPlanned ?: 0) > request.second && state.card?.kind == StudyKind.NEW &&
        state.card?.phase != CardPhase.FINISHED) {
        learnMoreRequest = null
        nav.navigate("study") { launchSingleTop = true }
      } else if (state.error == null) {
        learnMoreRequest = null
      }
      // A failed request stays on Home; its existing Retry can complete this same request.
    }
  }
  BackHandler(enabled = route == "study" && state.busy) { }
  val openWriting: (String) -> Unit = { id ->
    if (nav.currentDestination?.route != "writing/{id}") nav.navigate("writing/$id")
  }
  LaunchedEffect(settings.learningLanguage) {
    if (appliedLearningLanguage != settings.learningLanguage) {
      speech.stop()
      learnMoreRequest = null
      study.refresh()
      nav.navigate("today") { popUpTo("today"); launchSingleTop = true }
      appliedLearningLanguage = settings.learningLanguage
    }
  }
  AppLanguageProvider(settings.uiLanguage) {
  CompositionLocalProvider(LocalExplanationOptions provides settings.explanationOptions) {
  FlashcardTheme(stage = state.today?.stageProgress?.stage ?: VocabularyStage.PRIMARY, themeMode = settings.themeMode) {
  FlashcardSystemBars()
  Scaffold(modifier = Modifier.flashcardBackground(), containerColor = Color.Transparent,
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
    NavHost(nav, startDestination = "today", modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
      composable("today") {
        TodayScreen(study, onStart = { kind -> study.start(kind); nav.navigate("study") { launchSingleTop = true } },
          onResume = { study.refresh(); nav.navigate("study") { launchSingleTop = true } }, onWriting = openWriting,
          onLearnMore = {
            val before = study.state.value.today
            if (before != null && !study.state.value.busy) {
              learnMoreRequest = Triple(before.date, before.newPlanned, before.stageProgress.stage.rarity)
              study.learnMore()
            }
          }, onProfile = { nav.navigate("profile") { launchSingleTop = true } },
          onWordlist = { nav.navigate("wordlist") { launchSingleTop = true } }, onSpeak = configuredSpeak,
          onLanguages = { profile.beginEdit(); openLanguages = true })
      }
      composable("profile") { ProfileScreen(profile, onSettings = { nav.navigate("settings") { launchSingleTop = true } }) }
      composable("settings") { SettingScreen(profile, onBack = { nav.popBackStack() },
        onImportCsv = { csvPicker.launch(arrayOf("text/*", "application/csv", "application/x-csv",
          "application/octet-stream", "application/vnd.ms-excel")) },
        onLicenses = { nav.navigate("licenses") },
        onReadingOptions = { profile.beginEdit(); nav.navigate("reading-options") },
        onExplanationOptions = { profile.beginEdit(); nav.navigate("explanation-options") },
        defaultWordlistProgress = repositories.defaultWordlistProgress,
        csvImportProgress = repositories.csvImport.progress,
        defaultWordlistLoading = defaultWordlist.loading,
        defaultWordlistError = defaultWordlist.error,
        languageSwitchEnabled = !state.busy,
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
        WordlistScreen(wordlist, onWord = { id -> nav.navigate("word/$id") { launchSingleTop = true } })
      }
      composable("word/{wordId}") { detailEntry ->
        val wordId = requireNotNull(detailEntry.arguments?.getString("wordId"))
        val vm: WordDetailViewModel = viewModel(detailEntry, key = "$wordId:${settings.learningLanguage}",
          factory = factory {
            WordDetailViewModel(wordId, repositories.wordlist, repositories.study, repositories.wordState, settings.learningLanguage)
          })
        WordDetailScreen(vm, onBack = { speech.stop(); nav.popBackStack() }, onWriting = { sessionId ->
          if (nav.currentBackStackEntry == detailEntry) {
            nav.navigate("word-writing/$sessionId") { launchSingleTop = true }
          }
        }, onSpeak = configuredSpeak, ipaResolver = { pronunciations?.ipa(it) },
          onPreload = speech::preloadEnglish)
      }
      composable("word-writing/{id}") { writingEntry ->
        val id = requireNotNull(writingEntry.arguments?.getString("id"))
        val vm: WritingViewModel = viewModel(writingEntry, key = id,
          factory = factory { WritingViewModel(id, repositories.writing) })
        val returnToWord: () -> Unit = {
          if (nav.currentBackStackEntry == writingEntry) { speech.stop(); nav.popBackStack() }
        }
        WritingScreen(vm, onBack = returnToWord, onFinished = returnToWord, onSpeak = { text ->
          configuredSpeak(SpeechRequest(text, AppLanguage.CN, SpeechScene.WRITING_WORD,
            vm.state.value.snapshot?.word?.takeIf { it.hanzi == text }?.meanings?.firstOrNull()?.english))
        })
      }
      composable("study") {
        StudyScreen(study, onBack = {
          if (!study.state.value.busy) { speech.stop(); nav.popBackStack(); study.refresh() }
        }, onWriting = openWriting, onSpeakAndWait = configuredWait, ipaResolver = { pronunciations?.ipa(it) })
      }
      composable("writing/{id}") { writingEntry ->
        val id = requireNotNull(writingEntry.arguments?.getString("id"))
        val vm: WritingViewModel = viewModel(writingEntry, key = id,
          factory = factory { WritingViewModel(id, repositories.writing) })
        WritingScreen(vm, onBack = { speech.stop(); nav.popBackStack(); study.refresh() }, onFinished = {
          speech.stop()
          navigationScope.launch {
            // Resume refresh may still be saving the snapshot. Keep this completion until it is idle.
            study.state.first { !it.busy }
            if (nav.currentBackStackEntry == writingEntry) {
              val card = study.state.value.card
              if (card?.phase == CardPhase.WRITING && card.writingSessionId == id) study.advance() else study.refresh()
              nav.popBackStack()
            }
          }
        }, onSpeak = { text ->
          configuredSpeak(SpeechRequest(text, AppLanguage.CN, SpeechScene.WRITING_WORD,
            vm.state.value.snapshot?.word?.takeIf { it.hanzi == text }?.meanings?.firstOrNull()?.english))
        })
      }
      composable("licenses") { LicenseScreen { nav.popBackStack() } }
    }
  }
  if (openLanguages && route == "today") LanguagesDialog(profile, initial = settings,
    enabled = !state.busy && !defaultWordlist.loading,
    onDismiss = { openLanguages = false; profile.beginEdit() })
  }
  }
  }
}

@Composable
private fun LicenseScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val licenses by produceState(LicenseText()) {
    value = withContext(Dispatchers.IO) {
      try {
        val text = listOf("demo/COPYING", "demo/ARPHICPL.TXT", "wordlist-strokes/LICENSES.txt",
          "wordlist-strokes/LEXICON_LICENSES.txt", "avatars/NOTICE.txt", "level-icons/NOTICE.txt",
          "english-speech/LICENSES.txt", "english-teaching/LICENSES.txt").joinToString("\n\n") { name ->
          context.assets.open(name).bufferedReader().use { it.readText() }
        }
        LicenseText(text = text, loading = false)
      } catch (error: CancellationException) { throw error }
      catch (_: Exception) { LicenseText(loading = false, failed = true) }
    }
  }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)) {
    FlashcardToolbar(uiString(UiR.string.ui_sources_licenses), onBack)
    Text(uiString(UiR.string.ui_default_vocabulary_bundled_wordlist_csv_dictionary_attribution_and_content_sources_are_listed_below))
    Text(uiString(UiR.string.ui_legacy_demo_vocabulary_and_examples_original_teaching_material_retained_when_upgrading))
    Text(uiString(UiR.string.ui_additional_vocabulary_local_csv_wordlists_imported_in_settings))
    Text(uiString(UiR.string.ui_stroke_outlines_and_medians_make_me_a_hanzi_supplemented_by_animcjk_selected_glyphs_are_bundled_for_offline_writing_under_the_arphic_public_license))
    Text(when {
      licenses.loading -> uiString(UiR.string.ui_loading)
      licenses.failed -> uiString(UiMessageKey.SOURCE_LICENSES_COULD_NOT_BE_READ_PLEASE_REOPEN_THIS_PAGE)
      else -> licenses.text
    }, style = MaterialTheme.typography.bodySmall)
  }
}

private data class LicenseText(val text: String = "", val loading: Boolean = true, val failed: Boolean = false)
