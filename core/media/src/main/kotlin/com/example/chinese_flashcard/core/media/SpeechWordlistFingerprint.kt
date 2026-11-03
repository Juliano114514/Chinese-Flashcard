package com.example.chinese_flashcard.core.media

import android.content.res.AssetManager

/** Both speech manifests must describe the wordlist packaged with this app. */
internal fun AssetManager.wordlistFingerprint(): String = open("default-wordlist/version.txt").use {
  val bytes = it.readBytesBounded(66)
  require(bytes.size in 64..66)
  bytes.toString(Charsets.US_ASCII).trim().also { value -> require(value.matches(Regex("[0-9a-f]{64}"))) }
}
