#!/usr/bin/env bash
#
# Build locally and push straight to Firebase App Distribution, skipping the
# GitHub Actions round trip. Thin wrapper around `fastlane qa`; its only real
# job is keeping the signing secrets in the macOS keychain so you are not
# retyping them (or leaving them in shell history) on every build.
#
#   ./distribute.sh -m "MO-123 fix the crash"            # testnet release
#   ./distribute.sh -f prod -m "MO-123"                  # mainnet release
#   ./distribute.sh -f all -m "MO-123"                   # both flavors
#   ./distribute.sh -f prod -t debug -g qa -m "MO-123"
#
set -euo pipefail

cd "$(dirname "$0")"

FLAVOR="_testNet3"
TYPE="release"
GROUP="qa"
COMMENT=""
SIGNING="ci"
KEYSTORE_OVERRIDE=""
KEY_ALIAS_OVERRIDE=""
EXTRA=()

usage() {
	cat <<-EOF
	Usage: $0 [options]

	  -f, --flavor FLAVOR   prod | _testNet3 | staging | devnet | all  (default: _testNet3)
	  -t, --type TYPE       release | debug                            (default: release)
	  -g, --group GROUP     Firebase tester group                      (default: qa)
	  -m, --message TEXT    Release notes (defaults to branch + last commit)
	      --signing PROFILE ci | release                               (default: ci)
	                          ci      .deploy/keystore.jks, alias dash_wallet.
	                                  Same key GitHub Actions signs QA builds
	                                  with, so testers upgrade in place.
	                                  Decrypted from keystore.jks.gpg, which
	                                  needs the KEYSTORE_KEY passphrase.
	                          release .deploy/dash-wallet.keystore, alias
	                                  android-apps — the Play Store key, already
	                                  on disk unencrypted. If it is not the same
	                                  key pair as the CI keystore, testers must
	                                  uninstall before they can install.
	  -k, --keystore PATH   Keystore to sign with (overrides --signing)
	      --key-alias ALIAS Key alias (overrides --signing)
	      --version-code N  Override the version code
                        (default: the one in wallet/build.gradle)
	      --clean           Run a clean build
	      --refresh-explore Re-download explore.db even if cached
	      --reset-secrets   Forget the keychain-stored signing secrets
	  -h, --help            Show this help
	EOF
}

KEYCHAIN_SERVICE="dash-wallet-distribute"

keychain_get() {
	security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$1" -w 2>/dev/null || true
}

keychain_set() {
	security add-generic-password -U -s "$KEYCHAIN_SERVICE" -a "$1" -w "$2"
}

# get_secret <keychain-account> <prompt>; echoes the secret on stdout.
get_secret() {
	local account="$1" prompt="$2" value
	value="$(keychain_get "$account")"
	if [ -z "$value" ]; then
		read -rsp "$prompt: " value </dev/tty
		echo >&2
		[ -n "$value" ] || { echo "No value entered" >&2; exit 1; }
		read -rp "Save in the macOS keychain for next time? [Y/n] " save </dev/tty
		case "$save" in
			[Nn]*) ;;
			*) keychain_set "$account" "$value" >&2 ;;
		esac
	fi
	printf '%s' "$value"
}

while [ $# -gt 0 ]; do
	case "$1" in
		-f|--flavor)       FLAVOR="$2"; shift 2 ;;
		-t|--type)         TYPE="$2"; shift 2 ;;
		-g|--group)        GROUP="$2"; shift 2 ;;
		-m|--message|--comment) COMMENT="$2"; shift 2 ;;
		--signing)         SIGNING="$2"; shift 2 ;;
		-k|--keystore)     KEYSTORE_OVERRIDE="$2"; shift 2 ;;
		--key-alias)       KEY_ALIAS_OVERRIDE="$2"; shift 2 ;;
		--version-code)    EXTRA+=("versioncode:$2"); shift 2 ;;
		--clean)           EXTRA+=("clean:true"); shift ;;
		--refresh-explore) EXTRA+=("refresh_explore:true"); shift ;;
		--reset-secrets)
			for account in keystore-key store-pass-ci key-pass-ci store-pass-release key-pass-release; do
				security delete-generic-password -s "$KEYCHAIN_SERVICE" -a "$account" >/dev/null 2>&1 || true
			done
			echo "Cleared stored signing secrets."
			exit 0 ;;
		-h|--help)         usage; exit 0 ;;
		*) echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
	esac
done

# Prefer the bundled fastlane (matches CI), but fall back to a standalone
# install rather than forcing a `bundle install` on every developer machine.
FASTLANE=(bundle exec fastlane)
if ! bundle check >/dev/null 2>&1; then
	if command -v fastlane >/dev/null; then
		FASTLANE=(fastlane)
	else
		bundle install
	fi
fi

case "$SIGNING" in
	ci)
		KEYSTORE=".deploy/keystore.jks"
		KEY_ALIAS="dash_wallet"
		# CI signs with a single password for both the store and the key.
		SEPARATE_KEY_PASS=false ;;
	release)
		KEYSTORE=".deploy/dash-wallet.keystore"
		KEY_ALIAS="android-apps"
		SEPARATE_KEY_PASS=true ;;
	*)
		echo "Unknown signing profile '$SIGNING' (expected: ci, release)" >&2; exit 1 ;;
esac

if [ -n "$KEYSTORE_OVERRIDE" ]; then
	KEYSTORE="$KEYSTORE_OVERRIDE"
	SEPARATE_KEY_PASS=true
fi
[ -n "$KEY_ALIAS_OVERRIDE" ] && KEY_ALIAS="$KEY_ALIAS_OVERRIDE"

# The CI keystore lives in the repo encrypted; everything else must already
# be on disk.
if [ ! -s "$KEYSTORE" ]; then
	if [ "$KEYSTORE" = ".deploy/keystore.jks" ] && [ -s .deploy/keystore.jks.gpg ]; then
		if [ -z "${KEYSTORE_KEY:-}" ]; then
			KEYSTORE_KEY="$(get_secret keystore-key 'Passphrase for .deploy/keystore.jks.gpg')"
		fi
		export KEYSTORE_KEY
	else
		echo "Keystore not found: $KEYSTORE" >&2
		exit 1
	fi
fi

# An already-exported password wins over the keychain, so a scripted run can
# be fully non-interactive.
if [ -z "${SIGNING_STORE_PASS:-}" ]; then
	SIGNING_STORE_PASS="$(get_secret "store-pass-$SIGNING" "Keystore password for $KEYSTORE")"
fi
export SIGNING_STORE_PASS
if [ "$SEPARATE_KEY_PASS" = true ]; then
	# Via the environment, not argv, so it stays out of `ps` output.
	if [ -z "${SIGNING_KEY_PASS:-}" ]; then
		SIGNING_KEY_PASS="$(get_secret "key-pass-$SIGNING" "Key password for alias '$KEY_ALIAS'")"
	fi
	export SIGNING_KEY_PASS
fi

case "$KEYSTORE" in
	/*) KEYSTORE_ABS="$KEYSTORE" ;;
	*)  KEYSTORE_ABS="$PWD/${KEYSTORE#./}" ;;
esac

set -- qa "flavor:$FLAVOR" "type:$TYPE" "testgroup:$GROUP" \
	"keystore:$KEYSTORE_ABS" "keyalias:$KEY_ALIAS"
if [ -n "$COMMENT" ]; then
	set -- "$@" "comment:$COMMENT"
fi
if [ ${#EXTRA[@]} -gt 0 ]; then
	set -- "$@" "${EXTRA[@]}"
fi

exec "${FASTLANE[@]}" "$@"
