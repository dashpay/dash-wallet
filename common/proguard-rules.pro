# *Annotation* + Kotlin metadata are load-bearing for consumers of the
# RELEASE (minified) variant: :wallet's Room KSP reads @Entity/@PrimaryKey
# and Kotlin constructor metadata off these classes (ExchangeRate,
# BlockchainState, TransactionMetadata …). With only Exceptions/InnerClasses
# kept, ksp_*ReleaseKotlin fails with "Entities and POJOs must have a usable
# public constructor" / "must have at least 1 field annotated with
# @PrimaryKey" while debug (unminified) builds pass.
-keepattributes Exceptions, InnerClasses, *Annotation*, Signature
-keep class kotlin.Metadata
-keep public class org.dash.wallet.common.** {
    public protected *;
}
-keep public interface org.dash.wallet.common.** {*;}
-dontwarn java.lang.invoke.StringConcatFactory