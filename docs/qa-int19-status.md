# QA int19 (Pasta) — defect status against `fix/upgrade-memory-and-sync`

Source: QA testing by Pasta on an **int19** build. Assessed 2026-09-19 against this branch at
`5058e9194`, which now contains #1555 and #1559 via the merge in `1d9bbf659`.

"Addressed" below means *the defect's cause is fixed on this branch*. It does **not** mean
retested — see [Retest before closing](#retest-before-closing).

## Summary

| Status | Count | IDs |
|---|---|---|
| Fixed on this branch | 1 | SR-01 |
| Addressed by the #1555 merge | 2 | D-056, D-031 |
| Addressed by the pending master merge | 1 | D-011 |
| Still applies | 7 | D-037, **D-041** (root-caused to the SDK, see below), D-068, SR-22, SR-05, SR-03/04, SR-07 |
| Applies by deliberate decision | 2 | SR-06, SR-08 |

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
**6 min 32 s** while the SDK logged `wallet-event batch: folded=N` throughout with
`synced_height_persisted=None` — the scan was running the whole time and only the watermark WRITE
was blocked. It then persisted three heights in 130 ms and reached SYNCED unaided. A restart there
would have discarded real unpersisted progress to "fix" a healthy engine. The decision now also
requires the wallet-event stream to have gone quiet for the same window. See
[plan §34](upgrade-memory-and-sync-plan.md).

3 new tests; `L1ShadowSyncServiceTest` 111/111.

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

§32.7 leaves the fix deferred because it changes wake-up frequency and therefore battery
behaviour — a product call, not a patch. **Open decision, not an oversight.**

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
