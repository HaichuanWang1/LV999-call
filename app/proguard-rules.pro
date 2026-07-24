# Add project specific ProGuard rules here.
-keepattributes *Annotation*

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Keep data models
-keep class com.lv999call.app.data.local.entity.** { *; }
-keep class com.lv999call.app.data.remote.** { *; }
-keep class com.lv999call.app.domain.model.** { *; }

# Keep ViewModel classes (accessed via reflection by ViewModelProvider)
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }
-keepclassmembers class * implements androidx.lifecycle.ViewModelProvider$Factory { *; }

# Keep app DI module and UseCase classes
-keep class com.lv999call.app.di.** { *; }
-keep class com.lv999call.app.domain.usecase.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# JNA (required by Vosk) - 不能混淆，否则原生库加载失败
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Vosk ASR - 不能混淆，依赖JNA反射调用原生库
-keep class org.vosk.** { *; }
-keepclassmembers class org.vosk.** { *; }
-dontwarn org.vosk.**
