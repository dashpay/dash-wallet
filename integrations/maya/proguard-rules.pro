# Preserve generic type signatures so Gson can resolve element types of
# generic fields (e.g. List<SwapKitPoolSnapshot>). Without this R8 strips the
# Signature attribute and Gson deserializes such fields into LinkedTreeMaps,
# causing ClassCastException when the typed elements are accessed.
-keepattributes Signature, InnerClasses, EnclosingMethod

# Keep Maya model classes for Gson deserialization
-keep class org.dash.wallet.integrations.maya.model.** { <init>(...); *; }
-keep class org.dash.wallet.integrations.maya.swapkit.model.** { <init>(...); *; }

# Snapshot models persisted/restored via Gson are private nested classes of
# SwapKitApiAggregator (package swapkit, not swapkit.model). Keep them and their
# fields so reflection-based (de)serialization survives minification.
-keep class org.dash.wallet.integrations.maya.swapkit.SwapKitApiAggregator$* { <init>(...); *; }