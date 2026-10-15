package com.example.chinese_flashcard.core.media

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.SpeechCacheOwner
import com.example.chinese_flashcard.core.domain.SpeechCacheWindow
import com.example.chinese_flashcard.core.domain.SpeechCacheWindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal data class SpeechProgress(val chunks: List<SpeechAudioChunk> = emptyList(), val finished: Boolean = false,
  val succeeded: Boolean = false)

/** One native synthesis at a time. Foreground subscribers promote the existing task, including its first chunks. */
internal class SpeechResources(context: Context, private val chinese: ChineseSpeech, private val english: EnglishSpeech,
  private val system: SystemOfflineSpeech) {
  private val app = context.applicationContext
  private val debug = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val guard = Mutex()
  private val cache = SpeechDiskCache(app)
  val cacheUsage = cache.usage
  private val mutableChanges = MutableStateFlow(0L)
  val changes = mutableChanges.asStateFlow()
  private val tasks = LinkedHashMap<String, Task>()
  private enum class PreloadStatus { QUEUED, PREPARING, READY, FAILED, NOT_RETAINED }
  private data class PreloadDemand(val request: SpeechRequest, val priority: Int,
    var status: PreloadStatus = PreloadStatus.QUEUED)
  private data class PreloadWindow(val contextVersion: Long, val demands: Map<String, PreloadDemand>)
  private val windows = LinkedHashMap<SpeechCacheOwner, PreloadWindow>()
  private val mutablePreloadState = MutableStateFlow<Map<SpeechCacheOwner, SpeechCacheWindowState>>(emptyMap())
  val preloadState = mutablePreloadState.asStateFlow()
  private var preloadStateRevision = 0L
  private data class MemoryEntry(val audio: SpeechAudio, val generation: Long)
  private val memory = LinkedHashMap<String, MemoryEntry>(48, .75f, true)
  private var memoryBytes = 0L
  private val failures = LinkedHashMap<String, Long>()
  private val fingerprints = mutableMapOf<AppLanguage, String>()
  private val fingerprintLock = Mutex()
  private val wake = Channel<Unit>(Channel.CONFLATED)
  private var activeTask: Task? = null
  private var activeJob: Job? = null
  private var paused = true
  private var closed = false
  private var order = 0L
  private var epoch = 0L
  private var clearing = 0
  private var directPlayback = 0
  private val preloadRevision = AtomicLong()
  private val cacheLimitRevision = AtomicLong()

  internal class Task(val key: String, val request: SpeechRequest, val order: Long) {
    val progress = MutableStateFlow(SpeechProgress())
    val result = CompletableDeferred<SpeechAudio?>()
    var subscribers = 0
    var foreground = 0
    val owners = mutableMapOf<SpeechCacheOwner, Int>()
    val needed: Boolean get() = subscribers > 0 || owners.isNotEmpty()
    val priority: Int get() = owners.values.minOrNull() ?: 100
    var running = false
  }
  internal inner class Handle(private val task: Task, private val foreground: Boolean) {
    val progress: StateFlow<SpeechProgress> get() = task.progress
    suspend fun await(): SpeechAudio? = task.result.await()
    suspend fun release() = withContext(NonCancellable) {
      guard.withLock {
        task.subscribers--
        if (foreground) task.foreground--
        if (!task.needed && !task.result.isCompleted) {
          if (activeTask === task) activeJob?.cancel()
          else { tasks.remove(task.key); task.result.complete(null) }
        }
        preemptLocked()
        wake.trySend(Unit)
      }
      if (foreground) {
        cache.unpin(task.key)
        refreshRetainedPreloads()
      }
    }
  }

  private val worker = scope.launch {
    try { system.cleanupOrphans(); cache.initialize() } catch (_: Exception) { /* Cache failure never disables speech. */ }
    while (isActive) {
      val job = guard.withLock {
        val task = tasks.values.filter { !it.running && !it.result.isCompleted && it.needed &&
          ((clearing == 0 && !paused && directPlayback == 0) || it.foreground > 0) }
          .minWithOrNull(compareBy<Task>({ if (it.foreground > 0) 0 else it.priority }, { it.order }))
        if (task == null) null else {
          task.running = true; activeTask = task
          task.owners.keys.forEach { owner -> windows[owner]?.demands?.get(task.key)?.status = PreloadStatus.PREPARING }
          publishPreloadStateLocked()
          scope.launch(start = CoroutineStart.LAZY) { execute(task) }.also { activeJob = it }
        }
      }
      if (job == null) { wake.receive(); continue }
      job.start(); job.join()
      guard.withLock {
        activeTask?.let { task ->
          task.running = false
          task.owners.keys.forEach { owner -> windows[owner]?.demands?.get(task.key)?.let {
            if (it.status == PreloadStatus.PREPARING) it.status = PreloadStatus.QUEUED
          } }
        }
        activeTask = null; activeJob = null
        publishPreloadStateLocked()
      }
    }
  }

  suspend fun acquire(request: SpeechRequest, foreground: Boolean): Handle? {
    val normalized = request.copy(text = request.text.trim().take(2000))
    if (normalized.text.isEmpty()) return null
    val key = withContext(Dispatchers.IO) { key(normalized) }
    if (foreground) cache.pin(key)
    return try {
      guard.withLock {
        if (closed) { if (foreground) cache.unpin(key); return@withLock null }
        val task = tasks[key] ?: Task(key, normalized, order++).also { tasks[key] = it }
        task.subscribers++
        if (foreground) task.foreground++
        preemptLocked()
        wake.trySend(Unit)
        Handle(task, foreground)
      }
    } catch (error: Exception) {
      if (foreground) withContext(NonCancellable) { cache.unpin(key) }
      throw error
    }
  }

  private fun preemptLocked() {
    val active = activeTask ?: return
    if (active.foreground > 0) return
    val waitingForeground = tasks.values.any { it !== active && !it.result.isCompleted && it.foreground > 0 }
    if (paused || clearing > 0 || directPlayback > 0 || !active.needed || waitingForeground) {
      // No foreground subscriber can have consumed this history when it is cleared.
      active.progress.value = SpeechProgress()
      activeJob?.cancel()
    }
  }

  private suspend fun execute(task: Task) {
    try {
      val capturedEpoch = guard.withLock { epoch }
      val generation = cache.generation()
      var generated = false
      var origin = "synthesis"
      val audio = withTimeout(30_000L) {
        val inMemory = memoryGet(task.key)
        val cached = inMemory ?: safeRead(task.key)
        if (cached != null && (cached.systemVoiceFingerprint == null ||
            cached.systemVoiceFingerprint == system.fingerprint(task.request.language))) {
          origin = if (inMemory != null) "memory" else "disk"
          emit(task, cached)
          cached
        } else {
          if (cached != null) {
            guard.withLock { memory.remove(task.key)?.let { memoryBytes -= it.audio.pcmBytes } }
            cache.remove(task.key)
          }
          val recentFailure = guard.withLock { failures[task.key]?.let { android.os.SystemClock.elapsedRealtime() - it < 30_000 } == true }
          if (recentFailure) null else { generated = true; generate(task) }
        }
      }
      if (audio != null && audio.isValid()) {
        if (guard.withLock { capturedEpoch == epoch && clearing == 0 }) {
          if (cache.isCurrent(generation)) remember(task.key, audio, capturedEpoch)
          try { if (generated) cache.put(task.key, audio, generation) else cache.touch(task.key) }
          catch (error: CancellationException) { throw error }
          catch (_: Exception) { /* Disk-full and cache I/O failures leave the generated PCM playable. */ }
        }
        if (debug) Log.d("SpeechCache", "origin=$origin key=${task.key.take(12)}")
        finish(task, audio)
      } else finish(task, null)
    } catch (_: TimeoutCancellationException) { finish(task, null) }
    catch (error: CancellationException) {
      withContext(NonCancellable) { guard.withLock {
        if (closed || !task.needed) {
          tasks.remove(task.key)
          task.progress.value = SpeechProgress(finished = true)
          task.result.complete(null)
        }
      } }
      throw error
    } catch (_: Exception) { finish(task, null) }
    catch (_: LinkageError) { finish(task, null) }
  }

  private suspend fun generate(task: Task): SpeechAudio? {
    val emitChunk: suspend (SpeechAudioChunk) -> Unit = { chunk ->
      require(chunk.sampleRate in 8_000..192_000 && chunk.channelCount in 1..2 &&
        chunk.samples.isNotEmpty() && chunk.samples.size % chunk.channelCount == 0)
      guard.withLock {
        currentCoroutineContext().ensureActive()
        val previous = task.progress.value.chunks
        require(previous.size < 4096 && previous.sumOf { it.samples.size.toLong() } + chunk.samples.size <=
          chunk.sampleRate.toLong() * chunk.channelCount * 60)
        if (previous.isNotEmpty()) require(previous.first().sampleRate == chunk.sampleRate &&
          previous.first().channelCount == chunk.channelCount)
        task.progress.value = SpeechProgress(previous + chunk)
      }
    }
    val bundled = try {
      if (task.request.language == AppLanguage.CN) chinese.synthesize(task.request, emitChunk)
      else english.synthesize(task.request.text, emitChunk)
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }
    catch (_: LinkageError) { null }
    if (bundled != null) return bundled
    if (task.progress.value.chunks.isNotEmpty()) return null // Do not speak a second rendition after partial output.
    return system.synthesize(task.request, emitChunk, cache::reserveTemporary, cache::releaseTemporary)
  }

  private suspend fun emit(task: Task, audio: SpeechAudio) = guard.withLock {
    currentCoroutineContext().ensureActive()
    task.progress.value = SpeechProgress(audio.chunks.map { SpeechAudioChunk(audio.sampleRate, it, audio.channelCount) })
  }

  private suspend fun finish(task: Task, audio: SpeechAudio?) = withContext(NonCancellable) {
    val completedEpoch = guard.withLock { epoch }
    val retained = audio != null && retained(task.key)
    guard.withLock {
      tasks.remove(task.key)
      if (audio == null) {
        failures[task.key] = android.os.SystemClock.elapsedRealtime()
        while (failures.size > 128) failures.remove(failures.entries.first().key)
      } else failures.remove(task.key)
      task.progress.value = task.progress.value.copy(finished = true, succeeded = audio != null)
      task.result.complete(audio)
      windows.values.forEach { window -> window.demands[task.key]?.status = when {
        audio == null -> PreloadStatus.FAILED
        retained && completedEpoch == epoch && clearing == 0 -> PreloadStatus.READY
        else -> PreloadStatus.NOT_RETAINED
      } }
      publishPreloadStateLocked()
      mutableChanges.update { it + 1 }
    }
    refreshRetainedPreloads()
  }

  private suspend fun safeRead(key: String): SpeechAudio? {
    if (guard.withLock { clearing > 0 }) return null
    return try { cache.get(key) }
      catch (error: CancellationException) { throw error }
      catch (_: Exception) { null }
  }

  private suspend fun memoryGet(key: String): SpeechAudio? = guard.withLock {
    if (clearing > 0) null else memory[key]?.takeIf { it.generation == epoch }?.audio
  }

  private suspend fun remember(key: String, audio: SpeechAudio, generation: Long) = guard.withLock {
    if (generation != epoch || clearing > 0) return@withLock
    memory.remove(key)?.let { memoryBytes -= it.audio.pcmBytes }
    if (audio.pcmBytes <= MEMORY_LIMIT) {
      while (memory.isNotEmpty() && (memory.size >= 48 || memoryBytes + audio.pcmBytes > MEMORY_LIMIT)) {
        val oldest = memory.entries.first()
        memoryBytes -= oldest.value.audio.pcmBytes
        memory.remove(oldest.key)
      }
      memory[key] = MemoryEntry(audio, generation); memoryBytes += audio.pcmBytes
    }
  }

  /** Register the whole window atomically; completed demands retain metadata, never PCM handles. */
  suspend fun replacePreloadWindow(owner: SpeechCacheOwner, window: SpeechCacheWindow,
    current: () -> Boolean) = withContext(Dispatchers.IO) {
    require(window.demands.size <= 4096)
    val capturedEpoch = guard.withLock { epoch }
    val desired = linkedMapOf<String, PreloadDemand>()
    for (demand in window.demands) {
      currentCoroutineContext().ensureActive()
      require(demand.priority in 1..10_000)
      val request = demand.request.copy(text = demand.request.text.trim().take(2000))
      if (request.text.isEmpty()) continue
      val key = key(request)
      val previous = desired[key]
      if (previous == null || demand.priority < previous.priority)
        desired[key] = PreloadDemand(request, demand.priority)
    }
    // I/O stays outside guard so inspecting a window never blocks foreground subscriptions.
    val ready = desired.filter { (key, demand) -> available(key, demand.request) }.keys
    guard.withLock {
      currentCoroutineContext().ensureActive()
      if (closed || !current()) return@withLock
      val old = windows[owner]
      windows[owner] = PreloadWindow(window.contextVersion, desired)
      for ((key, demand) in desired) {
        val task = tasks[key]
        when {
          task != null -> {
            task.owners[owner] = demand.priority
            demand.status = if (task.running) PreloadStatus.PREPARING else PreloadStatus.QUEUED
          }
          capturedEpoch == epoch && clearing == 0 && key in ready -> demand.status = PreloadStatus.READY
          else -> tasks[key] = Task(key, demand.request, order++).also { it.owners[owner] = demand.priority }
        }
      }
      old?.demands?.keys?.filter { it !in desired }?.forEach { key -> detachPreloadLocked(owner, key) }
      preemptLocked()
      publishPreloadStateLocked()
      wake.trySend(Unit)
    }
    if (debug) Log.d("SpeechCache", "window=${owner.name} context=${window.contextVersion} resources=${desired.size}")
    refreshRetainedPreloads()
  }

  suspend fun releasePreloadWindow(owner: SpeechCacheOwner, current: () -> Boolean) = guard.withLock {
    if (!current()) return@withLock
    windows.remove(owner)?.demands?.keys?.forEach { detachPreloadLocked(owner, it) }
    preemptLocked()
    publishPreloadStateLocked()
    wake.trySend(Unit)
  }

  private fun detachPreloadLocked(owner: SpeechCacheOwner, key: String) {
    val task = tasks[key] ?: return
    task.owners.remove(owner)
    if (!task.needed && !task.running) {
      tasks.remove(key)
      task.progress.value = SpeechProgress(finished = true)
      task.result.complete(null)
    }
  }

  private fun publishPreloadStateLocked() {
    preloadStateRevision++
    mutablePreloadState.value = windows.mapValues { (_, window) ->
      val counts = window.demands.values.groupingBy { it.status }.eachCount()
      SpeechCacheWindowState(window.contextVersion, window.demands.size,
        counts[PreloadStatus.QUEUED] ?: 0, counts[PreloadStatus.PREPARING] ?: 0,
        counts[PreloadStatus.READY] ?: 0, counts[PreloadStatus.FAILED] ?: 0,
        counts[PreloadStatus.NOT_RETAINED] ?: 0)
    }
  }

  private suspend fun available(key: String, request: SpeechRequest): Boolean {
    val audio = memoryGet(key) ?: safeRead(key) ?: return false
    return audio.systemVoiceFingerprint == null || audio.systemVoiceFingerprint == system.fingerprint(request.language)
  }

  private suspend fun retained(key: String): Boolean {
    val memoryReady = guard.withLock {
      if (clearing > 0) return false
      memory.entries.any { it.key == key && it.value.generation == epoch }
    }
    return memoryReady || try { cache.contains(key) }
      catch (error: CancellationException) { throw error }
      catch (_: Exception) { false }
  }

  /** Eviction invalidates readiness, but must never enqueue an unlimited refill loop. */
  private suspend fun refreshRetainedPreloads() {
    val (revision, ready) = guard.withLock { preloadStateRevision to windows.values.flatMap { window -> window.demands.filterValues {
      it.status == PreloadStatus.READY
    }.keys }.toSet() }
    val missing = ready.filterNot { retained(it) }.toSet()
    if (missing.isEmpty()) return
    guard.withLock {
      if (preloadStateRevision != revision) return@withLock
      var changed = false
      windows.values.forEach { window -> missing.forEach { key -> window.demands[key]?.let {
        if (it.status == PreloadStatus.READY) { it.status = PreloadStatus.NOT_RETAINED; changed = true }
      } } }
      publishPreloadStateLocked()
      if (changed) mutableChanges.update { it + 1 }
    }
  }

  /** One bounded pass on resume/clear; a failed or evicted completion does not recursively refill. */
  private suspend fun reconcilePreloads() {
    val capturedEpoch = guard.withLock { epoch }
    val snapshot = guard.withLock { windows.map { (owner, window) -> owner to window } }
    for ((owner, window) in snapshot) {
      val readiness = window.demands.mapValues { (key, demand) -> available(key, demand.request) }
      guard.withLock {
        if (closed || clearing > 0 || epoch != capturedEpoch || windows[owner] !== window) return@withLock
        for ((key, demand) in window.demands) {
          val task = tasks[key]
          when {
            task != null -> { task.owners[owner] = demand.priority
              demand.status = if (task.running) PreloadStatus.PREPARING else PreloadStatus.QUEUED }
            readiness[key] == true && clearing == 0 -> demand.status = PreloadStatus.READY
            else -> { tasks[key] = Task(key, demand.request, order++).also { it.owners[owner] = demand.priority }
              demand.status = PreloadStatus.QUEUED }
          }
        }
        publishPreloadStateLocked()
        wake.trySend(Unit)
      }
    }
    refreshRetainedPreloads()
  }

  suspend fun isReady(requests: List<SpeechRequest>): Boolean = withContext(Dispatchers.IO) {
    if (requests.size > 4096) return@withContext false
    for (request in requests) {
      val normalized = request.copy(text = request.text.trim().take(2000))
      if (normalized.text.isEmpty()) return@withContext false
      val key = key(normalized)
      val audio = memoryGet(key) ?: safeRead(key) ?: return@withContext false
      if (audio.systemVoiceFingerprint != null && audio.systemVoiceFingerprint != system.fingerprint(request.language)) return@withContext false
    }
    true
  }

  fun configureCache(maxMiB: Int) {
    val revision = cacheLimitRevision.incrementAndGet()
    scope.launch {
      try { cache.configure(maxMiB) { revision == cacheLimitRevision.get() } } catch (_: Exception) { }
      refreshRetainedPreloads()
      mutableChanges.update { it + 1 }
    }
  }
  fun clearCache() { scope.launch {
    guard.withLock {
      epoch++; clearing++; memory.clear(); memoryBytes = 0; failures.clear()
      windows.values.forEach { window -> window.demands.values.forEach { it.status = PreloadStatus.NOT_RETAINED } }
      preemptLocked(); publishPreloadStateLocked()
      mutableChanges.update { it + 1 }
    }
    try { cache.clear() } catch (_: Exception) { }
    finally { withContext(NonCancellable) { guard.withLock {
      clearing--; wake.trySend(Unit); mutableChanges.update { it + 1 }
    } } }
    reconcilePreloads()
  } }
  fun setPreloadingPaused(value: Boolean) {
    val revision = preloadRevision.incrementAndGet()
    scope.launch {
      val resume = guard.withLock {
        if (revision != preloadRevision.get()) return@withLock false
        val changed = paused != value
        paused = value
        if (value) preemptLocked() else wake.trySend(Unit)
        changed && !value
      }
      if (resume) reconcilePreloads()
    }
  }

  suspend fun <T> withDirectPlayback(block: suspend () -> T): T {
    guard.withLock { directPlayback++; preemptLocked() }
    return try { block() }
    finally { withContext(NonCancellable) { guard.withLock { directPlayback--; wake.trySend(Unit) } } }
  }

  suspend fun close() = withContext(NonCancellable) {
    guard.withLock {
      closed = true
      tasks.values.forEach { task ->
        task.progress.value = SpeechProgress(finished = true)
        task.result.complete(null)
      }
      tasks.clear(); windows.clear(); publishPreloadStateLocked(); memory.clear(); memoryBytes = 0
      activeJob?.cancel()
    }
    worker.cancel()
    activeJob?.join(); worker.join()
    chinese.close(); english.close(); system.close()
    scope.cancel()
  }

  private suspend fun key(request: SpeechRequest): String {
    val fingerprint = fingerprintLock.withLock {
      fingerprints.getOrPut(request.language) {
        val name = if (request.language == AppLanguage.CN) "chinese-speech/manifest.json" else "english-speech/manifest.json"
        val bytes = app.assets.open(name).use { it.readBytesBounded(2 * 1024 * 1024) }
        if (request.language == AppLanguage.CN) JSONObject(bytes.toString(Charsets.UTF_8))
          .getString("baseManifestSha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        else hash(bytes)
      }
    }
    val pronunciation = if (request.language == AppLanguage.CN) request.chinesePronunciation?.let(ChineseSpeech::key).orEmpty() else ""
    val render = if (request.language == AppLanguage.CN && request.chinesePronunciation?.let {
        it.text.trim() == request.text && ChineseShortSpeech.isShortWord(it)
      } == true) ChineseShortSpeech.RENDER_FINGERPRINT else "render-v1"
    // Scene, counterpart and sourceId belong to presentation, not the acoustic resource identity.
    return hash("pcm-v1/$render/$fingerprint/${request.language}/$ENGLISH_SPEED_MULTIPLIER\n${request.text}\n$pronunciation"
      .toByteArray(Charsets.UTF_8))
  }

  private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

  companion object { private const val MEMORY_LIMIT = 16L * 1024 * 1024 }
}
