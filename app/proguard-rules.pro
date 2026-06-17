# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes Signature

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Apache POI
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn com.microsoft.schemas.**
-dontwarn org.openxmlformats.**
-dontwarn org.etsi.**
-dontwarn org.w3.**

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
