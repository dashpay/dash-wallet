# QA int19 (Pasta) — defect status against `fix/upgrade-memory-and-sync`

Source: QA testing by Pasta on an **int19** build. Assessed 2026-09-19 against this branch at
`5058e9194`, which now contains #1555 and #1559 via the merge in `1d9bbf659`. Re-assessed
2026-09-21 at `4a0a9ea9c` (base now `feat/kotlin-sdk-phase-1`) after a day of device testing on
release builds `12000016`/`12000017`; the entries below carry dated updates where that changed
anything, and **A-01** (Andrei's post-upgrade "still 99%") is added at the end of the fixed list.
**A-02** (Andrei's 2026-09-22 "resync got stuck") is fixed on the stacked branch
`fix/sync-process-stalls`; #1568 itself takes only review fixes from here. Re-assessed
2026-09-22 evening at `2e3e3518d` on SDK int22 after fresh restores on both devices
(plan §38); **A-03** (the per-launch 26,000-block re-walk) is added under "Still applies" as an SDK
defect. Re-assessed 2026-09-25 after Joel's upgrade of the reference install to `12000017` (plan
§39): **J-01** is added under "Still applies" — the same three SDK-side rewinds at mainnet scale —
and A-02 gets a second field sighting.

"Addressed" below means *the defect's cause is fixed on this branch*. It does **not** mean
retested — see [Retest before closing](#retest-before-closing).

## Summary

| Status | Count | IDs |
|---|---|---|
| Fixed on this branch | 4 | SR-01, **SR-06** (delivered on device 2026-09-21), **A-01** (Andrei's "still 99%", 2026-09-21), **A-02** (Andrei's "resync got stuck", 2026-09-22 — on `fix/sync-process-stalls`) |
| Addressed by the #1555 merge | 2 | D-056, D-031 |
| Addressed by the pending master merge | 1 | D-011 |
| Still applies | 9 | D-037, **D-041** (SDK defect; the display hold and the seed gate are now proven on device — see the 2026-09-21 update), D-068, SR-22, SR-05, SR-03/04, SR-07, **A-03** (per-launch re-walk, SDK defect, 2026-09-22), **J-01** (Joel's "synced almost to completion, then reset", 2026-09-24 — SDK defects) |
| Applies by deliberate decision | 1 | SR-08 |

---

## Fixed on this branch

### SR-01 — Filter-stall watchdog cancels its own coroutine and never restarts the engine

*Reported: static, high confidence. New in the HEAD commit at the time of review.*

**The defect.** `checkFilterStall()` runs inside `watchdogJob`. Its restart called `stop()` then
`startIfEnabled()` inline — and `stop()` contains `watchdogJob?.cancel()`, cancelling the very
coroutine performing the restart. `startIfEnabled()` threw `CancellationException` at its first
suspension point, `runCatching` swallowed it, and the engine stayed down. The watchdog reliably
turned a stalled-but-running engine into a dead one: **strictly worse than not shipping it.**

**Fixed** in `2a8247603`. The restart now runs on the service `scope`, which `stop()` does not
cancel (it cancels the four loop jobs only) — the same pattern `checkProbeHeartbeat` already used
twenty lines above.

**A second defect was found while fixing it, not in the report.** The watchdog judged on the filter
cursor alone. On a Samsung SM-S901U on 2026-09-19 the cursor sat at 1,555,999 of 1,556,844 for
**6 min 32 s**, then cleared on its own — three heights persisted in 130 ms and the engine reached
SYNCED unaided. A restart there would have torn down an engine that was about to recover. Two
changes followed: the decision now also requires the wallet-event stream to have gone quiet for
the same window, and successive restarts back off (10 → 20 → 30 min) instead of firing every
10 minutes.

*Correction.* An earlier revision of this note claimed the SDK logged `wallet-event batch: folded=N`
**throughout** the stall, and concluded the scan was running while only the watermark WRITE was
blocked. That was a misreading of the logs: the event stream stopped too, and nothing was blocked —
the final partial filter batch was holding its own commit because it had matched blocks it never
received. See [plan §34](upgrade-memory-and-sync-plan.md) for the mechanism. This matters for the
fix as well as the record: because the event stream *does* go quiet during these stalls, the
liveness gate would not have suppressed this restart. It remains a sound conservative guard, but
the **backoff** is what actually limits the damage of a watchdog that cannot cure this class of
stall.

3 new tests; `L1ShadowSyncServiceTest` 111/111.

**2026-09-21 update.** The watchdog now also RECOGNISES the §34 stall and declines to restart into
it (`SDK_FINAL_BATCH`, `743d7af42`): a confirmed stall parked less than one 5,000-block commit
batch short is reported once per park and not restarted — a restart recreates the same batch and
re-walks from the durable watermark for nothing. After review the suppression was bounded
(`4a0a9ea9c`): a park still there after 30 minutes falls through to the ordinary restart ladder,
because the signature is a distance heuristic and a process-lifetime latch would have silenced
restarts for a different, recoverable wedge. On device: `SDK-FINAL-BATCH` fired at 13:00:56 on a
release build at the real 10-minute threshold (2,167 short, event stream quiet, no restart, cleared
on its own 13½ minutes later); the later 12000017 park cleared at ~10 m 40 s, inside the threshold
plus the 60-second tick, so no watchdog decision was reached there — a correct non-event. Plan
§34.6, §35.3. `L1ShadowSyncServiceTest` 123/123.

### A-01 — "Still 99%" after upgrading (Andrei, 2026-09-21)

*Reported live, on a build carrying the SR-01 fix. Reproduced on device the same day: yes (Samsung
SM-S901U, three parks; emulator, one).*

**The defect.** The home header sits at "Syncing 99%" indefinitely on a wallet whose engine has
every filter downloaded, and after 90 static seconds shows the red "unable to connect" banner.
Neither is true. This is the user-facing form of the dash-spv final-partial-batch stall in
[plan §34](upgrade-memory-and-sync-plan.md): the filter COMMIT parks a few thousand blocks short of
the target (1,555,999 five times on 2026-09-21) while every filter is stored, and Android's synced
test compared exact heights with a 2-block tolerance, so the park read as "not synced" for as long
as it lasted. iOS never showed it: its test is the engine's aggregate percentage `>= 0.999`, a
three-phase mean that absorbs any shortfall under ~0.3% of chain height.

**Fixed** in `68d2ff02f` by adopting the iOS rule — plan §35. The synced predicate is now the union
of the height rule and `overallPercent >= 0.999`; the 90-second stall verdict does not raise the
NETWORK impediment at that threshold but logs a WARN naming the shape and numbers; the
pipeline-lag veto that used to sit inside the synced predicate moved to the durable seed and the
funding gate, the two places being wrong is expensive. `3e9fa5d9c` added once-per-edge logs for
the display decision and the seed write, because the publication line is value-gated and the
decision had otherwise to be inferred.

**Proven on device**, release build `12000017`, fresh restore on the Samsung:
`display predicate -> l1Synced=true` at 16:46:45 with the cursor 2,261 short at 99.952% (the
aggregate rule; the height rule could not have fired); `SPV progress static for 90s … NOT raising
the network impediment` at 16:48:15 and zero `networkStalled=true` across an 11-minute park; the
seed written once at 16:57:39 after the pipeline drained, at exactly the ledger's
`10805162729` duffs. The displayed figure was low by 0.62 DASH for 371 ms before correcting. Header
read via the accessibility tree on the emulator: no "Syncing balance", no banner, no flicker on a
one-block blip.

**What it is not.** A fix for the stall. The commit still parks; the screen now says synced and the
log says why. The root cause, corrected 2026-09-22 (plan §34): before it commits the final batch,
dash-spv re-tests every committed filter since wallet birth against the scripts derived during the
scan, inline on the filter task, with no persisted progress — a walk dominated by BIP158 false
positives (1.66% of 1.6M mainnet filters at this wallet's 13,024 scripts) that a phone never
finishes before something stops the engine, and which our integration branch's durable pending-sweep
set restarts from scratch on every launch. Already open upstream as rust-dashcore#1002 and fixed
by dropping the sweep in rust-dashcore#1016 (draft); our issue draft is retired in favour of a
comment there.

**2026-09-22 evening, int22, fresh restores (plan §38.1).** Emulator debug `12000017`: scan start
17:18:48, `l1Synced=true` 17:22:22, seed 17:23:15 at 10,805,162,729 duffs, no sweep, no stall.
Samsung release `12000018`: `l1Synced=true` 17:39:09 with `pipelineLagging=false`, seed 17:39:56 at
the same figure, no watchdog verdict, no WARN. No sweep because int22 carries rust-dashcore#1016,
which removed it (plan §34.6; the original reading here, a provisioning-order accident, was wrong
and is corrected in plan §38.1).

---

### A-02 — "Mo-1022 resync got stuck" (Andrei, 2026-09-22)

*Reported with logs and two screenshots from the Samsung SM-A536B (Android 16), release `12000017`.
Not reproduced on our devices: it needs a blockchain reset AND a pending wallet shield whose
InstantSend never arrives, together.*

**The defect.** After a blockchain reset the SPV engine was stopped by the idle detector at 06:31 UTC
and never ran again; four hours later the Network Monitor read "Network engine not started" and the
header "Syncing balance". Not the §34 filter park — the engine was off. The service's `onDestroy`
cleanup hung in `ShieldedBalanceServiceImpl.stop()`, waiting on a mutex held by a bring-up that was
itself parked inside the SDK's `bindShielded`, which was queued behind a resumed wallet shield
waiting indefinitely for a ChainLock (`shield_guard`, rs-platform-wallet). Every later start refused
itself as a "deadlock in onDestroy". Full chain in
[plan §37](upgrade-memory-and-sync-plan.md).

**Fixed** on `fix/sync-process-stalls`: `stop()` is bounded at 5 s and makes the abandoned bring-up
inert; a start refused by a cleanup stuck past 5 minutes ends the process when the app is in the
background, so the next alarm start is clean. The platform half — the guard held across an unbounded
wait — is drafted as [kotlin-sdk-issues-to-file.md §17](kotlin-sdk-issues-to-file.md).

**Not yet proven on device.** Unit-pinned only (`ShieldedBalanceServiceTest`,
`CleanupDeadlockPolicyTest`). A device proof needs the stuck asset lock's wallet, which is Andrei's.

**Second sighting, 2026-09-24 (Joel, Pixel 8a, `12000017`, plan §39.5).** The idle-stop cleanup at
23:54 UTC waited inside the shielded stop until the shielded bind returned at 00:44:40 — 50 minutes,
seven refused starts ("deadlock in onDestroy"), four of them delivered alarms. Same app-side shape;
the bind returned on its own, so no process death. Both halves of the fix on
`fix/sync-process-stalls` would have applied at 23:56.

---

## Addressed by the #1555 merge (`1d9bbf659`)

### D-056 — Locked-device upgrade: L1 engine never restarts after the bind heals

*Reproduced on device: yes (S3, 16-minute wedge).*

**The defect.** On a locked-device upgrade the SDK bind heals about 3 s after unlock, but nothing
restarts the L1 engine. The replay stays at 0% showing "Syncing…", with the foreground service
pinned alive, until the user force-stops the app.

**Cause and fix.** #1555: `platformSyncService.resume()` sat *below* the `!dashjEngineMayStart`
early return in `checkService()`, so the SDK's L1 engine only ever started once per process. Any
teardown that stopped it — `onTrimMemory`, the idle detector, the Android 15 FGS timeout — killed
L1 sync permanently. Merged today; this branch had been built on a base version that was rebased
away before #1555 landed.

### D-031 — Sends blocked after the idle detector tears the L1 engine down

*Reproduced on device: yes (S1, S7; S9 characterised the trigger).*

**The defect.** After the idle detector tears the L1 engine down *while the app is foregrounded*,
every send fails with "not fully synced" until the activity is paused and resumed, or the app is
restarted.

**Cause and fix.** Same root cause as D-056 — the engine could not be restarted within the process.
Partially mitigated on this branch already by the replay guard (`20975314f`) and its re-arm
(`b42f8f84e`), which hold the service alive *during a replay*; #1555 covers the general case.

---

## Addressed by the pending master merge

### D-011 — Lock screen bypass

*Reproduced on device: yes (S4; baseline S3).* **Security regression.**

**The defect.** Forgot PIN → Enter recovery phrase → BACK twice lands on the **unlocked home
screen**. Master 11.9.0 does not do this.

**Cause and fix.** This branch still overrides the deprecated `onBackPressed()` in
`LockScreenActivity`, `SetPinActivity` and `MainActivity`; master moved to the back-pressed
dispatcher in `73499a6d6` ("feat: support android 16"), which touches exactly those three files.
Pasta's diagnosis is precisely right.

**Merge risk.** All three files have local changes on this branch (`LockScreenActivity` was edited
on 2026-09-19). The merge must resolve in **master's** favour on the back handling or the bypass
survives the merge.

---

## Still applies — untouched by this branch

### A-03 — The engine re-walks 26,000 blocks on every launch (2026-09-22)

*Found on the emulator's engine log, six sessions since the 09-21 restore. SDK defect; not
reproducible on a wallet without DashPay contacts.*

**The defect.** Every engine session starts `Starting filter download (scan_start=1532171 …)` and
re-walks to the tip, although the previous session committed to the tip and the SDK's own Room row
holds `syncedHeight` at the tip. 1,532,170 is `coreHeightCreatedAt` of the wallet's earliest DashPay
contact request: at startup rs-platform-wallet's `reconcile_dashpay_rescan` lowers `synced_height`
to that height so contact receival addresses get filter coverage, and its "already done" guard is
in-memory only. Upstream: dashpay/platform#4302 (open since 2026-08-17), PR #4740 adjacent. Plan
§34.1a and §38.2.

**Cost.** 4–11 s per launch on the emulator with everything in storage. On a mainnet wallet with an
old first contact it is a re-walk of hundreds of thousands of filters on every launch, growing
forever. Not fixed by rust-dashcore#1016 or by int22; nothing on the app side can stop it.

### J-01 — "Synced almost to completion, then reset" (Joel, 2026-09-24)

*Reported from the reference install (Pixel 8a, Android 17, `prod` release `12000017`, 33,297
transactions, 229 DashPay contacts) after upgrading from `12000012`. Logs and the last 2 MB of the
engine logs at `~/Downloads/joel-stuck-at-99/`. Plan §39 has the full timeline.*

**The defect.** The header reached 99.9% at 23:34 UTC (2h49m after the upgrade launch) and read
95.1% when the report was filed at 01:47. No blockchain reset. The engine's filter cursor went
backwards three times: 70,000 blocks when the §35 rule declared the 99.904% final-batch park synced
and the idle rule stopped the service with the SDK's persisted height that far behind; 242,000 on an
in-process engine restart whose origin the truncated engine log does not show; and back to
**2,167,092** on each of the two process-fresh launches — A-03's per-launch re-walk on mainnet,
where the anchor is this wallet's earliest DashPay contact. Every fresh launch re-walks 377,000
filters from there, with the durable pending-sweep set (67,658 scripts) seeded into the lowest
batch, so the sweep adds **up to 800 block fetches per 5,000-filter batch** (Andrei's wallet: 93)
on top of the scan's own 438–817. A pass is ≈ 2.7 h of
foreground-service time at 2 GB PSS; the process died at ~01:43 with 2.3 GB PSS (cause not in the
evidence) and the next launch started the pass again.

**What applies.** A-03 (dashpay/platform#4302) for the anchor; §34 / rust-dashcore#1016 for the
sweep's share of the block fetches and the final-batch park — **already in int22, i.e. in
`12000018`**, which Joel has not run; the SDK's `synced_height` persistence granularity under the
park. The 50-minute deadlock in the middle is A-02 (fixed on `fix/sync-process-stalls`). On
`12000018` the sweep, its re-seeded 67,658-script set and the park are gone; the per-launch
re-walk from 2,167,092 is not, and on this wallet it is still tens of thousands of block fetches
per launch. Needs #4302 to finish; int22 makes each attempt about half the length — and carries
the recipe's §5 fund loss (plan §34.6, reproduced again on this build in §39.10: 0.44884976 DASH
across seven change outputs after one mid-sync kill): a session that derives new scripts and dies
before the next start loses what those scripts received. Joel's process dies mid-scan.

**Also seen.** The per-minute memory line's `Debug.getPss()` blocks the main thread for 5 s or
more on a 2 GB process — four in-app ANR-watchdog dumps in one evening. App-side; plan §39.6.

### D-037 — Historical fully-spent transactions get no history row

*Reproduced on device: yes (S8, S2, S11, S10).*

The display cache and its own completeness check both walk **txos** instead of **transactions**, so
a transaction whose outputs are all spent produces no history row. Measured: 111 of 1,601 then 458
of 3,001 rows missing on the synthetic wallet (including its only incoming tx); 2 receives worth
**0.538 tDASH** missing on the reference wallet; 1 of 22 on a small wallet. Racy, worse on bigger
wallets, and **not repaired by a rescan**.

### D-041 — Inflated balance persisted after a process kill during replay

*Reproduced on device: yes (S2 ×2, S11).*

A single process kill during replay leaves the home screen showing a **~22× inflated balance**
(2,319–2,363 against an actual 107.08) with no syncing indicator. The bad value is persisted as
`lastKnown`; only a force-stop clears it.

**Root cause found 2026-09-19 — and it is worse than reported. Not fixable in this repo.**

Reproduced on a Samsung on the job-flower wallet. The balance climbed to **3,024.56 DASH against a
true 107.08** — 28x — and, unlike the QA report, **stayed wrong after the sync completed**, with no
process kill involved at all:

```
19:18:43   107.08 DASH   <- app start, correct
19:29:34   110.07        <- replay to tip begins
19:31:07  3024.56        <- settles here, past SYNCED
```

The SDK's own database is correct throughout: 811 unspent txos totalling exactly 107.08173522, with
`isSpent` and `spendingTxid` agreeing on every row. And this repo does no arithmetic — 
`CutoverUiDataService.currentBalanceSplitDuffs` publishes whatever `wallet.balance()` returns. The
wrong figure comes from the SDK's in-memory native ledger, which applies block events on top of an
already-correct rehydrated balance and so double-counts anything re-walked.

That explains the report's "only a force-stop fixes it": a restart rehydrates from the table, which
is why every app start above reads exactly 107.08.

**Assignment note.** This is `dash-spv` / `platform-wallet`, below the FFI, so iOS is equally
exposed and an Android-side fix can only be a workaround — reconcile against the `txos` table once
synced, or refuse to publish until then. Note the `l1Synced=false` suppression proposed in
`DASHJ-KILL-LIST.md` §1a is necessary but no longer sufficient, because this instance was published
with `l1Synced=true`. Full analysis in §1a.1 there.

Reproduction: force a re-walk (the filter-stall watchdog's restart does it), watch the published
balance climb, confirm the `txos` table stays correct.

**2026-09-21 update — the SDK defect stands; the app-side containment is now proven on device.**
Two fresh restores and one upgrade-in-place on release builds, both devices:

- The mid-replay swing showed every time — Samsung 1.33 → 120.5 → 104.6 → 110.2 → … → 108.05,
  emulator 157.2 and 117.59 — and every one of those lines carried `l1Synced=false`, so the header
  held the last-known figure (or, with none, the live one) and never displayed an inflated value.
- The figure settled on `10805162729` duffs = **108.05162729** on both devices — one TXO (0.97 DASH)
  above the 107.08 recorded above, and equal to the ledger read directly that morning (812 unspent
  txos). A real receive, not drift; checked before calling it.
- The launch seed was written ONLY after the block pipeline drained — `PERSISTED … pipelineLagging=false`
  18 s after `lagging=false` on the Samsung, and read back exactly on the next launch — because the
  pipeline-lag veto now gates the seed directly (plan §35.2). This is the guard the original report's
  "persisted as `lastKnown`" was missing.
- No process-kill-during-replay case was run today, so the report's exact trigger is still not
  re-tested under the new gates; the mechanism it depends on (persisting a partial) is closed by
  construction, and the display path is observed.

Two observability notes for `DASHJ-KILL-LIST.md` §1a: publication is skipped when the value is
unchanged, so a wallet whose figure settles before the scan catches up flips to `l1Synced=true`
with no line — a suppression keyed on `l1Synced=false` alone would have hidden a CORRECT balance
permanently. `3e9fa5d9c` adds once-per-edge logs for the flip and the seed write so the decision is
read, not inferred.

### D-068 — CoinJoin funds stranded at cutover

*Reproduced on device: yes (S12).* **Funds stranded.**

Coins sitting in the CoinJoin account at cutover are **counted in the header but unspendable** —
the send engine sees 0.00. The mixed-funds migration prompt is hard-suppressed in code (D-063).
Reset and restore on 12.0.0 do not help. The only escape found was a **downgrade to 11.9.0**.

### SR-22 — Restore-from-seed date picker defaults to today

*Reproduced on device: yes (S4).* **Data loss.**

The restore date picker defaults to today. Confirming it restores the reference wallet with
**21.7 of 107.08 tDASH** — while reporting "Synced". The false "Synced" is what makes this
dangerous: nothing tells the user the wallet is incomplete.

### SR-05 — A stale wallet-wipe marker destroys a funded wallet

*Reproduced on device: yes (S4).* **Data loss.**

A stale — even empty — `wallet-wipe.pending` marker destroys a funded wallet on the next launch
**with no confirmation**. `WalletWipeState.complete()` itself documents leaving the marker behind
on a failed delete, so the precondition is self-inflicted.

### SR-03 / SR-04 — Receive and fresh address pinned to key #0

*Static, high confidence.*

Receive and fresh-address generation are pinned to key index 0. Root cause of **D-003**. Address
reuse, and a privacy as well as correctness problem.

### SR-07 — Global destructive migration on downgrade

*Static, high confidence.*

A downgrade triggers a global destructive database migration.

---

## Applies by deliberate decision

### SR-06 — Background-sync alarm never delivered

*Static, high confidence.*

Correct, and diagnosed to the bottom in [plan §32](upgrade-memory-and-sync-plan.md): the alarm is
**not refused** — `setInexactRepeating`'s window scales with the *repeat interval*, so an
`INTERVAL_DAY` repeat gets an 18-hour window and the alarm is simply never delivered. Forcing it
overdue does not compel delivery. Combined with §25 (boot start blocked on Android 15+), a v12
wallet has **no dependable background sync at all**.

§32.7 originally left the fix deferred because it changes wake-up frequency and therefore battery
behaviour — a product call, not a patch.

**2026-09-21 update — fixed on this branch** (`90851e8e9`; §32.8–32.13). The repeat interval now
honours the backoff already being computed (15 min just-used, 12 h recent, 24 h idle) instead of a
hardcoded `INTERVAL_DAY`, and the two schedulers no longer share one PendingIntent request code, so
neither silently replaces the other. **Delivered for the first time ever** at 13:08:33 on a Samsung
SM-S901U: `repeat=15min`, window `+11m15s`, `a background FGS start WAS permitted` — which also
answers §28 for a battery-exempt device. Still open: §32.13 (every service teardown cancels the
alarm), §32.11 (the replay guard can prevent it arming at all), and whether a NON-exempt device
permits the start — the Samsung turned out to be exempt (plan §36.4), so no device we have answers
that. Kept in this section for the history; the summary table counts it as fixed.


**2026-09-22 update (plan §36.6).** A second route to the same outcome, found on the Samsung: the
restart alarm was armed only inside onDestroy's cleanup, so a kill that pre-empts that cleanup — the
deferred task-removal kill, or `lowmemorykiller` on a cached process — left the app with no pending
alarm at all; 70 minutes with no relaunch on an awake, charging, exempt device. Fixed on
`fix/sync-process-stalls` by arming the alarm at service start as well. Device proof pending.

### SR-08 — Prod build seeds all SDK flags

*Static, high confidence.*

Accurate, but intentional. `DashPayConfig`'s KDoc records it: *"as of 2026-07-30 **all variants**
seed (the `BuildConfig.DEBUG` gate was removed) so a prodRelease store build is byte-for-byte
behaviourally identical to the QA builds — no divergence between what is tested and what ships."*
Per Brian's decisions.

The risk Pasta names is real and the comment states it outright: *"prodRelease exposes the SDK
paths to REAL funds by default"*. Pre-release gates still apply before a store rollout. Worth
revisiting as a decision; not a defect to fix.

---

## Retest before closing

D-056 and D-031 are marked addressed on the strength of the #1555 merge, but this branch
**rejected #1555's bind-evidence gates** under the §12 no-fallback policy (see
[plan §12.4](upgrade-memory-and-sync-plan.md)). Those gates were part of how #1555 reasoned about
engine liveness, so both should be retested on a build from this branch rather than closed on the
merge alone.

## A note on what the logs could not show

`5058e9194` attaches the app's own **logcat** to the support report. Until now `wallet.log` carried
only this app's slf4j output, so the framework's warnings about our own process — `SQLiteConnectionPool`
refusing the SDK database, ART GC pauses, StrictMode, ANR and low-memory notices — never reached a
report. Plan §34's database-lock lead is visible *only* there, which means **no tester bundle we
have ever collected could have contained it**, on any device. Some of the defects above may be
easier to diagnose from the next report than from any before it.
