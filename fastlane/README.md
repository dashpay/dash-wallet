fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android publish

```sh
[bundle exec] fastlane android publish
```

Build and upload for pre-launch report

### android upload

```sh
[bundle exec] fastlane android upload
```

Upload for pre-launch report

### android promote

```sh
[bundle exec] fastlane android promote
```

Promote to production track with 0.2 rollout by default

### android increase

```sh
[bundle exec] fastlane android increase
```

Increase rollout

### android prepare_changelog

```sh
[bundle exec] fastlane android prepare_changelog
```

Prepare changelog

### android test

```sh
[bundle exec] fastlane android test
```

Runs tests

### android build_distribute

```sh
[bundle exec] fastlane android build_distribute
```

Build and distribute with Firebase

### android distribute

```sh
[bundle exec] fastlane android distribute
```

Submit apk to Firebase Distribution

### android build

```sh
[bundle exec] fastlane android build
```

Build apk

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
