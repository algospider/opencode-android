-keep class kotlinx.serialization.** { *; }
-keepclassmembers class com.opencode.android.model.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
    @kotlinx.serialization.Serializable <fields>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
