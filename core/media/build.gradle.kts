import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins { id("flashcard.android.library") }
android {
  namespace = "com.example.chinese_flashcard.core.media"
  // Keep the mapped Chinese model uncompressed; the English model supports ZIP compression.
  androidResources { noCompress += "chinese-speech/model-duration.onnx" }
}

@CacheableTask
abstract class BundleChineseModel : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val modelFile: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun bundle() {
    val file = modelFile.get().asFile
    check(file.isFile) { "Run python tools/build_chinese_resources.py --prepare before building." }
    check(file.length() == 170429590L) { "Unexpected Chinese model size." }
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { stream ->
      val buffer = ByteArray(65536)
      while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        hash.update(buffer, 0, count)
      }
    }
    val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    check(actual == "c6029f601d660bd8ab720d1b6a159638c7613d9e4cbc4bd06f347c372205de55") {
      "Chinese model checksum mismatch; prepare the pinned model again."
    }
    val target = outputDirectory.get().file("chinese-speech/model-duration.onnx").asFile
    check(target.parentFile.isDirectory || target.parentFile.mkdirs())
    file.copyTo(target, overwrite = true)
    val legacy = outputDirectory.get().file("chinese-speech/model.onnx").asFile
    check(!legacy.exists() || legacy.delete()) { "Could not remove the obsolete generated model." }
  }
}

val bundleChineseModel = tasks.register<BundleChineseModel>("bundleChineseModel") {
  modelFile.set(rootProject.layout.projectDirectory.file(".gradle/melo-resource-source/model-duration.onnx"))
  outputDirectory.set(layout.buildDirectory.dir("generated/chineseModelAssets"))
}
androidComponents.onVariants { variant ->
  variant.sources.assets?.addGeneratedSourceDirectory(bundleChineseModel) { it.outputDirectory }
}
dependencies {
  implementation(project(":core:domain"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.onnxruntime.android)
}
