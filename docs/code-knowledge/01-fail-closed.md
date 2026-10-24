# 1. Unknown is not zero: fail closed

The most frequent blocking finding in v12 review: a value the code could not read was treated as an answer.
That answer was zero, `false`, "absent" or "not cut over", and it authorized a spend, a release, a delete or the
reuse of an address. 26 lessons across 20+ PRs come back to this one rule. #1607 had 7 blocking threads, #1616 had 5,
and #1576 had 5.

## Rules

**1.1 "Could not read" is its own state.** Do not collapse it into `null`, `0`, `false`, empty or "no row".
- Return a tri-state:
  - `observed / absent / unavailable` (#1616 `PendingPaymentEngine.observation()`);
  - `LockFound / NoLock / LookupFailed` (#1612);
  - `ABSENT / PENDING / UNKNOWN` (#1576 `RecoveryResetState.state(dir)`);
  - `ClaimResult { CLAIMED, ALREADY_PAID, STORE_UNAVAILABLE }` (#1608).
- Only an affirmatively read zero or absence may authorize a spend, a release or a delete. Unknown keeps polling, keeps
  the record and blocks the fresh action.

**1.2 Never make a safety decision with `BaseConfig.get()` / `observe()`.** `BaseConfig.data` catches `IOException`
and emits `emptyPreferences()`, so a failed read looks exactly like "key not set". This trap was hit in #1621, #1616,
#1608, #1607 and #1570.
- Use `observePreservingErrors(KEY)` or `cutoverCommittedOrThrow()` (and `getPreservingErrors()` / `getAllOrThrow()`,
  which arrive with the open #1607 and #1616) for
  any flag or marker whose absence *means something*. Examples: cutover ownership, reconcile/migration markers, recovery
  checkpoints, `USE_KOTLIN_SDK_*` flags that gate money paths.
- A `Flow<Boolean?>` where `null` = unreadable is fine. The UI shows "pending" and the gate stays closed.

**1.3 An SDK `null` has many meanings, and "zero" is not one of them.** SDK helpers return `null` (or a `Dash.ZERO`
placeholder) for engine/persistence disagreement, unsettled outputs, runtime not ready, and read failure.
- An unknown asset-lock status is *pending*, not terminal. Only `Consumed` (4) is terminal.
- A balance read before the shielded runtime is READY is unknown. Do not take `first()` of it as a baseline (#1607).
- An unknown shielded balance in a money total is `null`, and the header keeps "Syncing balance" (#1621).

**1.4 Malformed is not absent.** Validate persisted markers and stages on decode (#1607, 6 threads):
- Absent/blank is one case, malformed is another. Repair malformed under the owning lock, or rewrite it as an
  expired/uncertain stage; never continue it or rebuild a transaction from it.
- Validate per-phase field combinations, not "any non-empty phase string".
- Strict ID formats: wallet id is exactly 64 lowercase hex; a tx id is exactly `Sha256Hash.LENGTH` bytes before
  `wrapReversed` (#1603).
- Overflow-safe deadlines: compare elapsed time (`remainingMillis()`), never `startedAt + timeout`. Reject a clock
  skew explicitly: elapsed time alone does not expire a start that lies in the future. In #1607, a start more than
  60 s in the future is checked for, and reads as expired.

**1.5 `File.exists() == false` is not proof of absence.** It returns false on I/O and permission errors and for
dangling symlinks, without throwing (#1577, #1581, #1576).
- Confirm absence with `Files.notExists(path, LinkOption.NOFOLLOW_LINKS)`, or with `Files.readAttributes(...,
  NOFOLLOW_LINKS)` where `NoSuchFileException` → absent and anything else → unknown.
- Directories or symlinks where a marker file should be → `RECOVERY_REQUIRED`.

**1.6 Check every delete/clear result.** Mark completion only when the artefact is confirmed gone (#1576, 5 threads).
- `blockChainFile.delete()` ignored → a locked SPV store survived while the recovery marker was removed.
- A key-backup delete ignored → a "wiped" wallet came back from the backup on the next cold launch.
- Pattern: `complete()` returns whether the marker is actually absent; `confirm...Destroyed()` retries, confirms with
  no-follow NIO, and throws `IOException` naming the survivors.

**1.7 Do not reuse log-and-continue helpers on paths whose correctness depends on the write.** Example (#1576):
`backupWallet()` catches `IOException` and returns normally, so the replacement path got a throwing
`writeWalletBackup()`. Keep the lenient helper for its other callers; helpers return a success boolean that feeds the
completion decision.

**1.8 Never fabricate defaults for missing market, route or fee data.** Examples (#1504, #1493, #1469, all rated
Critical by coderabbit):
- `rate = 1`
- `tradable = true` / `halted = false`
- `route = MAYA`
- a fake fee on an actionable confirmation (#1616 `FAKE_FEE_FOR_EXCEPTIONS`)

Show "unavailable" and disable the action.

**1.9 Classify transient failures as transient.** "Protocol version unavailable" is UNREACHABLE (retry) and never
REJECTED (#1584). In WorkManager: `Result.retry()` with a cap, not `Result.failure()`, when nothing was spent.

**1.10 Walk the cause chain for typed exceptions.** Parsers wrap them: for example `PaymentIntentParserException`
wraps `PaymentProtocolException.Expired` (#1616).

**1.11 Name known pre-commit failures explicitly as "not broadcast".** For example `Wallet.CouldNotAdjustDownwards`
and `DustySendRequested` in `isCompleteTxFailure()` (#1608). Do not rely on the exception hierarchy staying the same.

**1.12 At native boundaries catch `Exception` + `LinkageError`, never `Throwable`.** `Sdk.initialize()` can throw
`UnsatisfiedLinkError` (#1584). Cancellation and other VM errors must still propagate.

**1.13 When an accessor starts throwing "unavailable", fix every consumer.** #1570 needed six rounds for this. Check:
- no `null` into navigation arguments (`SingleLiveEvent.setValue(null)` still fires);
- no `!!` on the result;
- every screen shows an error with a retry.

## Smells to grep for in your diff
- `?: false`, `?: 0`, `?: Dash.ZERO`, `?: emptyList()` on anything that gates money, deletion or address reuse
- `config.get(` / `.observe(` on a marker or a safety flag
- `.exists()` / `!file.exists()`
- `runCatching { ... }.getOrNull()` / `.getOrDefault(` around a read that a decision depends on
- `catch (e: Exception) { log...; return false }` in a predicate
