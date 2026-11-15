# Keep Room database entities, DAOs, and Kotlin Coroutines metadata
-keep class com.shashowtohaque.acs.data.local.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }
-dontwarn kotlinx.coroutines.**
