package com.example.chinese_flashcard.core.media

import android.content.res.AssetManager
import com.example.chinese_flashcard.core.domain.StrokeName
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Fixed clips only. Missing/corrupt narration never invokes a synthesizer or downloader. */
internal class StrokeSpeech(private val assets: AssetManager) {
  private val index by lazy {
    val bytes = assets.open("writing-strokes/index.json").use { it.readBytesBounded(32768) }
    JSONObject(bytes.toString(Charsets.UTF_8)).also {
      require(it.getInt("version") == 1 && it.getInt("sampleRate") == 44100 && it.getString("encoding") == "pcm-s16le")
    }.getJSONObject("entries")
  }

  fun audio(name: StrokeName): SpeechAudio {
    val entry = index.getJSONObject(name.name)
    val size = entry.getInt("bytes")
    require(size in 8820..529200 && size % 2 == 0)
    val bytes = assets.open("writing-strokes/${name.name}.pcm").use { it.readBytesBounded(size) }
    require(bytes.size == size)
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it.toInt() and 255) }
    require(hash == entry.getString("sha256"))
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return SpeechAudio(44100, listOf(FloatArray(size / 2) { buffer.short / 32768f }))
  }
}
