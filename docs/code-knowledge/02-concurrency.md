# 2. Coroutines, concurrency, cancellation

The second-largest group of findings, and the one that produced the longest review chains. #1570 had about 44
findings over ~25 rounds, #1621 more than 25, and #1571 about 14 rounds on one thread. In almost every chain, a fix for
one interleaving opened the next. The rules below are what finally converged.

## Ownership and staleness

**2.1 Guard every replace / cancel / latch of shared state with an ownership token checked under the same lock.**
The token is a generation, a lifetime, a session or a ticket. The race repeats: an obsolete actor passes its check, is
descheduled, resumes after a newer actor installed state, and cancels or overwrites it. Cancellation does not stop
synchronous code after the last suspension point. (#1632 `BindHealL1Starter.arm()`, #1621 reconcile/settlement, #1568
`externalStopGeneration`, #1576 marker generations.)

**2.2 Generations, not value equality.** `alice → bob → alice`: the first "alice" result passes
`currentLabel == "alice"` while the second lookup is still in flight (#1564). Use a monotonic request token captured per
lookup, and apply a result only while its token is current. The same applies to verdict publication
(`PendingInvitation(generation, data)`, #1615).

**2.3 A generation fence must cover every suspension between the snapshot and the publish.** Sample the generation
when the lock is taken, before any suspending work. Re-check it after every native step. Publish inside the lock that
checks it (#1571, #1605).

**2.4 Clear ownership markers inside `mutex.withLock`, not in an outer `finally`.** `withLock` releases first, so a
queued caller can set its marker in the gap, and your `finally` then clears it (#1591). Put a non-locking inline helper
(`holdingEngineStart { }`) inside the lock; only the owner clears.

**2.5 Take ownership (CAS) before the `try`.** A `return` inside `try` still runs `finally`, so a losing path must
never reach the winner's teardown. In #1568 the loser decremented `pendingDestroys` twice and cancelled the winner's
cleanup.

**2.6 Ownership-scoped clocks start at the claim and are retired by token** (`CleanupDeadlockClock` in #1571, now `PendingServiceCleanup` since #1577).

## Atomic claims and serialization

**2.7 Prevent duplicates with an atomic check-and-claim on a singleton.** Lookup-then-act is not enough. Examples:
- `SendCoinsActivity` can have several instances, so the invoice/contact-address claim lives on the `@Singleton`
  verifier/store, under its lock, and is persisted before `send()` (#1616, #1608).
- Read-and-clear a prepared payment atomically, so that a double tap cannot POST twice (#1531).
- For external orders, hold a single-flight guard from the tap through address read, purchase and navigation (#1570
  Coinbase).

**2.8 Serialize every mutation of a durable record under one mutex.** That covers scan/restore, cancel, settle,
release, reactivate and the removal worker. Re-read storage inside the lock and finish teardown inside it (#1616
`recordMutex`, 5 threads).

**2.9 When independent collectors feed one outcome, serialize the handlers and check the live source state inside the
lock.** A mutex only orders execution; it does not order when separately dispatched coroutines reach it (#1568
`SdkBindRetryService`, 7 threads).

**2.10 Recompute derived state on every input change, including `null`.** Serialize the read → derive → write. Route
publication through one consumer (`SerialRepublisher`: a conflated channel and a single coroutine, #1605).

**2.11 Check-then-act on main-thread-owned state runs as one snapshot on `Dispatchers.Main.immediate`.** Keep the heavy
part on IO (`snapshotWallet()`, #1594). The same goes for a final check before an irreversible act, e.g.
`endProcessIfStillBackgrounded()` before `Runtime.exit` (#1571).

**2.12 Moving a write off Main can create an ordering race.** For a tiny single-key preference, Main-thread
serialization is the correct trade-off (#1606: the move to IO re-enabled analytics after an opt-out).

## Cancellation

**2.13 Rethrow `CancellationException` before every broad `catch (e: Exception)`, including outer handlers and boolean
probes.** An inner rethrow does nothing if an outer catch takes it again (#1564 `RestoreIdentityWorker`; #1559
`isCutoverCommitted()`).
```kotlin
try { ... }
catch (e: CancellationException) { throw e }
catch (e: Exception) { log.warn("...", e); /* unknown / continue */ }
```

**2.14 Never `runCatching` around a suspend call.** It turns cancellation into a value (#1590).

**2.15 Must-complete work runs under `withContext(NonCancellable)`, and ownership is cleared only after success.**
Examples: funding plus persisting its recovery link (#1575); native stop (#1571); releasing a reservation in `finally`
until responsibility is handed off (#1616). `NonCancellable` does not survive process death; that is rule 3.1.

**2.16 Never wrap `Mutex.lock()` in `withTimeoutOrNull`.** The acquisition can complete while the timeout still wins,
and the lock leaks for the life of the singleton. Use `tryLock` polling up to a deadline (`tryLockWithin`, #1571).

## Jobs and lifecycles

**2.17 Every job a service launches on a long-lived scope is stored, cancellable from `stop()`, and joined before
destructive cleanup.** Check `!isCompleted`, not `isActive`: `cancel()` makes a job inactive before a non-cooperative
native call returns (#1568). A `CoroutineStart.LAZY` deferred is `isActive == false` until started (#1601).

**2.18 Never let a coroutine call `stop()` on the job it runs in** (#1568 watchdog).

**2.19 Periodic work is a loop owned by the service lifecycle, not a side effect of a change-gated `StateFlow`.** A
`StateFlow` stops emitting once the value stops changing (#1591 `durableHeightLoop`).

**2.20 A queue consumer must survive one item's cancellation.** Run each item as a sibling job and `join()` it (#1581
`TransactionMetadataSaveQueue`).

**2.21 A one-shot `tryEmit` on a `replay = 0` flow before its subscriber exists is lost, and `tryEmit` still returns
true.** Keep a pending flag and deliver it `onSubscription` (#1579).

**2.22 Work that must outlive a screen goes to an injected `@ApplicationScope CoroutineScope`, in an ordered queue if
the order matters.** Never `GlobalScope` (#1581, #1531).

**2.23 Catch inside `appScope` / `lifecycleScope` launches.** `SupervisorJob` does not consume an uncaught exception;
it crashes the process (#1616, #1608).

**2.24 No `runBlocking` in initializers, interceptors or field initializers** (#1489, #1417). An async restore must not
overwrite an explicit choice the user made meanwhile (#1504).

## Design advice that ended the long chains
For anything that holds money state across restarts and runtime sessions, or a cache shared across wallet bindings,
design these four pieces up front instead of adding them one per review round (#1621, #1570):
1. a durable marker;
2. one lock;
3. a generation or ticket;
4. protocol/SDK evidence for transitions.

Write the interleaving regression test for each race point (see [07-testing.md](07-testing.md)).
