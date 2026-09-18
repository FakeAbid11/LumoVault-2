# TDLib (official JNI bindings)
-keep class org.drinkless.tdlib.** { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Room
-keep class com.lumovault.lumovault.core.database.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }

-dontwarn org.jetbrains.annotations.**
