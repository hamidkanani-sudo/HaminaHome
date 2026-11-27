# Keep SMB and media related classes
-keep class com.hierynomus.** { *; }
-keep class androidx.media3.** { *; }

# smbj references optional libraries that are not on Android (release build is not tested yet)
-dontwarn org.slf4j.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.**
-dontwarn org.bouncycastle.**

# Kotlin
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
