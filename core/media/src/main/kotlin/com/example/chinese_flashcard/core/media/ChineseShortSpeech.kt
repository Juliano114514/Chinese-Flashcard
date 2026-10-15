package com.example.chinese_flashcard.core.media

import android.content.res.AssetManager
import com.example.chinese_flashcard.core.domain.ChinesePronunciation
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Reviewed Melo renditions; source PCM16 is decoded before the shared playback/cache path. */
internal class ChineseShortSpeech(private val assets: AssetManager) {
  private data class Reading(val text: String, val phones: LongArray, val tones: LongArray,
    val path: String, val bytes: Int, val samples: Int, val sha256: String) {
    fun matches(text: String, input: MandarinPhones): Boolean = this.text == text &&
      phones.contentEquals(input.phoneIds) && tones.contentEquals(input.tones)
  }

  private val readings: Map<String, Reading>

  init {
    val bytes = assets.open("chinese-speech/short-words/index.json").use { it.readBytesBounded(INDEX_BYTES) }
    check(bytes.size == INDEX_BYTES && sha256(bytes) == INDEX_SHA256)
    val index = JSONObject(bytes.toString(Charsets.UTF_8))
    require(index.getInt("schemaVersion") == 1 && index.getInt("sampleRate") == SAMPLE_RATE &&
      index.getInt("channelCount") == 1 && index.getString("encoding") == "pcm-s16le")
    require(index.getString("modelSha256") == "bf30582eb1b012250a35b1a4a80e7dfbcf8485e7bb9de0d95efbbeef0e4ad86d")
    val entries = index.getJSONObject("entries")
    require(entries.length() == 23)
    readings = entries.keys().asSequence().associateWith { key ->
      require(key.matches(SHA256))
      val entry = entries.getJSONObject(key)
      val hint = ChinesePronunciation(entry.getString("text"), entry.getString("pinyin"))
      require(isShortWord(hint) && hint.pinyin.length in 1..100 && ChineseSpeech.key(hint) == key)
      val ids = entry.getJSONArray("phoneIds")
      val tones = entry.getJSONArray("tones")
      require(ids.length() in 1..32 && ids.length() == tones.length())
      val phones = LongArray(ids.length()) { ids.getLong(it).also { id -> require(id in 1L..102L) } }
      val toneIds = LongArray(tones.length()) { tones.getLong(it).also { tone -> require(tone in 0L..5L) } }
      val path = entry.getString("path")
      val samples = entry.getInt("samples")
      require(path == "chinese-speech/short-words/$key.pcm" && samples in 1..SAMPLE_RATE * 4)
      require(entry.getInt("bytes") == samples * 2 && entry.getString("sha256").matches(SHA256))
      Reading(hint.text, phones, toneIds, path, samples * 2, samples, entry.getString("sha256"))
    }
  }

  fun samples(hint: ChinesePronunciation, key: String, phones: MandarinPhones, context: CoroutineContext): FloatArray? {
    val text = hint.text.trim()
    // Equivalent pinyin spellings may share a sample only after their supplied
    // pronunciation has resolved to exactly the same text, phones and tones.
    val reading = readings[key]?.takeIf { it.matches(text, phones) }
      ?: readings.values.firstOrNull { it.matches(text, phones) } ?: return null
    context.ensureActive()
    val bytes = assets.open(reading.path).use { it.readBytesBounded(reading.bytes) }
    check(bytes.size == reading.bytes && sha256(bytes) == reading.sha256)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(reading.samples) { index ->
      if (index % 16384 == 0) context.ensureActive()
      buffer.short / 32768f
    }
  }

  companion object {
    private const val SAMPLE_RATE = 44100
    private const val INDEX_BYTES = 8084
    private const val INDEX_SHA256 = "4746e4835f5ecf637b8bfe81228d5f208e1f82e0d75a16ebe28f941dd3795b44"
    // Bump the render version when the gain policy changes. Long/English keys stay on render-v1.
    const val RENDER_FINGERPRINT = "render-short-v2/$INDEX_SHA256/c6029f601d660bd8ab720d1b6a159638c7613d9e4cbc4bd06f347c372205de55"
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val TARGET_RMS = 10.0.pow(-22.0 / 20) // Existing long-speech activity median is approximately -21.8 dBFS.
    private val MIN_ACTIVE_RMS = 10.0.pow(-38.0 / 20)
    private val ACTIVITY_FLOOR = 10.0.pow(-55.0 / 20)
    private val RELATIVE_ACTIVITY_FLOOR = 10.0.pow(-35.0 / 20)
    private val MAX_GAIN = 10.0.pow(18.0 / 20)
    private const val MAX_SAMPLE_PEAK = .79

    private data class Activity(val rms: Double, val samples: Int, val peak: Double) {
      fun usable(): Boolean = samples >= SAMPLE_RATE * .08 && rms >= MIN_ACTIVE_RMS && peak > 0.0
    }

    fun isShortWord(hint: ChinesePronunciation): Boolean {
      val text = hint.text.trim()
      val count = text.codePointCount(0, text.length)
      return count in 1..4 && (count == 1 || hint.chunks.isEmpty()) &&
        text.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
    }

    /** Amplitude only; never raises an unreviewed extremely weak/noise-like rendition. */
    fun normalize(samples: FloatArray, context: CoroutineContext): FloatArray {
      val activity = activity(samples, context)
      if (!activity.usable()) return samples
      val gain = minOf(TARGET_RMS / activity.rms, MAX_GAIN, MAX_SAMPLE_PEAK / activity.peak)
      samples.indices.forEach { index ->
        if (index % 16384 == 0) context.ensureActive()
        samples[index] = (samples[index] * gain).toFloat()
      }
      context.ensureActive()
      return samples
    }

    // This is a waveform guard, not a phoneme-correctness classifier.
    fun hasActivity(samples: FloatArray, context: CoroutineContext): Boolean = activity(samples, context).usable()

    private fun activity(samples: FloatArray, context: CoroutineContext): Activity {
      val frameSize = SAMPLE_RATE / 50 // 20 ms; complete waveform and edge phones are preserved.
      val frameSquares = DoubleArray((samples.size + frameSize - 1) / frameSize)
      var maximumRms = 0.0
      var peak = 0.0
      frameSquares.indices.forEach { frame ->
        if (frame % 16 == 0) context.ensureActive()
        val start = frame * frameSize
        val end = minOf(start + frameSize, samples.size)
        var squares = 0.0
        for (index in start until end) {
          val sample = samples[index].toDouble()
          peak = maxOf(peak, abs(sample))
          squares += sample * sample
        }
        frameSquares[frame] = squares
        maximumRms = maxOf(maximumRms, sqrt(squares / (end - start)))
      }
      val threshold = maxOf(ACTIVITY_FLOOR, maximumRms * RELATIVE_ACTIVITY_FLOOR)
      var activeSquares = 0.0
      var activeSamples = 0
      frameSquares.indices.forEach { frame ->
        val count = minOf(frameSize, samples.size - frame * frameSize)
        if (sqrt(frameSquares[frame] / count) >= threshold) {
          activeSquares += frameSquares[frame]
          activeSamples += count
        }
      }
      return Activity(if (activeSamples == 0) 0.0 else sqrt(activeSquares / activeSamples), activeSamples, peak)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
  }
}
