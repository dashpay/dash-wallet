-keepattributes Exceptions, InnerClasses
-keep class org.dash.wallet.integration.coinbase_integration.** {
    public protected private *;
}
-keep interface org.dash.wallet.integration.coinbase_integration.** {*;}

-keep class org.dash.wallet.integration.uphold.** {
    public protected private *;
}

#OkHttp
-dontwarn com.squareup.okhttp.**
-dontnote com.squareup.okhttp.internal.Platform
-dontwarn okio.**

# Retrofit
-dontnote retrofit2.Platform
-dontwarn retrofit2.Platform$Java8
-keepattributes Signature
-keepattributes Exceptions

# Spongy Castle
-keep class org.spongycastle.** { *; }
-keepclassmembers class org.spongycastle.** { *; }
-dontwarn org.spongycastle.**