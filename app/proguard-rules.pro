# Keep SMB and media related classes
-keep class com.hierynomus.** { *; }
-keep class jcifs.** { *; }
-keep class androidx.media3.** { *; }

# Kotlin
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
