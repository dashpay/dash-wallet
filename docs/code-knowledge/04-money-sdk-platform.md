# 4. Money, fees, SDK, Platform, CoinJoin and shielded

## Amounts and fees

**4.1 Units in names, and the right scale.** 1 DASH = 1e8 duffs = 1e11 credits. Name variables `feeDuffs`,
`feeCredits`, `approvedAmountDuffs`. Test fixtures in duffs: 0.15 DASH = `15_000_000` (#1584 found a 10× comment error
and a test that modelled 150 DASH).

**4.2 Unknown amounts are `null`, never `Dash.ZERO`.** A zero placeholder added to a total is a wrong number shown as
settled (#1621). No partial sums: a mixing group with a member missing is unknown, not the sum of the present members
(#1597). Do not keep permanent negative caches built from incomplete evidence (#1595 `feeUnrecoverable`).

**4.3 Make "funded ≤ confirmed" hold by construction.** #1584 threaded an approval cap through every retry path, and
the bot found ten paths that lost it. The cap was reverted. A persisted monotonic `ProtocolVersionLatch` ("protocol
versions only ever increase") closed the risk at the source.

**4.4 Never cache the Platform protocol version for funding.** Read it live and latch it monotonically (#1584).
Do not trust an SDK "success" that can be its own cached fallback. The pinned `refresh_protocol_version()` returns the
seeded 13 on failure. Read the native source of an FFI call before treating its return as authoritative.

**4.5 Strict fee resolution only where the spent amount depends on it.** `ContestedUsernameFees.resolved()` throws;
`current()` falls back for display. Shielded denomination selection maps 0.15 and 0.25 to the same denomination, so it
uses the conservative read (#1584).

**4.6 One fee convention, list and detail in sync.** Non-contact sends show the fee-free principal. Contact sends keep
the engine's fee-inclusive signed net. Details read the walker's record (`SdkTxStoreWalker.recordFor`) instead of
inferring. Fee-only repairs run before every early return for decorated rows (#1595).

**4.7 Combine sibling events so the result does not depend on their order** (#1595 OUTGOING+INCOMING → INTERNAL).

**4.8 Use a dry run or preview only for the exact send it was built for.** That means payee, amount, engine mode and
checked balance. One immutable `DryRunResult` is published by the latest run, and a mismatch throws
`DryRunOutdatedException` (#1608).

**4.9 Convert fees only with the rate of the fee's own asset, and keep units consistent.** Do not mix percent and basis
points (#1489, #1429). `x?.rate.toString()` gives the string `"null"`, so the elvis default never runs. Compare
denominations numerically, not as locale-formatted strings (#1433).

**4.10 Release reservation and lock mirrors on every terminal send outcome.** That includes `Broadcast`; only
`Ambiguous` keeps the locks (#1535).

**4.11 An advertised maximum cost covers every funding route, including shielded denominations** (#1584).

## SDK (dash-sdk-kotlin / platform-wallet-ffi) and dashj coexistence

**4.12 Pin the wallet identity and destination through the native build.**
- Never build on "whichever wallet is current" (`values.single()`).
- The destination must be the building wallet's own address, checked again under the send lock before the final build
  (#1607).

**4.13 dashj output locks do not stop SDK input selection.**
- Gate every SDK funding entry point: ordinary, deferred, Maya, asset lock.
- Treat the reservation's lifetime as block-based (it expires after 24 blocks; distrust it at 16). Measure it from the
  preview build height (#1616).
- Fail closed on app-locked outputs with the shared dashj ∪ `SeamOutputLockRegistry` check (#1607).

**4.14 After cutover, never fall back to the held (frozen) dashj wallet for an advertised address.** Throw
`ReceiveAddressUnavailableException` instead (#1570). On a restored wallet the dashj address can be the already-paid
index 0.

**4.15 Self-transfers never go to the advertised receive address.** Unshield, sweep and CoinJoin combine use
`unadvertisedDestinationLive()`, an SDK internal-chain address, and fail before broadcast if none exists (#1570).
Known SDK gap: the change allocator does not reserve that address, so ordinary payment change can land on it. Deferred to
the SDK.

**4.16 Address discovery windows come from dashj's *issued* frontier**, not its last paid address
(`freshReceiveAddress()` issues without payment). Use issued+100, with a floor of 100 and a cap of 1000. Widened
families keep 1000 (#1614).

**4.17 Reaching the tip proves history only for scripts that were watched during the scan.** Late-registered contact
accounts and silently failed fetches leave holes (#1608).

**4.18 SDK rescans are durable debts** (see 3.5). Deleting dashj blockstores does not reset the SDK watermark.

**4.19 Parity diagnostics compare only after the SDK's committed height reaches dashj's comparison height.** Use the
same txid-membership rule as `SdkTxStoreWalker` (#1601). Compare only like with like (a confirmed SDK height on both
sides), and count "could not compare" separately from "equal" (#1605).

**4.20 Production SDK migration flags are pinned ON in prodRelease** (`productionFlagOverride()`). The one deliberate
exception is `USE_KOTLIN_SDK_L1_SEND`, which is pinned OFF. A policy that ignores persisted
flags must apply to reads, observations and setters, at every consumer (#1578).

**4.21 In dashj, turning a sync flag off stops outbound requests, not inbound admission.** Check that something still
drains what is admitted (#1600, ISLocks).

## Platform / identity / DashPay

**4.22 Never collapse a failed Platform read into "absent".**
- Carry an explicit retryable outcome, and keep positive evidence once you have it (#1564 contender check).
- `PlatformRepo.getVoteContenders()` converts exceptions into empty maps, so do not trust "empty" from it.

**4.23 Platform's `AssetLockAlreadyConsumed` is unauthenticated.** It is never success, and only pauses automatic
retries. Only the SDK's own Consumed row settles a lock (#1607).

**4.24 After cutover, a username retry for an existing SDK identity is name-only.** Never go back through
`CreateIdentityService` (#1616). Every retry route loads the persisted requested names, including a requested instant
secondary name (#1626).

**4.25 Recovery of already-committed funds runs before, and independently of, fresh-funding preconditions** (#1584).

**4.26 Paged Platform queries over many identities must not let one saturated identity (100-doc limit) starve the
rest** (#1602).

**4.27 Validate external IDs before conversion and before batch work.** One malformed `TxMetadataItem.txId` must not
abort the whole prefetch (#1603).

**4.28 DIP-15: one address per contact payment.** Exclusions survive restart, pruning, retained intents and recovery.
Mark the address used when `SdkWriteResult.Broadcast` arrives, before bridging (#1608).

**4.29 "Disconnect" must revoke, or the UI must not call it revocation** (#1569 DashConnect).

## CoinJoin migration and shielded

**4.30 Prove completion against the recorded transaction, not against global balances or account emptiness.** Every
recorded input must be spent by the recorded txid, and the persisted TXOs must show the output to the destination
(#1607).

**4.31 Journal intent before the native call, and persist what you need to replay.** Stages go
`SHIELD_PREPARING → SHIELDING` via an `onSubmitting` checkpoint. Signed bytes are written in the same write as the txid.
A missing lock row does not prove non-submission (#1607).

**4.32 Respect Core's 100-input asset-lock limit.** Consolidate in validated batches that always shrink (at least 3
inputs per batch) (#1607).

**4.33 After any shielded spend the balance is stale until SDK success evidence for a pass that carries its own balance
arrives.**
- Check the stale flag before READY.
- Never release the hold on a polled busy→idle edge, on Room re-reads or on wall-clock age (#1621).
- READY means "first pass done", not "this spend settled".

**4.34 Gate fresh money-moving actions with one policy, enforced in the service as well as in every UI entry** (#1607
`cutoverCommitted()`, per-route `MixedFundsRoutes`; #1607 is still open).

**4.35 Require PIN/biometric authentication before every fresh spend.** The SDK signs with a stored mnemonic resolver
(DEVICE_BOUND), so an unlocked session authorizes nothing. Resuming an in-flight spend stays auth-free (#1607).

## Swaps and integrations (Maya / SwapKit / DashSpend / Coinbase)

**4.36 Validate chain addresses and payment URIs fully.** That means checksum, prefix, empty value, exact identifiers and
chain-qualified parser keys (12 threads, rated Critical). A second `USDT` registration silently overwrote the Ethereum
parser (#1468).

**4.37 Carry memo/tag into deposit instructions and QR URIs** (#1504).

**4.38 Wrap external parse and network failures in the swap/commit flow into a Failure result, not a crash.** Use
`toBigDecimalOrNull`, and guard `maxOf` on possibly empty lists (#1489, #1468).
