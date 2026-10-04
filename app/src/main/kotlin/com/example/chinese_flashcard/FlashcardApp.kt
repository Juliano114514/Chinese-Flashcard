package com.example.chinese_flashcard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.example.chinese_flashcard.core.data.FlashcardRepositories
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.CsvSource
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.media.OfflineSpeech
import com.example.chinese_flashcard.core.ui.flashcardMessage
import com.example.chinese_flashcard.feature.profile.ProfileScreen
import com.example.chinese_flashcard.feature.profile.ProfileViewModel
import com.example.chinese_flashcard.feature.study.*
import com.example.chinese_flashcard.feature.writing.WritingScreen
import com.example.chinese_flashcard.feature.writing.WritingViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun <T : ViewModel> factory(create: () -> T) = object : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}

data class StartupState(val ready: Boolean = false, val busy: Boolean = false,
  val settings: StudySettings = StudySettings(), val error: String? = null)
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
      catch (error: Exception) { mutable.value = mutable.value.copy(busy = false, error = error.flashcardMessage()) }
    }
  }
}

@Composable
fun FlashcardApp(repositories: FlashcardRepositories) {
  val startup: StartupViewModel = viewModel(factory = factory { StartupViewModel(repositories) })
  val boot by startup.state.collectAsStateWithLifecycle()
  Surface(Modifier.fillMaxSize()) {
    when {
      !boot.ready -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Chinese Flashcard", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        if (boot.error != null) {
          Text(boot.error!!); Button(onClick = startup::prepare, enabled = !boot.busy) { Text("Retry") }
        } else CircularProgressIndicator()
      }
      !boot.settings.welcomed -> Box(Modifier.safeDrawingPadding()) {
        WelcomeScreen(boot.settings, boot.busy, boot.error, startup::welcome)
      }
      else -> AppNavigation(repositories)
    }
  }
}

@Composable
private fun AppNavigation(repositories: FlashcardRepositories) {
  val nav = rememberNavController()
  val navigationScope = rememberCoroutineScope()
  val study: StudyViewModel = viewModel(factory = factory { StudyViewModel(repositories.study, repositories.settings) })
  val profile: ProfileViewModel = viewModel(factory = factory { ProfileViewModel(repositories.settings, repositories.study, repositories.csvImport) })
  val context = LocalContext.current
  val resolver = context.applicationContext.contentResolver
  val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let { selected -> profile.readCsv(CsvSource {
      requireNotNull(resolver.openInputStream(selected)) { "The selected CSV couldn't be opened." }
    }) }
  }
  val speech = remember { OfflineSpeech(context) }
  val owner = LocalLifecycleOwner.current
  DisposableEffect(owner, speech) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) speech.stop()
      if (event == Lifecycle.Event.ON_RESUME) study.refresh()
    }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer); speech.close() }
  }
  val snackbar = remember { SnackbarHostState() }
  val speechMessage by speech.message.collectAsStateWithLifecycle()
  LaunchedEffect(speechMessage) {
    speechMessage?.let { snackbar.showSnackbar(it); speech.dismissMessage() }
  }
  val entry by nav.currentBackStackEntryAsState()
  val route = entry?.destination?.route
  val state by study.state.collectAsStateWithLifecycle()
  BackHandler(enabled = route == "study" && state.busy) { }
  val openWriting: (String) -> Unit = { id ->
    if (nav.currentDestination?.route != "writing/{id}") nav.navigate("writing/$id")
  }
  Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
    if (route == "today" || route == "profile") NavigationBar {
      listOf("today" to "Today", "profile" to "Profile").forEach { (destination, label) ->
        NavigationBarItem(selected = route == destination, onClick = {
          nav.navigate(destination) { popUpTo("today") { saveState = true }; launchSingleTop = true; restoreState = true }
        }, icon = { Icon(if (destination == "today") Icons.Default.Home else Icons.Default.Person, label) },
          label = { Text(label) })
      }
    }
  }) { padding ->
    NavHost(nav, startDestination = "today", modifier = Modifier.padding(padding)) {
      composable("today") {
        TodayScreen(study, onStart = { kind -> study.start(kind); nav.navigate("study") { launchSingleTop = true } },
          onResume = { study.refresh(); nav.navigate("study") { launchSingleTop = true } }, onWriting = openWriting)
      }
      composable("profile") { ProfileScreen(profile,
        onImportCsv = { csvPicker.launch(arrayOf("text/*", "application/csv", "application/x-csv",
          "application/octet-stream", "application/vnd.ms-excel")) },
        onLicenses = { nav.navigate("licenses") }) }
      composable("study") {
        StudyScreen(study, onBack = {
          if (!study.state.value.busy) { speech.stop(); nav.popBackStack(); study.refresh() }
        },
          onWriting = openWriting, onSpeak = speech::speak)
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
        }, onSpeak = speech::speak)
      }
      composable("licenses") { LicenseScreen { nav.popBackStack() } }
    }
  }
}

@Composable
private fun LicenseScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val licenses by produceState("Loading…") {
    value = withContext(Dispatchers.IO) {
      try {
        listOf("demo/COPYING", "demo/ARPHICPL.TXT", "wordlist-strokes/LICENSES.txt",
          "wordlist-strokes/LEXICON_LICENSES.txt").joinToString("\n\n") { name ->
          context.assets.open(name).bufferedReader().use { it.readText() }
        }
      } catch (error: CancellationException) { throw error }
      catch (_: Exception) { "Source licenses could not be read. Please reopen this page." }
    }
  }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)) {
    TextButton(onClick = onBack) { Text("Back") }
    Text("Sources & licenses", style = MaterialTheme.typography.headlineMedium)
    Text("Demo vocabulary and examples: original teaching material for Chinese Flashcard.")
    Text("Imported vocabulary: local CSV wordlists. Dictionary attribution and content sources are listed below.")
    Text("Stroke outlines and medians: Make Me a Hanzi, supplemented by AnimCJK. Selected glyphs are bundled for offline writing under the Arphic Public License.")
    Text(licenses, style = MaterialTheme.typography.bodySmall)
  }
}
