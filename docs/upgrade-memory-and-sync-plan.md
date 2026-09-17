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

It also bounds the proposed "keep paying from an established contact" experiment. On B,
`test-dash-username-2` (emulator-5558) sits at `externalHighestUsed = 0` with the SDK gap
limit at 1000, and the window **slides forward** with each address used. Sequential
payments never outrun it. The predicted result is that B catches every one of them, locked
or unlocked. That makes it a clean falsifiable control rather than a reproduction: if a
payment IS missed while locked, the exposure is far wider than section 16 claims, because
it would then reach every contact rather than only new ones.
