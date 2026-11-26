-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }

# litertlm's function-calling support (ReflectionTool/ToolKt) references
# kotlin-reflect, which is excluded in app/build.gradle.kts. The app never uses
# tools, so those code paths are unreachable at runtime; silence the missing
# class warnings R8 would otherwise report.
-dontwarn kotlin.reflect.**

# Keep line numbers in release stack traces (names are obfuscated, so use the
# generated mapping file with retrace).
-keepattributes SourceFile,LineNumberTable
