package com.example.chinese_flashcard.core.data

import java.io.InputStream
import java.io.BufferedInputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException

internal const val CSV_MAX_ROWS = 20000
internal const val CSV_MAX_BYTES = 32L * 1024 * 1024

internal class CsvFormatException(val line: Int, val fieldIndex: Int, message: String) : IllegalArgumentException(message)
internal data class CsvRecord(val line: Int, val fields: List<String>)

/** Bounded RFC 4180 records, including quoted commas, doubled quotes and embedded newlines. */
internal class CsvParser(input: InputStream) : AutoCloseable {
  private val reader = StrictUtf8Reader(input)
  private var line = 1
  private var firstCharacter = true

  fun next(): CsvRecord? {
    val startLine = line
    val fields = mutableListOf<String>()
    val value = StringBuilder()
    var state = 0 // 0: empty field, 1: unquoted, 2: quoted, 3: closing quote
    var bytes = 0
    var seen = false
    fun fail(message: String): Nothing = throw CsvFormatException(line, fields.size, message)
    fun readCharacter(): Int = try { reader.read() }
    catch (_: java.nio.charset.CharacterCodingException) { fail("This field contains invalid UTF-8 bytes.") }
    fun finishField() {
      if (fields.size >= 64) fail("A record has more than 64 columns.")
      fields += value.toString()
      value.setLength(0)
      state = 0
    }
    while (true) {
      val read = readCharacter()
      if (read < 0) {
        if (state == 2) fail("A quoted field is not closed.")
        if (!seen) return null
        finishField()
        return CsvRecord(startLine, fields)
      }
      val character = read.toChar()
      if (firstCharacter) {
        firstCharacter = false
        if (character == '\uFEFF') continue
      }
      seen = true
      bytes += when {
        read < 0x80 -> 1
        read < 0x800 -> 2
        character.isHighSurrogate() || character.isLowSurrogate() -> 2
        else -> 3
      }
      if (bytes > 32 * 1024) fail("A record is larger than 32 KiB.")
      if (character == '\r' || character == '\n') {
        if (character == '\r') {
          // Look only for the ASCII LF byte; do not decode the next record ahead of its location.
          if (reader.consumeLineFeed()) bytes++
          if (bytes > 32 * 1024) fail("A record is larger than 32 KiB.")
        }
        if (state == 2) {
          value.append('\n')
          line++
        }
        else {
          finishField()
          line++
          return CsvRecord(startLine, fields)
        }
        continue
      }
      when (state) {
        0 -> when (character) {
          ',' -> finishField()
          '"' -> state = 2
          else -> { value.append(character); state = 1 }
        }
        1 -> when (character) {
          ',' -> finishField()
          '"' -> fail("A quote must start a quoted field.")
          else -> value.append(character)
        }
        2 -> if (character == '"') state = 3 else value.append(character)
        3 -> when (character) {
          '"' -> { value.append('"'); state = 2 }
          ',' -> finishField()
          else -> fail("Only a comma or newline may follow a closing quote.")
        }
      }
    }
  }

  override fun close() = reader.close()
}

/** Decode one code point at a time so a later malformed byte cannot mask its physical CSV location. */
private class StrictUtf8Reader(input: InputStream) : Reader() {
  private val input = BufferedInputStream(input)
  private val decoder = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
  private val bytes = ByteArray(4)
  private val byteBuffer = ByteBuffer.wrap(bytes)
  private val characters = CharBuffer.allocate(2)
  private var pending = -1

  fun consumeLineFeed(): Boolean {
    input.mark(1)
    val following = input.read()
    if (following == '\n'.code) return true
    if (following >= 0) input.reset()
    return false
  }

  override fun read(): Int {
    if (pending >= 0) return pending.also { pending = -1 }
    val first = input.read()
    if (first < 0 || first < 0x80) return first
    val length = when (first) {
      in 0xC2..0xDF -> 2
      in 0xE0..0xEF -> 3
      in 0xF0..0xF4 -> 4
      else -> throw MalformedInputException(1)
    }
    bytes[0] = first.toByte()
    for (position in 1 until length) {
      val next = input.read()
      if (next < 0) throw MalformedInputException(position)
      bytes[position] = next.toByte()
    }
    decoder.reset()
    characters.clear()
    byteBuffer.clear()
    byteBuffer.limit(length)
    val result = decoder.decode(byteBuffer, characters, true)
    if (result.isError) result.throwException()
    characters.flip()
    val character = characters.get().code
    if (characters.hasRemaining()) pending = characters.get().code
    return character
  }

  override fun read(buffer: CharArray, offset: Int, length: Int): Int {
    require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
    if (length == 0) return 0
    val character = read()
    if (character < 0) return -1
    buffer[offset] = character.toChar()
    return 1
  }

  override fun close() = input.close()
}
