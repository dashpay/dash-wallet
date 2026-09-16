# Dash Wallet v12: Memory and Sync Recovery Plan

**Status:** draft for review, 2026-09-15; revised 2026-09-16 for the no-fallback cutover policy (section 12)
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
10. **SPV before DashPay bring-up.** The scan does not need the keystore; the bring-up does (section 12). Start the SDK engine as soon as the wallet is open and let the DashPay drain run behind it, resuming whenever the keystore becomes available. Today the bring-up runs first with no effective budget: 5 to 42 minutes on 09-15, 766 s to 10,139 s on 09-16, 0 of 154 to 177 builds drained, filter position frozen throughout, and the idle detector tore the service down before SPV ever began.
11. **Do not open or lock the dashj blockstore when dashj is held.** If a start does find a live lock, wait for the previous cleanup instead of stopping the service.
12. **Do not restart the engine into a dying service.** Gate `startIfEnabled` on the service not being in cleanup.
13. **Flush the SDK watermark on every engine stop** from the app side, so a clean stop never loses progress even before Phase 1c item 15 lands.

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
- If the bind fails because the device is locked, the app waits for the unlock and retries on the unlock broadcast, on app foreground, and on a slow ladder. It tells the user what it is waiting for. Waiting until the user next opens the app is acceptable.
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
