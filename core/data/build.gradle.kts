import java.security.MessageDigest
import java.io.File
import java.io.InputStreamReader
import java.io.PushbackReader
import java.nio.charset.CodingErrorAction
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins { alias(libs.plugins.android.library); alias(libs.plugins.ksp) }

@CacheableTask
abstract class BundleDefaultWordlist : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val csvFile: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun bundle() {
    val directory = outputDirectory.get().dir("default-wordlist").asFile
    check(directory.isDirectory || directory.mkdirs()) { "Default wordlist assets could not be prepared." }
    val bundled = directory.resolve("wordlist.csv")
    csvFile.get().asFile.copyTo(bundled, overwrite = true)
    val digest = MessageDigest.getInstance("SHA-256")
    bundled.inputStream().use { input ->
      val block = ByteArray(8192)
      while (true) {
        val count = input.read(block)
        if (count < 0) break
        digest.update(block, 0, count)
      }
    }
    val version = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    directory.resolve("version.txt").writeText("$version\n", Charsets.US_ASCII)
    directory.resolve("rows.txt").writeText("${countCsvRows(bundled)}\n", Charsets.US_ASCII)
  }

  /** Count records without decoding teaching JSON; newlines inside quoted fields stay in a record. */
  private fun countCsvRows(file: File): Int {
    check(file.length() <= 32L * 1024 * 1024) { "The default wordlist exceeds 32 MiB." }
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    var records = 0
    var state = 0 // empty field, unquoted, quoted, closing quote
    var fields = 1
    var hasContent = false
    var first = true
    fun finishRecord() {
      check(state != 2) { "The default wordlist has an unclosed quoted field." }
      if (fields > 1 || hasContent) records++
      check(records <= 20_001) { "The default wordlist exceeds 20,000 words." }
      state = 0; fields = 1; hasContent = false
    }
    PushbackReader(InputStreamReader(file.inputStream(), decoder).buffered(), 1).use { reader ->
      while (true) {
        val read = reader.read()
        if (read < 0) break
        val character = read.toChar()
        if (first) {
          first = false
          if (character == '\uFEFF') continue
        }
        if (character == '\r' || character == '\n') {
          if (character == '\r') {
            val next = reader.read()
            if (next >= 0 && next != '\n'.code) reader.unread(next)
          }
          if (state != 2) finishRecord()
          continue
        }
        when (state) {
          0 -> when (character) {
            ',' -> fields++
            '"' -> state = 2
            else -> { state = 1; if (!character.isWhitespace()) hasContent = true }
          }
          1 -> when (character) {
            ',' -> { fields++; state = 0 }
            '"' -> error("A quote must start a default CSV field.")
            else -> if (!character.isWhitespace()) hasContent = true
          }
          2 -> if (character == '"') state = 3 else if (!character.isWhitespace()) hasContent = true
          3 -> when (character) {
            '"' -> { state = 2; hasContent = true }
            ',' -> { fields++; state = 0 }
            else -> error("Only a comma or newline may follow a closing quote.")
          }
        }
      }
      finishRecord()
    }
    check(records >= 2) { "The default wordlist contains no words." }
    return records - 1
  }
}

val bundleDefaultWordlist = tasks.register<BundleDefaultWordlist>("bundleDefaultWordlist") {
  csvFile.set(rootProject.layout.projectDirectory.file("wordlist.csv"))
  outputDirectory.set(layout.buildDirectory.dir("generated/defaultWordlistAssets"))
}

android {
  namespace = "com.example.chinese_flashcard.core.data"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  defaultConfig { minSdk = 28 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

androidComponents.onVariants { variant ->
  variant.sources.assets?.addGeneratedSourceDirectory(bundleDefaultWordlist) { it.outputDirectory }
}

dependencies {
  implementation(project(":core:domain"))
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)
  implementation(libs.kotlinx.coroutines.core)
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
