# Disable obfuscation (we use Proguard exclusively for optimization)
-dontobfuscate

# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# sherpa-onnx on-device STT (issue #104): the native library reads these Kotlin config fields and
# invokes their methods via JNI by name (e.g. GetFieldID "blankPenalty"). R8 shrinking otherwise
# drops the "unused" fields, causing NoSuchFieldError at runtime — keep the whole package intact.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ONNX Runtime Java API for Smart Turn v3 (issue #191): the native JNI bridge (libonnxruntime4j_jni.so)
# looks up these classes, constructors and fields BY NAME via FindClass/GetMethodID/NewObject. If R8
# renames or removes them the lookup returns a null methodID and the run() call aborts the process with
# "JNI DETECTED ERROR: mid == null". Keep the whole ai.onnxruntime package (and don't warn on its
# optional providers) so release builds behave like debug.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
