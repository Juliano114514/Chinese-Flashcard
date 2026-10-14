package com.example.chinese_flashcard.core.media

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** App-private, regenerable PCM files. The index is rebuilt from files, never from business data. */
internal class SpeechDiskCache(context: Context) {
  private val directory = File(context.applicationContext.noBackupFilesDir, "speech-cache")
  private val lock = Mutex()
  private val entries = LinkedHashMap<String, Entry>()
  private val pins = mutableMapOf<String, Int>()
  private val deleteOnRelease = mutableSetOf<String>()
  private data class Temporary(val reserved: Long, val used: Long)
  private val temporary = mutableMapOf<String, Temporary>()
  private var initialized = false
  private var generation = 0L
  private var limit = 256L * 1024 * 1024
  private var clock = System.currentTimeMillis()
  private val mutableUsage = MutableStateFlow(SpeechCacheUsage(0, limit))
  val usage = mutableUsage.asStateFlow()
  private data class Entry(val file: File, val bytes: Long, var accessed: Long)

  suspend fun initialize() = withContext(Dispatchers.IO) { lock.withLock { initializeLocked() } }
  suspend fun generation(): Long = lock.withLock { generation }
  suspend fun isCurrent(value: Long): Boolean = lock.withLock { generation == value }

  suspend fun pin(key: String) = lock.withLock { pins[key] = (pins[key] ?: 0) + 1 }
  suspend fun unpin(key: String) = withContext(Dispatchers.IO) { lock.withLock {
    val remaining = (pins[key] ?: 1) - 1
    if (remaining > 0) pins[key] = remaining else {
      pins.remove(key)
      if (deleteOnRelease.remove(key)) removeLocked(key)
    }
    trimLocked()
  } }

  suspend fun get(key: String): SpeechAudio? = withContext(Dispatchers.IO) { lock.withLock {
    initializeLocked()
    if (key in deleteOnRelease) return@withLock null
    val entry = entries[key] ?: return@withLock null
    try {
      currentCoroutineContext().ensureActive()
      val audio = read(entry.file)
      entry.accessed = nextAccess()
      entry.file.setLastModified(entry.accessed)
      audio
    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
    catch (_: Exception) { removeLocked(key); publish(); null }
  } }

  suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) { lock.withLock {
    initializeLocked()
    if (key in deleteOnRelease) return@withLock false
    val entry = entries[key] ?: return@withLock false
    if (entry.file.isFile && entry.file.length() == entry.bytes) true
    else { removeLocked(key); publish(); false }
  } }

  suspend fun touch(key: String) = withContext(Dispatchers.IO) { lock.withLock {
    entries[key]?.takeIf { key !in deleteOnRelease }?.let {
      it.accessed = nextAccess(); it.file.setLastModified(it.accessed)
    }
  } }

  suspend fun put(key: String, audio: SpeechAudio, expectedGeneration: Long) = withContext(Dispatchers.IO) {
    require(key.matches(KEY) && audio.isValid())
    lock.withLock {
      initializeLocked()
      if (generation != expectedGeneration || deleteOnRelease.contains(key)) return@withLock
      // One writer, with its temporary bytes reserved against the same capacity before writing.
      val estimated = audio.pcmBytes + 64L + audio.chunks.size * 4L +
        (audio.systemVoiceFingerprint?.toByteArray(Charsets.UTF_8)?.size ?: 0)
      if (estimated > limit) return@withLock
      trimLocked(estimated, key)
      if (entries.values.sumOf { it.bytes } + temporary.values.sumOf { it.reserved } + estimated > limit) return@withLock
      val target = File(directory, "$key.pcm")
      val atomic = AtomicFile(target)
      var output: java.io.FileOutputStream? = null
      try {
        val opened = atomic.startWrite()
        output = opened
        write(opened, audio)
        currentCoroutineContext().ensureActive()
        atomic.finishWrite(opened)
        output = null
        val accessed = nextAccess()
        target.setLastModified(accessed)
        entries[key] = Entry(target, target.length(), accessed)
        trimLocked()
      } catch (error: kotlinx.coroutines.CancellationException) {
        output?.let(atomic::failWrite)
        throw error
      } catch (_: Exception) { output?.let(atomic::failWrite) }
      finally { publish() }
    }
  }

  suspend fun remove(key: String) = withContext(Dispatchers.IO) { lock.withLock {
    if ((pins[key] ?: 0) > 0) deleteOnRelease.add(key) else removeLocked(key)
    publish()
  } }

  suspend fun configure(maxMiB: Int, isCurrent: () -> Boolean = { true }) = withContext(Dispatchers.IO) { lock.withLock {
    if (!isCurrent()) return@withLock
    limit = maxMiB.coerceIn(1, 4096).toLong() * 1024 * 1024
    initializeLocked()
    trimLocked()
  } }

  suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock {
    initializeLocked()
    generation++
    entries.keys.toList().forEach { key ->
      if ((pins[key] ?: 0) > 0) deleteOnRelease.add(key) else removeLocked(key)
    }
    publish()
  } }

  /** Silent system-TTS WAV output uses the same storage budget, despite living in cacheDir. */
  suspend fun reserveTemporary(id: String, reservedBytes: Long, usedBytes: Long): Boolean = withContext(Dispatchers.IO) {
    lock.withLock {
      initializeLocked()
      require(reservedBytes >= 0 && usedBytes in 0..reservedBytes)
      if (temporary[id]?.reserved == reservedBytes) {
        temporary[id] = Temporary(reservedBytes, usedBytes)
        publish()
        return@withLock true
      }
      if (reservedBytes > limit) return@withLock false
      val previous = temporary.remove(id)
      trimLocked(reservedBytes)
      if (entries.values.sumOf { it.bytes } + temporary.values.sumOf { it.reserved } + reservedBytes > limit) {
        previous?.let { temporary[id] = it }
        publish()
        false
      } else {
        temporary[id] = Temporary(reservedBytes, usedBytes)
        publish()
        true
      }
    }
  }
  suspend fun releaseTemporary(id: String) = withContext(Dispatchers.IO) { lock.withLock {
    temporary.remove(id); trimLocked()
  } }

  private fun initializeLocked() {
    if (initialized) return
    check(directory.isDirectory || directory.mkdirs())
    directory.listFiles().orEmpty().forEach { file ->
      val key = file.name.removeSuffix(".pcm")
      if (file.isFile && file.name.endsWith(".pcm") && key.matches(KEY) && file.length() in 1..MAX_FILE_BYTES) {
        entries[key] = Entry(file, file.length(), file.lastModified())
        clock = maxOf(clock, file.lastModified())
      } else if (file.isFile) file.delete()
    }
    initialized = true
    trimLocked()
  }

  private fun trimLocked(reserved: Long = 0, protectedKey: String? = null) {
    var used = entries.values.sumOf { it.bytes }
    val temporaryReserved = temporary.values.sumOf { it.reserved }
    val ordered = entries.entries.sortedWith(compareBy({ it.value.accessed }, { it.key }))
    for ((key, entry) in ordered) {
      if (used + temporaryReserved + reserved <= limit) break
      if (key == protectedKey || (pins[key] ?: 0) > 0) continue
      if (removeLocked(key)) used -= entry.bytes
    }
    publish()
  }

  private fun removeLocked(key: String): Boolean {
    val entry = entries[key] ?: return true
    if (!entry.file.exists() || entry.file.delete()) { entries.remove(key); return true }
    return false
  }
  private fun nextAccess(): Long = maxOf(System.currentTimeMillis(), clock + 1).also { clock = it }
  private fun publish() { mutableUsage.value = SpeechCacheUsage(entries.values.sumOf { it.bytes } + temporary.values.sumOf { it.used }, limit) }

  private suspend fun write(stream: java.io.OutputStream, audio: SpeechAudio) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffered = BufferedOutputStream(stream, 64 * 1024)
    val hashed = DigestOutputStream(buffered, digest)
    val data = DataOutputStream(hashed)
    data.writeInt(MAGIC); data.writeInt(VERSION)
    data.writeInt(audio.sampleRate); data.writeInt(audio.channelCount)
    data.writeUTF(audio.systemVoiceFingerprint.orEmpty())
    data.writeInt(audio.chunks.size)
    data.writeLong(audio.sampleCount)
    audio.chunks.forEach { samples ->
      data.writeInt(samples.size)
      samples.forEachIndexed { index, sample ->
        if (index % 16384 == 0) currentCoroutineContext().ensureActive()
        data.writeFloat(sample)
      }
    }
    data.flush()
    hashed.on(false)
    data.write(digest.digest())
    data.flush()
  }

  private suspend fun read(file: File): SpeechAudio {
    require(file.length() in 1..MAX_FILE_BYTES)
    val digest = MessageDigest.getInstance("SHA-256")
    return file.inputStream().use { raw ->
      val hashed = DigestInputStream(BufferedInputStream(raw, 64 * 1024), digest)
      val data = DataInputStream(hashed)
      require(data.readInt() == MAGIC && data.readInt() == VERSION)
      val rate = data.readInt(); val channels = data.readInt()
      require(rate in 8_000..192_000 && channels in 1..2)
      val voice = data.readUTF().takeIf(String::isNotEmpty)
      require((voice?.length ?: 0) <= 2048)
      val count = data.readInt(); val expectedSamples = data.readLong()
      require(count in 1..4096 && expectedSamples in 1..rate.toLong() * channels * 60)
      require(expectedSamples % channels == 0L && expectedSamples * 4 < file.length())
      var total = 0L
      val chunks = ArrayList<FloatArray>(count)
      repeat(count) {
        val size = data.readInt()
        require(size > 0 && size % channels == 0 && total + size <= expectedSamples)
        val samples = FloatArray(size)
        for (index in samples.indices) {
          if (index % 16384 == 0) currentCoroutineContext().ensureActive()
          val value = data.readFloat()
          require(value.isFinite() && value in -1f..1f)
          samples[index] = value
        }
        chunks.add(samples); total += size
      }
      require(total == expectedSamples)
      val actual = digest.digest()
      hashed.on(false)
      val expected = ByteArray(32); data.readFully(expected)
      require(MessageDigest.isEqual(actual, expected) && data.read() == -1)
      SpeechAudio(rate, chunks, channels, voice)
    }
  }

  companion object {
    private const val MAGIC = 0x43535043
    private const val VERSION = 1
    private const val MAX_FILE_BYTES = 96L * 1024 * 1024
    private val KEY = Regex("[0-9a-f]{64}")
  }
}
