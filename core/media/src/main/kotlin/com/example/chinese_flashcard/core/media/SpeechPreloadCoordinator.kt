package com.example.chinese_flashcard.core.media

import com.example.chinese_flashcard.core.domain.SpeechCacheController
import com.example.chinese_flashcard.core.domain.SpeechCacheOwner
import com.example.chinese_flashcard.core.domain.SpeechCacheWindow
import com.example.chinese_flashcard.core.domain.SpeechRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Owns commands, not a second synthesis queue. Revision checks reject late window updates/releases. */
internal class SpeechPreloadCoordinator(private val resources: SpeechResources) : SpeechCacheController {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val revisions = mutableMapOf<SpeechCacheOwner, Long>()
  private var serial = 0L
  override val state = resources.preloadState
  override val changes = resources.changes
  override val usage = resources.cacheUsage

  override suspend fun updateWindow(owner: SpeechCacheOwner, window: SpeechCacheWindow) {
    val revision = synchronized(revisions) { (++serial).also { revisions[owner] = it } }
    resources.replacePreloadWindow(owner, window) { synchronized(revisions) { revisions[owner] == revision } }
  }

  override fun release(owner: SpeechCacheOwner) {
    synchronized(revisions) { revisions.remove(owner) }
    scope.launch { resources.releasePreloadWindow(owner) { synchronized(revisions) { owner !in revisions } } }
  }

  override suspend fun isReady(requests: List<SpeechRequest>): Boolean = try { resources.isReady(requests) }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { false }
    catch (_: LinkageError) { false }

  override fun setForeground(active: Boolean) = resources.setPreloadingPaused(!active)
  override fun configure(maxMiB: Int) = resources.configureCache(maxMiB)
  override fun clear() = resources.clearCache()
  fun close() { synchronized(revisions) { revisions.clear() }; scope.cancel() }
}
