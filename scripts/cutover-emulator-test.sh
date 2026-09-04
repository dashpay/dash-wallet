#!/bin/bash
#
# MO-995 cutover / SPV-restart test harness for the Android emulator.
#
# WHY A RELEASE BUILD: PlatformSyncService.shutdown() skips stopSdkEngines()
# when BuildConfig.DEBUG (PlatformSyncService.kt:656) — a deliberate warm-SPV
# battery trade-off. On a debug build the SDK engine never stops on teardown,
# so neither MO-995 nor its fix can be observed. Everything below needs the
# _testNet3Release variant.
#
# WHY THE AOSP EMULATOR IMAGE: steps 1-3 fake the previous-launch versionCode
# by writing shared_prefs directly, which needs `adb root`. The
# android-36/google_apis_playstore image blocks root; android-36/default
# allows it. Use Pixel_9_API_36_AOPS (Android 16, arm64-v8a — matches all four
# field devices).
#
# WHAT THIS CANNOT REPRODUCE: walletB's FALSE-LOCKED classification
# (KeyguardManager reports unlocked while Keystore denies). That is the HONOR
# PTP-N49 OEM defect; on AOSP every denial classifies as "genuinely locked".
# The false-locked branch is covered by SdkBindRetryServiceTest instead. A
# green run here does NOT clear the HONOR case.
#
# ORDER OF OPERATIONS: you install the PRE-CUTOVER build yourself and onboard
# /sync it. Then `setup` builds, signs and installs the fix build OVER it —
# that install is the upgrade under test. The scenarios run after that.
#
# Usage:  ./scripts/cutover-emulator-test.sh <step>
#         ./scripts/cutover-emulator-test.sh setup      # build+sign+upgrade-install
#         ./scripts/cutover-emulator-test.sh s1        # upgrade, bind works
#         ./scripts/cutover-emulator-test.sh s2        # upgrade, bind denied
#         ./scripts/cutover-emulator-test.sh s3        # denied then healed in-session
#         ./scripts/cutover-emulator-test.sh s3b       # ...and the next launch commits
#         ./scripts/cutover-emulator-test.sh s4        # trim -> SPV restart
#         ./scripts/cutover-emulator-test.sh log       # pull + tail wallet.log
set -uo pipefail

PKG=hashengineering.darkcoin.wallet_test
SVC=$PKG/de.schildbach.wallet.service.BlockchainServiceImpl
AVD=Pixel_9_API_36_AOPS
# Device lock PIN, for the scenarios that must lock and unlock the emulator.
# NEVER hardcode it — supply it per run:  EMULATOR_PIN=... ./this-script s3
# Only the scenarios that lock the screen need it (s2, and s3's unlock).
PIN="${EMULATOR_PIN:-}"
# MUST match CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE. The cutover ships
# in 12.0.0, so the boundary is 12000000 — it MOVED here from 11100000 when the
# release slipped a line. Two copies of a boundary is one copy too many: this
# used to be hardcoded again below, where a device last run on 11.10–11.25 (which
# the APP counts as pre-cutover) was judged post-cutover by the script, so the
# script overwrote a genuine last_version instead of leaving it alone.
FIRST_CUTOVER_VERSION_CODE=12000000
PREV_VERSION_PRE_CUTOVER=11090000   # any value < FIRST_CUTOVER_VERSION_CODE
BT=~/Library/Android/sdk/build-tools/35.0.1
APK_DIR=wallet/build/outputs/apk/_testNet3/release
OUT=/tmp/mo995-emulator
mkdir -p "$OUT"

# Assertion ledger. Every FAIL bumps this; the EXIT trap turns a non-zero
# count into a non-zero exit status.
#
# WHY NOT `exit 1` AT THE FAILING ASSERTION: each scenario is a SEQUENCE of
# assertions and the diagnostic value is in seeing all of them. S3 asserting
# "bind failed" then "dashj came back" then "state not committed" tells you
# WHERE the chain broke; aborting at the first FAIL reports only that it broke.
# So assertions still print and continue — but the script no longer exits 0
# after printing FAIL, which would let an automated run call a failed cutover
# scenario a success.
FAILURES=0

fail() {
  FAILURES=$((FAILURES + 1))
  printf '   \033[31mFAIL\033[0m %s\n' "$1"
  [ $# -gt 1 ] && printf '        %s\n' "$2"
  return 0
}

on_exit() {
  local rc=$?
  if [ "$FAILURES" -gt 0 ]; then
    printf '\n\033[31m%s assertion(s) FAILED\033[0m\n' "$FAILURES"
    exit 1
  fi
  exit "$rc"
}
trap on_exit EXIT

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
adbs() { adb shell "$@"; }

require_device() {
  adb get-state >/dev/null 2>&1 || { echo "No device. Start it: emulator -avd $AVD -no-snapshot-load"; exit 1; }
}

# Root is required for the shared_prefs poke and for `run-as`-free log reads.
require_root() {
  adb root >/dev/null 2>&1
  adb wait-for-device >/dev/null 2>&1
  if [ "$(adbs id -u | tr -d '\r')" != "0" ]; then
    echo "adb root failed — are you on the google_apis_playstore image? Use $AVD (android-36/default)."
    exit 1
  fi
}

# Pull the log, NEVER silently truncating our copy. The old version fell back
# to `run-as`, which cannot work on a release build (not debuggable) — so a
# failed pull wrote an EMPTY file and every assertion reported a false FAIL
# while the log on the device was fine. Now: re-assert root, retry, and keep
# the previous copy rather than clobbering it with nothing.
pull_log() {
  local dst="$OUT/wallet.log" tmp="$OUT/.wallet.pull" i
  for i in 1 2 3; do
    rm -f "$tmp"
    if adb pull "/data/data/$PKG/files/log/wallet.log" "$tmp" >/dev/null 2>&1 && [ -s "$tmp" ]; then
      mv -f "$tmp" "$dst"; echo "$dst"; return 0
    fi
    adb root >/dev/null 2>&1; adb wait-for-device >/dev/null 2>&1; sleep 2
  done
  rm -f "$tmp"
  if [ -s "$dst" ]; then
    echo "   WARN: could not refresh the log (adb pull failed) — asserting on the last good copy" >&2
    echo "$dst"; return 0
  fi
  echo "   ERROR: cannot read wallet.log from the device (need adb root; release builds are not run-as-able)" >&2
  : > "$dst"; echo "$dst"; return 1
}

# Assertions are SCOPED TO LINES ADDED SINCE mark_log(), not to a truncated
# file. `rm`-ing wallet.log proved unreliable (logback may hold or recreate the
# handle), and a stale line from an earlier run silently satisfied a refute —
# which is exactly how a contaminated S1 reported a false failure. Line-offset
# scoping is immune to that, and to log rotation (guarded below).
LOG_MARK=0

mark_log() {
  local f; f=$(pull_log)
  LOG_MARK=$(wc -l < "$f" 2>/dev/null | tr -d ' ')
  : "${LOG_MARK:=0}"
  note "log marked at line $LOG_MARK — assertions below only see NEW lines"
}

# Lines appended since the mark. If the log shrank (rotation/truncation),
# fall back to the whole file rather than silently asserting on nothing.
# Writes the window to a FILE and echoes its path. Callers must grep the file,
# never `since_mark | grep -q`: under `set -o pipefail`, grep -q exits on the
# first match, tail dies of SIGPIPE, and the pipeline reports 141 — so a match
# looks like a failure. That produced false FAILs for patterns appearing early
# in a large window while later ones passed.
since_mark() {
  local f n out="$OUT/.window"; f=$(pull_log); n=$(wc -l < "$f" | tr -d ' ')
  if [ "${n:-0}" -lt "${LOG_MARK:-0}" ]; then cp -f "$f" "$out"
  else tail -n +$((LOG_MARK + 1)) "$f" > "$out"; fi
  echo "$out"
}

# assert_log <label> <grep-pattern> [timeout-secs]
# POLLS rather than sampling once: engine startup on the emulator has been seen
# to take 15s+ (STARTUP breadcrumb SDK_L1_ENGINE_STARTING +15910ms), so a fixed
# sleep followed by a single check reports false FAILs for a healthy engine.
assert_log() {
  local label="$1" pat="$2" budget="${3:-45}" waited=0
  while [ "$waited" -lt "$budget" ]; do
    if grep -qE "$pat" "$(since_mark)"; then
      printf '   \033[32mPASS\033[0m %s%s\n' "$label" "$([ "$waited" -gt 0 ] && echo " (after ${waited}s)")"
      return 0
    fi
    sleep 5; waited=$((waited + 5))
  done
  fail "$label" "(no match in ${budget}s for: $pat)"
}

# refute_log <label> <grep-pattern>   — PASS if absent from the new lines
refute_log() {
  local label="$1" pat="$2"
  local w; w=$(since_mark)
  if grep -qE "$pat" "$w"; then
    fail "$label" "(unexpected: $pat)"
    grep -E "$pat" "$w" | head -2 | sed 's/^/          /'
  else printf '   \033[32mPASS\033[0m %s\n' "$label"; fi
}

# assert_not_committed <label> — reads the PERSISTED state, not the log.
# The engine-gate lines (`dashjEngineMayStart=`, `starting peergroup`) are only
# emitted at service onCreate. A scenario that deliberately keeps ONE process
# alive has no new onCreate, so those lines cannot appear in its window and
# asserting on them is a guaranteed false FAIL. The DataStore is the durable
# source of truth for who owns L1.
assert_not_committed() {
  local label="$1" raw
  raw=$(adbs "strings /data/data/$PKG/files/datastore/dashpay.preferences_pb 2>/dev/null" | tr -d '\r')
  if echo "$raw" | grep -qE "CUT_OVER|SETTLED"; then
    fail "$label" "(persisted state is committed)"
  else
    printf '   \033[32mPASS\033[0m %s\n' "$label"
  fi
}

fake_pre_cutover_previous_launch() {
  # Configuration.lastVersionCode is read from the default SharedPreferences
  # ("last_version", Configuration.java:61) at construction. Writing it here is
  # exactly equivalent to "the previous launch ran a pre-cutover build", which is
  # what CutoverCoordinator's GATE 1 tests — without needing a real v11.9.0 APK
  # signed with matching keys.
  #
  # CAVEAT: this exercises the GATE logic, not real upgrade mechanics (Room
  # migrations, wallet-protobuf format). For a true upgrade rehearsal, build
  # tag v11.9.0 with the same signing config and install that first instead.
  local f="/data/data/$PKG/shared_prefs/${PKG}_preferences.xml"
  adbs am force-stop "$PKG"
  sleep 1
  if ! adbs "test -f $f" 2>/dev/null; then
    note "prefs file not present yet — launch the app once, complete onboarding, then re-run"
    return 1
  fi
  # If a REAL upgrade was just installed over a pre-cutover build, lastVersionCode
  # already holds the genuine value — leave it alone. Faking it would only
  # overwrite the truth with the same thing, and hide a mismatch if the real
  # upgrade did not record what we expect.
  local cur
  cur=$(adbs "grep -o 'last_version\" value=\"[0-9]*' $f" 2>/dev/null | grep -o '[0-9]*$' | tr -d '\r')
  if [ -n "$cur" ] && [ "$cur" -gt 0 ] && [ "$cur" -lt "$FIRST_CUTOVER_VERSION_CODE" ]; then
    note "REAL pre-cutover upgrade detected (last_version=$cur) — not faking it"
    return 0
  fi
  note "no real pre-cutover previous launch (last_version=${cur:-unset}) — faking it"
  adbs "sed -i 's#<int name=\"last_version\" value=\"[0-9]*\" />#<int name=\"last_version\" value=\"$PREV_VERSION_PRE_CUTOVER\" />#' $f"
  note "last_version now: $(adbs "grep -o 'last_version\" value=\"[0-9]*' $f" | tr -d '\r')"
}

clear_cutover_state() {
  # DashPayConfig is an androidx preferences DataStore named "dashpay"
  # (DashPayConfig.kt:107). Deleting it resets cutover_state,
  # sdk_bind_ever_succeeded and cutover_upgrade_boundary_crossed together.
  #
  # OPT-IN ONLY (RESET=1). After a REAL upgrade from a pre-cutover build, this
  # state IS the thing under test — wiping it would destroy the scenario and
  # silently turn a real upgrade run into a synthetic one.
  if [ "${RESET:-0}" != "1" ]; then
    note "keeping the existing cutover state (set RESET=1 to wipe it for a synthetic run)"
    adbs "ls /data/data/$PKG/files/datastore/ 2>/dev/null" | tr -d '\r' | sed 's/^/     datastore: /'
    return 0
  fi
  adbs am force-stop "$PKG"
  sleep 1
  adbs "rm -f /data/data/$PKG/files/datastore/dashpay.preferences_pb"
  note "cutover DataStore CLEARED (state, bind marker, boundary latch)"
}

# Print the persisted cutover state. Contaminated preconditions are what make
# these scenarios silently meaningless, so every run shows its starting point.
show_state() {
  local pb="/data/data/$PKG/files/datastore/dashpay.preferences_pb"
  local raw; raw=$(adbs "strings $pb 2>/dev/null" | tr -d '\r')
  local st="DUAL_RUNNING(default)"
  echo "$raw" | grep -q "CUT_OVER"  && st="CUT_OVER"
  echo "$raw" | grep -q "SETTLED"   && st="SETTLED"
  local bind="unset" latch="unset"
  echo "$raw" | grep -q "sdk_bind_ever_succeeded"          && bind="present"
  echo "$raw" | grep -q "cutover_upgrade_boundary_crossed"  && latch="present"
  note "state: $st | bind marker: $bind | boundary latch: $latch"
  note "installed: $(adbs "dumpsys package $PKG | grep -m1 versionName" | tr -d ' \r')"
}

# require_state <CUT_OVER|PRECOMMIT> — refuse to run a scenario whose
# precondition is already violated, instead of reporting a bogus FAIL.
require_state() {
  local want="$1" pb="/data/data/$PKG/files/datastore/dashpay.preferences_pb" raw
  raw=$(adbs "strings $pb 2>/dev/null" | tr -d '\r')
  local committed=no
  echo "$raw" | grep -qE "CUT_OVER|SETTLED" && committed=yes
  case "$want" in
    PRECOMMIT) [ "$committed" = no ]  || { echo "   ABORT: needs a pre-commit state but the cutover is already committed."; echo "          Re-run with RESET=1 to start clean."; exit 2; } ;;
    CUT_OVER)  [ "$committed" = yes ] || { echo "   ABORT: needs a committed cutover — run s1 first."; exit 2; } ;;
  esac
}

lock_screen()   { adbs input keyevent 26; sleep 2; }   # power -> screen off, keyguard on
# WAKE ONLY — never `keyevent 26` (power), which TOGGLES: on an already-unlocked
# device it turns the screen OFF and re-locks it, which silently re-created the
# Keystore device-locked denial in the middle of a scenario that needs a working
# bind. Also note `input text` does NOT reach the keyguard PIN pad (digits need
# keyevents 8-11); scripted PIN entry proved unreliable, so an actually-locked
# device must be unlocked by hand before running the unlocked scenarios.
# Keep the screen on for the whole run: the scenarios poll for up to 45s and a
# screen-off re-locks the device, which re-creates the Keystore denial in the
# middle of a scenario that needs a working bind.
keep_awake()    { adbs svc power stayon true >/dev/null 2>&1
                  adbs settings put system screen_off_timeout 1800000 >/dev/null 2>&1; }

# Enter $PIN on the keyguard using DIGIT KEYEVENTS. `input text` does not reach
# the keyguard PIN pad — that is why earlier scripted unlocks silently failed and
# left the device locked, which then denied the master-alias keystore op inside
# scenarios that needed a working bind. KEYCODE_0 is 7, so digit d -> 7+d.
pin_keyevents() {
  local i ch
  if [ -z "$PIN" ]; then
    note "no PIN supplied — set EMULATOR_PIN=<pin> to let the script unlock the device,"
    note "or unlock it by hand before running this scenario."
    return 1
  fi
  for (( i=0; i<${#PIN}; i++ )); do
    ch=${PIN:$i:1}
    adbs input keyevent $((7 + ch))
  done
  adbs input keyevent 66   # ENTER
}

# Wake and, if a keyguard is up, dismiss it. Never `keyevent 26` (power), which
# TOGGLES and would turn the screen OFF on an already-unlocked device.
wake_unlock() {
  keep_awake
  adbs input keyevent 224; sleep 2
  if adbs dumpsys window 2>/dev/null | grep -q "mDreamingLockscreen=true"; then
    adbs input swipe 540 2000 540 600 300; sleep 2
    pin_keyevents; sleep 4
  fi
  if adbs dumpsys window 2>/dev/null | grep -q "mDreamingLockscreen=true"; then
    note "WARNING: keyguard still up after entering \$PIN — unlock by hand, then re-run."
    note "         a locked device denies the master-alias keystore op and this"
    note "         scenario needs a WORKING bind."
  else
    note "device unlocked"
  fi
}

launch_app()    { adbs monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 12; }
start_service() { adbs am start-foreground-service -n "$SVC" >/dev/null 2>&1; sleep 12; }

case "${1:-}" in

setup)
  say "Build the RELEASE testnet APK"
  ./gradlew :wallet:assemble_testNet3Release || exit 1
  # archivesBaseName is 'dash-wallet' but the concrete name has varied
  # (dash-wallet-*-release-unsigned.apk vs wallet-*-release-unsigned.apk), and
  # the flavor dir is '_testNet3'. Discover it instead of hardcoding.
  UNSIGNED=$(find wallet/build/outputs/apk -path '*release*' -name '*unsigned*.apk' -newermt '-30 minutes' 2>/dev/null | grep -i testnet | head -1)
  [ -n "$UNSIGNED" ] || UNSIGNED=$(find wallet/build/outputs/apk -path '*release*' -name '*unsigned*.apk' 2>/dev/null | grep -i testnet | head -1)
  if [ -n "$UNSIGNED" ]; then
    # The APK must be signed with the SAME key as the build already installed,
    # or `adb install -r` fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE. The
    # keystore holds more than one key, so the alias matters:
    #   KS_ALIAS=<alias>            pick the key (default: keystore's first)
    #   KEYSTORE=/path/to.keystore  use a different keystore entirely
    # Find the right one by comparing certs with the installed build:
    #   keytool -list -v -keystore ~/.android/hashengineering.keystore
    note "signing $UNSIGNED with ${KEYSTORE:-~/.android/hashengineering.keystore}${KS_ALIAS:+ (alias $KS_ALIAS)}"
    "$BT/zipalign" -p -f 4 "$UNSIGNED" "$OUT/aligned.apk"
    if [ -n "${KS_ALIAS:-}" ]; then
      "$BT/apksigner" sign --ks "${KEYSTORE:-$HOME/.android/hashengineering.keystore}" \
        --ks-key-alias "$KS_ALIAS" --out "$OUT/test.apk" "$OUT/aligned.apk" || exit 1
    else
      "$BT/apksigner" sign --ks "${KEYSTORE:-$HOME/.android/hashengineering.keystore}" \
        --out "$OUT/test.apk" "$OUT/aligned.apk" || exit 1
    fi
    rm -f "$OUT/aligned.apk"
    APK="$OUT/test.apk"
  else
    APK=$(find wallet/build/outputs/apk -path '*release*' -name '*.apk' 2>/dev/null | grep -i testnet | head -1)
  fi
  [ -n "${APK:-}" ] && [ -f "$APK" ] || { echo "No testnet release APK found under wallet/build/outputs/apk"; exit 1; }
  say "Install the fix build OVER the previous version already on the device"
  require_device; require_root
  PREV_NAME=$(adbs "dumpsys package $PKG | grep -m1 versionName" 2>/dev/null | tr -d ' \r')
  PREV_CODE=$(adbs "dumpsys package $PKG | grep -m1 versionCode" 2>/dev/null | tr -d '\r' | sed 's/^ *//')
  if [ -n "$PREV_NAME" ]; then
    note "currently installed: $PREV_NAME  ($PREV_CODE)"
  else
    note "WARNING: $PKG is not installed — this will be a CLEAN INSTALL, not an upgrade."
    note "Install your pre-cutover build first if you want the upgrade path tested."
  fi
  if ! adb install -r "$APK"; then
    echo
    echo "Install failed. If it was INSTALL_FAILED_UPDATE_INCOMPATIBLE, the signing keys differ."
    echo "Compare them:"
    echo "  adb shell pm path $PKG                     # then: adb pull <path> old.apk"
    echo "  $BT/apksigner verify --print-certs old.apk"
    echo "  $BT/apksigner verify --print-certs $APK"
    echo "Then re-run with the matching alias, e.g.:"
    echo "  KS_ALIAS=<alias> $0 setup"
    exit 1
  fi
  note "installed: $(adbs "dumpsys package $PKG | grep -m1 versionName" | tr -d ' \r')"
  say "Ensure a secure lock screen (this is what makes the master alias lock-bound)"
  if [ -n "$PIN" ]; then
    adbs locksettings set-pin "$PIN" >/dev/null 2>&1 \
      && note "device PIN set from EMULATOR_PIN" \
      || note "locksettings unavailable — set a device PIN via Settings before running s2"
  else
    note "no EMULATOR_PIN given — set a device PIN yourself; s2/s3 need one to lock+unlock"
  fi
  note "then run: $0 s1"
  ;;

s1)
  say "S1 — upgrade with a WORKING bind (walletC/D shape; regression check)"
  require_device; require_root
  clear_cutover_state
  show_state
  require_state PRECOMMIT
  fake_pre_cutover_previous_launch || exit 1
  mark_log
  note "launch 1: boundary should latch, commit should DECLINE (bind has not run yet)"
  wake_unlock; launch_app
  assert_log "launch 1 declined the commit"      "declining to commit .*bind has never succeeded"
  refute_log "launch 1 did NOT cut over"         "DUAL_RUNNING -> CUT_OVER"
  note "launch 2: bind marker now set, latch carries the crossing -> should commit"
  adbs am force-stop "$PKG"; sleep 2; launch_app
  assert_log "launch 2 committed"                "cutover state DUAL_RUNNING -> CUT_OVER \(upgraded-wallet launch\)"
  assert_log "explainer armed"                   "one-time sync explainer armed"
  assert_log "SDK L1 engine started"             "L1 shadow SPV started"
  ;;

s2)
  say "S2 — upgrade with a DENIED bind (walletB shape; the core fix)"
  require_device; require_root
  clear_cutover_state
  show_state
  require_state PRECOMMIT
  fake_pre_cutover_previous_launch || exit 1
  mark_log
  note "locking the screen so the bind runs while Keystore's super key is zeroed"
  lock_screen
  start_service
  assert_log "keystore denied the master alias"  "Keystore denied '(encrypt|createWallet)' on lock-bound alias"
  assert_log "commit declined on a broken bind"  "declining to commit .*bind has never succeeded"
  refute_log "did NOT cut over"                  "DUAL_RUNNING -> CUT_OVER"
  assert_log "dashj is the live engine"          "starting peergroup"
  refute_log "wallet is NOT engine-less"         "holding the dashj L1 engine"
  note "the old bug looked like: 'holding the dashj L1 engine' with no 'L1 shadow SPV started'"
  ;;

s3)
  say "S3 — denied, then healed IN-SESSION (the walletB recovery path)"
  require_device; require_root
  note "assumes S2 just ran: state DUAL_RUNNING, bind marker unset, screen locked"
  show_state
  mark_log
  wake_unlock
  launch_app

  # The recovery chain, in order. Each of these was broken until b49ef25b0:
  #   - ProcessLifecycleOwner never fired, so the foreground signal never existed
  #   - noteAppForeground was state-only, so nothing drove a retry
  #   - nothing else calls maybeRetry once the cutover correctly declines to commit
  assert_log "app-foreground signal fired"       "App moved to foreground"
  assert_log "foreground drove a bind retry"     "app foregrounded with an SDK bind retry pending"
  assert_log "the retry ran a pass"              "SDK bind retry [0-9]+ \(app foreground\)"
  assert_log "bind healed after the unlock"      "app wallet (bound to new|already bound to) SDK wallet"
  assert_log "retry pressure cleared"            "bind established after [0-9]+ failed pass"

  # …and it must be the SAME process: a restart would heal it trivially and prove
  # nothing. walletB restarted repeatedly and never recovered.
  refute_log "healed WITHOUT a process restart"  "WalletApplication.onCreate\(\)"

  # dashj must still own L1 for this launch. The commit is NOT expected here and
  # asserting it was my error: the upgrade seam only runs at process start, so
  # in-session the only route is the readiness-gated auto-commit, which needs
  # MIN_PARITY_STREAK readings at a 10s throttle AND the SDK scan caught up to
  # tip. 45s cannot satisfy that, so a missing commit here is correct deferral,
  # not a defect. The commit is checked on the NEXT launch instead — see s3b.
  assert_not_committed "dashj still owns L1 (state not committed)"
  note "commit is deliberately NOT asserted here — run s3b to check the next launch"
  ;;

s3b)
  say "S3b — the launch AFTER an in-session heal should commit"
  require_device; require_root
  show_state
  note "the bind marker must be set by now (s3 healed it); the seam can act at process start"
  adbs am force-stop "$PKG"; sleep 3
  mark_log
  wake_unlock
  launch_app
  assert_log "committed on the next launch"      "cutover state DUAL_RUNNING -> CUT_OVER|READY_OBSERVED -> CUT_OVER"
  assert_log "SDK L1 engine started"             "L1 shadow SPV started"
  ;;

s4)
  say "S4 — memory trim -> SPV engine restart (the ea506f978 fix)"
  require_device; require_root
  show_state
  require_state CUT_OVER
  # Force-stop and mark BEFORE launching, so the engine's start is a NEW line.
  # Marking while the app is already up leaves nothing to observe and reports a
  # bogus FAIL for a healthy engine.
  adbs am force-stop "$PKG"; sleep 3
  mark_log
  launch_app
  assert_log "engine started on a cold launch"   "L1 shadow SPV started"
  note "firing TRIM_MEMORY_BACKGROUND deterministically"
  adbs am send-trim-memory "$PKG" BACKGROUND; sleep 6
  assert_log "service tore down"                 "low memory detected, stopping service"
  assert_log "engine stopped (release build)"    "L1 shadow sync stopped"
  note "bringing the app back — the engine MUST restart"
  launch_app
  COUNT=$(grep -c "L1 shadow SPV started" "$(since_mark)")
  if [ "${COUNT:-0}" -ge 2 ]; then
    printf '   \033[32mPASS\033[0m engine restarted after the teardown (%s starts)\n' "$COUNT"
  else
    fail "engine did NOT restart — MO-995 latch still present (${COUNT:-0} starts)"
  fi
  ;;

log)
  require_device
  f=$(pull_log); echo "$f"
  grep -nE "cutover state|declining to commit|Keystore denied|L1 shadow SPV started|L1 shadow sync stopped|low memory detected|idling detected|starting peergroup|holding the dashj L1 engine|bind has never succeeded|explainer armed" "$f" | tail -40
  ;;

*)
  sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
  ;;
esac
