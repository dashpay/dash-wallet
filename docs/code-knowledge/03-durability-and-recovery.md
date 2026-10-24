# 3. Durability, recovery and state machines

Android kills the process at any point. Many v12 blockers were a crash window between two writes, or an in-memory flag
that a restart reset to its safe-looking default. Typical cases: an armed rescan that was never persisted, a latch
cleared before its replacement existed, a marker written after the side effect.

## Order of operations

**3.1 Write the intent or recovery record durably *before* the irreversible step, and abort if that write fails.**
Irreversible steps are a broadcast, a row commit, a destructive cleanup, or exposing a recovered wallet.
- Gift-card purchase: build → await the order callback → persist the quarantine → broadcast (#1616).
- Shielded spend: `beginLocalSpend` writes the reconcile marker and drops the cache in one DataStore transaction.
  If that write fails, the spend is not submitted (#1621).
- Recovered wallet: `recovery-reset.pending` is written before the primary save, and a failed marker write = a failed
  save (#1576).
- Address-pool repair: write the rescan token before committing the repaired holes (#1614).
- Recovery material (a one-time invite key, a raw link) is persisted before awaiting any network sync (#1621, #1575).

**3.2 Never reroute after recording.** Once an order is keyed to tx A, a cutover during the callback must not build and
broadcast tx B (#1616 `recordedForDashjRoute`).

**3.3 Run refusal checks before any side effect, including "harmless" metadata writes inside factories.**
`restoreWalletFromSeed()` wrote the wallet creation date before `setWallet()` returned REFUSED, which moved the scan
start and hid older funds (#1577). Validate the backup network *before* overwriting the primary (#1576).

**3.4 Get every fallible prerequisite before an irreversible financial action.** Read the deposit address before
placing the purchase (#1570 Coinbase); refresh the Maya vault address and check `halted` right before each swap (#1469).
Write side-effect metadata only after the remote operation succeeded (#1504 tax tag, #1486 `KEY_CHANGED`).

## Obligations and debts

**3.5 An obligation is a durable debt, and it is cleared only on durable confirmation.** For example,
`armSpvRescan()` only rewinds the checkpoint in memory. The durable height is `WalletEntity.syncedHeight`. Clearing the
debt when "arm returned true" loses it on process death (#1576, #1571, #1617; 7 threads).
- Keep distinct keys for distinct obligations: mandatory `SDK_RECOVERY_RESCAN_OWED` and best-effort
  `SDK_GAP_WIDEN_RESCAN_OWED`, so that one path cannot clear the other's debt.
- Never run a destructive cleanup because of an in-memory "armed" result. Gate it on recorded debt
  (`oweSpvRescanForReset`).

**3.6 A failed cleanup keeps an owner that retries it.** Never drop the obligation marker before its replacement is
durable (#1616, 5 threads). Cancel the watch only after `commit()` succeeded.

**3.7 Retry caps may waive only re-syncable work, never a safety obligation.** A capped retry must not turn "unsafe"
into "done". Bound the retry *activity*, for example one arm per process, not the obligation (#1576).

**3.8 A gate that refuses work schedules its own retry, and `stop()` cancels it** (#1571: 30 s doubling to 5 min, one
in flight, carrying the stop generation).

**3.9 A persisted latch whose write fails is retried independently of the success path** (#1555
`CUTOVER_UPGRADE_BOUNDARY_CROSSED`).

**3.10 An explicit user retry gets a real attempt even when the automatic budget is exhausted** (#1607).

## Markers and latches

**3.11 Treat a marker as a state machine.**
- Never overwrite a pending or unverified marker. `begin()` on PENDING returns untouched; on RECOVERY_REQUIRED it
  refuses (#1577).
- Write atomically:
  1. create a temp file in the same directory;
  2. write it and fsync it;
  3. `Files.move(..., ATOMIC_MOVE)` onto the target.

  A failure at any step is a failed write. That includes `AtomicMoveNotSupportedException`, which is not a cue to
  fall back to a plain move. Whether `ATOMIC_MOVE` replaces an existing target is implementation-specific: it may
  throw instead. So a marker that may already exist is written with `REPLACE_EXISTING` as well (as
  `BackupReplacementState` does), or is never overwritten (rule above). This protects against a torn read and against process death, which is what
  the markers here need. To survive an OS crash or a power loss, also fsync the parent directory after the move:
  open it with `FileChannel.open(dir, READ)` and call `force(true)`, where the platform allows it.
- Version shared markers with a generation; `complete(dir, generation)` is a compare-and-delete under the writer lock
  (#1576 `BackupReplacementState`).
- Arm a marker at the single choke point every write goes through, for example `protobufSerializeWallet()`, not in one
  caller (#1576).

**3.12 Every latch that is set needs a matching, success-gated clear.** Clear degraded/recovery latches exactly when the
condition is resolved: after a successful wipe, or after the full save sequence of a restore (#1576). Resume normal
operation after a wipe only on confirmed success (#1581).

**3.13 Multi-key records are written in one transaction.**
- Use one `BaseConfig.editPreferences {}` or one `SharedPreferences.Editor ... commit()`.
- `BaseConfig.set()` is one `dataStore.edit` per key, so a crash between keys leaves a torn record (#1568, #1607, #1606,
  #1581).
- Renewals are conditional on the current record (compare-and-set), so a retry cannot resurrect a cleared record.

**3.14 A failed `SharedPreferences.commit()` still changes the in-memory map.** Restore the saved value, report the
failure, and make the retry real (#1606). Safety latches use `commit()`, not `apply()` (#1584 `ProtocolVersionLatch`).

**3.15 Keep "absent" sentinels.** Never substitute `now()` or another constant for a missing preference (#1581). The same
goes for WorkManager input data, which survives app upgrades: default a missing key by inference, never to a constant
that changes behaviour (#1584 `KEY_CONTESTED`).

## Wipe and wallet replacement

**3.16 Reset Wallet quiesces every writer at wipe *initiation*, down to the last side effect.** Cancellation is
cooperative and is not a fence. A lock-guarded generation check around the irreversible step is (#1581, 6 threads).
- Stop app-scoped queues first. Run the final enqueue under the queue lock only while the generation is current.
- Cancel WorkManager work by a shared tag; workers re-check "reset in progress" before each publish.
- Before erasing, fence the gate, cancel and join every wallet-scoped job, and re-arm in a `finally` (#1588, #1596).
- SDK secret erasure is a checked postcondition. An empty `_wallets` map does not prove removal (#1612).

**3.17 Scope every SDK-DB query, cache and persisted diagnostic to the active wallet id.** `dash-sdk.db` is shared by
the loaded wallets. `core_addresses` has no `walletId`, so join through `accounts` and bind `a.walletId = ?`.
- With zero or several wallets loaded, skip the probe, return unknown, and do not negative-cache it (#1591, #1559,
  #1601).
- Do not consume an install-wide cached money value unless you can prove it belongs to the loaded wallet. Removing the
  consumer was the fix that worked (#1621).

**3.18 Capture the origin wallet/session and an immutable context before the first suspension, and pass them through.**
- A wipe can install wallet B while a `NonCancellable` purchase is suspended (#1616 `WalletSession`).
- Capture a mutable field once and use that reference for both the guard and the action
  (`final Wallet walletToSave = wallet`, #1621).

## Status semantics

**3.19 "Synced" means a running engine whose committed wallet cursor reached the tip.** Scan/filter progress, a
persisted `BlockchainState` row, or display percent do not count (#1616, #1601, #1568). Keep lifecycle completion
separate from display completion.

**3.20 Deleting recovery state needs finality (ChainLock or 6 confirmations).** An ACK, a local commit, or our own SDK
store row is not network delivery (#1616).

**3.21 Distinguish definitive failure from uncertain submission** in the UI and in the persisted state. Uncertain blocks
a fresh spend; definitive failure offers the fallback (#1607). After an ambiguous send failure, offer no generic retry,
only an explicit "check status" (#1534).

**3.22 Notifications and alarms outlive the process.**
- Reconcile process-local "displayed" state with `getActiveNotifications` (#1590).
- Build each PendingIntent identity in exactly one place, and cancel all of them on wipe (#1568).
