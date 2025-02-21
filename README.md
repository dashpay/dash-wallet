Welcome to _Dash Wallet_, a standalone Dash payment app for your Android device!

This project contains several sub-projects:

 * __wallet__:
     The Android app itself. This is probably what you're searching for. [wallet README](wallet/README.md)
 * __common__:
     Contains common components used by integrations.
 * __features__:
     Contains features such as Explore Dash
 * __market__:
     App description and promo material for the Google Play app store.
 * __integration-android__:
      A tiny library for integrating Dash payments into your own Android app
     (e.g. donations, in-app purchases).
 * __integrations__
     Contains the various integrations: Crowdnode, Coinbase, Uphold,
 * __sample-integration-android__:
     A minimal example app to demonstrate integration of digital payments into
     your Android app.

You can build the production version using Gradle:

`./gradlew assembleProdRelease`

The built apks will be in `wallet/build/outputs/apk`


