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