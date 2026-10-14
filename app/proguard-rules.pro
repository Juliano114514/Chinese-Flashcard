# First adopt shrinking in dependencies without rewriting application code.
# This also preserves persisted enum names, saved state and Room's generated _Impl lookup.
-keep class com.example.chinese_flashcard.** { *; }

# ONNX Runtime's native code looks up Java classes, constructors and members by name.
# Its bundled consumer rules do not protect the complete JNI surface.
-keep class ai.onnxruntime.** { *; }
