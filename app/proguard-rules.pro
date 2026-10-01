# Keep accessibility / voice-interaction service entry points
-keep class com.sqlai.assistant.service.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Kotlin
-dontwarn kotlin.**
