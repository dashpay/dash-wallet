Welcome to _Dash Wallet_, a standalone Dash payment app for your Android device!

This project contains several sub-projects:

 * __wallet__:
     The Android app itself. This is probably what you're searching for.
 * __market__:
     App description and promo material for the Google Play app store.
 * __integration-android__:
     A tiny library for integrating Dash payments into your own Android app
     (e.g. donations, in-app purchases).
 * __sample-integration-android__:
     A minimal example app to demonstrate integration of digital payments into
     your Android app.

You can build all sub-projects at once using Gradle:

`gradle clean build -x test`

Full Guide for building the APK:

`$ git clone https://github.com/HashEngineering/dashj.git `

`$ cd dashj`

`$ git checkout release-0.14`

`$ mvn clean install -DskipTests`

`$ cd ..`

`$ git clone https://github.com/HashEngineering/dash-wallet.git `

`$ cd dash-wallet`

`$ git checkout release-4`

`$ gradle clean build -x test`


