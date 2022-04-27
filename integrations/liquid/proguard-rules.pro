# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keepattributes Exceptions, InnerClasses
-keep class org.dash.wallet.integration.uphold.** {
    public protected private *;
}
-keep class org.dash.wallet.integration.liquid.** {
    public protected private *;
}
-keep interface org.dash.wallet.integration.uphold.** {*;}
-keep interface org.dash.wallet.integration.liquid.** {*;}




#OkHttp
-dontwarn com.squareup.okhttp.**
-dontnote com.squareup.okhttp.internal.Platform
-dontwarn okio.**

# Retrofit
-dontnote retrofit2.Platform
-dontwarn retrofit2.Platform$Java8
-keepattributes Signature
-keepattributes Exceptions

-keepattributes *Annotation*

-keepattributes Signature
-keepattributes Exceptions

#Moshi
-dontwarn javax.annotation.**
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonQualifier interface *
-dontnote com.squareup.moshi.**
-dontwarn com.squareup.moshi.**