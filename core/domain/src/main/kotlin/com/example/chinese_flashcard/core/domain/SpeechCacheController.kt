package com.example.chinese_flashcard.core.domain

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Identity belongs to one caller lifetime, even when two pages have the same display name. */
class SpeechCacheOwner(val name: String)

data class SpeechCacheDemand(val request: SpeechRequest, val priority: Int)
data class SpeechCacheWindow(val contextVersion: Long, val demands: List<SpeechCacheDemand>)
data class SpeechCacheUsage(val usedBytes: Long, val limitBytes: Long)

data class SpeechCacheWindowState(val contextVersion: Long, val total: Int = 0,
  val queued: Int = 0, val preparing: Int = 0, val ready: Int = 0, val failed: Int = 0,
  val notRetained: Int = 0) {
  val finished: Boolean get() = queued == 0 && preparing == 0
  val isReady: Boolean get() = ready == total
}

object SpeechCachePriority {
  const val CURRENT_WORD = 10
  const val UPCOMING_WORD = 20
  const val CURRENT_EXAMPLE = 100
  const val UPCOMING_EXAMPLE = 120
  const val HOME_ENTRY = 300
}

/** All speculative speech work shares this entry point and the playback resource worker. */
interface SpeechCacheController {
  val state: StateFlow<Map<SpeechCacheOwner, SpeechCacheWindowState>>
  val changes: StateFlow<Long>
  val usage: StateFlow<SpeechCacheUsage>
  suspend fun updateWindow(owner: SpeechCacheOwner, window: SpeechCacheWindow)
  fun release(owner: SpeechCacheOwner)
  suspend fun isReady(requests: List<SpeechRequest>): Boolean
  fun setForeground(active: Boolean)
  fun configure(maxMiB: Int)
  fun clear()
}

/** A short-lived client can await a window without retaining any PCM or playback handles. */
suspend fun SpeechCacheController.prepare(owner: SpeechCacheOwner, window: SpeechCacheWindow): Boolean {
  try {
    updateWindow(owner, window)
    val result = state.first { it[owner]?.let { value ->
      value.contextVersion == window.contextVersion && value.finished
    } == true }[owner] ?: return false
    return result.isReady && isReady(window.demands.map { it.request })
  } finally { release(owner) }
}
