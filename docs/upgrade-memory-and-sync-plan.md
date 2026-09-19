# Dash Wallet v12: Memory and Sync Recovery Plan

**Status:** draft for review, 2026-09-15; revised 2026-09-16 for the no-fallback cutover policy (section 12); Phase 1a and 1b implemented 2026-09-16 on `fix/upgrade-memory-and-sync` (section 13); verified on two emulators the same day, with corrections (sections 14 and 15)
**Source:** field logs from one mainnet install (Pixel 8a, Android 17, build 12000010 `12.0.0-sync`), sessions 2026-09-12 through 2026-09-15 UTC, plus the `fix/kotlin-sdk-balance-issues` branch at `50a42be8b`.

---

## 1. Goal

An upgraded wallet the size of the reference install must:

1. survive the upgrade launch without running two SPV engines;
2. cut over to the SDK on the upgrade launch itself, with no fallback to dashj; when the bind cannot run because the device is locked, hold everything until it can, and tell the user;
3. complete the SDK replay on a 512 MB-heap device without the process being killed and without the replay losing ground on restart;
4. idle well under the heap ceiling once cut over.

Reference install: 33,297 transactions, 67,770 CoinJoin keys, 229 DashPay friend chains (29,970 keys), 12,646 tx-metadata documents, 62 MB wallet file, wallet birth May 2023.

### Targets

| Metric | Measured | Target |
|---|---|---|
| JVM heap idle after cutover, dashj held | 482 MB of 512 MB | under 150 MB |
| SPV engines running on an upgrade launch | 2 | 1 |
| Launches from upgrade to CUT_OVER | 3 (22 hours) | 1; the bind runs at the first unlocked moment |
| dashj peergroup starts on a v12 install | every pre-commit launch | only with Tools › dashj sync on |
| Native heap peak during SDK replay | 1.75 GB (foreground LMK kill) | bounded, under 600 MB |
| Blocks lost per engine teardown | up to 155,000 | 0 on clean stop, at most 5,000 on kill |
| Service stops during an SDK replay | 9 in one day | 0 |
| Full-wallet serializations during a 40 s sync burst | 3 | at most 1 |
| Application.onCreate on a background start | 10 to 14 s (ANR-killed 7 times) | under 10 s, or no background wallet load |

---

## 2. What the logs established

### 2.1 The crash (2026-09-15 12:17 to 12:18 UTC)

Java `OutOfMemoryError`, 512 MB heap full, 90 seconds after launch. Exit record: `CRASH_NATIVE` status 6 then `CRASH`, RSS 1,122 MB, foreground.

- The dashj wallet object graph alone occupies about 450 MB of the 512 MB heap. Measured at 482 MB idle with no engine running.
- On this launch the upgrade seam declined to commit the cutover (no bind evidence yet), so dashj started. The SDK bind then succeeded mid-launch and the SDK engine started beside dashj at 488 MB heap. Concurrent load: dashj chain download with a 33k-tx bloom filter, about 20,000 InstantSend verification log lines, three 62 MB wallet autosaves in 40 s, a 23 s tx-metadata re-merge, 201 DashPay account builds queued.

### 2.2 The upgrade path (2026-09-14)

Version code 11090100 (v11.9.1) to 12000010 (v12.0.0-sync build 10).

- Launch 1 (background, package-replaced broadcast, phone locked): wallet parse 10.2 s, killed by bind-application ANR before the cutover code ran.
- Launch 2 (background, phone locked): upgrade seam Gate 1 passed and latched the boundary flag; Gate 2 (bind evidence) failed by construction. Bind failed: `KeystoreDeviceLockedException` on the lock-bound SDK master key, `isDeviceLocked=true`. Nothing retried for 26 minutes; the retry service only drives in the committed-and-unbound state. LMK killed the cached process.
- Launch 3 (foreground): the crash above.
- Launch 4: cutover committed, dashj held, SDK owns L1.

### 2.3 The aftermath (2026-09-15 12:18 to 23:01 UTC)

dashj never synced again after the cutover commit. Every problem below is SDK-path only.

- **Idle detector tore the engine down nine times.** It samples the SDK filter position once a minute and stops the service after three quiet minutes. The SDK replay has multi-minute stretches with no filter advance (downloading and processing matched blocks; the CoinJoin years produce 56 to 124 MB block segments), and the "DashPay bring-up before SPV" that precedes every engine start ran 5 to 42 minutes with the filter position frozen. Four times the SPV engine started and was torn down within 0 to 15 seconds.
- **Progress was lost on every teardown.** The SDK persisted its synced height only at 5,000-block steps and, after the first minutes, rarely: 101 of about 150 wallet-event batches logged `synced_height_persisted=None`; nothing persisted between 12:37 and 13:48 while the scan advanced 170,000 blocks. One teardown dropped the watermark from 2,310,479 to 2,155,479 (97.0% shown, restart at 95.0%).
- **The `replaying` flag is hard-coded false on the SDK path** (`BlockchainStateDataProvider.updateSdkBlockchainState`), so the one-minute restart alarm never fires after an idle stop. Restarts came from the 15-minute periodic job or the user opening the app, 5 to 18 minutes later.
- **Native memory, not JVM, is the post-cutover killer.** JVM heap sat at 300 to 470 MB. Native heap climbed from 75 MB to 1.0 to 1.1 GB within 30 minutes of replay and reached 1.75 GB before the LMK killed the foreground process at RSS 1.73 GB. It falls back to 200 to 400 MB when the engine is torn down.
- **Restarts collide with slow shutdowns.** Three launches failed on `OverlappingFileLockException` opening the dashj SPV blockstore, which the service still opens and locks even though dashj is held, while the previous instance's cleanup (serializing the 62 MB wallet) still held the lock.
- **Net progress:** durable watermark from 1,860,479 to about 2,210,479 in 11 hours, roughly half of the 679,000-block replay. The remaining 200,000 blocks are the densest.
- **Balance published by the SDK swung between 0 and 812 DASH** all day with `l1Synced=false`. The header code should hold the last known total while unsynced; not confirmed on device.
- Two more bind-application ANR kills on background starts (wallet parse 10 to 14 s).

### 2.4 Old build baseline (11.9.1, 09-12 to 09-14)

Already failing on this wallet: 889 MB RSS when swiped away, one `EXCESSIVE_RESOURCE_USAGE` kill (714 MB, 50% CPU over 5 min while cached), four bind-application ANR kills. A downgrade returns the user to this state and requires uninstall plus seed restore; not a fix.

---

## 3. Root causes, one line each

1. Full dashj wallet loaded on every launch, including post-cutover where it is held and unused. 482 MB idle.
2. Upgrade seam cannot commit on the launch that records bind evidence, so a successful bind mid-launch leaves dashj running and the SDK engine starts beside it. (Removed by Phase 1a item 1: the commit no longer waits for bind evidence.)
3. Bind attempted while the device is locked counts as a failure and nothing retries in the pre-commit state. (Phase 1a item 3: classified as pending, retried on unlock and foreground.)
4. Idle detector treats a paused SDK replay as an idle service.
5. `replaying` is never true on the SDK path, and never set at all for an upgraded wallet, so nothing protects the replay or restarts it quickly.
6. DashPay bring-up before SPV has no effective time budget on a wallet with 200 pending account builds.
7. SDK persists its synced height too rarely and not on stop.
8. SDK replay native memory is unbounded on a wallet with thousands of matched blocks.
9. dashj SPV blockstore is opened and file-locked post-cutover; shutdown serializes the wallet, so restarts collide.
10. Wallet parse on the main thread inside `Application.onCreate` exceeds the 10 s background ANR limit.
11. Log volume: about 27,000 of the 28,000 lines in the crash minute were InstantSend and metadata-merge lines; one OOM fired inside logback.
12. Flat 5 s autosave serializes the whole 62 MB wallet repeatedly during sync.

---

## 4. Plan

### Phase 1a: cut over on the upgrade launch, no dashj fallback (app, about one week)

Policy (2026-09-16): an upgraded wallet cuts over to the SDK immediately. If the SDK bind fails, the app does not fall back to dashj. dashj syncs only when the user turns on Tools › dashj sync. If the bind fails because the keystore is lock-bound and the device is locked, the app waits for the unlock, retrying on the unlock broadcast and on app foreground, and shows the user what it is waiting for. Section 12 has the reasoning and the keystore analysis.

1. **Cutover is unconditional and dashj never starts on its own.** The upgrade seam commits CUT_OVER on the upgrade launch without bind evidence, the way `commitForFreshWalletSetup` already does for new wallets. Gate 2 (`SDK_BIND_EVER_SUCCEEDED`) and `refusesCutOverWithoutBindEvidence` go away for the upgrade path; `CutoverCoordinator.dashjEngineMayStart()` returns false on every install. The service gate becomes `dashjEngineMayStart = dashjSyncDiagnostic`, so the Tools toggle is the only thing that starts the dashj peergroup. "Never two engines" follows: the SDK engine is the only engine unless the user opts into the diagnostic, and the diagnostic already runs beside the SDK by design.
2. **Delete the fallback and the automatic wipe.** Remove `rollbackForFailedBind` and the rollback consult in `SdkBindRetryService`; remove `CutoverAutoCommitObserver` and the readiness table as an ownership gate; retire `L1ShadowSyncService` shadow mode and its `ShadowResetDecider.REBUILD_WALLET` path (emulator finding 8: it wiped the SDK wallet and rescanned from genesis without the user knowing). The parity probe survives as one log line per synced probe with both balances, and as a support-report field.
3. **"SDK setup pending" state.** A bind failure is classified, not counted:
   - `KeystoreDeviceLockedException`, or `KeyguardManager.isDeviceLocked` true at attempt time: **pending**. Persist the state, arm the `ACTION_USER_PRESENT` receiver immediately (today it arms only after a commit), retry on app foreground (`noteAppForeground` already exists; walletB's HONOR never delivered the broadcast), and keep the existing 5/15/30/60 s then hourly ladder. While pending nothing runs: no dashj, no SPV, no DashPay bring-up, and the service stops rather than idling. UI: a persistent notification "Unlock your phone to finish the wallet update" for the background case, a blocking sheet on app open while still pending, and the header holds the last-known dashj total with an "update pending" label.
   - Denial while `KeyguardManager` reports unlocked, on three consecutive foreground attempts: **keystore problem**. Show an error surface naming the device keystore, offer Tools › dashj sync as the manual escape, and log the classification. Nothing automatic; PR #1555's walletB had 7 such denials out of 16 and they never heal on unlock.
   - Any other failure: log with the exception class, retry on the ladder, and reach the same error surface after the ladder is exhausted.
4. **Bind first on an unlocked launch, then everything else.** The bind needs the mnemonic out of the dashj wallet under the SecurityGuard password, so it cannot precede the wallet parse, but it runs before any other heavy work: before the tx-metadata merge, before "DashPay bring-up", before the SDK engine start. It is `createWallet` plus `storeMnemonic`, about a second on the emulator. If the screen locks between the parse and the bind, the attempt lands in pending; the half-bound recovery that already exists ("re-stored the missing mnemonic for SDK wallet") covers a lock that lands between the two writes.
5. **Log diet.** `InstantSendManager`, `SPVQuorumManager`, `SigningManager` to WARN in the logback config. Per-transaction "exists, only do update" and "metadata merged" lines to debug.
6. **Size-aware autosave.** Replace the flat 5 s `WALLET_AUTOSAVE_DELAY_MS` with a delay scaled by the size-guard verdict (for example 60 s above RISKY) and suppress saves during active chain download. The code already carries a TODO for this.

Result on the reference upgrade: launch 1 (background, locked) parses the wallet, commits, attempts the bind, records pending, and stops; the notification appears. The next unlock or foreground open binds in about a second and starts the SDK engine alone. dashj never starts, so the 12:17 crash session never runs two engines. The 22-hour gap becomes "until the user next unlocks the phone". Idle heap is still 482 MB until Phase 2, and the replay still has to finish, which is Phase 1b and 1c.

### Phase 1b: let the replay finish (app, same week, ships with 1a)

7. **The blockchain service stays alive until the replay is complete.**
   - The idle detector does not stop the service while `blockchainState.replaying` is true. Today the flag only reschedules a one-minute alarm after the stop; make it a guard that skips the stop. Hold the wake lock for the same span. Genuinely stuck engines are covered by the SDK stall impediment and the watchdog, not the idle rule.
   - `updateSdkBlockchainState` stops writing `replaying = false` unconditionally and writes `replaying = percentageSync < 100`. `BlockchainStateDao.saveState` already clears the flag at 100%.
8. **Set the replay flag when an upgrade's SDK sync starts.** Only restore and rescan call `resetBlockchainState` (which writes `BlockchainState(replaying = true)`) today. Call it from the cutover commit path when the SDK engine is about to start from a watermark below the tip. Covers the upgraded-wallet launch, including the bind that completes after a pending wait (Phase 1a item 3), and makes the "sync paused" notification and home-screen sync state read correctly during the scan.
9. **One-minute reschedule becomes the fallback.** With item 7 the service should not stop mid-replay. If the OS or a failed start stops it anyway, the existing `rescheduleService` alarm now fires because the flag is true.
10. **Budget the DashPay bring-up before SPV.** The bring-up runs first for a reason (commit 1266edc1c: restored wallets missed contact-payment coins when DIP-15 receival accounts registered after the scan had passed their funding heights), so it keeps its place but gets a 20 s budget, the figure from the one unlocked foreground launch. Past the budget SPV starts and the bring-up finishes in the background; the SDK marks late accounts covered at synced height 0. The bring-up needs the keystore (section 12); on the reference install's locked overnight starts it ran 5 to 42 minutes on 09-15 and 766 s to 10,139 s on 09-16 with 0 of 154 to 177 builds drained and the filter position frozen, and the idle detector tore the service down before SPV ever began.
11. **Do not open or lock the dashj blockstore when dashj is held.** If a start does find a live lock, wait for the previous cleanup instead of stopping the service.
12. **Do not restart the engine into a dying service.** Gate `startIfEnabled` on the service not being in cleanup.
13. **Log the SDK watermark gap on every engine stop.** The app cannot flush the watermark: the SDK's `WalletDao` has no synced-height setter and the value is owned by the native engine. Every stop now logs the durable synced height against the cursor the engine committed and the filter position, at WARN when blocks will be re-walked. That line is the evidence for Phase 1c item 15, which is the real fix.

### Phase 1c: SDK (file now; needed before Phase 2 is meaningful on large wallets)

14. **Bound native memory during replay.** 75 MB to 1.1 GB in 30 minutes; 1.75 GB at the foreground LMK kill. Bound the in-flight matched-block set and release processed blocks.
15. **Persist synced height on a fixed cadence and on stop.** Every 5,000 blocks unconditionally, plus on every engine stop. Evidence: `synced_height_persisted=None` on 101 of about 150 batches; 170,000 blocks advanced with no persist between 12:37 and 13:48.
16. **Account builds never drain.** `deferredContactBuilds` sat at 154 to 201 all day with `SEED_BINDING_UNVERIFIED`. This blocks `l1Synced` and balance settling.

### Phase 2: drop the dashj transaction graph after cutover (app, 2 to 3 weeks)

The only item that changes the 482 MB idle baseline.

17. **In-memory release.** Once the state is CUT_OVER, dashj is held, and `TxDisplayCacheService` confirms the cache is complete, clear the wallet's transaction pool in memory and disable autosave so the file on disk is untouched. Expected idle well under 150 MB.
18. **Lean load.** On a CUT_OVER launch, parse the transaction-stripped key-backup shape instead of the full file. Removes the 8x parse peak, cuts startup from 7 to 14 s to under 2 s, and ends the background bind-application ANRs for cut-over installs.
19. **Reader audit.** Seventeen files still read dashj transactions:
    - Already SDK-backed, verify the guard: `MainViewModel` balance via `CutoverUiDataService`; `TxDisplayCacheService` cache-complete logic.
    - Reroute to the display cache or SDK records: `TransactionResultViewModel`, `WalletTransactionMetadataProvider`, `TransactionExporter`, `ZenLedgerViewModel`, `ChangeTaxCategoryExplainerDialogFragment`, `WalletObserver`.
    - Become post-cutover no-ops: L1 shadow parity probes, `L1SendProbeService`, dashj branches in `BlockchainServiceImpl`.
    - Diagnostics, null-guard: `CrashReporter`, `WalletUtils`, `BlockListFragment`.
    - **Scope question:** the migration plan says the main Send UI stays dashj-typed until Phase 5c.4 and `SdkBridgedTransactionFactory` reads dashj transactions. Confirm what the main send does on a held-dashj install before item 17 clears the pool. If it still builds from dashj UTXOs, the send path moves first or item 17 keeps the unspent subset.
20. **The only un-hold is the Tools toggle.** With `rollbackForFailedBind` gone (Phase 1a item 2), nothing un-holds dashj automatically. Turning on Tools › dashj sync after item 17 or 18 has run needs the full wallet file, so that path forces a process restart that loads the full file; the restart is acceptable for a diagnostic.

### Phase 3: installs still on dashj (only if Phase 2 measurements say so)

21. Refuse background service starts while the size guard says RISKY, so the wallet parse only happens in the foreground.
22. Transaction pruning in the dashj fork, dropping fully spent and deeply confirmed CoinJoin chain transactions. Large, touches coin selection. Last resort.

---

## 5. Measurement and verification

- The existing per-minute `MEM pss/nativeHeap/jvm` line is emitted at every startup breadcrumb and at every engine start and stop, with the `ReplayMemTelemetry` native figures folded in.
- A synthetic large-wallet fixture: 30k transactions, 200 friend chains, CoinJoin history, run on an emulator pinned to a 512 MB heap.
- A replay test that stops the engine mid-scan and asserts the persisted watermark equals the in-memory height.
- **Phase 1a acceptance:** an upgrade launch on the fixture with the device unlocked logs the commit, the bind, and the SDK engine start, and never logs a dashj peergroup start. The same launch with the device locked logs the pending state, starts no engine, shows the notification, and binds within 5 s of `ACTION_USER_PRESENT` or of the app coming to the foreground. A forced keystore denial while unlocked reaches the keystore-problem surface after three foreground attempts and never starts dashj. Turning on Tools › dashj sync is the only way to produce a dashj peergroup start in the log.
- **Phase 1b acceptance:** on the reference wallet, one service instance runs from the first SDK start to `percentageSync == 100` with no "idling detected" line; the `replaying` column is true throughout and false at the end; an induced stop is followed by a service start within 90 seconds; no `OverlappingFileLockException` on any launch.
- **Phase 2 acceptance:** the reference install idles under 150 MB JVM heap after cutover and launches in under 2 s.

## 6. Rollout

1. Ship Phase 1a and 1b together. Under the no-fallback policy they are one change: 1a removes the only other sync, so 1b's "the replay finishes" is what makes 1a safe.
2. File Phase 1c with the SDK team now. Items 14 and 15 are prerequisites for shipping 1a to installs the size of the reference one, not follow-ups: a wallet whose replay cannot finish has no sync at all once dashj is out of the picture. If the SDK items slip, the fleet gate (section 6 item 5) holds 1a for large wallets.
3. Phase 2 in the following build.
4. Phase 3 only if Phase 2 numbers leave pre-cutover installs exposed.
5. Fleet gate: the no-fallback cutover can be held behind the size-guard verdict (RISKY wallets stay on the current gating until 1c lands) if the SDK items are not in the build the app consumes. Decide per build.

## 7. Decisions needed

- **Shadow flag.** `USE_KOTLIN_SDK_L1_SHADOW` is seeded on for every variant including prodRelease (2026-07-30 decision). Phase 1a item 2 retires shadow mode, so the seed should go with it.
- **Keystore-problem devices.** With no automatic fallback, a device whose keystore denies while unlocked (the HONOR case) syncs nothing until the user turns on Tools › dashj sync. Decide whether that manual escape is acceptable for non-technical users, or whether to ask the SDK for a key policy that is not lock-bound. The SDK offers `DEVICE_BOUND` and `AUTH_GATED` only, and `AUTH_GATED` is stricter.
- **Pending-state UX.** Notification and sheet wording, and whether the wallet UI is usable read-only (last-known balances, history from the display cache) while the bind is pending, or blocked.
- **Rollback build.** A v12-numbered build carrying 11.9.1 code (plus a 22 to 21 Room migration dropping `instant_send_locks` and the friend-lookahead deferral backported) is cheap insurance as a fleet kill switch. It does not fix memory and returns large wallets to the 11.9.1 failure profile.
- **Balance display during replay.** Confirm on a device that the home header holds the last known total while `l1Synced=false`. If it shows the raw SDK figure, it is a support-ticket generator on its own.

## 8. The reference install today

Nothing in the current build lets its replay finish: every start dies before SPV begins, and each teardown costs up to 155,000 blocks. Until Phase 1b ships, a seed restore on v12 is the only path to a working wallet, and it runs the same replay with the same native memory profile, so it should wait for Phase 1c item 14 or be done on Wi-Fi with the app kept in the foreground.

## 9. Related review

PR #1555 (`fix/sdk-spv-restart-on-service-restart`) carries the cutover gating work. Its blocking review finding, retrying the boundary-latch write independently of a successful commit, is correct and cheap, but it concerns the one-time sync explainer only. Phase 1a item 2 makes the arming path run in the same launch and largely dissolves the finding. The PR's own verification note applies: real upgrade mechanics on a large wallet are untested; the scenario that took the reference install down sits between its S2 and S3b cases.

Under the 2026-09-16 policy the seam commits and arms the explainer in the same write on the upgrade launch, so the finding dissolves entirely; the retry it asks for has nothing left to retry.

## 10. Emulator upgrade test, 2026-09-16

**Setup.** emulator-5556 (Pixel_8 AVD, Android 14, release-keys, 4 GB RAM, largeHeap 576 MB). Testnet wallet "topple", 7,320 transactions, 19 DashPay contacts, 12 MB wallet file, restored and synced on 11.9.1 (11090106). Upgraded in place to 12000013 `12.0.0-username` (branch `fix/contested-username-restore-identity`) with the device unlocked, the happy path. Logs came from logcat, which the app's `LogcatAppender` mirrors on release builds; the emulator image is not rootable so `wallet.log` could not be pulled. Capture and per-process extracts are in `~/Downloads/upgrade-emulator-test-09-15-2026/`. The emulator clock jumped forward about 6 h 40 m at 23:56 local when it synced time; timestamps below are the emulator's.

### 10.1 Timeline

| Time | Process | Event |
|---|---|---|
| 23:37:33 | 9410 | Package replaced. Process started for the `MY_PACKAGE_REPLACED` broadcast, background |
| 23:37:36 | 9410 | Upgrade detected 11090106 → 12000013. Seam declines the cutover: no bind evidence |
| 23:37:37 | 9410 | SDK bind succeeds. Birth height resolves to 0, full testnet scan from genesis |
| 23:37:43 | 9410 | SDK engine starts, still background, no user action |
| 23:38:00 | 9410 | User opens the app. Service gate `dashjEngineMayStart=true`; dashj peergroup starts. **Two engines** |
| 23:46 to 23:47 | 9410 | Idle detector fires three times on dashj counters; service does not stop because MainActivity is bound |
| 23:48:57 | 9410 | SDK reaches SYNCED 100%. Parity probe: SDK 169.055 vs dashj 167.820 DASH, inflated |
| 23:49:17 | 9410 | **Recovery by wallet re-creation**: SDK wallet removed, SPV data dir deleted, rebind, scan restarts from genesis. Displayed % falls 100 → 64 |
| 23:54 | 9410 | User opens Network Monitor; service restart tears the second scan down mid-run; third scan starts |
| 06:29 | 9410 | Third scan SYNCED, SDK balance 167.82052936 = dashj exactly. Observer: "readiness says DUAL_RUNNING", no blocker logged |
| 06:39:08 | 9410 | User backgrounds the app. Pending stop lands immediately: service destroyed, both engines down, native heap 314 MB → 1.3 MB |
| 06:44:40 | 9410 | User swipes the app away (`USER_REQUESTED`) |
| 06:44:56 | 13979 | Relaunch. Seam commits **DUAL_RUNNING → CUT_OVER** in 2 s, explainer armed, dashj held, home screen served from SDK |
| 06:45:02 | 13979 | SDK engine resumes at filter 1,226,329, not the 1,554,887 it had persisted: 330,000-block re-walk. Live SDK balance climbs to 277.69 DASH; header holds last-known 167.82 |
| 06:46:03 | 13979 | SYNCED, live balance settles to 167.82052936 |
| 06:53:00 | 13979 | Backgrounded; SDK-fed idle detector fires after three quiet minutes at tip; service and engine stop cleanly |
| 07:08:29 | 13979 | 15-minute job restarts the service in the cached process. Engine resumes at tip in 8 s, **no rewind** |
| 07:08 to 07:32 | 13979 | Cached. `PlatformSyncService` 15-second ticker runs 54 full contact/profile/invite/metadata cycles, 1 to 3 s CPU each |
| 07:32:00 | 13979 | Idle stop |
| 07:33:54 | 13979 | **Killed: EXCESSIVE_RESOURCE_USAGE**, "excessive cpu 106050 during 300017", 35% while cached |
| 07:58:11 | 17778 | Job starts a cold process for the blockchain service. Engine rewinds to 1,371,329, live balance climbs to 278 DASH again, settles |
| 08:03 to 08:07 | 17778 | User opens and swipes away; unremarkable |
| 08:19:25 | 19127 | **WorkManager job** starts a cold process for `SystemJobService`. `WalletApplication.onCreate` starts platform sync and the SDK engine; no foreground service. Engine rewinds to 1,226,329 |
| 08:19:40 | 19127 | **Android freezes the process** nine seconds into the re-walk |
| 08:24:44 | 19127 | Blockchain service job thaws it; foreground service starts; re-walk resumes and settles |

### 10.2 Findings confirmed against the reference install's logs

1. The upgrade launch declines the cutover, the bind succeeds a second later, and nothing acts on it (root cause 2).
2. Opening the app starts dashj beside the already-running SDK engine. Reproduced on a 12 MB wallet; no OOM only because the heap was 576 MB and the wallet small.
3. The home screen shows dashj's synced state while the SDK scans; the sync explainer cannot appear because it is armed only on commit.
4. Swipe-away plus relaunch commits in two seconds. A tap-to-resume of the cached process would not, since the seam runs only in `Application.onCreate`.
5. The post-cutover idle detector stops the service after three quiet minutes at tip.
6. dashj never syncs again after the commit.

### 10.3 New findings, not visible in the reference logs

7. **Idle stops are no-ops while the activity is bound.** `stopSelf` sets `startRequested=false` but the `ConnectionRecord` keeps the service alive; the stop lands the instant the app is backgrounded. Explains why the reference install's teardowns clustered around leaving the app.
8. **The parity probe can wipe the SDK wallet.** An inflated mismatch for three synced probes triggered `REBUILD_WALLET`: SDK wallet removed, SPV data directory deleted, full rescan from genesis. The rebuild did fix the ledger (the third scan matched dashj to the duff), but the cost is a full re-sync and it fires without user knowledge. On this branch the decider still auto-recreates; the `fix/kotlin-sdk-balance-issues` branch documents it as manual-only.
9. **Readiness refusals are not diagnosable.** After a nine-minute synced-and-matching run the observer logged only "readiness says DUAL_RUNNING". Nothing logs the blocker set, and the parity probe logs only mismatches.
10. **Cold process starts rewind the SDK scan.** Three of three cold starts resumed 183,000 to 330,000 blocks below the persisted height (twice at exactly 1,226,329). An in-process service restart resumed at tip. No rewind arm is logged app-side, so this is SDK start-up behaviour. During each re-walk the live SDK balance inflates by about 65% before settling; the header's last-known hold masks it, but every cold start pays the CPU and network.
11. **The 15-second platform ticker kills cached processes.** `PlatformSyncService.UPDATE_TIMER_DELAY = 15.seconds` re-runs the full contact/profile/invite/tx-metadata cycle for as long as the service lives, foreground or not. Fifty-four cycles in a cached process earned an `EXCESSIVE_RESOURCE_USAGE` kill. The reference install shows 3 cycles an hour, likely because each pass on 229 contacts takes long enough for the "already running" guard to skip ticks.
12. **The SDK engine starts from `WalletApplication` init, not from the service.** A WorkManager job start therefore runs the engine in a plain background process with no foreground service, and the cached-app freezer suspended it nine seconds in, mid re-walk, mid balance inflation. It stayed frozen until an unrelated job thawed it five minutes later.
13. **Native memory scales with chain length, not wallet size.** 370 to 600 MB native during the scan of 1.55M testnet blocks for a 7,320-transaction wallet, released fully on engine stop (1.3 MB). The reference install's 1.1 to 1.75 GB is mainnet's 2.5M blocks plus its matched-block volume.
14. **The header hold works.** Post-cutover the home screen held 167.82 while the live figure swung to 278; the reference install's hold never engaged because `l1Synced` never came true there.

### 10.4 Additions to the plan from the test

Phase 1b gains:

- **Gate the SDK engine start on the foreground service.** Do not start the L1 engine from `WalletApplication` init; start it from `BlockchainServiceImpl` after `startForeground`, so a WorkManager- or broadcast-started process never runs a scan the freezer can suspend (finding 12).
- **Background-aware platform ticker.** Keep 15 s only while the app is in the foreground and the SDK is not caught up; back off to minutes when cached, and skip the cycle when nothing changed (finding 11).
- **Log the readiness verdict.** `CutoverAutoCommitObserver` and `autoAdvanceToCutover` log the blocker set on every refusal, and the parity probe logs one line per synced probe with both balances (finding 9).
- **Parity-probe wipe policy.** Decide whether `REBUILD_WALLET` may fire automatically in production. If it stays, log it at WARN with the balances and persist a marker the support report can surface (finding 8).

Phase 1c gains:

- **Cold-start rewind.** File with the SDK: on a fresh process the engine rewinds the filter watermark far below the persisted synced height and re-walks; the same wallet resumes at tip on an in-process restart (finding 10). Include the three observed resume heights.

Verification gains a scenario: a WorkManager-started process with no foreground service must not start the engine, and a frozen-then-thawed process must resume at the persisted height.

## 11. Reference install follow-up, 2026-09-15 23:01 to 2026-09-16 13:50 UTC

New logs from the reference install after the 09-15 report. The user ran **Settings → Rescan blockchain** at 23:07:43 with a start date of 2023-05-04. Section 4's prediction for that action held in every particular.

### 11.1 What the rescan did

- Armed `armSpvRescan` to height 1,860,480, rewinding the SDK watermark from 2,335,479. The dashj wallet was untouched, so idle heap stayed at 440 to 485 MB.
- Progress since, per engine run (filter watermark at run end, loss on the next restart):

| Run | Ended at | Loss on next restart |
|---|---|---|
| 23:09 to 23:56 | 2,140,480 | 25,000 (OOM crash) |
| 00:01 to 01:02 | 2,275,480 | 105,000 (idle stop) |
| 01:25 to 01:55 | 2,315,480 | 100,000 (idle stop) |
| 02:21 to 03:13 | 2,242,092 | 2,000 (idle stop, then phone idle overnight) |
| 12:37 to 12:41 | 2,242,092 | none |
| 13:50 | 2,265,480 at start | report filed |

After 15 hours the watermark is 2,265,480, still 70,000 blocks below where it stood before the rescan. The native log shows only 50 `synced_height_persisted` values, none above 2,155,480, the same ceiling as on 09-15.

### 11.2 Second OOM crash, 00:00:32 to 00:00:37

Idle stop at 23:58:00 began the shutdown serialization of the 62 MB dashj wallet. The user reopened at 00:00:20, so `onCreate` waited on that cleanup while `CutoverUiDataService` ran a full reconcile walk over Room flows. Three `OutOfMemoryError`s inside 4 seconds on the 512 MB heap, the service logged "CRITICAL: Cleanup did not complete within 15 seconds ... deadlock in onDestroy", and the process died as `CRASH`, foreground, RSS 1,222 MB. Same root cause as 09-15: a dashj wallet that fills the heap, plus a heavy transient, this time the shutdown serialization overlapping a start.

### 11.3 Everything else, counted

| Event | Count | Notes |
|---|---|---|
| Idle-detector stops | 10 | 23:07, 23:58, 01:05, 01:58, 02:07, 02:28, 03:15, 03:23, 12:45, 13:00 |
| Service starts failed on `OverlappingFileLockException` | 5 | 02:19, 03:40, 12:15 twice, 13:46 |
| Engine started then torn down within 15 s | 3 | 02:20, 12:21, 13:47 |
| "DashPay bring-up before SPV" over 10 minutes | 3 | 766 s, 1,438 s, and 10,139 s across the overnight freeze; 177 account builds pending, `SEED_BINDING_UNVERIFIED`, 0 drained |
| Native heap peak | 1,861 MB | 12:xx, PSS 2,325 MB; the LMK threshold from 09-15 was 1,729 MB RSS |
| `check()` invocations | 347 | 311 triggered by `network capabilities changed`; 223 in the 23:xx hour alone |

The header hold is present on this build and engaged: `lastKnown=3738332075`, so the home screen held 37.38 DASH while the SDK published figures between 223 and 746 DASH, none with `l1Synced=true`.

### 11.4 Plan impact

No new items. This set confirms, on the reference device, four things the plan already carries: the idle detector's teardown-and-lose-progress cycle (Phase 1b item 7), the SDK persistence ceiling (Phase 1c item 15), the file-lock collision on restart (Phase 1b item 11), and the bring-up budget (Phase 1b item 10). It adds one detail to Phase 2 item 17: the shutdown-time wallet serialization is a heap peak that can coincide with a start, so the in-memory release must also skip the save-on-stop when the pool has been cleared. The network-capabilities callback firing `check()` 200 times an hour is worth a debounce but is not on the critical path.

The user's second crash and the 70,000-block deficit are the cost of the rescan. Section 8's recommendation stands: nothing on this install finishes a replay until Phase 1b ships.

## 12. Policy change, 2026-09-16: immediate cutover, no dashj fallback

### 12.1 The policy

- An upgraded wallet cuts over to the SDK on the upgrade launch. No bind evidence is required to commit.
- If the SDK bind fails, the app does not fall back to dashj. dashj syncs only when the user turns on Tools › dashj sync.
- If the bind fails because the device is locked, the app waits for the unlock and retries on the unlock broadcast, on app foreground, and on a slow ladder. It tells the user what it is waiting for. Waiting until the user next opens the app is acceptable. **Corrected 2026-09-16 (section 15.4):** the unlock broadcast is not a dependable trigger, so the ongoing notification driving the user into the app is the guaranteed path, not a convenience.
- Open issue, addressed in Phase 1b rather than here: the user opens the app, the launch takes long, and the app is backgrounded or the screen locks before the launch finishes.

What it removes from the previous plan: the two-launch commit dance, the dual-run, the bind-evidence gate, the readiness table as an ownership gate, the rollback, and the automatic SDK wallet wipe. What it makes mandatory: the replay must finish on its own (Phase 1b, Phase 1c items 14 and 15), because nothing else syncs.

### 12.2 How the lock screen and the keystore interfere

The app constructs the SDK's `WalletStorage` with `KeySecurityPolicy.DEVICE_BOUND` (`DashSdkServiceImpl.kt:1117`). The SDK offers two policies, `DEVICE_BOUND` and `AUTH_GATED`; the second adds a biometric requirement, so `DEVICE_BOUND` is already the most permissive available. It creates the master alias `org.dashfoundation.wallet.master` with `setUnlockedDeviceRequired`, and Keystore2 refuses every encrypt and decrypt on that key while the lock screen is engaged. Which SDK operations go through the key decides what breaks while locked:

| Operation | Needs the unlocked keystore | Evidence |
|---|---|---|
| Bind: `createWallet` + `storeMnemonic` | Yes, once | 09-14 14:49 `Keystore denied 'createWallet' on lock-bound alias ... isDeviceLocked=true` |
| Opening an existing SDK wallet at process start | No | "restored 1 wallet(s)" on every overnight background start |
| SPV scan: headers, filters, matched blocks | No | 09-16 01:25 to 01:55: filter watermark 2,170,480 → 2,315,480 while the bring-up reported `SEED_BINDING_UNVERIFIED`, 0 drained |
| DashPay contact crypto, account builds, identity key heal | Yes | `unlockWalletFromKeystore` → `drainPendingContactCrypto` in every thread dump; every overnight bring-up `SEED_BINDING_UNVERIFIED`, 177 pending, 0 drained |
| Signing: sends, asset locks, shielded operations | Yes | by construction: `retrieveMnemonic` and the private-key exclusion path |

Consequences for the new mechanism:

- **Locked at upgrade time.** Only the bind is blocked. It runs after the wallet parse (the mnemonic comes out of the dashj wallet) and takes about a second. Pending state, retry at unlock. Nothing else is lost, and nothing else should run in the meantime.
- **Locked after the bind, during the replay.** Scanning continues. DashPay verification, account builds, and sends wait. Today the code puts a keystore-dependent step, "DashPay bring-up before SPV", in front of the scan with no effective budget, which is why the reference install's overnight starts spent 12 minutes to 2.8 hours frozen before SPV began. Phase 1b item 10 flips that order.
- **Screen locks or app is backgrounded mid-launch.** If the lock lands before `createWallet` completes, the bind throws `KeystoreDeviceLockedException` and lands in pending; a lock between `createWallet` and `storeMnemonic` is covered by the existing half-bound recovery. If it lands after the bind, the scan keeps going and only the DashPay tail waits. The larger risk in that window is the process, not the keystore: backgrounding hands the service to the idle detector and, without a foreground service, to the cached-app freezer. Those are Phase 1b items 7 and the foreground-service gate from section 10.4.
- **Devices whose keystore denies while unlocked.** PR #1555's walletB (HONOR PTP-N49) produced 7 such denials out of 16, and its OEM suppressed `ACTION_USER_PRESENT` for ten hours. These never heal on unlock. With no fallback, the device syncs nothing until the user turns on Tools › dashj sync. Phase 1a item 3 gives this its own classification and error surface; section 7 carries the policy question.
- **Process-level effects of a locked phone** are unchanged by the policy: the app is in the background, so Doze, the freezer, and the idle detector apply. A background job that starts the process while locked will open the SDK wallet, start SPV, and fail or wait on anything that touches the seed. The failure has to be classified as "locked, retry later", not counted toward an error state.

### 12.3 What the user sees

On the upgrade launch the home screen switches to the SDK immediately. The header holds the last-known dashj total under a syncing label until the SDK replay reaches the tip (section 10.1 and 11.3 show the hold engaging). For the reference install that replay is about 680,000 mainnet blocks, and with the current engine and idle detector it has not finished in two days. The one-time sync explainer already says this happens once; under the no-fallback policy that statement has to be true before the policy ships, which is why section 6 ties Phase 1a to 1b and 1c.

### 12.4 The policy was re-decided against #1555, 2026-09-19

This branch was built on a version of `fix/contested-username-restore-identity` that has since
been rebased away, so it never carried #1555 ("restore L1 sync after service teardown, and never
leave the wallet without an engine") or #1559. Merging them in forced the policy to be settled
rather than assumed, because #1555 rebuilt the very mechanism §12.1 removed.

**The two designs, both internally coherent:**

| | #1555 | This branch (§12) |
|---|---|---|
| dashj fallback | kept — "never hold dashj without an SDK L1 owner" | removed; `dashjEngineMayStart()` returns false always |
| Cutover commit | gated on a boundary crossing AND durable bind evidence, inside `commitLocked` so every path inherits it | unconditional, on the upgrade launch |
| `CutoverAutoCommitObserver` | retained; owns the gated decision | deleted — every install commits on its first launch, so there is nothing to observe |
| Worst case | two engines can overlap, but there is always AN engine | one engine, and a permanently failing bind leaves NONE |

**Why the gate cannot simply be adopted here.** #1555's `dashjEngineMayStart()` consults the
cutover state and returns true when the state allows dashj or the SDK L1 flag is off. Ours returns
false unconditionally. Declining to commit therefore does not keep dashj running on this branch —
it leaves the SDK not owning L1 *and* dashj held, which is strictly worse than either design alone.
The gate is only meaningful with the fallback it was written alongside.

**Decision (Eric, 2026-09-19): no-fallback stands.** The merge takes our side for
`CutoverCoordinator`, `SdkBindRetryService`, `DashPayConfig`, the cutover emulator script and the
four affected test files; `CutoverAutoCommitObserver` stays deleted.

**The cost, stated plainly.** §12.2 already names the devices this hurts — walletB
(HONOR PTP-N49), 7 keystore denials out of 16 while unlocked, `ACTION_USER_PRESENT` suppressed for
ten hours. Under this policy such a device syncs nothing until the user turns on
Tools › dashj sync. #1555 exists because that was observed in the field, and its emulator script
asserts `wallet is NOT engine-less` and names our behaviour as "the old bug". We are knowingly
reinstating it, betting that Phase 1a item 3's classification and error surface gets the user to
act instead. That bet is the open item, not the merge.

**Consequence for whoever merges from this base next.** The collision will recur on every merge
until one side of the codebase gives way. Do not resolve it file-by-file from the diff — read this
section first, because the resolution is a policy choice and the conflict markers do not say so.

## 13. Implementation status, 2026-09-16

Branch `fix/upgrade-memory-and-sync` in the `dash-wallet-upgrade` worktree, forked from `fix/contested-username-restore-identity`. One commit per item; the full `test_testNet3DebugUnitTest` suite passes at the end. Nothing is pushed and nothing has run on a device yet.

| Item | Commit | What landed, and where it differs from the plan text |
|---|---|---|
| build | d4d8dd6e7 | Cherry-pick of the main checkout's SDK bump to `0.1.0-v42int19-SNAPSHOT`. The branch pinned int5, which predates `startWalletSubsystems`, so it did not compile. |
| 1a.1 | aeb97698f | Upgrade seam commits CUT_OVER without bind evidence; `dashjEngineMayStart()` is false in every state; the service gate reduces to the Tools toggle; a failed coordinator read defaults to false. Emulator harness scenarios rewritten. |
| 1a.2 | 96811aee6 | Rollback, auto-commit observer, the rollback un-hold in the service, the debug ROLLBACK action removed. `REBUILD_WALLET` is advisory: logged with both balances and tx counts, never executed. The readiness table and parity probe remain as diagnostics. |
| 1a.3 | 1ba7f8189 | `SdkBindBlocker` classification (DEVICE_LOCKED, KEYSTORE_DENIED_UNLOCKED, KEYSTORE_PROBLEM after three, OTHER, SETUP_FAILED after five). Receiver armed on the first failure, blocker persisted for the support report, background notification, foreground sheet with a retry button for the blockers that need the user. The header label from the plan text was not done; the sheet carries the explanation. |
| 1a.4 | b7c588b66 | First bind skipped while `KeyguardManager.isDeviceLocked`, classified as DEVICE_LOCKED without scrypt or a keystore call. The bind was already the first thing after the wallet parse. |
| 1a.5 | 790ae1616 | Three dashj loggers capped at WARN from the app's logback setup; the two per-transaction metadata lines at DEBUG. No dashj change. |
| 1a.6 | bb02700a1 | Autosave debounce 5 s below half the size-guard soft limit, 30 s from half, 60 s at and above it. |
| 1b.7 | 20975314f | `shouldStopForIdle(history, replaying)`; the SDK state writer derives `replaying` from the percentage instead of forcing false; the wake lock is held for the replay on the SDK path. A blocked bind lets the service idle out. |
| 1b.8 | 20dc062ef | The upgrade commit marks the replay started (replaying true, percentage 0, heights preserved) before the SDK's first progress update. |
| 1b.9 | b42f8f84e | The one-minute alarm is armed from `onDestroy` for any non-deliberate stop during a replay, not from the idle path. |
| 1b.10 | 10ba754bd | Bring-up budget of 20 s, then SPV; the bring-up finishes in the background. Order kept, not flipped (see the item text). |
| 1b.11 | 8ef28d16f | `pendingDestroys` closes the onCreate/onDestroy race behind the file-lock failures; a lock-blocked blockstore open retries three times after waiting for the cleanup. Opening the store is still unconditional; skipping it when dashj is held touches too many `blockChain` readers for this pass. |
| 1b.12 | 4b8f50ef2 | Engine start skipped while the service is tearing down. |
| 1b.13 | d25c784fc | Diagnostic only (see the item text). |
| 10.4 FGS gate | 839f0d6ce | `init()` binds and starts the UI data services; the L1 engine starts from `resume()`, called after `startForeground`. |
| 10.4 ticker | 40513a6c6 | Contact cycle every 15 s in the foreground, every 20 ticks in the background. |
| 10.4 readiness log | moot | The observer is gone; the parity probe already logs one line per mismatch and the `REBUILD_WALLET` verdict now logs both sides. |
| 10.4 wipe policy | in 1a.2 | Automatic wipe removed. |

Still open after this pass: the header label during the pending state; skipping the dashj blockstore open entirely on a held install; the Android 15 dataSync six-hour foreground limit, which a multi-hour replay will hit and which item 9 can only answer with a restart; and everything in Phase 1c, which the SDK team owns. The reference install still needs Phase 1c items 14 and 15 before its replay can finish, and Phase 2 before its idle heap changes.

## 14. Two-emulator upgrade test, 2026-09-16

Build 12000011 `12.0.0-upgrade` from `fix/upgrade-memory-and-sync`, installed over a synced 11.9.1 (11090106) on two Android 16 emulators holding the same 11.9 MB testnet wallet: **5556** unlocked, no device credential; **5554** locked, six-digit PIN, screen off at install time. Captures in `~/Downloads/upgrade-test-09-16-2026/`.

### 14.1 Confirmed on hardware

| Item | Evidence |
|---|---|
| 1a.1 commit on the upgrade launch, no bind evidence | 5556 13:58:33.415 and 5554 14:24:26.189, both about 3 s after process start |
| explainer armed in the same launch | both devices, same second as the commit |
| 1b.8 replay marked before the SDK's first progress | both devices |
| 1a.1/1a.2 dashj never starts | zero peergroup starts on either device after the install |
| 1a.4 bind deferred while locked | 5554 14:24:26.172, 2 ms after platform init, no scrypt and no keystore call |
| 1a.3 classification, persistence, notification | 5554: `DEVICE_LOCKED`, `sdk_bind_blocker=DEVICE_LOCKED` on disk, notification on the lock screen |
| 1a.3 retry ladder | 5554 retries at +5 s and +15 s, matching `bindRetryDelayMs` |
| 1a.2 no fallback | 5554 `retry N failed … No dashj fallback`, state stayed CUT_OVER |
| §10.4 engine starts only from the foreground service | both devices refused it from Application init |
| 1b.10 bring-up budget | 5556 `status=READY … elapsedMs=1477`, fast path not timeout |
| 1b.7 wake lock spans the replay | 5556 acquired 14:07:00, released 14:11:00 at the tip |
| recovery from a blocked bind | 5554: app opened 14:29:27.550, bound 14:29:28.042, pending state cleared 14:29:32.268, engine started 14:29:33.984 |

5556 replayed 503,000 blocks in 5 min 6 s, peaking at 728 MB PSS with native at 487 of 595 MB and settling to about 500 MB PSS. JVM stayed between 75 and 97 MB of 576, which says nothing about the reference install's 482 MB baseline: that comes from a 62 MB wallet, this one is 11.9 MB.

### 14.2 Defects found, and fixed in 326fc31b9

- **An upgrade never started syncing.** `BootstrapReceiver` fired and called `startBlockchainService`, which silently no-ops below `IMPORTANCE_FOREGROUND`; a broadcast-started process is below it. The cutover committed and the wallet bound, then nothing scanned, because §10.4 means the engine only starts from the foreground service. The only fallback was a daily alarm up to 18 hours out. Fixed by starting a foreground service directly on the package-replaced path, which is exempt from the Android 12+ background FGS restrictions, with the boot path's delayed alarm as a fallback.
- **The one-time explainer never displayed.** Armed correctly, then lost: `onLockScreenDeactivated` cleared the pending marker before `showOnce`, which refuses while fragment state is saved. `showOnce` now reports whether it showed and callers keep the marker pending, with a retry on resume.
- **The unlock receiver cannot be relied on.** `ActivityManager: freezing <pid>` landed 30 s after the package-replaced broadcast on 5554, the platform logged "Sending oneway calls to frozen process" while two `USER_PRESENT` broadcasts went out, and the receiver never ran across a real device unlock. The notification is the real recovery path, so it is now ongoing and its copy directs the tap. **Superseded in part by section 15.2:** the freezer was real but was not the root cause. The receiver was registered `RECEIVER_NOT_EXPORTED`, which cannot match a broadcast sent by SystemUI, so it would not have fired even in a live process. Both are fixed; the conclusion that the notification is the guaranteed path survives, for the reasons in section 15.4.

### 14.3 Open, not fixed

The SDK settled at 16,905,513,269 duffs on 5556. The 09-15 test settled the same wallet at 16,782,052,936 after the parity probe judged the SDK inflated and rebuilt it, and that figure matched dashj exactly. The 1.2346 DASH gap is entirely in the BIP44 account; CoinJoin agrees to the duff. The discrepancy is now silent, because the probe suspends itself post-cutover against a frozen dashj balance and item 1a.2 retired the automatic rebuild. Turning on Tools › dashj sync un-holds dashj and resumes the probe in log-only mode, which is how to establish which side is right. Parked at the user's direction.

## 15. Second two-emulator round, 2026-09-16, and what it corrected

Same two devices, both reset to a synced 11.9.1 at chain 1555101, upgraded to build 12000011 carrying the three fixes in `326fc31b9`. 5556 unlocked with no device credential, 5554 with a PIN and the screen locked at install time.

### 15.1 The three fixes hold

- **Foreground service on the package-replaced path.** 5556: install 15:14:05, `Background started FGS: Allowed … uidState: RCVR` at 15:14:08.386, service `onCreate` finished 15:14:08.678, wallet bound 15:14:08.920, SPV started 15:14:18.524. The replay then ran to `SYNCED 100.0%` at 15:19:33 — 5 min 15 s, **with the app never opened** (zero foreground events, launcher on top throughout). On the previous build this device scanned nothing at all. 5554 started its service the same way while locked.
- **The one-time explainer appears.** Confirmed by observation on 5554 after the app PIN was entered. My own check reported it missing, which was wrong: it looked at the fragment list after the sheet had been dismissed.
- **The pending notification is ongoing.** `flags=ONGOING_EVENT|ONLY_ALERT_ONCE` on 5554, title `Unlock your phone to finish the wallet update`, cleared automatically on bind success.

Phase 1b behaved as designed on both devices. 5556 ran one service instance for 14 m 42 s with **no idle stop during the replay**, and stopped only after reaching the tip with three genuinely quiet minutes; the item 1b.13 diagnostic then logged `durable syncedHeight 1555113 covers the committed cursor 1555113`, no re-walk. The wake lock was acquired at 15:16:00 and released at 15:20:00. On 5554 both bind-blocked exceptions fired verbatim: `idling detected with the SDK bind blocked (DEVICE_LOCKED)` and `replay flagged but the SDK bind is blocked (DEVICE_LOCKED) — not rescheduling`.

### 15.2 A fourth defect: the unlock receiver was never registered for delivery

Fixed in `3b697a3af`. The receiver was created with `RECEIVER_NOT_EXPORTED`, reasoning that a protected system broadcast needs no app-facing surface. That inverts the consequence: `NOT_EXPORTED` matches only senders sharing our uid or the platform, and `ACTION_USER_PRESENT` is broadcast by **SystemUI at a normal app uid**. The filter was registered and visible:

    ReceiverList{… hashengineering.darkcoin.wallet_test/10169/u0}
      Filter #0: BroadcastFilter{59c4fef}  Action: "android.intent.action.USER_PRESENT"

and the broadcast after a real unlock reached four receivers, none of them ours:

    caller=com.android.systemui 1547:com.android.systemui/u0a124 uid=10124
    DELIVERED #0 system/1000/u0   #1 system/1000/u-1   #2 com.android.launcher3/10116/u0   SKIPPED #3 (manifest)

The launcher receives it because it registers exported. The same dump shows the wallet receiving `TIME_TICK`, sent by the system server at uid 1000 — the one sender `NOT_EXPORTED` admits, which is why the registration looked healthy while being inert. Exporting is safe because the action is a declared `<protected-broadcast>`. Not yet verified on device: the flag lives in the APK and needs another locked install.

### 15.3 A locked device does **not** stall the scan after the first bind

Measured directly at 16:12 on 5554: screen locked, app force-stopped, blockchain service started cold.

| Time | Event |
|---|---|
| 16:12:19.801 | Cold process start, device locked |
| 16:12:21.307 | `SDK bind deferred: the device is locked` |
| 16:12:22.413 | `Dash Platform SDK started … restored 1 wallet(s)` |
| 16:12:23.544 | `DashPay bring-up: status=SEED_BINDING_UNVERIFIED … drained=0` |
| 16:12:23.817 | `L1 shadow SPV started` |
| 16:12:32.847 | `phase=SYNCED 100.0%` |

SPV started 273 ms after the bring-up returned and reached the tip 9 s later, with the app-side bind deferred the whole time. The reason is that `L1ShadowSource.boundWalletIdOrNull` reads the SDK's loaded wallets (`manager().wallets`), not the app's bind state, and `restored 1 wallet(s)` needs no keystore. **So the lock blocks sync only before the first successful bind.** Afterwards a locked phone scans to the tip normally, and what it loses is the DashPay side.

This also re-explains Joel's overnight stalls. They were not the lock blocking his scan; they were the unbounded bring-up blocking `startSpv`, with the idle detector then killing the service before SPV ran. Every long bring-up in his logs carries a large pending-account count:

| Status | `dashPaySyncRan` | Durations observed |
|---|---|---|
| `SEED_BINDING_UNVERIFIED` | false | 20 s, 20 s, 8.7 min, 10.4 min, 21 min, 24 min, **2 h 49 min** |
| `SEED_BINDING_UNVERIFIED` | true | 7.4 to 10.7 s |
| `PARTIAL_ACCOUNTS_PENDING` | true | mostly ~20 s, one 12.8 min |

The 20 s budget was therefore **not exercised** on either emulator: this wallet has 19 friend chains with nothing pending and the bring-up returned in 2.2 s. The budget remains the right protection for a wallet of Joel's shape and is still unproven at its boundary against the real SDK.

### 15.4 What recovery actually exists, measured

`config.touchLastUsed()` is called from exactly one place, `MainActivity`, so a user who never opens the app never advances it. The restart alarm keys off it: every 15 minutes while last use is under an hour, every 12 hours under two days, every 24 hours beyond. Each locked cycle costs a wallet parse and a full serialization for about five minutes of service uptime and makes no progress.

Because the process is frozen roughly ten seconds after the service stops, **the unlock receiver can only ever fire inside that five-minute window** — five minutes in fifteen for the first hour, then five minutes in twelve hours. Even with the export fix it cannot be the primary mechanism. Of the five paths, only the app-foreground one has been demonstrated, twice, healing in 1.6 s each time. The autonomous heal via the scheduled restart has still never been observed, because the app was opened before the alarm was due both times.

### 15.5 The unbound keystore aliases are not an escape hatch

`MASTER_ALIAS_UNBOUND` (`org.dashfoundation.wallet.master.unbound`) and `KEYS_ALIAS_DEVICE_BOUND_UNBOUND` are an **automatic degradation for devices with no secure lock screen**, not a selectable policy. Android refuses `setUnlockedDeviceRequired(true)` without a PIN, and the SDK catches that and regenerates unbound. 5556, which has no credential, logged it on every install:

    W KeystoreManager: No secure lock screen (KeyguardManager.isDeviceSecure=false);
    generating 'org.dashfoundation.wallet.master' WITHOUT lock-screen binding

5554, with a PIN, never logged it once. That makes the two devices an unintended controlled comparison, and explains why every fast bind was on 5556 and every deferral on 5554. There is no supported opt-out on a device that has a lock screen. The question for the SDK team is therefore the harder one: whether an unbound master key should be permitted on a secured device, which is a security trade rather than a technical gap. Separately, `hasUnboundMasterKey()`, `effectiveKeySecurityPolicy()` and `sampleDeviceLockState()` are public and should be recorded in the support report.

### 15.6 Still open

1. **The mid-sync DashPay gap — mostly self-healing; only the diagnostic is worth building.** A lock during sync leaves the bring-up at `SEED_BINDING_UNVERIFIED` with contact accounts undrained. An earlier draft of this item proposed dispatching the unlock and foreground edges to that outstanding work. **That is withdrawn**: two independent paths already re-attempt it.

   - `startWalletSubsystems` runs on every engine start (`L1ShadowSyncService.startIfEnabled`, guarded by `if (!source.isSpvRunning())`), so any service restart re-runs the ordered bring-up.
   - `updateContactRequests` — the contact ticker — calls `provisionContactAccountsInBackground` on each pass, throttled to once a minute and forced when a contact was just established. While the service is alive, the first pass after the device unlocks derives the friend chains and queues the builds.

   What remains is a **latency bound, not a correctness gap**, and only in a narrow case: the wallet reaches the tip while locked, the service idles out, the process freezes, and the phone is then unlocked with nothing running. Recovery waits for the next alarm — 15 minutes if the app was opened within the hour, otherwise 12 or 24 (section 15.4). Opening the app collapses it to seconds.

   The piece still worth building is the **diagnostic**: persist the seed-blocked state the way the bind blocker is persisted, so a device in this condition reports one field instead of requiring a day of log reading, which is what Joel's case cost. No behavior change. Section 16 measures whether the latency has any fund-visibility consequence.
2. **The 20 s bring-up budget** is unproven at its boundary on a device.
3. **Autonomous recovery** via the scheduled restart is unobserved.
4. **The receiver export fix** needs a locked install to verify.
5. **The balance gap** of 1.2346 DASH against the September post-rebuild figure reproduced exactly on 5556 and remains parked at the user's direction.

## 16. Test to run: a contact payment that arrives while the device is locked

> Scope note added 2026-09-16: the locked device is one of **three** ways into the same defect. Section 17 has the general form; this test is the cheapest instance of it.

The one thing section 15 could not settle. We know the derivation stalls while locked, and we know the SDK has machinery to rewind once new accounts register. What nobody has watched is a real contact payment being missed and then recovered. Until that is observed, "the money is not lost" is an inference from code, not a result.

This test also isolates the failure, because a BIP44 payment sent in the same window is the control: it should be caught live while locked, since those keys already exist.

### 16.1 What it proves

| Payment | Expected while locked | Expected after unlock |
|---|---|---|
| To a plain BIP44 receive address | **Seen immediately**, already inside the gap-1000 window | unchanged |
| To a DIP-15 address for a contact whose account is not yet derived | **Missed**, address not in the filter set | found after derivation forces a rescan |

If the contact payment never appears after unlock, the rewind machinery does not work in this path and that is a fund-visibility defect, not a latency one.

### 16.2 Setup

Two testnet wallets, A the sender and B the device under test. B on a build with the Phase 1a/1b fixes, on a device **with a PIN**, since a device without one takes the unbound-keystore path (section 15.5) and cannot reproduce this at all. B starts bound and fully synced; note its balance and the current chain height.

The precondition that matters is a contact relationship whose receival account B has **not** derived. Create it with B unable to act:

1. On B: force-stop the app, then lock the screen. Nothing of B's is running.
2. On A: send B a contact request, and wait for it to land on Platform.
3. On A: send **two payments to the DIP-15 contact address** for that new relationship, and **one payment to B's plain BIP44 receive address**, captured before step 1.
4. Wait for all three to confirm.

### 16.3 Run

5. With B still locked, start the blockchain service the way the alarm would. On a rooted emulator: `adb shell su 0 am start-foreground-service -n <pkg>/de.schildbach.wallet.service.BlockchainServiceImpl`.
6. Let it reach the tip. Record from the log: the bring-up status and `drained`/`pending` counts, the phase line at `SYNCED`, and the published balance.

   Expected: `status=SEED_BINDING_UNVERIFIED … drained=0`, the scan reaching 100%, the BIP44 payment present in the balance, and the two contact payments absent.
7. Unlock B. Do not open the app yet. Wait through at least one scheduled service start and record whether anything changes. This doubles as the still-unobserved autonomous-recovery case from section 15.6 item 3.
8. Open the app. Record the drain (`drained=N`), any rewind (`armSpvRescan`, `backfill coverage invalidated`, a filter height going backwards), the rescan completing, and the final balance and history.

### 16.4 What to capture

Continuous logcat for the whole run, the persisted `sdk_bind_blocker` at each stage, and screenshots of the balance and history before and after. The decisive numbers are the `drained`/`pending` pair at step 6 versus step 8, the filter height before and after the rewind, and whether the final balance includes all three payments.

### 16.5 Why it is worth the setup cost

Commit `1266edc1c` exists because restored wallets lost contact-payment coins when DIP-15 accounts registered after the scan had passed their funding heights, measured at 0.0836 DASH on the topple wallet. That fix ordered the bring-up before SPV. A locked device defeats that ordering, since the bring-up cannot derive anything without the seed, so the same exposure returns by a different route. This test measures whether the SDK's account-generation rewind closes it, and if it does not, it is the strongest argument for the section 15.6 item 1 work, and possibly for asking whether contact receival addresses can be derived from public material alone.

## 17. Uncovered contact chains — PREMISE DISPROVEN 2026-09-16, see 17.6

> **Read 17.6 first.** A direct experiment on emulator-5554 showed the automatic rewind this
> section claims is missing does in fact happen. The sections below are kept as the reasoning
> that led to the test, with the conclusion corrected at the end. The fix proposed in 17.4 is
> **withdrawn**.

Section 16 started as a locked-device question. Tracing the recovery path showed the lock is only one entry point. The defect is general:

> **A DIP-15 contact chain registered after the filter scan has passed a payment's height is never re-scanned, so that payment stays invisible to the wallet.**

The coins are on chain and the keys are derivable. The wallet simply stops looking.

### 17.1 Why nothing recovers it

| Mechanism | Fires? | Reason |
|---|---|---|
| Ordering — bring-up before SPV (`1266edc1c`) | Only if the bring-up completes | needs the seed and a finished contact sync |
| `wallets_behind` rescan | No | filters on wallet-level `synced_height() < height`; at the tip that is false |
| `account_generation` guard | No | only stops an **in-flight** batch from certifying coverage; rolls nothing back |
| `create_account` | No | calls `bump_structural_revision()` only; never touches `synced_height` |
| DashPay backfill rewind | No | now `ALWAYS_RUN`: `isRewindAccountedFor() = true`, `noteAccountBuildsRegistered() = false`, "nothing is ever recorded, so nothing is ever owed" |
| `armSpvRescan` | No | two callers only: the user's Settings rescan, and the one-shot gap-widen heal in `maybeWidenAddressWindows` |

The protection added in `1266edc1c` is **ordering, not rewinding**. It holds exactly while the bring-up can register every contact before `startSpv`, and there are three ways it cannot.

### 17.2 The three entry points

| Flow | Exposed | Why |
|---|---|---|
| **Restore from seed** | Yes | contacts are rediscovered from Platform; incomplete discovery at SPV start leaves chains unregistered. The original case: 0.0836 DASH missed on the topple wallet |
| **Upgrade, device locked** | Yes | bring-up returns `SEED_BINDING_UNVERIFIED`, derives nothing, scan runs to the tip uncovered (section 15.3) |
| **Upgrade or start, bring-up incomplete** | Yes | the bring-up returns with work outstanding: Joel's logs show `PARTIAL_ACCOUNTS_PENDING drained=50 pending=94` |
| Settings → Rescan | No | accounts persist in the SDK and only the watermark rewinds, so the re-scan carries the full current contact set. This is the manual recovery for every row above |
| New contact in normal use | Narrow race | the chain registers within about a minute; only a payment landing between the contact request and registration is at risk |

### 17.3 Item 1b.10 widened the third entry point

Honest accounting: capping the bring-up at 20 s and starting SPV regardless removed the stall, and it also lets SPV start with accounts still pending, which **is** the uncovered-chain condition. `PARTIAL_ACCOUNTS_PENDING drained=50 pending=94 elapsedMs=20114` is 94 chains unregistered at the moment that scan began.

This does not argue for reverting it. Before the cap, Joel's bring-up ran 2 h 49 m and the idle detector killed the service before SPV ever started, so the result was no sync at all rather than partial coverage. Both are defects; the cap traded one for the other. The fix below closes both.

### 17.4 The fix

Arm a rescan whenever a provisioning pass registers accounts **after** the scan has started — from the contact's `coreHeightCreatedAt`, or from the wallet birth height as a blunt version. That is what the retired `DashPayBackfillGate` rewind did, and retiring it was safe only under the ordering assumption that all three rows above break.

One fix covers every entry point, which is why it is worth doing once rather than patching the locked case alone.

### 17.5 Verify before building

Two things are unproven and both are cheap to settle:

1. The SDK's own contact-registration path may already call `rescanSpvFilters` where only bytecode is visible. Ask the SDK team, or observe a registration and watch the filter height.
2. Neither the locked path nor the pending-accounts path has been reproduced. Section 16 covers the first. The second is the same test with the bring-up budget set artificially low, so SPV starts with `pending > 0`, and then checking whether a payment to one of those pending contacts is ever seen.

### 17.6 The experiment, and the correction

The blocker for everything above was a question the code could not answer: when a DIP-15 contact
receival account is registered against a wallet whose scan has *already* reached the tip, does
anything rewind? The SDK database on a rooted emulator answers it directly, with no second wallet
and no payment — delete one `dashpayReceivingFunds` row from `dash-sdk.db`, restart, and watch
`wallets.syncedHeight`.

| Phase | Receival accounts | `wallets.syncedHeight` | Result |
|---|---|---|---|
| A — deleted, restarted **unlocked** | 6 → **7** | 1555145 → **1451329** | re-registered **and rewound 103,816 blocks**, rescan ran to completion |
| B — deleted, cold start **locked** | 6, unchanged | 1555148, unchanged | `SEED_BINDING_UNVERIFIED drained=0 pending=14` |
| B — **3 min after unlocking**, app untouched | 6, unchanged | unchanged | nothing at all |
| B — **app opened** | 6 → **7** | → **1466329** | bind established, drain 2, rewound, rescan |

**Correction.** The rewind exists and works. Registering a contact account after the scan reached
the tip lowers `syncedHeight` and re-scans, exactly as `provisionContactAccountsIfEnabled`'s own
comment describes ("the sweep … unconditionally lowers the SPV synced_height"). The 17.1 table was
looking in the wrong place: `wallets_behind`, `create_account` and `account_generation` are indeed
not the mechanism, but the sweep is, and I did not verify it before writing the section.
**Contact payments are not permanently lost, and 17.4's rewind-arming fix is not needed.**

**What is actually wrong** is narrower and is a recovery-trigger problem, not a fund-visibility one:

1. A locked device derives nothing, so nothing registers and nothing rewinds. Expected.
2. On unlocking, nothing happened for three minutes. The service had already idled out under the
   bind-blocked exception (item 1b.7), so no poller was alive; the retry ladder logged **zero**
   retries in that window; and the unlock receiver is inert in this build (the export fix of
   `3b697a3af` is committed but not installed).
3. Opening the app recovered it completely in under a minute.

So the exposure is a **delay bounded by the next app open or service restart** — 15 minutes, 12
hours or 24 depending on `touchLastUsed` (section 15.4). During that window a contact payment is
on chain and invisible. That is worth fixing, but with a recovery trigger, not with new rewind
logic.

**What this makes of the other open items.** Section 16 is still worth running, because this test
proves the mechanism and not the money; it does not show a real payment becoming visible. The
diagnostic added in `e0df0eddb` remains useful for spotting the debt window in the field. The
receiver export fix matters more than it did, since it is one of the two triggers that could close
the gap without the user, though only when the process is still alive.

### 17.7 When a contact payment becomes visible, if the device was locked

The practical form of the question, answered from the 17.6 measurements. A contact payment that
arrives while the chain for that contact is not yet derived is invisible until **three** things
have happened, in order:

| Step | Requirement | Duration |
|---|---|---|
| 1 | The device is unlocked | whenever the user unlocks |
| 2 | Something derives the contact chain | the variable one — see below |
| 3 | The rewind's rescan reaches the payment's height | minutes on testnet; on mainnet proportional to how far back the rewind goes |

**Step 2 does not follow from step 1.** Unlocking alone changes nothing. On 5554, three minutes
after a real unlock with the app untouched, the account was still unregistered, `syncedHeight` was
unchanged and the retry ladder had logged nothing — the service had idled out under the
bind-blocked exception (item 1b.7) so no poller was alive to notice the unlock.

What actually drives step 2:

| Trigger | Latency | Caveat |
|---|---|---|
| User opens the app | under a minute, measured | the only path demonstrated end to end |
| Alarm-driven service start landing while unlocked | 15 min / 12 h / 24 h | keyed on `touchLastUsed`, which only `MainActivity` advances (section 15.4) |
| `ACTION_USER_PRESENT` receiver | immediate | needs the export fix (`3b697a3af`), and only works while the process is alive — roughly the five minutes a service runs |

Measured on the app-open path: bind established, 2 accounts drained, `syncedHeight` 1555148 →
1466329, rescan underway, all within one minute.

**So:** a user who opens their wallet sees the payment about a minute later plus the rescan. A user
who does not may wait up to 24 hours on a wallet not recently used. The coins are never lost — the
rewind is automatic once the chain is derived (17.6).

**Two limits on this answer.** 17.6 proved the mechanism by deleting an account and watching
`syncedHeight` drop; no real contact payment has been observed appearing, which is what section 16
remains for. And step 3 was cheap in that test because the rewind was ~104,000 testnet blocks; a
mainnet wallet with an older contact could rewind much further and take proportionally longer
before the payment is visible, which also re-raises Phase 1c item 14 (native memory during a long
replay) for wallets of the reference install's size.
---

## 18. Third round, 2026-09-16 — the unlock receiver fix verified on 5554

Build: commit `3b697a3af` (`RECEIVER_EXPORTED`), clean wipe and reinstall of v12 onto a locked
emulator-5554. The reinstall moved the app uid from `u0_a169` to `u0_a170`, which matters below.

### 18.1 The receiver fires — the fix works

This is what the round was for, and it passed. Registration logged the new form:

```
unlock-heal receiver registered (ACTION_USER_PRESENT, exported — SystemUI is the sender)
```

and on unlock, for the first time across three rounds:

```
device unlocked (ACTION_USER_PRESENT) — running an immediate SDK bind retry
SDK bind retry 1 (device unlock): re-running the wallet bind pass (5 consecutive failure(s) so far)
```

Rounds 1 and 2 never got this line. The cause was `RECEIVER_NOT_EXPORTED`: `ACTION_USER_PRESENT` is
a protected broadcast sent by SystemUI, a different uid, so a not-exported registration can never
receive it. The round-2 hypothesis that the cached-app freezer was suppressing it was wrong and is
withdrawn — round 2 already showed the process alive and unfrozen with no delivery.

### 18.2 One failure in the middle was mine, not the product's

The retry the receiver kicked off failed with
`SQLiteCantOpenDatabaseException … Permission denied`. Cause: an earlier diagnostic `su 0 sqlite3`
query of mine had created a **root-owned zero-byte `dash-sdk.db`** in the app's data directory. The
clean reinstall then changed the app uid, so the app could not open its own database. Repaired by
force-stopping and removing the root-owned stubs; the database owner is now correctly `u0_a170`.

Lesson for the test scripts: never run `sqlite3` as root against a live app database. Pull a copy
with `run-as` instead, or query through `su` with `--readonly` after confirming the file exists.

### 18.3 After the repair, the whole chain ran clean

```
Dash Platform SDK started: version=4.2.0-dev.8, restored 0 wallet(s)
app wallet bound to new SDK wallet 7dc06ad3… (birthHeight=1070784 via checkpoint mapping)
DashPay bring-up before SPV: status=READY discovery=0 dashPaySyncRan=true drained=13 pending=0 elapsedMs=4117
L1 shadow SPV started for SDK wallet 7dc06ad3…
```

and the scan reached tip: `phase=SYNCED 100.0% headers 1555166/1555166 filters 1555166/1555166`.
The pending-bind notification cleared.

### 18.4 A real defect found by the round — stale `sdk_bind_blocker`

After the bind succeeded, `sdk_bind_blocker` in the DataStore still read `OTHER`, and no
"SDK bind established — clearing the pending state" line appeared.

Not a test artifact. The clear path hung off `SdkWalletBinder.lastBindFailure` going null, gated on
the in-memory `_blocker` being non-null. Two problems compose:

- `lastBindFailure` is null in **two** different situations — the wallet is bound, and no pass has
  run yet. The feed cannot tell them apart, so it could not safely write NONE.
- `_blocker` starts null in every new process and is **never seeded** from the persisted value.

So a process that bound the wallet without first failing in that same process hit the `?: return`
and left the previous process's record standing. The force-stop during the repair produced exactly
that sequence. Any restart-then-succeed does, which is the common case: the blocker gets written,
the user reboots or the process is killed, the next launch binds fine, and the support report still
accuses a device lock or keystore that is no longer a problem.

No user-visible harm — the in-memory blocker was null, so the sheet and the notification were both
correctly absent. The damage is to the diagnostic, which is the one thing it exists for.

**Fixed** in `9ebcbfc05`: new `SdkWalletBinder.bindEstablished`, raised only by a pass that actually
leaves the wallet bound, with sole ownership of the success path (reset streaks, drop the in-memory
blocker, clear the notification, persist NONE). The failure feed no longer interprets null.
Regression test `bindSuccess_inAProcessThatNeverSawAFailure_stillClearsThePersistedRecord`.

### 18.5 The coverage-debt diagnostic fired for real

The diagnostic added for section 17 produced its first real reading on this wallet:

```
DashPay contact coverage DEBT on 7dc06ad3…: the filter scan is at 1555164 but the earliest
received contact request sits at core height 1226329 (328835 blocks below, 19 contact request(s)).
Payments to those chains were scanned past.
```

19 contact requests, 328,835 blocks of debt. This is the shape section 17 was about, now measured
instead of argued, and it is reported rather than repaired by design. Two consequences:

- It strengthens the case for section 16 (the two-wallet contact-payment test), which is the only
  thing that proves whether money is actually missed or merely scanned past and later recovered.
- A rewind of that size on a mainnet wallet re-raises Phase 1c item 14 (native memory during a long
  replay), since the provisioning sweep is what rewinds `syncedHeight`.

### 18.6 Still open after this round

- Section 16, the two-wallet contact-payment test. Unchanged and now better motivated by 18.5.
- The recovery-trigger gap: up to 24h before anything retries when the user never opens the app.
- Phase 1c with the SDK team, plus the 15.5 question about an unbound master key on a secured device.
- Parked: the 1.2346 DASH balance gap on 5556; `scripts/cutover-emulator-test.sh` never run.

---

## 19. The defect is already present on B — found in the SDK database, 2026-09-16

While assembling the section 16 pair, a read of `dash-sdk.db` on emulator-5554
(`test-coinjoin-wallet-2`, identity `85KDhz…`) settled several things at once.

### 19.1 Two incoming contacts have no receiving chain at all

B holds 19 contact requests. Nine are INCOMING. Only seven have a
`dashpayReceivingFunds` account row:

| Incoming contact | core height | receiving account on B |
|---|---:|---|
| test-eleven-two | 1226329 | derived |
| test-qr-code-2 | 1229605 | derived |
| test-23 | 1252305 | derived |
| test-progress-screen-2 | 1252305 | derived |
| splawik21hornyTester | 1510979 | derived |
| test-profile-file-2 | 1510998 | derived |
| **5AJ174w3RzKhDKhUncbAddRswLWMSny9YNAiqqiHGKzU** | **1514538** | **NOT DERIVED** |
| **asdash13jul** (`DaLWz…`) | **1514673** | **NOT DERIVED** |
| test-dash-username-2 | 1528705 | derived |

Absence is real, not an artifact of having no funds: six derived rows carry
`externalHighestUsed = -1`, so an unused chain still gets a row.

**Any DIP-15 payment those two contacts have already sent B is invisible to B right
now.** That is the section 16 defect, already in the field on this wallet, needing no
setup. Sending from `asdash13jul` reproduces it with no Platform write, no new contact
request and no credits.

The three OUTGOING-only rows (Bartek123, test-android-35-2, test-android-15-2) have no
receiving account because the friendship is not mutual yet. That is correct behaviour,
not debt.

### 19.2 The coverage-debt diagnostic overstates the problem

`logContactCoverageDebt` compares the scan height against the earliest **received**
contact request, derived or not. On this wallet that is `test-eleven-two` at 1226329,
giving the 328,835-block figure from section 18.5. But that chain **is** derived and its
addresses are in the filter set, so nothing about it is at risk.

The real exposure is the earliest **underived incoming** contact, 1514538 — roughly
40,600 blocks, not 328,835. The diagnostic overstates the debt by about 288,000 blocks
and would send someone chasing the wrong wallets. It should join `dashpay_contact_requests`
against `accounts` on `friendIdentityId` and report only incoming contacts with no
`dashpayReceivingFunds` row.

### 19.3 Why an already-derived contact cannot reproduce the defect

Every derived receiving account stores `accountExtendedPubKeyBytes` (104 bytes). Extending
an existing friend chain's address window therefore needs only that stored xpub, **not the
seed**, so a locked device does not block it. The lock blocks *creating* a contact account,
because that derivation starts from the seed at `m/9'/coin'/15'`.

This is the answer to the 17.4 question about new addresses on existing accounts:

- **New contact, device locked** — the account cannot be created at all, and the payment is
  missed. This is the real exposure.
- **Existing contact, device locked** — the xpub is stored, the window extends, and the
  payment is seen.

### 19.4 Correction: the gap limit for contact chains is 10, not 1000 — and dashj is irrelevant

Two errors in the first version of 19.3, both caught by the user.

**dashj does not apply.** After cutover the dashj engine is held, so
`FriendKeyChainLookahead`'s 100-key window (and its 131-keys-per-chain derivation) plays no
part on A or B. Only the Rust SDK's watch set matters.

**1000 was the wrong constant.** `DashSdkServiceImpl.MIGRATION_GAP_LIMIT = 1000` is applied
by `widenAddressWindows` to exactly three families — BIP44, BIP32 and COIN_JOIN. It is the
only `setGapLimit` call in the app and it never touches a contact account.

The DIP-15 figure is `DEFAULT_CONTACT_GAP_LIMIT = 10`
(`rs-platform-wallet/src/wallet/identity/crypto/dip14.rs:254`), quoting the DIP directly:
load 10 addresses past the last used one.

**And it appears to have no consumer.** In the local `platform` checkout that constant
occurs only as its definition, three re-exports and one test asserting it equals 10.
`derive_contact_payment_address` likewise has no caller outside its own module. Caveat: this
checkout is not verified to be the revision the app pins, and a grep finding nothing is not
proof of nothing. But if the contact gap limit really is unwired, the watched window for a
contact chain is only what has already been derived.

### 19.5 What that does to the proposed experiment

Sending repeated payments from an established contact (emulator-5558,
`test-dash-username-2`) now looks materially more promising than it did under the 1000
figure. B's receiving chain for that contact sits at `externalHighestUsed = 0`.

| If the window is | A miss should appear at |
|---|---|
| 1000 and sliding (the wrong first answer) | never, by hand |
| 10 past the last used address | payment 11 or so |
| only what is already derived (no lookahead wired) | payment 2 |

So the run is cheap either way and it discriminates between the three. Send a dozen
payments, half with B unlocked and half locked, and record which ones B sees. It is no
longer merely a control: under the latter two readings it can reproduce a miss on an
**already-derived** contact, which would be a wider exposure than section 16 describes,
because it would reach every contact rather than only new ones.

The seed argument in 19.3 still stands and is what makes the locked half meaningful: the
stored `accountExtendedPubKeyBytes` means extending a window needs no seed, so if a locked
device still misses payments an unlocked one catches, the cause is not key material.

---

## 20. ROOT CAUSE: DIP-15 contact chains are permanently capped at 20 addresses

> **FILED** as dashpay/rust-dashcore#1032.

Found 2026-09-16 while running the emulator-5558 payment experiment. This is a defect in
the pinned `rust-dashcore` revision `af88edf`, not in the app, and **the device lock is
irrelevant to it**.

### 20.1 The chain

`key-wallet/src/wallet/helper.rs:612`

```rust
AccountTypeToCheck::DashpayReceivingFunds |
AccountTypeToCheck::DashpayExternalAccount => {
    // Currently not retrieved via this helper
    None
}
```

`extended_public_key_for_account_type` returns `None` for both DashPay account types, so
`key_source_for_account_type` returns `KeySource::NoKeySource`. In
`transaction_checking/wallet_checker.rs:357`:

```rust
if matches!(key_source, KeySource::NoKeySource) {
    continue;   // skips maintain_gap_limit for the whole account
}
```

`mark_address_used` runs *before* that guard, so used flags advance normally. But
`maintain_gap_limit` is never reached, so the pool never grows past the size it was built
with. And `managed_account_type.rs:726/745` builds both DashPay pools with a **hardcoded
literal 20**:

```rust
let pool = Self::single_pool(account_type, AddressPoolType::Absent, 20, network, key_source)?;
```

`maintain_gap_limit` with `highest_used = None` targets `gap_limit - 1` = 19. That is
exactly the window observed.

### 20.2 Consequence

**Every DIP-15 contact chain is permanently limited to 20 addresses, indices 0 to 19. The
21st payment from any contact is invisible, forever, on every device, locked or not.**

Not a latency problem and not recoverable by rescanning, because the addresses are never
derived at all. It applies to `dashpayExternalAccount` too, so the wallet also stops
tracking its own outgoing contact payments past index 19.

The comment says "currently not retrieved via this helper", which reads as an unfinished
wiring task rather than a cryptographic limit. The material is present: every contact
account in B's `dash-sdk.db` stores `accountExtendedPubKeyBytes` (104 bytes), and public
derivation of a friend receiving chain needs only that xpub. The fix is to return it from
`extended_public_key_for_account_type`.

### 20.3 The measurement that produced it

emulator-5558 (`test-dash-username-2`) paying B (`test-coinjoin-wallet-2`), B's screen
**locked** throughout, service restarted by hand after each batch.

| Address index | Amount (duffs) | Confirmed | Caught by B |
|---:|---:|---|---|
| 0 | 2,008,427 + 1,799,434 | yes (older, h1528706/1528755) | yes |
| 1 | 10,000 | h1555179 | yes |
| 2 | 11,000 | h1555211 | yes |
| 3 | 12,000 | mempool | yes |
| 4 | 13,000 | mempool | yes |
| 5 | 14,000 | mempool | yes |

Unspent total rose by exactly the sum of the new payments. Five for five while locked, so
the locked-device hypothesis for *derived* contacts is disproven, as section 19.3 predicted.

Across all six uses the window never moved:

| Highest used | Window if it slid (`highest_used + 20`) | Window actually observed |
|---:|---|---|
| 2 | 0 to 22 | 0 to 19 |
| 5 | 0 to 25 | 0 to 19 |

All fourteen of B's contact accounts sit at top index 19 regardless of use.

### 20.4 Corrections to earlier sections

- **18.5 / 19.2**: the installed build already carries the improved diagnostic
  (`receival-account coverage … establishedContacts=9, receivalAccounts=7, dark=2
  [5AJ174w3…, DaLWziYB…]`). It independently confirms the two dark contacts. The older
  `coverage DEBT` line is the one that overstates.
- **My claim that B had no recorded contact receipt from 5558 was wrong.** Index 0 holds
  two older payments. I read `transaction_account_involvements`, which is simply unpopulated
  in this schema version; every one of the 7,359 outputs has a null `accountId`.
- **The gap limit is 20, hardcoded**, not `DEFAULT_CONTACT_GAP_LIMIT` (10, dead code), not
  `DEFAULT_EXTERNAL_GAP_LIMIT` (30), not `MIGRATION_GAP_LIMIT` (1000, BIP44/BIP32/CoinJoin
  only).

### 20.5 CONFIRMED end to end, 2026-09-16 19:43

The walk to index 20 was run. B received every payment through index 19 and **missed the
one that landed on index 20**, exactly as predicted.

The sender broadcast it and the network accepted it:

```
19:43:25 TransactionBroadcast: broadcastTransaction: IX_TYPE: TX b837bcb2f4f5…1ab0 seen by 0
19:43:27 TransactionBroadcast: broadcastTransaction: b837bcb2f4f5…1ab0 complete
```

Testnet insight confirms it on-chain — 29,000 duffs to
`yR3kpYnbcPipq4982WcEK2mCBrQXh7P39K`, block **1555216**, 2 confirmations. That amount
continues the sequence (10,000 … 28,000, then 29,000), so it is unambiguously the next
payment in the run.

B's side, read directly from `dash-sdk.db` with the service up and B synced through
**1555217**, i.e. past the block carrying the payment:

| Check | Result |
|---|---|
| `transactions` rows for `b837bcb2…` (either byte order) | 0 |
| `txos` rows for `b837bcb2…` | 0 |
| `core_addresses` rows for `yR3kpYnb…` | **0 — the address is unknown to B** |
| Highest known index on the contact chain | 19 |
| Used indices | 0 through 19, all 20 |
| Unspent total, before and after | 16,905,873,269 unchanged |

B did not merely fail to attribute the payment. It has **no record of the destination
address at all**, so the filter scan could never match it. The coins are on-chain, spendable
by B's seed, and invisible to B's wallet permanently.

The device was locked throughout, but that is incidental: the cause is the unmaintained
address pool, not key availability. B caught all nineteen earlier payments while equally
locked.

### 20.6 Controls that make this conclusive

- **The payment really was sent** — the sender's own broadcast log plus two confirmations
  on a public explorer, not merely an absence on B.
- **B really was scanning** — the service was kept alive for the whole run and reached
  `phase=SYNCED` at 1555217 after the payment confirmed at 1555216.
- **B was otherwise healthy** — nineteen consecutive payments on the same chain were caught,
  so this is not a broken wallet or a stalled engine.
- **The window never moved** — top index 19 across all twenty uses, on all fourteen contact
  accounts.

---

## 21. Post-cutover DashPay goes intermittently dark: "no available addresses to use"

Hit 2026-09-16 20:26 on B while trying to send a contact request for the section 16 retest.

### 21.1 The chain

```
invalid quorum: quorum not found 6:[0,0,0,56,104,219,…]
  -> Proof verification error: context provider error
  -> that DAPI address is banned (ban_reason recorded)
  -> once enough are banned: ExecutionError { inner: NoAvailableAddresses }
  -> "Dapi client error: no available addresses to use"
  -> PlatformRepo: error updating contacts
```

The user-visible symptom is a Dash Platform error on the Contacts screen. The failure is in
`PlatformSynchronizationService.updateContactRequests`, which still runs on the **legacy
dashj-platform** path (`org.dashj.platform.dashpay.ContactRequests` + `DapiClient`), not the Rust
SDK. The SDK's own `rs_dapi_client` was healthy at the same moment, calling masternodes normally.

### 21.2 The mitigation is present and did run — it is just incomplete

`BlockchainServiceImpl:2404` already handles this. With the dashj engine held, `checkService()`
never runs `initDashSync()` / `setMasternodeListManager()`, so the legacy DAPI client has no
quorum source. The code wires it to `SdkSourcedQuorums` instead, and the log confirms it ran
27 seconds before the failures:

```
20:26:10 cutover committed — Platform quorum lookups wired to the SDK-sourced quorum list
20:26:37 rs_dapi_client: NoAvailableAddresses
```

No `serving an empty quorum list` or `SDK quorum key lookup failed` warning appeared, so the
source was populated. The gap is the caveat `SdkSourcedQuorums` documents about itself:

> `getCurrentQuorumsInfo` returns the CURRENT platform validator-set quorums. A proof signed by
> a quorum that rotated out long ago can still miss.

A proof signed by a rotated-out quorum is unverifiable, the address serving it gets banned, and
with enough bans DashPay goes dark. Ban counts in the same window (64 at 0, 76 at 1, 5 at 2)
show addresses cycling in and out rather than failing permanently.

### 21.3 Consequence

**Post-cutover DashPay contact reads and writes are intermittently unavailable**, by an amount
that depends on how many referenced quorums have rotated out. Bans expire, so it recovers on its
own, but any single attempt can fail. This is a cutover-caused regression: pre-cutover the legacy
client had dashj's full masternode and quorum history.

Worth deciding between: moving the contact-request path off the legacy client onto the SDK
(the DASHJ-KILL-LIST direction), or widening the SDK quorum source beyond current validators.

### 21.4 Effect on the section 16 retest

The revised test needs **B** to send the contact request first, since a receiving account is only
created when requests are mutual (`contacts.rs:150`). B is the cut-over wallet, so B is exactly
the one that hits this. Either retry until the bans lapse, or use the shortcut: B already holds
three outgoing-only contacts with no receiving account — `Bartek123`, `test-android-15-2`,
`test-android-35-2` — which is precisely the state step 1 is meant to produce.

---

## 22. The three missing receiving accounts are a RESTORE bug — already fixed upstream

User hypothesis 2026-09-16, confirmed.

### 22.1 Direction, settled empirically

Receiving accounts correspond to B's **outgoing** contact requests, not incoming ones. On the
reference wallet: 11 outgoing requests, 8 `dashpayReceivingFunds` rows, every row matching an
outgoing request and none matching an incoming-only one. Sending a contact request publishes our
DIP-15 receiving xpub, which is what lets that contact pay us.

Confirmed live: B sent a request to `test-contacts-33` at height 1555232 and the receiving
account appeared immediately (7 -> 8), with no external account and no reciprocal request.

### 22.2 What is actually missing

| Group | State | Consequence |
|---|---|---|
| 7 mutual contacts | receiving + external | fine |
| `test-contacts-33` (sent live, post-restore) | receiving only | fine |
| `Bartek123`, `test-android-15-2`, `test-android-35-2` (sent, never accepted) | **neither** | **they can pay B; B cannot see it** |
| `5AJ174w3…`, `asdash13jul` (received only) | neither | B cannot pay them; no receive-side exposure |

The pattern is that restore rebuilt only the **mutual** contacts. One-way relationships were
dropped in both directions. That matches `contacts.rs:150` — "Call this when a contact is
established (mutual requests exist)" — and explains why the sweep never repairs them: it does not
consider them established, so it reports `success=1 errors=0 pendingBuilds=0` while they stay dark.

### 22.3 Already fixed upstream, not in our build

`dashpay/platform` PR #4740 (open, targets `v4.2-dev`) carries
`rescan_covers_restored_sent_only_account_and_reestablishment`, documented as "A one-way outgoing
request already publishes our receiving xpub", plus "register sent-only receival account". Its PR
body lists "Reconcile restored receiving accounts even for sent-only requests" under
**Cover restoration and retries**.

So this is a known restore defect with a fix in flight. Our SDK (4.2.0-dev.8) predates it, which
is why the reference wallet still shows it. **Action: pick up #4740 and re-check the three.**

Note this is a different defect from rust-dashcore#1032. That one is the 20-address cap on an
account that exists; this one is the account never being rebuilt at all.

### 22.4 My coverage diagnostic was wrong — fixed

`readDashPayReceivalCoverage` filtered on RECEIVED requests (`filterNot { it.isOutgoing }`), so it
flagged the two incoming-only contacts — which need an *external* account and carry no receive-side
exposure — while missing the three outgoing-only ones that are the real money risk. Corrected to
`filter { it.isOutgoing }`; the log field is now `channelsWePublished`.

---

## 23. What the Phase 1 work does and does not do for a CLEAN INSTALL + RESTORE

Asked 2026-09-16. The branch was built for the v11 -> v12 upgrade seam, so it is worth being
explicit about which of it reaches a wallet restored from seed onto a fresh install.

### 23.1 Applies unchanged, and two of them matter MORE on restore

A restore replays from birth height, which is the longest sync the wallet ever performs. The
survive-a-long-sync fixes are therefore most valuable here, not on the upgrade.

| Fix | Why it reaches a restore |
|---|---|
| `ACTION_USER_PRESENT` receiver export (`3b697a3af`) | A restore onto a locked device sat unbound until the user next opened the app; it now heals on unlock. Not upgrade-specific. |
| `SdkBindBlocker` classification, notification, and the durable clear (`9ebcbfc05`) | Any install can fail to bind; the support record stays honest on all of them. |
| `shouldStopForIdle` replay guard + `holdWakeLockWhileReplaying` | **Most valuable here.** Without them a long restore is torn down mid-replay and re-enters the idle latch. |
| SDK engine restart on every service start (`kickSdkEngines` from `resume()`) | A multi-hour restore sees many service restarts; previously the engines only ever came up once per process. |
| `BRING_UP_BUDGET_MS` (20s) | Stops contact provisioning blocking SPV start on a large wallet. |
| Log diet (quorum loggers -> WARN) + `WalletFileSizeGuard` autosave | Memory and I/O pressure during that same long replay. |
| Corrected receival-coverage diagnostic (`406aeaae8`) | Now names the sent-only channels a restore actually drops (see §22) instead of the wrong two. |

### 23.2 Inert on a fresh install

The cutover state machine and its unconditional commit, the upgrade explainer
(`CutoverSyncNoticeDialogFragment`), the `MY_PACKAGE_REPLACED` foreground-service start
(`startBlockchainServiceAfterUpgrade`), and the advisory `REBUILD_WALLET` handling. A fresh
install has no previous version, so none of this has anything to act on.

### 23.3 Still broken on restore — and none of it is ours

Both defects that actually lose money on a restored wallet are upstream:

- **Sent-only contact accounts are never rebuilt** (§22) — `dashpay/platform` PR #4740, being
  picked up in a separate session to build the AAR.
- **DIP-15 chains capped at 20 addresses** (§20) — `dashpay/rust-dashcore#1032`, filed.

### 23.4 Correction: §21 is NOT upgrade-only

I had assumed the post-cutover DashPay-dark failure only affected upgraded wallets. It does not.

`commitForFreshWalletSetup()` commits the cutover for a **restore or new wallet**, and
`dashjEngineMayStart()` now returns `false` unconditionally. So dashj is held on a fresh install
exactly as on an upgrade, the legacy dashj-platform DAPI client has no masternode list of its own,
and it runs against the same current-validators-only `SdkSourcedQuorums` source.

**A restored wallet is therefore just as exposed to the intermittent
`Dapi client error: no available addresses to use` as B was tonight.** Contact requests are as
fragile on a clean restore as on an upgraded install. That widens §21 from an upgrade-seam issue
to something every v12 wallet carries, which strengthens the case for moving the contact-request
path off the legacy client onto the SDK.

---

## 24. Test 1 RUN: clean install + restore from seed, 2026-09-17

Build 12000011 (predates `9ebcbfc05` and `406aeaae8`), emulator-5554 wiped and reinstalled at
08:07:39, restored from seed onto a device **with a PIN**. Reference wallet: 7,344 transactions,
birth height 1,051,776, ~504,000 blocks of filter replay.

### 24.1 The sync side passed on every measure

| Measure | Result |
|---|---|
| SDK start -> tip | **7m 06s** (08:08:53 -> 08:15:59, tip 1555530) |
| Peak native heap | **536 MB** |
| Peak JVM used | 40 MB |
| Service teardowns mid-replay | **0** |
| Idle stops | **0** |
| `onTrimMemory` / low-memory events | **0** |
| Transactions restored | 7,344 — identical to pre-wipe |
| Unspent balance | 16,902,773,043 duffs — identical to pre-wipe |
| Contact requests | 20 — identical to pre-wipe |

Bring-up ran before SPV and completed cleanly: `status=READY discovery=1 dashPaySyncRan=true
drained=14 pending=0`.

### 24.2 Two §23.1 claims moved from reasoning to observation

Section 23.1 asserted the replay guard and the wake lock matter *more* on a restore than on the
upgrade. Both are now measured on a real restore:

```
08:10:00 replay in progress on the SDK path — acquiring the wake lock
08:15:59 L1Shadow phase=SYNCED 100.0% headers 1555530/1555530
08:16:00 replay complete — releasing the wake lock
```

The wake lock bracketed the replay exactly, and `idling detected, stopping service` fired **zero**
times across the seven minutes. That is the failure that used to tear the service down mid-replay
and latch it, and it did not occur.

Peak native heap of 536 MB on a 7,344-transaction wallet is the first real number for Phase 1c
item 14 (native memory during a long replay) measured on a restore rather than an upgrade.

### 24.3 The §22 restore bug reproduced exactly

| | pre-wipe | after restore |
|---|---:|---:|
| Outgoing contact requests | 11 | 11 |
| `dashpayReceivingFunds` accounts | 8 | **7** |

Outgoing requests with no receiving account after the restore:

- `Bartek123`
- `test-android-15-2`
- `test-android-35-2`
- **`test-contacts-33`**

All four can pay B and B has no watched addresses for any of them.

`test-contacts-33` is the decisive one. It was created **yesterday** by this same build: B sent the
contact request at height 1555232 and its receiving account appeared within seconds (§22.1). The
restore dropped it. So this is current-build behaviour on a one-day-old contact, not legacy residue
from an older app — which is what the three historical entries could always have been dismissed as.

Only the 7 mutual contacts survived, exactly as §22.2 predicted from
`contacts.rs:150` ("Call this when a contact is established (mutual requests exist)").

This is a live reproduction to re-check against `dashpay/platform` PR #4740
(`rescan_covers_restored_sent_only_account_and_reestablishment`) once that AAR is built.

### 24.4 Still to check on this restored wallet

- Whether DashPay contact requests hit the `no available addresses` failure on a fresh restore, as
  §23.4 predicts. B is in the right state to test it now.
- Cold boot with no unlock (the §23 test 2), which no run has covered yet.

---

## 25. Cold-boot test, 2026-09-17: the wallet never resumes syncing after a reboot

emulator-5554 (restored wallet from §24, PIN set, Android API 36), rebooted at 08:26:06.

### 25.1 Before the first unlock: nothing runs, and that part is correct

| Check | Result |
|---|---|
| User 0 state | `RUNNING_LOCKED` |
| App process | not running |
| Blockchain service | not running |
| `am start-foreground-service` (the alarm path) | `Error: Not found; no service started.` |

The app declares **no** `directBootAware` component, so while credential-encrypted storage is
locked the service does not exist as far as the system is concerned and the start is refused
outright. Nothing is corrupted; the wallet is simply dormant.

This is a materially different state from every other locked test in this document. Those all had
a live process with CE storage available, which is why binds could fail in interesting ways and
why the `ACTION_USER_PRESENT` receiver had something to heal. Here no code runs at all — that
receiver is registered at runtime by a running process, and there is no process.

### 25.2 After the first unlock: still no sync — the workaround fails silently

Unlock at 08:34:42 delivered `BOOT_COMPLETED` and `BootstrapReceiver` ran:

```
08:34:42 BootstrapReceiver: scheduling delayed blockchain service start for Android 15+
08:34:40 DelayedServiceStartReceiver: starting delayed blockchain service   (pid 2537 = _test)
```

And then nothing. Six minutes later:

| Check | Result |
|---|---|
| App process | running (SDK started, "restored 1 wallet(s)", DashPay drain ran) |
| `dumpsys activity services` | **(nothing)** |
| `L1Shadow phase=` lines | **none at all** |

So the process is alive and the SDK is up, but there is no blockchain service and no L1 engine.
**B did no block sync whatsoever until the app was opened by hand.**

(The mainnet app on the same device did sync — it is a separate package at a separate height,
2,540,414. Do not mistake its log lines for B's.)

### 25.3 Root cause: two failures stacked

**1. The Android 15+ branch defers through an alarm.** `BootstrapReceiver:106` sends
`BOOT_COMPLETED` to `scheduleDelayedBlockchainServiceStart` — a 5-second `AlarmManager.set`,
commented "to avoid BOOT_COMPLETED restrictions". But `BOOT_COMPLETED` is itself one of the
documented exemptions to the background FGS-start restriction, and an alarm broadcast is not.
Deferring through the alarm **throws the exemption away**.

**2. The alarm lands on the guarded start.** `DelayedServiceStartReceiver` called
`WalletApplication.startBlockchainService(false)`, whose entire body is inside:

```java
if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) { ... }
```

A process woken by an alarm broadcast sits well below that, so the method returned having done
nothing — no log, no exception. This is the **same guard** that silently defeated the upgrade path
on 2026-09-16 and was bypassed there by `startBlockchainServiceAfterUpgrade()`. The boot path was
never given the same treatment.

### 25.4 What was changed, and what remains a design decision

`DelayedServiceStartReceiver` now calls the guard-bypassing start and logs a warning when it is
refused, so this failure is diagnosable in a field log instead of invisible, and pre-Android-15
devices actually resume syncing.

That does **not** fully fix Android 15+. `BlockchainServiceImpl` is declared
`foregroundServiceType="dataSync"`, and API 35+ prohibits launching a `dataSync` foreground
service from `BOOT_COMPLETED`. The platform will still refuse; the difference is that it now says
so. A real fix is a design choice, not a patch:

- run post-boot catch-up as WorkManager expedited work rather than a foreground service, or
- give the boot-time sync a foreground service type that API 35+ still permits from boot, or
- accept that a rebooted phone does not sync until opened, and make that explicit in the UI.

### 25.5 DECIDED 2026-09-17: background catch-up after a reboot is NOT required

The third option. Confirmed against the Android 15 behaviour-change documentation: `dataSync` is one
of six foreground service types blocked from a `BOOT_COMPLETED` receiver (with `camera`,
`mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone`), it throws
`ForegroundServiceStartNotAllowedException`, there is **no exemption**, and the restriction applies
to any app targeting API 35+ — which we do.

Consequences, accepted deliberately:

- A phone that reboots overnight does no catch-up until someone opens the app. No incoming-payment
  notifications until then. Funds are never at risk; only visibility is.
- This subsumes the recovery-trigger gap in §18.6. After a reboot it is not a 24-hour delay, it is
  indefinite — and that is now the intended behaviour, not a bug.

**Code changed to match the decision.** `BootstrapReceiver` no longer calls
`scheduleDelayedBlockchainServiceStart()` on API 35+. That 5-second `AlarmManager` hop was meant to
dodge the restriction and provably cannot: an alarm broadcast is not an exempt start reason, and
deferring through it discards the `BOOT_COMPLETED` exemption the receiver itself holds. It now logs
plainly that post-boot sync is not attempted and that syncing resumes when the app is opened. That
removes a doomed device wake-up and a log line that implied the opposite of what happened.

`DelayedServiceStartReceiver`'s guard-bypass fix stays: it is still the fallback when
`startBlockchainServiceAfterUpgrade()` is refused on the package-replaced path.

**Still open, product-side:** surfacing staleness in the UI (a "last synced" indicator), so a user
whose phone rebooted can tell the wallet is behind rather than assuming it is current. Not
implemented — it is a design change, not a patch.

**Worth one test before this is considered closed.** The cause is the general background
FGS-start restriction plus the process-importance guard, not anything specific to boot. The same
combination would block any background-initiated sync, which would mean the periodic alarm path is
broken too. If that is so, the question stops being "how do we resume after a reboot" and becomes
"does this wallet sync in the background at all", which is a materially bigger decision than the
one just made.

---

## 26. Contact requests on the restored wallet — §23.4 CONFIRMED

emulator-5554, restored wallet from §24, Contacts screen opened at ~09:09 on 2026-09-17.

### 26.1 The prediction held

§23.4 argued that the post-cutover DashPay failure is not upgrade-only, because
`commitForFreshWalletSetup` commits the cutover for a restore too. The restored wallet's state
confirms the premise — `cutover_state=CUT_OVER`, dashj held, quorum lookups wired to the
SDK-sourced list at 08:43:20 — and opening Contacts produced the same failure:

```
09:09:26 rs_dapi_client: ExecutionError { inner: NoAvailableAddresses }
09:09:26 Documents: Dapi client error: no available addresses to use
09:09:38 (same, three more times)
```

**A clean install plus restore is exactly as exposed as an upgraded wallet.** This is a property of
every v12 wallet, not an artifact of the upgrade seam, which strengthens the §21 case for moving
the contact-request path off the legacy dashj-platform client onto the SDK.

### 26.2 It is intermittent, and it did recover

About a minute later the same call succeeded with no error:

```
09:10:19 updateContactRequests(false) starting now
09:10:19 DapiClient: getDocuments(contractId=Bwr4WHCP…, type=contactRequest, …)
09:10:38 updateContactRequests(true) starting now
```

That matches the ban-and-expire behaviour in §21.2 — addresses are banned on a failed proof
verification and come back when the ban lapses. So the user-visible symptom is an intermittent
Platform error on the Contacts screen rather than a permanent outage, which makes it easy to
dismiss as flaky network and hard to attribute.

### 26.3 Bonus 1: the provisioning rewind re-confirmed on a restore

Opening Contacts rewound the filter scan. From a wallet sitting at tip 1555536:

```
phase=FILTERS 95.4% filters 1341329/1555557
phase=FILTERS 98.2% filters 1471329/1555557
phase=SYNCED  100%  filters 1555559/1555559
```

Roughly 214,000 blocks re-scanned. This is the same DashPay-provisioning rewind measured in §17.6,
now seen on a freshly restored wallet and triggered simply by visiting the Contacts screen.

### 26.4 Bonus 2: the published balance is badly wrong during the rewind

While the rewind ran with `l1Synced=false`, `CutoverUiDataService` published these in sequence:

| Time | Published balance (duffs) | In DASH |
|---|---:|---:|
| 09:10:11 | 28,214,058,609 | 282.1 |
| 09:10:18 | 24,233,701,645 | 242.3 |
| 09:10:26 | 21,351,026,841 | 213.5 |
| 09:10:44 | 16,902,773,043 | **169.0** |

The final figure is exactly the pre-wipe balance from §24, so the wallet ends correct. But for
roughly 30 seconds it displayed up to **113 DASH more than the user actually has**, decreasing in
steps as the rescan re-derived the UTXO set.

Every one of the wrong values was published with `l1Synced=false`. A user who opens Contacts on a
restored wallet sees their balance swing by that much before it settles. Worth deciding whether
the publisher should suppress or mark balances while `l1Synced` is false rather than emitting
partial rescan state as fact.

---

## 27. Test 2 RUN: the restore bug loses real money — CONFIRMED 2026-09-17

§24.3 showed a restored wallet comes back missing receiving accounts. This run turns that
database observation into demonstrated fund loss.

### 27.1 Setup

After the §24 restore, B (`test-coinjoin-wallet-2`) holds an outgoing contact request to
`test-contacts-33` (identity `FozRDBStPZAiuJixjmEZSosfv85c7xaR1DodPBszks1G`, the
`org.dash.dashpay.testnet` app on emulator-5558) and **no receiving account for it**. The
provisioning sweep had run repeatedly, including through a Contacts visit and a 214,000-block
rewind (§26.3), and never repaired it.

That outgoing request is precisely what entitles `test-contacts-33` to pay B.

### 27.2 The payment

```
txid   491fc9f154356963a71a536ffb7cb57ec7c99e9fcf6b54c02fd599c19e0d4eba
value  0.00010000 DASH -> yNqz8JTP71ddSCrwftBMmfrPFdxgAot9Bv
block  1555561 (1 confirmation at time of check)
```

### 27.3 B, synced through the very block that contains it

| Check against B's `dash-sdk.db` | Result |
|---|---|
| `core_addresses` rows for `yNqz8JTP…` | **0 — address unknown to B** |
| `transactions` rows for the txid | 0 |
| `txos` rows for the txid | 0 |
| Total unspent, before vs. after | 16,902,773,043 — unchanged |
| `dashpayReceivingFunds` accounts | still 7 |

B reached `phase=SYNCED headers 1555561/1555561`, i.e. it scanned the block, and matched nothing.
The address was never derived, so the BIP158 filter could not match it.

### 27.4 Why this matters more than §20

`rust-dashcore#1032` (the 20-address cap) needs a contact to have sent 20 payments first. This one
needs **nothing**: a restore, and the very first payment from a sent-only contact is lost. On the
reference wallet four contacts are in that state right now — `Bartek123`, `test-android-15-2`,
`test-android-35-2`, `test-contacts-33`.

This is the concrete reproduction to re-check against `dashpay/platform` PR #4740
(`rescan_covers_restored_sent_only_account_and_reestablishment`) when that AAR is built. Re-run
this exact payment afterwards; the test passes when B sees it.

### 27.5 Incidental: a misleading broadcast warning on a cut-over sender

The sender logged:

```
W BlockchainServiceImpl: peergroup not available, not broadcasting transaction 491fc9f1…
```

…and the transaction reached the chain anyway, via the SDK path. With dashj held the legacy
peergroup is gone, so this warning fires on every send from a cut-over wallet and says the exact
opposite of what happened. It cost time in this run and will mislead anyone reading a field log.
Either route the message through the path that actually broadcasts, or drop it post-cutover.

---

## 28. The periodic alarm path — structural finding, 2026-09-17

Follow-up to the §25.5 question of whether the boot failure is boot-specific. It is not, but the
answer is conditional rather than a flat "broken". Recorded from code and documentation; the
empirical confirmation was deliberately not run (see §28.4).

### 28.1 Both alarms are inexact, so neither is exempt on its own

| Scheduler | Call |
|---|---|
| `WalletApplication:1790` | `setInexactRepeating(RTC_WAKEUP, now + alarmInterval, INTERVAL_DAY, …)` |
| `BlockchainServiceImpl:1183` | `setInexactRepeating(RTC_WAKEUP, restartTime, INTERVAL_FIFTEEN_MINUTES, …)` |

Both fire a `PendingIntent.getForegroundService`, which is the right mechanism and correctly
sidesteps the process-importance guard that defeated the boot path (§25.3). But Android's
background FGS-start exemption list includes only **exact** alarms — "your app invokes an exact
alarm to complete an action that the user requests". `setInexactRepeating` does not qualify.

### 28.2 Every test in this document ran on a permissive device

```
$ adb shell dumpsys deviceidle whitelist
user,hashengineering.darkcoin.wallet_test,10171
```

emulator-5554 has the app **user-whitelisted from battery optimisation**, which is itself an
exemption on that same list. So background FGS starts are permitted here regardless of alarm
exactness, and none of our runs measured the default state of a user's phone.

The app exposes this toggle in Settings (`SettingsFragment.kt:220`,
`SettingsViewModel.isIgnoringBatteryOptimizations`). So the install base splits in two:

- **exemption granted** — the inexact alarms can start the service; background sync works;
- **exemption not granted (the default)** — no exemption applies, and with the boot path
  separately blocked (§25.5) there is **no working background sync path at all**. The wallet
  syncs only while the user has it open.

### 28.3 The two schedulers overwrite each other

Both construct the PendingIntent with the same component and the same request code `0`, so they
are the *same* PendingIntent and each `setInexactRepeating` replaces the other. Whichever ran last
wins. Observed on B:

```
tag=*walarm*:…/BlockchainServiceImpl
type=RTC_WAKEUP origWhen=2026-09-17 09:13:00 window=+18h0m0s0ms repeatInterval=86400000
```

A **24-hour** repeat with an **18-hour** delivery window — the `WalletApplication` daily alarm.
The fifteen-minute restart alarm from `BlockchainServiceImpl` is not armed; it was overwritten.
So the "15-minute periodic sync" is not what the device actually holds, and the §18.6 "up to 24h"
figure is the optimistic reading of an alarm whose window is 18 hours wide.

### 28.4 Not confirmed empirically, and why

Confirming this on hardware needs the whitelist removed **and** the alarm made to fire. There is no
force-fire command (`cmd alarm` offers only `set-time`), and the armed alarm's window runs 18 hours,
so the only lever is jumping the emulator clock — which perturbs SPV sync and the wallet's own
time handling enough to muddy any result. Deliberately not done.

**What this changes:** §25.5 accepted "no background catch-up after a reboot" as a scoped decision.
If 28.2 holds for users without the battery exemption, the real scope is "no background sync at
all, ever, unless the user grants an exemption they are never prompted for". That is a materially
larger product question than the one decided, and it should be settled before the v12 release
rather than after.

**Cheapest way to settle it** without clock games: add a one-line log at the point the alarm's
service start is attempted, ship it to a tester whose device is *not* whitelisted, and read whether
`ForegroundServiceStartNotAllowedException` appears. Alternatively switch the alarms to
`setExactAndAllowWhileIdle` (needs `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`, which carries its own
Play policy weight) and the question disappears.

---

## 29. Locked new-contact test — §16 ANSWERED, 2026-09-17

Build 12000012 on emulator-5554 (B, `test-coinjoin-wallet-2`, PIN set). The trigger was the
**acceptance** of a contact request B had already sent, not a new send — acceptance lands on the
counterparty's schedule, so B can be locked when it arrives. That is what every earlier attempt at
this test failed to isolate, because sending always required B unlocked.

### 29.1 The lock DOES block DIP-15 account creation

B locked, service running, `test-contacts-33` accepted on emulator-5558. B picked the acceptance up
over its background platform sync while still locked, and then:

```
10:00:59  DashPay bring-up before SPV: status=SEED_BINDING_UNVERIFIED   (every unlocked bring-up: READY)
10:05:45  KeyChainGroup: Activating a new HD chain: FriendKeyChain{P2PKH, accountPath=[9H, 1H, 15H, 224459747H, …
10:05:47  account-build drain: queued=2 built=0 stillQueued=2 (blocked or still draining)
10:05:47  contact provisioning: sweep success=1 errors=0, pendingBuilds=2
```

Four signals, all pointing the same way:

- **`status=SEED_BINDING_UNVERIFIED`** while locked, against `READY` on every unlocked bring-up in
  this document.
- **`queued=2 built=0 stillQueued=2`**, explicitly reported as blocked. The two are the receiving
  and external accounts for the newly-mutual relationship.
- **`pendingBuilds=2`** — non-zero for the first time in the whole session. Every other sweep,
  including all the ones that failed to repair the restore-dropped contacts, reported
  `pendingBuilds=0`. So this is a genuinely different failure from §22/§27, not the same one.
- **dashj got further than the SDK.** The friend key chain activated; the SDK-side account build
  stalled. That locates the blockage at the keystore-backed derivation, not at discovery.

### 29.2 Unlocking heals it

```
10:05:47  (locked)   queued=2 built=0 stillQueued=2   pendingBuilds=2
10:08:31  (unlocked) queued=2 built=2 stillQueued=0
```

Afterwards `test-contacts-33` holds both a `dashpayReceivingFunds` and a `dashpayExternalAccount`;
totals moved 7/7 -> 8/8 and the dark count fell 4 -> 3.

**So this path is a deferral, not fund loss** — unlike §20 (the 20-address cap) and §27 (the
sent-only restore bug), both of which lose money permanently. The exposure here is a window: any
contact payment arriving between the acceptance and the next unlock has no watched address and is
missed, and it is recovered only if the later rescan covers it.

### 29.3 Caveat on what "unlock" proved

The app process was already running and came to the foreground as the device unlocked, so the
unlock and the app being foregrounded coincided. The measurement cannot separate "the device
unlocking released the keystore" from "the app reaching the foreground drove the drain". The
practical answer is the same either way — the user unlocking their phone fixes it — but if the
distinction matters, repeat with the app swiped out of recents before unlocking.

### 29.4 Bonus: a workaround for the §27 money loss

The dark count fell from 4 to 3 because `test-contacts-33` got provisioned once the relationship
became **mutual**. Its receiving account had been dropped by the §24 restore and no sweep had
repaired it across many runs (§27).

That confirms the rule exactly as `contacts.rs:150` states it, and it gives a field workaround for
the §27 loss: **a restore-dropped sent-only channel is rebuilt as soon as the counterparty
accepts.** Users stuck in that state can be told to get the other party to accept, rather than
waiting for PR #4740. The three still-dark contacts are the ones that never accepted.

---

## 30. Register of findings, 16-17 September 2026

A single place to look. Sections 18-29 are the detail.

### 30.1 Defects that lose money

| # | Defect | Evidence | State |
|---|---|---|---|
| §20 | DIP-15 contact chains capped at 20 addresses; the 21st payment from any contact is invisible and unrecoverable | txid `b837bcb2…`, 29,000 duffs, block 1555216, receiver synced through 1555217 with no record of the address | **FILED** `dashpay/rust-dashcore#1032` |
| §27 | A restore drops receiving accounts for sent-only contacts, so their next payment is invisible | txid `491fc9f1…`, 10,000 duffs, block 1555561, receiver synced through that block with no record | Fix in flight: `dashpay/platform` PR #4740. **Workaround** in §29.4 |

Both are SDK-side. Neither is fixed by anything on this branch.

### 30.2 Defects that degrade without losing money

| # | Defect | State |
|---|---|---|
| §29 | A locked device defers DIP-15 account creation (`SEED_BINDING_UNVERIFIED`, `built=0 stillQueued=2`). Heals on unlock | Understood; exposure is a window, not a loss |
| §21, §26 | Post-cutover DashPay goes intermittently dark (`no available addresses`) because the SDK quorum source carries only current validators. Affects **restored wallets too**, not just upgrades | Open. Argues for moving contacts onto the SDK |
| §25 | No sync after a reboot until the app is opened | **Decided acceptable.** Code made honest |
| §28 | The periodic alarm path may depend on a battery-optimisation exemption users are never prompted for | Awaiting the `ALARM-DIAG` result from a non-whitelisted phone |
| kill-list 1a | The balance publisher emits partial rescan state as fact — over-reported by up to 113 DASH for ~30s | Open; blocks deleting dashj |
| kill-list 1b | The dead dashj broadcast handler warns it is not broadcasting while the SDK broadcasts anyway | Open; cheap |

### 30.3 Fixes made on this branch, and whether they are verified on hardware

| Commit | Fix | Verified |
|---|---|---|
| `326fc31b9` | Start syncing after a package replacement; stop losing the explainer | yes, emulator |
| `3b697a3af` | Export the unlock-heal receiver (`ACTION_USER_PRESENT` never matched before) | yes — receiver fired for the first time |
| `9ebcbfc05` | Clear the durable bind blocker when a fresh process binds | unit tests |
| `e0df0eddb` | Report contact chains registered below the scan height | yes |
| `406aeaae8` | Coverage diagnostic must count SENT requests | yes — named the right 4, then 3 after §29 |
| `17cf9150d` | Delayed service start bypasses the process-importance guard | compile + reasoning |
| `fd2a31781` | Stop scheduling a post-boot start that cannot succeed | compile + reasoning |
| `72c9cbfed` | `ALARM-DIAG` logging | pending the phone test |

§24 additionally measured the restore path end to end: 7m06s to tip, 536 MB peak native heap, zero
teardowns, zero idle stops, balances identical to pre-wipe.

### 30.4 Tests still to run

1. **`ALARM-DIAG` on a phone that is not battery-optimisation whitelisted** (§28). Decides whether
   the wallet has any background sync path at all by default. Build 12000012 carries the logging.
2. **Separate "device unlocked" from "app foregrounded"** (§29.3). Repeat §29 with the app swiped
   out of recents before unlocking. The current run cannot tell the two apart.
3. **Re-run §27's payment after PR #4740's AAR is built.** The test passes when B sees it.
4. Parked: the 1.2346 DASH balance gap on 5556; `scripts/cutover-emulator-test.sh` scenarios were
   rewritten but never run.

---

## 31. Does build 12000012 help the reference install (Joel's device)?

Asked 2026-09-17. Answered against his actual logs
(`~/Downloads/joel-oom-aftermath-09-16-2026`), not from memory.

### 31.1 What actually failed on his device

The OOM is a **JVM heap** exhaustion at the 512 MB growth limit, not native:

```
java.lang.OutOfMemoryError: Failed to allocate a 80 byte allocation with 1557424 free bytes
  and 1520KB until OOM, target footprint 536870912, growth limit 536870912; giving up
    at de.schildbach.wallet.service.platform.sdk.AssetLockKindResolverKt.displayHexOf(AssetLockKindResolver.kt:120)
    at ….CutoverUiDataServiceKt.l1TxUiRecord(CutoverUiDataService.kt:152)
    at ….SdkTxStoreWalker.recordRowFrom(SdkTxStoreWalker.kt:428)
    at ….SdkTxStoreWalker.walkAll(SdkTxStoreWalker.kt:1075)
```

The walker is the allocation SITE, not the cause — it was already paged (`walkAll` →
`queryTxoPage`/`onPage`, added 2026-08-03 in `0f01e38a0`, so his build had it). The cause is the
standing memory profile underneath it:

```
23:54:00  MEM pss=1424MB nativeHeap=974/1009MB jvm=356/512MB
23:58:00  MEM pss=1592MB nativeHeap=986/1021MB jvm=382/512MB
          ReplayMemTelemetry phase=FILTERS nativeHeapAllocated=1023554912 jvmUsed=374001344 jvmMax=536870912
```

JVM sat at **314-382 MB of 512 MB** for the whole replay — roughly 130 MB of headroom — while
native heap ran near **1 GB**. For comparison, the §24 restore on our emulator peaked at 536 MB
native and **40 MB** JVM. His wallet is in a different weight class and nothing in this document
was measured against it.

### 31.2 The service-stopping failures — these WE DO fix

**The idle stop that killed his replay at 94.8 %.** Five minutes before the OOM:

```
23:56:06  L1Shadow phase=FILTERS 94.8%
23:58:00  idling detected, stopping service
23:58:00  .onDestroy()
23:58:10  L1ShadowLifecycle STOPPED after 49m33s up
```

`shouldStopForIdle(history, replaying) = !replaying && isSyncIdle(history)` skips the stop outright
while a replay is running. **This teardown does not happen on 12000012.** It is the single most
consequential difference for him.

**No wake lock.** Zero `acquiring the wake lock` lines in any of his logs — his build has none.
His replay ran 15:31 → 23:56, over eight hours; with the screen off the CPU suspends and that
stretches further. 12000012 brackets the replay with a wake lock.

**Nothing re-armed after an interrupted replay.** Zero `Scheduled service restart` lines.
`rescheduleIfReplayInterrupted` now arms a restart instead of leaving the engine dead.

**Severe create/destroy churn**, worse than the idle stops alone account for:

| Log | onCreate | onDestroy | idle stops |
|---|---:|---:|---:|
| `wallet.log-25` | 32 | 20 | 8 |
| `wallet.1.2026-09-15` | 23 | 11 | 7 |

with engine lifetimes of `STOPPED after 0s up` and `after 11s up` — the startup cost paid over and
over for nothing. The engine-restart-on-every-service-start change and the cleanup serialisation
(`pendingDestroys` / `isCleaningUpNow`) help, but the churn's own driver on his device has **not**
been established, so this is not claimed as fixed.

### 31.3 Honest verdict

12000012 makes his replay **far more likely to survive to completion**: the mid-replay idle stop is
gone, the CPU stays awake, and an interrupted replay re-arms itself.

It does **not** lower the ~1 GB native heap or the ~370 MB JVM baseline. If the replay still
allocates into ~130 MB of headroom at 94.8 %, it can still die in the same place. The memory
ceiling is Phase 1c work (native memory bound, SDK-side) and remains untouched.

The log diet shipped in this branch buys nothing for him specifically: his logs contain **zero**
`InstantSendManager` / `SPVQuorumManager` / `SigningManager` lines, so those loggers were not what
was filling his heap.

**Recommended next step for his case:** get a heap dump, or at minimum the same `MEM`/
`ReplayMemTelemetry` trace from 12000012, to find what holds ~370 MB of JVM during FILTERS. Until
that is known, any further memory work is guesswork.

---

## 32. The periodic alarm is not refused — it is simply never delivered

First `ALARM-DIAG` output, emulator-5554, build 12000012, 2026-09-17. This sharpens §28 and
partly corrects it.

### 32.1 What was armed

```
10:00:39 WalletApplication: ALARM-DIAG armed reason=periodic-15min firstFireInMinutes=15
         repeat=1440min exact=false batteryOptimisationExempt=true
```

Confirms §28.3 directly: the fifteen minutes is only the FIRST fire. Everything after is a
**24-hour** repeat, because `WalletApplication:1790` passes `AlarmManager.INTERVAL_DAY` as the
repeat interval regardless of which first-fire backoff was chosen.

### 32.2 What happened next: nothing, for over two hours

No `ALARM-DIAG service started by alarm` line ever followed, across a capture running to 12:20.
`dumpsys alarm` explains why — the alarm is **still pending, overdue, undelivered**:

```
origWhen=2026-09-17 10:15:39.224  window=+18h0m0s0ms  repeatInterval=86400000  count=0
whenElapsed=-2h5m15s509ms   maxWhenElapsed=+15h54m44s491ms
operation=PendingIntent{… startForegroundService}
```

- due at 10:15:39, **2h05m overdue** at the time of reading;
- `count=0` — it has never fired;
- **`window=+18h`** — the system may defer delivery for up to eighteen hours, and is doing so;
- ~15h55m of slack still remains before it must be delivered.

Doze was `ACTIVE` (not idle) on both deep and light, and the app is
`batteryOptimisationExempt=true`, so neither Doze nor the background-start restriction explains it.

### 32.3 Correction to §28

§28 framed the question as whether a background FGS start would be *refused*, and made it
conditional on the battery-optimisation exemption. That framing was too narrow. **The start is
never attempted**, because the alarm is not delivered.

The cause is `setInexactRepeating(RTC_WAKEUP, now + alarmInterval, AlarmManager.INTERVAL_DAY, …)`:
for an inexact repeating alarm the delivery window scales with the **repeat interval**, not with
the first-fire delay. A 24-hour interval buys an 18-hour window. So the fifteen-minute backoff the
code computes for a recently-used wallet is thrown away — the alarm inherits the daily alarm's
slack either way.

**The real background-sync cadence is therefore not 15 minutes, nor 24 hours, but "some time
within an 18-hour window, at the system's convenience".** That is consistent with never having
observed an alarm-driven service restart anywhere in this document, including the reference
install's logs.

### 32.4 What this makes of the §25.5 decision

§25.5 accepted "no background catch-up after a reboot" as a scoped decision. §28 warned the real
scope might be larger. It is: combined with §25 (boot start blocked on Android 15+), a v12 wallet
has **no dependable background sync at all** — not because a start is refused, but because nothing
reliably wakes it.

Whether the FGS start would *also* be refused on a non-exempt device is now a secondary question.
It cannot be reached until the alarm is delivered.

### 32.5 Forced overdue — it still refuses to fire

Rather than wait, the emulator clock was jumped past the alarm's due time. The alarm went overdue
and **still did not fire**:

```
device time: Fri Sep 18 00:28:04 PDT 2026
type=RTC_WAKEUP origWhen=2026-09-18 00:22:53.658 window=+18h0m0s0ms repeatInterval=86400000 count=0
whenElapsed=-5m11s287ms   maxWhenElapsed=+17h54m48s713ms
```

Five minutes past due, `count=0`, and the system still holds **17h54m** of remaining permission to
defer. Nothing was suppressing it: deep idle is not even enabled on this emulator
(`Unable to go deep idle; not enabled`), so Doze plays no part, and the app is
`batteryOptimisationExempt=true`.

**Being overdue does not compel delivery.** The window is the whole mechanism. This is a stronger
demonstration than waiting would have produced, and it closes the question the §28 diagnostic was
built to answer: the background FGS start is never reached, so whether it would be *refused* is
moot until the window is fixed.

Also confirmed along the way: there is no adb lever that forces it. `cmd alarm` offers only
`set-time`/`set-timezone` with no flush, `deviceidle force-idle` is unavailable on the emulator,
and stopping the service with `am stopservice` does not reach the `onDestroy` path that re-arms.

### 32.6 Closing the app makes it worse

Closing the app re-arms from scratch, and because `lastUsedAgo` has then crossed
`LAST_USAGE_THRESHOLD_JUST_MS` (one hour) the backoff moves up a tier:

```
10:00:39  armed reason=periodic-15min   firstFireInMinutes=15
12:22:53  armed reason=periodic-720min  firstFireInMinutes=720   (after the app was closed)
          origWhen=2026-09-18 00:22:53  whenElapsed=+11h58m  maxWhenElapsed=+1d5h58m
```

So the next background sync moved from "15 minutes" to **between 12 and 30 hours away** — and it
moved there at the exact moment the user stopped using the app, which is when background sync is
the only thing keeping the wallet current. Every subsequent close re-arms again and pushes it out
further, so a user who dips in and out keeps resetting the clock rather than accumulating progress.

### 32.7 The fix is small

Pass a repeat interval that matches the intent instead of `INTERVAL_DAY` — the window follows the
interval, so a 15-minute repeat gets minutes of slack rather than hours. Or drop
`setInexactRepeating` for `setWindow`/`setExactAndAllowWhileIdle` and state the tolerance
explicitly. Either removes the 18-hour window; the FGS-start question in §28 can then be tested for
real.

**Implemented 2026-09-19 (§32.8).** This section originally deferred it as a battery/product call.
That was the wrong frame — see below.

### 32.8 IMPLEMENTED, 2026-09-19 — and why the deferral was reconsidered

QA on int19 reported the same defect independently (SR-06), which prompted a re-reading of the
deferral. It does not hold up:

> Not implemented — it changes wake-up frequency and therefore battery behaviour.

The premise is that the alarm currently fires rarely and would afterwards fire often. It does not
fire **at all**. §32.5 forced one overdue on a battery-exempt device with deep idle disabled and it
still did not fire — `count=0` with 17h54m of remaining permission to defer. So the comparison is
not "more wakeups versus fewer", it is "some versus none".

And the battery policy is not being overridden, it is being implemented. The tiers already encode
it — 15 minutes when the app was just used, 12 hours when recent, 24 hours when idle. The code
computed the right tier and then discarded it:

```java
// before: trigger honours the tier, repeat is hardcoded
alarmManager.setInexactRepeating(RTC_WAKEUP, now + alarmInterval, AlarmManager.INTERVAL_DAY, alarmIntent);
// after
alarmManager.setInexactRepeating(RTC_WAKEUP, now + alarmInterval, alarmInterval, alarmIntent);
```

**Scope note.** The `JobScheduler` branch above it always did this correctly, but it runs only on
`SDK_INT == O || O_MR1` — Android 8.0 and 8.1 exactly. Every device from Android 9 onward took the
`setInexactRepeating` path, including both reference installs and every test device.

### 32.9 A second defect in the same lines: the two alarms were one alarm

A `PendingIntent`'s identity ignores EXTRAS — it is the package, the request code, and the Intent's
component/action/data. Both schedulers built theirs with **request code 0** against
`BlockchainServiceImpl` with no action:

| | armed by | repeat | request code |
|---|---|---|---|
| Periodic sync | `WalletApplication.scheduleStartBlockchainService`, from the service's `onDestroy` | tier (15 min / 12 h / 24 h) | **0** |
| One-minute restart | `BlockchainServiceImpl.rescheduleService`, on a replay-interrupting stop or a blockstore timeout | 15 min | **0** |

So they were the SAME `PendingIntent`. `FLAG_UPDATE_CURRENT` swapped the extras and the second
`setInexactRepeating` replaced the first alarm outright — observed on a test device as the daily
alarm with an 18-hour window standing where a 15-minute restart had just been armed. Whichever
armed last won, silently.

Now `ALARM_REQUEST_CODE_PERIODIC = 0` and `ALARM_REQUEST_CODE_RESTART = 1`, named in
`BlockchainServiceImpl` so both sites share the definition. The periodic keeps 0 deliberately: an
alarm scheduled by an older build carries that code, and `AlarmManager.cancel` only matches an
EQUAL `PendingIntent`, so renumbering it would orphan the very alarm the reschedule means to
replace.

### 32.10 How to verify it — the test that has never passed

Neither fix is unit-testable as written (static methods over a `Context`, calling `AlarmManager`).
The verification is §28's, and it needs a device left alone:

1. Open the app and use it, so `lastUsedAgo` is small. This matters: §32.6 showed that arming after
   an hour of non-use selects the **720-minute** tier, not 15.
2. Get the SERVICE to stop — not the app. Swiping the app from recents does NOT do this, and the
   recipe originally said it did: the blockchain service is a foreground service and survives the
   swipe (verified on the Samsung, 2026-09-19 — the service logged on for minutes afterwards and
   nothing was armed). The alarm is armed from `BlockchainServiceImpl.onDestroy` and there is no
   other way, so the service has to actually stop, which in practice means the idle detector.
   `am force-stop` is NOT a substitute: it kills without lifecycle callbacks, so `onDestroy` never
   runs, and Android cancels the package's alarms into the bargain.
3. Expect `ALARM-DIAG armed reason=periodic-15min firstFireInMinutes=15 repeat=15min`. The
   `repeat=15min` IS the fix; it read `repeat=1440min` before, and the line hardcoded that value so
   it would have kept saying so.
4. Confirm the window with `adb shell dumpsys alarm | grep -A6 darkcoin` — §32.5 caught the
   18-hour window exactly there.
5. **Do not touch the device.** Opening the app re-arms and restarts the clock.
6. After ~15 minutes, look for a `started by alarm` line.

### 32.11 The replay guard and the alarm are in tension

Found while trying to run §32.10 on the Samsung. The idle detector wanted to stop the service and
the replay guard refused, correctly:

```
18:28:01  idle counters, but a replay is in progress (99%) — keeping the service alive until it completes
18:53:33  replay in progress on the SDK path — acquiring the wake lock
```

Both mitigations are doing their job, and together they produce a gap neither anticipated. The
guard (`20975314f`) holds the service open so a replay is not interrupted. The alarm exists to
restart a service that has stopped. While a replay is in progress the service cannot stop, so the
alarm is never ARMED at all — not merely undelivered.

For a healthy wallet that is harmless: the replay finishes, the service idles out, the alarm arms.
For a wallet whose replay never finishes — Joel's exact case, 89.4 % for days — the service is held
open indefinitely and the periodic alarm never enters the picture. The wallet then depends entirely
on the foreground service surviving, which §33's ANR and LMK evidence says it does not.

Not a defect in either fix, and no change is proposed here. It is recorded because it means §32's
alarm work cannot be tested on a wallet that is still replaying, and because "the alarm never
fires" and "the alarm was never armed" are different failures that look identical in a field log.

### 32.12 DELIVERED, 2026-09-19 — and §28 is answered

Samsung SM-S901U, Android 16, build from `5f8c0a8ef`. The first time this alarm has been observed
to fire.

```
19:36:05  ALARM-DIAG armed reason=periodic-15min firstFireInMinutes=15 repeat=15min
          exact=false batteryOptimisationExempt=true
20:08:33  ALARM-DIAG service started by alarm (reason=periodic-15min)
          — a background FGS start WAS permitted
```

Confirmed independently in AlarmManager's own scheduling record rather than only in our logging:

| | before the fix | after |
|---|---|---|
| `repeatInterval` | 86,400,000 ms (24 h) | **900,000 ms (15 min)** |
| `window` | `+18h0m0s0ms` | **`+11m15s0ms`** |
| PendingIntent | one shared `PI:35881e8` | two distinct identities |

Due 13:06:26, delivered 13:08:33 — 2m7s into an 11m15s window — and AlarmManager then rolled to
the next repeat by itself (`origWhen=13:21:26.872`), which is the repeat working rather than a
re-arm from a service teardown. `reason=periodic-15min` is only ever set on the alarm's own
PendingIntent, so the start provably came from the alarm.

**§28 is answered.** That section asked whether the background `dataSync` FGS start would be
REFUSED once an alarm reached it. It could never be tested, because the alarm never arrived —
§32.5 could only show it going overdue with 17h54m of deferral still permitted. The delivery
message's second clause settles it: on this device the background FGS start from an alarm **is
permitted**.

**Two things this does NOT establish.**

The arming line records `batteryOptimisationExempt=true`. Every device used for this work has held
that exemption, which is exactly why §28 could not be settled in the lab. Whether a NON-exempt
device is also permitted the FGS start is still untested, and the reference installs are the
likely non-exempt case.

And §32.11 is unaffected: the alarm is only armed when the service stops, and the replay guard
holds the service open while a replay runs. A wallet whose replay never completes still never arms
it, however well the alarm works once armed.

### 32.13 A second reason it was never seen: every teardown cancels it

Found while running §32.10, and independent of the interval bug.

`scheduleStartBlockchainService` calls `alarmManager.cancel(alarmIntent)` unconditionally before
re-arming, and it runs from the service's `onDestroy`. So ANY start-and-stop of the service
cancels the pending alarm and restarts its 15 minutes from zero. Observed directly: an alarm due
at 12:51:05 was cancelled at 12:51:28 — 23 seconds after becoming due, still inside its window —
because platform sync had woken the service, which then stopped and re-armed for 13:06:26.

On a device whose service is touched more often than the alarm interval, the alarm can therefore
be deferred indefinitely while appearing perfectly healthy in `dumpsys`. The interval fix does
nothing about this. It only became visible because the window was short enough to watch a single
cycle end to end.

Not fixed. The obvious repair is to re-arm only when no equivalent alarm is already pending, or to
leave a delivered-but-overdue alarm alone rather than cancelling it.

Step 6 of §32.10 was first observed on 2026-09-19 — see §32.12. Until it is, §28's question —
whether the background FGS start is refused once the alarm IS delivered — remains unreachable
rather than answered.

## 33. Move the wallet load off the main thread

Raised from Joel's 12000010 bundle (`joel-oom-crash-2026-09-18`): "crash on startup, never
would finish syncing". The sync half is a consequence — the engine is progressing
(`last_activity: 0s`, filters 2,272,092 / 2,540,300 = 89.4 %) but no session survives long
enough to finish. The startup half is this section.

Retitled from "the protobuf parse": §33.4 shows the parse is only part of the main-thread
block, and §33.7 shows the expensive part of it has almost no consumer after the cutover.

### 33.1 The measurement

`WalletApplication.fullInitialization()` → `loadWalletFromProtobuf()` is called from
`WalletApplication.onCreate()`. Six loads are timed in the bundle's logs, and **every one ran on
`[main]`**:

```
n=6   min 6,222 ms   median 10,320 ms   max 21,567 ms
```

The input is a 61,641,747-byte wallet holding 33,297 transactions and 229 friend chains /
29,970 keys. For scale, Andrei's job-flower wallet is 10.2 MB and 6,787 transactions, and the
emulator-5556 test wallet is 3,269 transactions and parses in 623–975 ms.

The ANR threshold is 5 s. The **fastest** observed load is 6.2 s. The app's own
`ApplicationExitInfo` history over these logs records **33 ANRs**, 18 `LOW_MEMORY_LMK`, 5 crashes.
Every cold start on this device is an ANR by construction.

### 33.2 What is already in place, and why it is not enough

Three guards already surround this load, and none of them shortens it:

- `WalletFileSizeGuard` — a **pre-parse** verdict on file size. Joel's file passes; it is 61 MB,
  well under the 2 GiB protobuf wall.
- `WalletLoadBudget` (20 s) — deliberately does **not** abort. `readWallet` cannot be interrupted
  safely mid-parse, so the budget only marks the breadcrumb and arms safe mode for the next launch.
  Joel's max load of 21.6 s is the first to cross it.
- `StartupBreadcrumbs` safe mode — opens the app *without* the wallet after two deaths.

They convert "silent death" into "diagnosable death". The load itself is untouched.

**Safe mode is firing on Joel's device.** The breadcrumb trail carries all three markers:

```
91 WALLET_LOAD_SKIPPED_SAFE_MODE  +25 ms
98 SAFE_MODE_RETRY              +7202 ms
99 SAFE_MODE_RETRY_OK          +28792 ms
```

So he has been through the crash-loop breaker — two consecutive deaths before the main UI, the app
opened degraded, then the in-process retry recovered. The guard works. It is also evidence that the
deaths cluster at load rather than spreading through the session.

### 33.3 Why it is on the main thread

Not by accident. `WalletApplication` exposes the wallet synchronously and non-null:

```java
@Override public Wallet getWallet() { return wallet; }

@Override public TransactionBag getTransactionBag() {
    if (wallet == null) throw new IllegalStateException("Wallet is null");
    return wallet;
}
```

Loading inside `onCreate()` is what makes that contract true for every caller that follows.
`observeWallet()` already exists as a `MutableStateFlow<Wallet>` — the asynchronous contract is
half-built, it is simply not the one callers use.

### 33.4 The main-thread block is LOAD, not parse

Joel's fastest launch, from the breadcrumbs:

```
 4 WALLET_LOAD_BEGIN            +15 ms
 5 WALLET_PROTOBUF_PARSED     +2710 ms
 6 WALLET_CONSISTENCY_CHECKED +5966 ms     <-- 3.3 s AFTER the parse
 7 FINALIZE_INIT_BEGIN        +5966 ms
 8 INIT_DASH_DONE             +6139 ms
11 ONCREATE_COMPLETE          +6254 ms
12 MAIN_UI_SHOWN              +6504 ms
```

`isWalletConsistent()` calls dashj's `isConsistentOrThrow()`, which walks every transaction against
the wallet's pools. On 33,297 transactions that is **3.3 seconds**, on the main thread, immediately
after the parse — on this launch more than half the ANR budget by itself, against a parse that was
2.7 s here versus a 10.3 s median.

The unit to move is therefore everything between `WALLET_LOAD_BEGIN` and
`WALLET_CONSISTENCY_CHECKED`: parse, `adoptAuthenticationGroupExtension`, consistency check.
Moving the parse alone leaves seconds behind.

### 33.5 The precedent is in this codebase

`FriendKeyChainLookahead` solved the same shape one level down — work inside `readWallet` killing
launches on a 2.5 MB file — and the pattern it used is the pattern here:

1. a `KeyChainFactory` handed to the serializer returns deferring subclasses, so the parse builds
   the objects and returns without doing the expensive work;
2. `completeAsync()` does that work on a background pool;
3. `awaitComplete()` is a gate placed immediately before the one consumer that must not see a
   partial state (`peerGroup.addWallet(wallet)`).

A wallet-level gate is the same construction one level up. What makes it harder is that the
consumer set is "the whole app" rather than one call, which is what §33.6 is about.

### 33.6 Scope the audit by what runs before first frame

The surface is ~37 `getWallet()` call sites and ~359 `.wallet` references. That number is the wrong
estimate, because most of them are on screens the user has not opened. The question is how many run
**before the first frame** — everything else can await the gate on its own path.

Post-cutover that set is small, because `peerGroup.addWallet(wallet)` — the classic early consumer —
never runs at all (`checkService()` returns at `BlockchainServiceImpl.kt:1506`), and the home screen
is served by `CutoverUiDataService` and `SdkBlockchainStateService`.

Measure it before estimating: instrument `getWallet()` to log its caller until `MAIN_UI_SHOWN`, run
one launch, and the audit list is that log rather than a grep.

### 33.7 What the Wallet is actually FOR after the cutover

Enumerated from the real call sites, not from the class's history. Post-cutover the dashj `Wallet`
has four jobs:

1. **The keystore.** `isEncrypted` (12 sites), `keyCrypter`/`getKeyCrypter` (5), `keyChainSeed` (3),
   `encrypt`/`decrypt`. Every PIN check, every send authorisation and the SDK bind itself run
   `wallet.keyChainSeed.decrypt(wallet.keyCrypter, null, pinDerivedKey)`
   (`SecurityGuardMnemonicProvider`). There is no other source — `SecurityGuard` stores keys
   *derived from* the mnemonic, not the mnemonic.
2. **The friend-chain store.** `addAndActivateHDChain`, still writing post-cutover (observed on
   emulator-5556 at 21:25 creating `FriendKeyChain{…}` as contact requests arrived). The sending
   chains carry the CONTACT's xpub, which `FriendChainAccess` documents as not re-derivable from
   our seed.
3. **A txid → transaction lookup.** `getTransaction(txid)` in `WalletTransactionMetadataProvider`
   (×2), `TransactionResultViewModel`, `L1SendProbeService` — point lookups, fired when a
   transaction detail screen opens or a tax category is set.
4. **Diagnostics.** `L1ShadowSyncService` reads `getTransactions(false).size`,
   `calculateAllSpendCandidates` and `lastBlockSeenHeight` for the parity facts.

What it is NOT used for post-cutover: balance, history, the home screen, sends
(`SdkL1SendService` never touches it), coin selection, or L1 sync. `lastBlockSeenHeight` is frozen
— the autosave line reads `last seen block is height -1`.

The one straggler is `SweepWalletFragment`, which still calls `getTransactions(...)` and
`freshReceiveAddress()` for real work because paper-wallet sweep has not moved to the SDK.

### 33.8 Which reframes the fix

The 33,297 transactions are parsed on every launch, and cost 3.3 s of consistency checking on top,
so that a detail screen the user may never open can do a hash lookup and a diagnostic can count
them. Moving that to a background thread HIDES a cost that post-cutover should not be paid at all.

Two options, and they are not exclusive:

**A. Load asynchronously** (the original §33 proposal). Removes the main-thread stall. Does nothing
about the ~430 MB the transactions occupy in a 512 MB JVM.

**B. Do not materialise the transaction graph post-cutover.** Consumers 3 and 4 above are a hash
lookup and a count. Both can be served lazily — on the first `getTransaction` call — or from the
SDK store. This attacks the parse time AND the footprint.

B is bounded by two real constraints: `readWallet` returns a `Wallet` that callers expect to be
whole, and pre-cutover the transactions genuinely are load-bearing — so it has to be conditional on
a committed cutover, which means two shapes of `Wallet` in one codebase. A wallet with an empty
transaction set is a sharper edge than a wallet that arrives late; cf. DASHJ-KILL-LIST 1a, where a
partial view was published as real.

### 33.9 Do the measurement first

dashj's `WalletProtobufSerializer` already instruments the WRITE side per category (observed on
emulator-5556):

```
walletToProto (serial=12) timing: 22ms total, init=0ms, tx=0ms[0], keys=1ms,
  scripts=0ms[0], metadata=0ms, ext=17ms, friendRecv=2ms, friendSend=0ms, build=0ms
```

There is no equivalent on the READ side. Mirroring it — tx, keys, scripts, extensions, friend
chains — and running one launch against a copy of Joel's 61.6 MB wallet answers in one reading
what share of the 6–21 s is transactions. That number decides between A and B, and it is the
cheapest thing on this list.

### 33.10 What this does not address

The other half of Joel's failure is memory: PSS 2.3–2.45 GB, native heap 1.86 GB, JVM peaking at
475 MB of 512, driving 18 low-memory kills. Option A does not reduce the footprint. Option B would,
by an amount nobody has measured — a heap dump (`adb shell am dumpheap`) on a wallet of this shape
is the way to attribute the 430 MB rather than estimate it.

The 1.86 GB of native heap held during the FILTERS replay is the SDK's and is not reachable from
here: the whole app-side SPV surface is `startSpv(dataDir)` — no batch size, no concurrency limit,
no memory budget. That is an SDK issue to file, with the `ReplayMemTelemetry` curve attached.

Nor does any of this help the two idle stops observed mid-replay at 12:45:35 and 13:00:00. Those
are already fixed on the branch (`20975314f` replay guard, `b42f8f84e` re-arm) and simply absent
from 12000010. The filter-stall watchdog (`616ac58ff`) would **not** fire for him: he is
progressing, not wedged.

Not implemented.

## 34. What actually stalls the sync: a filter batch commits only when its SUCCESSOR is created

Settled 2026-09-19 with SQL-level and SDK-level instrumentation on a Samsung SM-S901U. Two earlier
hypotheses in this section were wrong and are recorded at the end so the reasoning is not repeated.

### 34.1 The mechanism

`dash_spv` downloads filters in 5,000-block batches. Its own log shows a batch being COMMITTED only
after the NEXT batch has been created — every time, across three engine sessions:

```
19:19:02  Creating lookahead batch  start=1547171
19:19:04  Creating lookahead batch  start=1552171     <- successor created
19:19:05  Committed batch           start=1547171     <- predecessor commits
```

The final batch runs from the last 5,000 boundary to the chain tip, and it has no successor. So it
is never committed, and `committed_height` — the value the whole app reads as "how far are we
synced" — parks at the last full boundary:

```
19:19:04  Creating lookahead batch 1552171-1556922 (active_batches=1)
19:19:05  Batch 1552171-1556922: found 95 matching blocks across 1 behind wallets
19:19:05  Committed batch 1547171-1552170, committed_height now 1552170
          ... still uncommitted 9 minutes later
```

The engine is not broken and nothing is lost. Every filter IS stored — the same dump reports
`Filters: … stored:1556922` against a tip of 1,556,922. Only the COMMIT is withheld.

### 34.2 Everything it explains

- **Why the cursor always parks a few thousand blocks short.** The gap is the final partial batch.
- **Why it parks on odd round numbers** — 1,552,170, 1,547,170. Batch boundaries.
- **Why it clears suddenly, in a burst.** The chain grows enough to form a new batch, which
  releases the previous one. Observed at 18:53:30: `Batch 1556911-1556911: found 0 matching blocks`
  is created, and `Committed batch 1552171-1556910` lands in the same second.
- **Why restarting rarely helps.** A restart recreates the same final batch against a slightly
  newer tip — 1556917, then 1556918, then 1556922 — and it stalls identically. One of four
  watchdog restarts appeared to help; that was the tip advancing, not the restart.
- **Why the wait is unpredictable.** It is the time for the chain to produce enough blocks, which
  on testnet is erratic.
- **Why Joel's 12000012 sat at 3 blocks short for 49 minutes.** Same shape on mainnet.

### 34.3 What it is NOT

- **Not the database.** Direct measurement during a stall: zero `SQLiteConnectionPool` warnings and
  ZERO engine writes on `dash-sdk.db` — the only writes were Room's own
  `room_table_modification_log` bookkeeping. The engine is not blocked on storage; it is not trying
  to use it.
- **Not the network.** `stored:1556922` means every filter arrived.
- **Not the app's.** `committed_height` is the SDK's, and nothing on the app side can advance it.

### 34.4 Two earlier hypotheses, both wrong

Kept because both looked convincing and cost time.

**"Database lock contention."** Built on a single `SQLiteConnectionPool` warning 30 s before one
stall — a thread waiting 4 then 8 seconds for the primary WRITE connection to `dash-sdk.db`, with
the pool reporting spare capacity. It was a coincidence. Under instrumentation the database is
idle during a stall. The warning most likely came from the app's own observer queries (below).

**"The scan runs, only persistence is blocked."** Corrected once already (§34.2a in the previous
revision): the wallet-event stream stops during the stall too, so nothing was being persisted
because nothing was being produced. That correction was right, but the conclusion drawn from it —
that writes were blocked — was still wrong.

The lesson worth keeping: both hypotheses came from the APP's logs, and the answer was in the
SDK's own `files/sdk-logs/dash_spv/run.log`, which is pullable with `run-as` on a debug build and
was not being read.

### 34.5 A separate real finding: the app's observer queries are expensive

Not the stall, but measured while chasing it. During a 2.5-minute window the app re-ran the same
handful of Room observer queries on `dash-sdk.db` more than thirty times, at roughly 750 ms each:

```
754 ms  SELECT DISTINCT t.txid FROM txos t JOIN core_addresses ca ON ca.address...
753 ms  SELECT s.spendingTxid, COUNT(*), COALESCE(SUM(s.amount), 0) FROM (SELECT...
747 ms  SELECT t.txid, t.vout, t.amount, CASE WHEN EXISTS (SELECT 1 FROM core_...
```

with Room's `InvalidationTracker` tearing observer triggers down and rebuilding them throughout
(36 `CREATE TEMP TRIGGER`, 18 `DROP TRIGGER`, 18 `BEGIN IMMEDIATE` in the same window). Roughly 25
seconds of query time in 150 seconds. Worth its own investigation — it is a plausible source of the
`SQLiteConnectionPool` warning that started §34 down the wrong path.

### 34.6 Where this goes

This is an SDK defect in `dash_spv::sync::filters::manager` and is not fixable from the app. The
filter-stall watchdog stays as a mitigation, but §34.2 explains why it mostly cannot help: a
restart does not create a successor batch. Its EXHAUSTED message already says the right thing —
"this needs an SDK-side fix".

To be filed against `dash_spv` with the log excerpts above. The fix is presumably to commit the
final batch on its own completion rather than on the creation of the next one, or to flush it when
the scan reaches the tip.
