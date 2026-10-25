# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-keep class rikka.shizuku.** { *; }

# Modern Xposed API 模块入口由 META-INF/xposed/java_init.list 按类名加载
-dontwarn io.github.libxposed.annotation.**
-keep public class * extends io.github.libxposed.api.XposedModule { *; }
-keep class io.github.libxposed.** { *; }