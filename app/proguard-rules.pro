# ProGuard Rules for LoanPhotoApp v4.0

# --- Apache POI ---
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.apache.commons.collections.**
-dontwarn org.openxmlformats.**
-dontwarn schemaorg_apache_xmlbeans.**
-dontwarn org.osgi.**
-dontwarn org.apache.logging.log4j.**
-dontwarn java.awt.**
-dontwarn com.graphbuilder.**
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }
-keep class org.apache.commons.collections.** { *; }
-keep class org.apache.commons.collections4.** { *; }
-keep class com.graphbuilder.** { *; }
# commons-compress 是 POI 的可选传递依赖，其 asm/xz 子模块在 classpath 缺失；
# 不 -keep compress（避免 R8 强保留引用缺失类的代码），仅 dontwarn 抑制缺失类告警
-dontwarn org.objectweb.asm.**
-dontwarn org.tukaani.xz.**
-dontwarn org.apache.commons.compress.**

# --- Retrofit ---
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep interface okhttp3.** { *; }

# --- Moshi ---
-keepclassmembers class * {
    @com.squareup.moshi.* <methods>;
    @com.squareup.moshi.* <fields>;
}
-keep class com.banktool.loanphoto.data.dto.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers @com.squareup.moshi.JsonClass class * {
    <init>(...);
    <fields>;
}

# --- Hilt / Dagger ---
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# --- CameraX ---
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# --- BuildConfig ---
-keep class com.banktool.loanphoto.BuildConfig { *; }

# --- Compose ---
-dontwarn androidx.compose.**

# --- Domain entities (used by Moshi reflection) ---
-keep class com.banktool.loanphoto.domain.entity.** { *; }

# --- Kotlin Metadata ---
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations

# --- General ---
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.**

# --- R8 full mode optimizations ---
# 注：移除 -repackageclasses，避免破坏 Apache POI xmlbeans 按名加载机制（v4.0.8 打开 Excel 闪退根因）
-allowaccessmodification
