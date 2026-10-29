package com.example.chinese_flashcard.core.data

import com.example.chinese_flashcard.core.domain.MAX_PROGRESS_CODE_LENGTH
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal data class ProgressCodeWord(val id: String, val chinese: Int)

/** CFP3: four adaptive numeric-ID sets, literal custom IDs, then an envelope CRC32. */
internal object ProgressCode {
  const val MAX_TEXT = MAX_PROGRESS_CODE_LENGTH
  private const val MAX_BYTES = 8_000_000
  private const val MAX_WORDS = 100_000
  private const val MAX_NUMBER = 99_999
  private val numericId = Regex("wl_[0-9]{5}")
  const val LEARNING = 1
  const val LEARNED = 2
  const val SKIPPED = 4
  const val MISTAKE = 8
  private val flags = intArrayOf(LEARNING, LEARNED, SKIPPED, MISTAKE)

  fun encode(words: List<ProgressCodeWord>): String {
    require(words.size <= MAX_WORDS)
    val ids = HashSet<String>()
    words.forEach {
      validateId(it.id)
      require(ids.add(it.id) && it.chinese in 1..15 && it.chinese and 3 != 3)
    }
    val numbers = words.mapNotNull { word -> number(word.id)?.let { it to word.chinese } }.sortedBy { it.first }
    val output = ByteArrayOutputStream()
    output.write(3)
    for (flag in flags) {
      val values = numbers.filter { it.second and flag != 0 }.map { it.first }
      val candidates = listOf(delta(values), ranges(values), bitmap(values))
      // Compare complete stored blocks, including method, count, size and optional GZIP overhead.
      val block = candidates.flatMapIndexed { method, raw ->
        listOf(block(method, values.size, raw), block(method or 4, values.size, gzip(raw)))
      }.minBy { it.size }
      output.write(block)
    }
    val custom = words.filter { number(it.id) == null }.sortedBy { it.id }
    val literals = ByteArrayOutputStream()
    for (word in custom) {
      val bytes = word.id.toByteArray(Charsets.UTF_8)
      literals.varInt(bytes.size)
      literals.write(bytes)
      literals.write(word.chinese)
      require(literals.size() <= MAX_BYTES)
    }
    val raw = literals.toByteArray()
    output.write(listOf(block(0, custom.size, raw), block(4, custom.size, gzip(raw))).minBy { it.size })
    val payload = output.toByteArray()
    require(payload.size <= MAX_BYTES)
    val crc = CRC32().apply { update(payload) }.value
    for (shift in intArrayOf(24, 16, 8, 0)) output.write((crc ushr shift).toInt() and 255)
    return ("CFP3:" + Base64.getEncoder().encodeToString(output.toByteArray())).also {
      require(it.length <= MAX_TEXT)
    }
  }

  fun decode(code: String): List<ProgressCodeWord> {
    require(code.length <= MAX_TEXT)
    val text = code.filterNot { it.isWhitespace() }
    require(text.startsWith("CFP3:"))
    val encoded = text.substring(5)
    val bytes = Base64.getDecoder().decode(encoded)
    require(bytes.size >= 20 && Base64.getEncoder().encodeToString(bytes) == encoded)
    val length = bytes.size - 4
    val storedCrc = (length until bytes.size).fold(0L) { value, i -> (value shl 8) or (bytes[i].toLong() and 255) }
    require(CRC32().apply { update(bytes, 0, length) }.value == storedCrc)
    val reader = Reader(bytes.copyOf(length))
    require(reader.byte() == 3)
    val masks = sortedMapOf<Int, Int>()
    for (flag in flags) {
      val method = reader.byte()
      require(method in 0..2 || method in 4..6)
      val count = reader.varInt().also { require(it <= MAX_NUMBER) }
      val body = reader.block(method, maxOf(count * 6, 12_503))
      val values = readSet(method and 3, count, body)
      for (value in values) masks[value] = (masks[value] ?: 0) or flag
    }
    val method = reader.byte().also { require(it == 0 || it == 4) }
    val count = reader.varInt().also { require(it <= MAX_WORDS - masks.size) }
    val custom = Reader(reader.block(method, MAX_BYTES))
    val words = masks.map { (id, mask) ->
      require(mask and 3 != 3)
      ProgressCodeWord("wl_" + id.toString().padStart(5, '0'), mask)
    }.toMutableList()
    val customIds = HashSet<String>()
    repeat(count) {
      val size = custom.varInt().also { require(it in 1..1024) }
      val id = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(custom.bytes(size))).toString()
      validateId(id)
      require(number(id) == null && customIds.add(id))
      val mask = custom.byte().also { require(it in 1..15 && it and 3 != 3) }
      words.add(ProgressCodeWord(id, mask))
    }
    require(custom.finished && reader.finished)
    return words
  }

  private fun validateId(id: String) {
    require(id.isNotBlank() && id.length <= 256 && id.none { it.isISOControl() })
    require(Charsets.UTF_8.newEncoder().canEncode(id))
  }

  private fun number(id: String): Int? = if (numericId.matches(id)) id.substring(3).toInt().takeIf { it > 0 } else null

  private fun delta(values: List<Int>): ByteArray {
    val out = ByteArrayOutputStream()
    var previous = 0
    for (value in values) { out.varInt(value - previous); previous = value }
    return out.toByteArray()
  }

  private fun ranges(values: List<Int>): ByteArray {
    val out = ByteArrayOutputStream()
    var index = 0
    var previousEnd = 0
    while (index < values.size) {
      val start = values[index++]
      var end = start
      while (index < values.size && values[index] == end + 1) end = values[index++]
      out.varInt(start - previousEnd)
      out.varInt(end - start)
      previousEnd = end
    }
    return out.toByteArray()
  }

  private fun bitmap(values: List<Int>): ByteArray {
    if (values.isEmpty()) return byteArrayOf()
    val start = values.first()
    val bits = ByteArray((values.last() - start) / 8 + 1)
    for (value in values) {
      val offset = value - start
      bits[offset / 8] = (bits[offset / 8].toInt() or (1 shl (offset % 8))).toByte()
    }
    return ByteArrayOutputStream().apply { varInt(start); write(bits) }.toByteArray()
  }

  private fun readSet(method: Int, count: Int, bytes: ByteArray): List<Int> {
    val input = Reader(bytes)
    val result = ArrayList<Int>(count)
    var previous = 0
    fun add(value: Int) {
      require(result.size < count && value in 1..MAX_NUMBER && value > previous)
      result.add(value)
      previous = value
    }
    when (method) {
      0 -> repeat(count) {
        val gap = input.varInt()
        require(gap in 1..MAX_NUMBER - previous)
        add(previous + gap)
      }
      1 -> while (!input.finished) {
        val gap = input.varInt()
        require(gap in 1..MAX_NUMBER - previous)
        val start = previous + gap
        val length = input.varInt()
        require(length <= MAX_NUMBER - start && length < count - result.size)
        for (value in start..start + length) add(value)
      }
      2 -> if (count > 0) {
        val start = input.varInt().also { require(it in 1..MAX_NUMBER) }
        var offset = 0
        require(!input.finished)
        while (!input.finished) {
          val bits = input.byte()
          for (bit in 0..7) if (bits and (1 shl bit) != 0) add(start + offset + bit)
          offset += 8
          require(offset <= MAX_NUMBER + 7)
        }
      }
    }
    require(input.finished && result.size == count)
    return result
  }

  private fun block(method: Int, count: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().apply {
    write(method); varInt(count); varInt(body.size); write(body)
  }.toByteArray()

  private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
    GZIPOutputStream(out).use { it.write(bytes) }
  }.toByteArray()

  private fun ByteArrayOutputStream.varInt(value: Int) {
    require(value >= 0)
    var remaining = value
    while (remaining >= 128) { write((remaining and 127) or 128); remaining = remaining ushr 7 }
    write(remaining)
  }

  private class Reader(private val data: ByteArray) {
    private var position = 0
    val finished: Boolean get() = position == data.size
    fun byte(): Int {
      require(position < data.size)
      return data[position++].toInt() and 255
    }
    fun varInt(): Int {
      var value = 0
      for (index in 0..4) {
        val next = byte()
        require(index < 4 || next <= 7)
        value = value or ((next and 127) shl (index * 7))
        if (next < 128) {
          require(index == 0 || next != 0)
          return value
        }
      }
      error("Invalid integer")
    }
    fun bytes(size: Int): ByteArray {
      require(size >= 0 && size <= data.size - position)
      return data.copyOfRange(position, position + size).also { position += size }
    }
    fun block(method: Int, maxBytes: Int): ByteArray {
      val size = varInt().also { require(it <= MAX_BYTES) }
      val stored = bytes(size)
      if (method and 4 == 0) return stored.also { require(it.size <= maxBytes) }
      val out = ByteArrayOutputStream()
      GZIPInputStream(stored.inputStream()).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
          val read = input.read(buffer)
          if (read == -1) break
          require(out.size() + read <= maxBytes)
          out.write(buffer, 0, read)
        }
      }
      return out.toByteArray()
    }
  }
}
