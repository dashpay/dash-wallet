-keepattributes Exceptions, InnerClasses, Signature, *Annotation*
-keep public class org.dash.wallet.common.** {
    public protected *;
}
-keep public interface org.dash.wallet.common.** {*;}

# Moshi
-keepclassmembers class ** {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}
