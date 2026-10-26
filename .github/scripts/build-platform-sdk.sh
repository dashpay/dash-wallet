#!/usr/bin/env bash
set -euo pipefail

root="$(pwd)"
revision="$(tr -d '\n\r' < platform-sdk-revision.txt)"
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid Platform SDK revision' >&2; exit 1; }
work="${RUNNER_TEMP:?}/connect-platform-sdk"
repository="$work/maven"
mkdir -p "$work" "$repository"
export CARGO_TARGET_DIR="$work/target"
export CARGO_PROFILE_DEV_DEBUG=0
export CARGO_INCREMENTAL=0
git clone https://github.com/dashpay/kotlin-platform.git "$work/kotlin-platform"
git -C "$work/kotlin-platform" checkout --detach "$revision"
git clone --branch ferment-5.0 https://github.com/HashEngineering/platform.git "$work/platform"
platform_revision="$(cat "$work/kotlin-platform/platform-revision.txt")"
git -C "$work/platform" checkout --detach "$platform_revision"
git -C "$work/platform" fetch https://github.com/dashpay/platform.git tag v5.0.0-rc.7
git -C "$work/platform" merge-base --is-ancestor v5.0.0-rc.7 HEAD

sudo apt-get update
sudo apt-get install -y cmake ninja-build swig clang libclang-dev libssl-dev pkg-config unzip
sdkmanager --install 'ndk;29.0.14206865'
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/29.0.14206865"
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
rustup toolchain install 1.98.1 --profile minimal
rustup toolchain install nightly-2026-09-24 --profile minimal --component rust-src
rustup target add --toolchain nightly-2026-09-24 aarch64-linux-android x86_64-linux-android
cargo +1.98.1 install cargo-ndk --version 4.1.2 --locked
cargo +1.98.1 install cbindgen --version 0.29.0 --locked

protoc="$work/protoc.zip"
curl --fail --location --output "$protoc" https://github.com/protocolbuffers/protobuf/releases/download/v25.3/protoc-25.3-linux-x86_64.zip
printf '%s  %s\n' f853e691868d0557425ea290bf7ba6384eef2fa9b04c323afab49a770ba9da80 "$protoc" | sha256sum --check
unzip -q "$protoc" -d "$work/protoc"
export PATH="$work/protoc/bin:$PATH"
export PROTOC_INCLUDE="$work/protoc/include"

cd "$work/kotlin-platform"
./gradlew :dash-sdk-java:publishMavenJavaPublicationToMavenLocal \
  :dpp:publishMavenJavaPublicationToMavenLocal \
  :dash-sdk-android:publishReleasePublicationToMavenLocal \
  -Dmaven.repo.local="$repository" -Dorg.gradle.jvmargs=-Xmx4g \
  -x :dash-sdk-java:signMavenJavaPublication -x :dpp:signMavenJavaPublication \
  -x :dash-sdk-android:signReleasePublication --no-parallel --console=plain
test -s "$repository/org/dashj/platform/dash-sdk-android/5.0.0-SNAPSHOT/dash-sdk-android-5.0.0-SNAPSHOT.aar"
./gradlew :dash-sdk-java:test --tests '*TokenPurchaseBridgeTest' \
  :dpp:test --tests '*AppConnectKeyTest' --tests '*AppConnectIdentityTest' --tests '*IdentityNativeReadbackTest' --tests '*DataContractResponseTest' --tests '*VerifiedContractGroupTest' \
  -x :dash-sdk-java:buildRustBindings -x :dash-sdk-java:cmakeBuild --no-parallel --console=plain
printf 'ORG_GRADLE_PROJECT_platformSdkRepository=%s\n' "$repository" >> "${GITHUB_ENV:?}"
cd "$root"
