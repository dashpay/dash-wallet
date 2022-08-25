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

#Moshi
-dontwarn javax.annotation.**
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonQualifier interface *
-dontnote com.squareup.moshi.**
-dontwarn com.squareup.moshi.**