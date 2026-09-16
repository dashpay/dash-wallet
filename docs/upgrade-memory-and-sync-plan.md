# Dash Wallet v12: Memory and Sync Recovery Plan

**Status:** draft for review, 2026-09-15
**Source:** field logs from one mainnet install (Pixel 8a, Android 17, build 12000010 `12.0.0-sync`), sessions 2026-09-12 through 2026-09-15 UTC, plus the `fix/kotlin-sdk-balance-issues` branch at `50a42be8b`.

---

## 1. Goal

An upgraded wallet the size of the reference install must:

1. survive the upgrade launch without running two SPV engines;
2. cut over to the SDK in a single launch, without requiring the user to open the app;
3. complete the SDK replay on a 512 MB-heap device without the process being killed and without the replay losing ground on restart;
4. idle well under the heap ceiling once cut over.

Reference install: 33,297 transactions, 67,770 CoinJoin keys, 229 DashPay friend chains (29,970 keys), 12,646 tx-metadata documents, 62 MB wallet file, wallet birth May 2023.

### Targets

| Metric | Measured | Target |
|---|---|---|
| JVM heap idle after cutover, dashj held | 482 MB of 512 MB | under 150 MB |
| SPV engines running on an upgrade launch | 2 | 1 |
| Launches from upgrade to CUT_OVER | 3 (22 hours) | 1, any kind, first unlocked bind |
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
2. Upgrade seam cannot commit on the launch that records bind evidence, so a successful bind mid-launch leaves dashj running and the SDK engine starts beside it.
3. Bind attempted while the device is locked counts as a failure and nothing retries in the pre-commit state.
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

### Phase 1a: stop the crash (app, about one week)

1. **Never two engines.** `L1ShadowSyncService.startIfEnabled` refuses to start while the dashj peergroup is running and not held.
2. **Commit on bind success.** On a boundary-crossed install, when the bind pass records success, commit the cutover immediately and hold dashj, stopping the peergroup if it already started. The state machine already permits the direct DUAL_RUNNING to CUT_OVER write; the seam does it one launch late today.
3. **Locked-phone bind handling.** Check `KeyguardManager.isDeviceLocked` before the bind pass. If locked, log a deferral rather than a failure, and arm the existing `ACTION_USER_PRESENT` receiver on any boundary-crossed install so the bind runs at the next unlock. Today the receiver arms only after a commit.
4. **Optionally await the bind before the engine gate.** When the boundary is crossed and no bind evidence exists, the blockchain service waits a bounded time (about 10 s) for the bind pass before deciding whether dashj may start. On an unlocked device this yields one-launch cutover with dashj never started.
5. **Log diet.** `InstantSendManager`, `SPVQuorumManager`, `SigningManager` to WARN in the logback config. Per-transaction "exists, only do update" and "metadata merged" lines to debug.
6. **Size-aware autosave.** Replace the flat 5 s `WALLET_AUTOSAVE_DELAY_MS` with a delay scaled by the size-guard verdict (for example 60 s above RISKY) and suppress saves during active chain download. The code already carries a TODO for this.

Result: the reference crash session would have committed at 12:17:11, never started the SDK engine beside dashj, and not crashed. Idle heap is still 482 MB.

### Phase 1b: let the replay finish (app, same week, ships with 1a)

7. **The blockchain service stays alive until the replay is complete.**
   - The idle detector does not stop the service while `blockchainState.replaying` is true. Today the flag only reschedules a one-minute alarm after the stop; make it a guard that skips the stop. Hold the wake lock for the same span. Genuinely stuck engines are covered by the SDK stall impediment and the watchdog, not the idle rule.
   - `updateSdkBlockchainState` stops writing `replaying = false` unconditionally and writes `replaying = percentageSync < 100`. `BlockchainStateDao.saveState` already clears the flag at 100%.
8. **Set the replay flag when an upgrade's SDK sync starts.** Only restore and rescan call `resetBlockchainState` (which writes `BlockchainState(replaying = true)`) today. Call it from the cutover commit path when the SDK engine is about to start from a watermark below the tip. Covers the upgraded-wallet launch and the in-session commit from item 2, and makes the "sync paused" notification and home-screen sync state read correctly during the scan.
9. **One-minute reschedule becomes the fallback.** With item 7 the service should not stop mid-replay. If the OS or a failed start stops it anyway, the existing `rescheduleService` alarm now fires because the flag is true.
10. **Hard budget on "DashPay bring-up before SPV."** Cap at the 20 s it took on the first launch; let the account drain continue after SPV starts. Today it ran 5 to 42 minutes with 0 of 154 builds drained.
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
20. **Rollback safety.** `rollbackForFailedBind` un-holds dashj, which then needs the full wallet. Make rollback force a process restart that loads the full file.

### Phase 3: installs still on dashj (only if Phase 2 measurements say so)

21. Refuse background service starts while the size guard says RISKY, so the wallet parse only happens in the foreground.
22. Transaction pruning in the dashj fork, dropping fully spent and deeply confirmed CoinJoin chain transactions. Large, touches coin selection. Last resort.

---

## 5. Measurement and verification

- The existing per-minute `MEM pss/nativeHeap/jvm` line is emitted at every startup breadcrumb and at every engine start and stop, with the `ReplayMemTelemetry` native figures folded in.
- A synthetic large-wallet fixture: 30k transactions, 200 friend chains, CoinJoin history, run on an emulator pinned to a 512 MB heap.
- A replay test that stops the engine mid-scan and asserts the persisted watermark equals the in-memory height.
- **Phase 1a acceptance:** an upgrade launch on the fixture never logs "two SPV engines are now running"; cutover commits on the first launch where the device is unlocked.
- **Phase 1b acceptance:** on the reference wallet, one service instance runs from the first SDK start to `percentageSync == 100` with no "idling detected" line; the `replaying` column is true throughout and false at the end; an induced stop is followed by a service start within 90 seconds; no `OverlappingFileLockException` on any launch.
- **Phase 2 acceptance:** the reference install idles under 150 MB JVM heap after cutover and launches in under 2 s.

## 6. Rollout

1. Ship Phase 1a and 1b together as a hotfix. Measure on the reference install.
2. File Phase 1c with the SDK team now; items 14 and 15 gate whether large wallets can finish a replay at all.
3. Phase 2 in the following build.
4. Phase 3 only if Phase 2 numbers leave pre-cutover installs exposed.

## 7. Decisions needed

- **Shadow flag seeding.** `USE_KOTLIN_SDK_L1_SHADOW` is seeded on for every variant including prodRelease (2026-07-30 decision), while the engine's own documentation says it must never ship enabled. Phase 1a item 1 makes it safe either way; the policy call remains.
- **Rollback build.** A v12-numbered build carrying 11.9.1 code (plus a 22 to 21 Room migration dropping `instant_send_locks` and the friend-lookahead deferral backported) is cheap insurance as a fleet kill switch. It does not fix memory and returns large wallets to the 11.9.1 failure profile.
- **Balance display during replay.** Confirm on a device that the home header holds the last known total while `l1Synced=false`. If it shows the raw SDK figure, it is a support-ticket generator on its own.

## 8. The reference install today

Nothing in the current build lets its replay finish: every start dies before SPV begins, and each teardown costs up to 155,000 blocks. Until Phase 1b ships, a seed restore on v12 is the only path to a working wallet, and it runs the same replay with the same native memory profile, so it should wait for Phase 1c item 14 or be done on Wi-Fi with the app kept in the foreground.

## 9. Related review

PR #1555 (`fix/sdk-spv-restart-on-service-restart`) carries the cutover gating work. Its blocking review finding, retrying the boundary-latch write independently of a successful commit, is correct and cheap, but it concerns the one-time sync explainer only. Phase 1a item 2 makes the arming path run in the same launch and largely dissolves the finding. The PR's own verification note applies: real upgrade mechanics on a large wallet are untested; the scenario that took the reference install down sits between its S2 and S3b cases.
