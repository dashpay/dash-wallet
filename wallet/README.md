Dash Wallet
===========
Dash Wallet is a single coin wallet app for the Dash cryptocurrency. 
This repo contains the source code for the android platform.  iOS is supported 
at the [dashwallet-ios](https://github.com/dashpay/dashwallet-ios) repo on Github.

Technical details
=================

### FILES

Your wallet contains your private keys and various transaction related metadata. It is stored in app-private
storage:

	Mainnet: /data/data/hashengineering.darkcoin.wallet/files/wallet-protobuf (MODE_PRIVATE)
	Testnet: /data/data/hashengineering.darkcoin.wallet_test/files/wallet-protobuf-testnet (MODE_WORLD_READABLE | MODE_WORLD_WRITEABLE)

The wallet file format is not compatible to wallet.dat (Satoshi client). Rather, it uses a custom protobuf format
which should be compatible between clients using dashj.

Certain actions cause automatic rolling backups of your wallet to app-private storage:

	Mainnet: /data/data/hashengineering.darkcoin.wallet/files/key-backup-protobuf (MODE_PRIVATE)
	Testnet: /data/data/hashengineering.darkcoin.wallet_test/files/key-backup-protobuf-testnet (MODE_PRIVATE)

Your wallet can be manually backed up to and restored from external storage:

	Mainnet: /sdcard/Download/dash-wallet-backup-<yyyy-MM-dd>
	Testnet: /sdcard/Download/dash-wallet-backup-testnet-<yyyy-MM-dd>

Your wallet can be manually backed up and restored using a recovery phrase (12 word mnemonic).

If you want to recover coins from manual backups and for whatever reason you cannot use the app
itself to restore from the backup, see the separate [README.recover.md](README.recover.md) guide.

The current fee rate for each of the fee categories (economic, normal, priority) is cached in
app-private storage:

    Mainnet: /data/data/hashengineering.darkcoin.wallet/files/fees.txt
    Testnet: /data/data/hashengineering.darkcoin.wallet_test/files/fees-testnet.txt


### DEBUGGING

Wallet file for Testnet can be pulled from an (even un-rooted) device using:

	adb pull /data/data/hashengineering.darkcoin.wallet/files/wallet-protobuf-testnet

Log messages can be viewed by:

    adb logcat

The app can send extensive debug information. Use **Options > Settings > Report Issue** and follow the dialog.
In the generated e-mail, replace the support address with yours.


### BUILDING THE DEVELOPMENT VERSION

It's important to know that the development version uses Testnet, is debuggable and the wallet file
is world readable/writeable. The goal is to be able to debug easily.

The _testNet3 flavor builds for Testnet.

You can probably skip some steps, especially if you built Android apps before.

You'll need git, a Java SDK 6 (or later) and Gradle 2.10 (or later) for this. I'll assume Ubuntu Xenial Linux
for the package installs, which comes with slightly more recent versions.

    # first time only
    sudo apt install git gradle openjdk-8-jdk libstdc++6:i386 zlib1g:i386

Download the [Android SDK Tools](https://developer.android.com/studio/index.html#command-tools)
and unpack to your workspace directory. Point your `ANDROID_HOME` variable to the unpacked Android SDK directory
and switch to it.


Download and install the required Android dependencies:
    tools/android update sdk --no-ui --force --all --filter tool,platform-tool,build-tools-28,android-15,android-28

Download the [Android NDK](https://developer.android.com/ndk/downloads/), then unpack it to your workspace directory. Point your `ANDROID_NDK_HOME` variable to the unpacked Android NDK directory.

Finally, you can build Dash Wallet and sign it with your development key. Again in your workspace,
use

	# first time only
	git clone -b master https://github.com/HashEngineering/dash-wallet.git dash-wallet
	cd dash-wallet
	git pull
    
	# each time
	cd dash-wallet
	git pull
    gradle clean assemble_testNet3Debug -x test

To install the app on your Android device, use:

    # first time only
    sudo apt install android-tools-adb

	# each time
	adb install wallet/build/outputs/apk/dash-wallet-_testNet3-debug.apk

If installation fails, make sure "Developer options" and "USB debugging" are enabled on your Android device, and an ADB
connection is established.



### BUILDING THE PRODUCTION VERSION

These files must exist to result in a fully functional build:
* `wallet/google-services.json` - supports analytics, crash-lytics, google cloud services, etc
* `services.properties` - contains the keys for Uphold and Coinbase (see below)
* `local.properties` - contains the support email and Google Map API keys (see below)

At this point I'd like to remind that you continue on your own risk. According to the license,
there is basically no warranty and liability. It's your responsibility to audit the source code
for security issues and build, install and run the application in a secure way.

The production version uses Mainnet, is built non-debuggable, space-optimized with ProGuard and the
wallet file is protected against access from non-root users. In the code repository, it is build with
the 'prod' flavor.

	# each time
	cd dash-wallet
	git pull
    gradle clean build assembleProdRelease

The resulting production release version will be at:  `wallet/build/outputs/apk/wallet-prod-release.apk`

BUILDING ALL FLAVORS

	# each time
	cd dash-wallet
	git pull
    gradle clean build assembleProdRelease
    gradle clean build assemble_testNet3Release
    gradle clean build assembleProdDebug
    gradle clean build assemble_testNet3Debug
    
All flavors (debug and release) will be at:  wallet/build/outputs/apk

### BUILDING THE PRODUCTION VERSION WITH FASTLANE
Place these files in `./deploy`
* `app-distribution-key.json` - Firebase app distribution key
* `dash-wallet.keystore` - the production signing key
* `gc-storage-service-account.json` - Google Cloud Storage key

## Build the production version of the app
The APK is placed here: `wallet/build/outputs/apk/wallet-prod-release.apk`
```sh
    fastlane build storepass:[keystore password] keypass:[key password]
```
## Upload existing wallet-prod-release.apk file to the internal test track.
```shell
    fastlane upload
```
## Build, followed by Upload
```shell
    fastlane publish storepass:[keystore password] keypass:[key password]
```
## Promote to production with 50% rollout (20% by default if no args)
```shell
    fastlane promote rollout:0.5
```
## Increase rollout to 70%
```shell
    fastlane increase rollout:0.7
```

### CONFIGURATION FOR UPHOLD AND COINBASE

The file services.properties must be in the root folder of the repo with the keys for the Uphold and
Coinbase Services as follows:

```
UPHOLD_CLIENT_ID="<uphold client id>"
UPHOLD_CLIENT_SECRET="<uphold secret>"
UPHOLD_CLIENT_ID_SANDBOX="<uphold sandbox client id>"
UPHOLD_CLIENT_SECRET_SANDBOX="<uphold sandbox secret"
COINBASE_CLIENT_ID="<coinbase client id>"
COINBASE_CLIENT_SECRET="<coinbase secret>"
```

### CONFIGURATION FOR SUPPORT EMAIL

The default support email used by Dash Wallet will be an empty string.  However, this can be 
customized.  `build.gradle` will assign a value `BuildConfig.SUPPORT_EMAIL` will be assigned 
according to the following:

The email will be determined by looking in `local.properties` followed by the environment for these
two variables:
- SUPPORT_EMAIL
- INTERNAL_SUPPORT_EMAIL - if the build is debug or SUPPORT_EMAIL is empty, then this will be used.

This allows `local.properties` to specify a support email for debug builds and a different support 
email for release/production builds. 

### CONFIGURATION FOR GOOGLE MAPS

`local.properties` should also have a value for `GOOGLE_PLAY_API_KEY` to support Google Maps in the
Explore features of Dash Wallet.

### SETTING UP FOR DEVELOPMENT

You should be able to import the project into Android Studio, as it uses Gradle for building.


### TRANSLATIONS

The source language is English. Translations for all other languages [happen on Transifex](https://app.transifex.com/dash/dash-mobile-wallets/).
The source text and translations are shared as much as possible with the iOS app.

The English resources are pushed to Transifex. Changes are pulled and committed to the git
repository from time to time. It can be done by manually downloading the files, but using the `tx`
command line client is more convenient.  See [Transifex Client](https://developers.transifex.com/docs/cli)
for help for usage and installation.

If strings resources are added or changed, the source language files need to be pushed to
Transifex. This step will probably only be executed by the maintainer of the project, as special
permission is needed:

    # push source files to Transifex
    tx push -s

As soon as a translation is ready, it can be pulled:

    # pull translation from Transifex
    tx pull -f -l <language code>

Note that after pulling, any bugs introduced by either translators or Transifex itself need to be
corrected manually.


### NFC (Near field communication)

Dash Wallet supports reading Dash requests via NFC, either from a passive NFC tag or from
another NFC capable Android device that is requesting coins.

For this to work, just enable NFC in your phone and hold your phone to the tag or device (with
the "Request coins" dialog open). The "Send coins" dialog will open with fields populated.

Instructions for preparing an NFC tag with your address:

- We have successfully tested [this NFC tag writer](https://play.google.com/store/apps/details?id=com.nxp.nfc.tagwriter).
  Other writers should work as well, let us know if you succeed.

- Some tags have less than 50 bytes capacity, those won't work. 1 KB tags recommended.

- The tag needs to contain a Dash URI. You can construct one with the "Request coins" dialog,
  then share with messaging or email. You can also construct the URI manually. Mainnet example:
  `dash:XywwpkwZYAypoW2cCmdczh4kFcvWWb9ZZW`

- The type of the message needs to be URI or URL (not Text).

- If you put your tag at a public place, don't forget to enable write protect. Otherwise, someone
  could overwrite the tag with his own Dash address.


### DASHJ

Dash Wallet uses [dashj](https://github.com/dashpay/dashj) for Dash specific logic.  This project is forked from [bitcoinj](https://bitcoinj.github.io/)


### EXCHANGE RATES

Dash Wallet has four sources for exchange rates
- Source 1: Dash Retail - https://rates2.dashretail.org/rates?source=dashretail
- Source 2: *Currently disabled* - Spark
- Source 3: *Currently disabled* - BitcoinAverage (BTC/all), CryptoCompare(DASH/BTC), DashCasa(DASH/VES)
- Source 4: BitPay (BTC/all), Dash Central(DASH/BTC), Poloniex (DASH/BTC), Local Bitcoins (BTC/VES)

### SWEEPING WALLETS

When sweeping wallets, Dash Wallet uses a set of Electrum servers and block explorers to query for
unspent transaction outputs (UTXOs).
