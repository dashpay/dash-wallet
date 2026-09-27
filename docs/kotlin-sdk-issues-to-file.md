# Kotlin SDK issues to file on dashpay/platform

Drafted during the Android wallet migration (dash-wallet#1507) — live-verified on testnet
(Galaxy S22 Ultra) unless noted. Ready to paste as GitHub issues.

## 1. `dashpay.syncState` throws Generic FFI error for unmanaged identities (should be null / typed NotFound)

Calling `Dashpay.syncState(identityId)` for an identity the wallet doesn't manage throws
`DashSdkError$PlatformWallet$Generic: requested platform_wallet::wallet::identity::state::managed_identity::ManagedIdentity not found`
(via `TokensNative.getManagedIdentity`). Consumers must message-match to distinguish
"not managed" from real failures. Expected: null or a typed NotFound. **Live-verified.**

## 2. Signing failures surface as Generic errors — no typed pre-broadcast signal

`Dashpay.sendContactRequest` failed with
`Generic: SDK error: Protocol error: Generic Error: no private key stored for <pubkeyHex>`.
Signing happens strictly pre-submission, so integrators need a typed error (e.g.
`SigningFailed`) to safely classify "definitively not broadcast" for fallback/retry logic.
Currently message-matching `"no private key stored"`. **Live-verified.**

## 3. Identity discovery attaches identities without signable keys (auth-window dependent)

`identityRegistration.discoverIdentities` verifies and attaches on-chain identities, and the
persistence bridge (`PlatformWalletPersistenceHandler`) tries to derive+store each private key —
but storage uses the auth-gated Keystore alias (`setUserAuthenticationRequired(true)`, ~30s
post-unlock validity) and **silently skips** on failure. A discovery pass running >30s after
device unlock attaches the identity with zero signable keys; subsequent wallet-bound writes fail
with issue #2's error. Requests:
- expose a repair op that (re)derives and stores keys **with public-key byte-verification**
  (the app now implements this client-side via `deriveIdentityKeyPair` + `WalletStorage`);
- make the key-alias auth policy configurable (app-supplied auth gate or non-gated alias),
  since host apps like the Dash wallet have their own auth model (PIN via SecurityGuard) that
  does not open the Android Keystore auth window. **Live-verified.**

## 4. `dpns.resolve` returns InternalError for unregistered names (should be null / typed NotFound)

`rs-sdk-ffi/src/dpns/queries/resolve.rs` returns `InternalError("Name 'x' not found")` for
unregistered names. Integrators message-match. Expected: null payload or typed NotFound.

## 5. `dpns.usernames` / search default limit is 10 when 0 is passed

FFI treats `limit=0` as "default (10)" — surprising vs. dashj semantics (default 100 for
list queries). At minimum document it; ideally align or make explicit.

## 6. accountReference derivation diverges from dashj (DIP-15 hygiene)

`rs-platform-encryption/src/account_reference.rs` extracts `u32_BE(ASK[28..32]) >> 4`
("iOS convention"); dashj-core 22.0.3 `BlockchainIdentity.getAccountReference` extracts
`u32_LE(ASK[0..4]) >> 4`. Same HMAC key/message, different 28-bit slice. Funds-safe (the
friendship xpub and derived addresses are byte-identical — verified), but if both stacks
author contact requests for the same (sender, recipient, account) channel they produce
different `accountReference` values → duplicate unique-index documents and rotation-detection
noise. Reconcile (confirm iOS's deployed convention first; the field may effectively be
per-platform today).

## 7. `createOrUpdateProfile` only accepts raw avatarBytes

Profiles carrying a precomputed `avatarHash`/`avatarFingerprint` (without raw bytes) can't be
routed through the SDK write. The Android wallet keeps such profiles on dashj. Accept
hash+fingerprint fields directly.

## 8. DPNS query projections omit document metadata

`dpns.resolve`/`search`/`usernames` results lack `$createdAt`, document id, alias records,
and preorder salt. Verified no current Android caller needs them, but parity consumers
migrating from dashj document reads will.

## 9. No identityVerify surface in the Kotlin SDK

The Android wallet broadcasts `identityVerify` documents (BroadcastIdentityVerifyWorker,
username-request verification links). The SDK has no equivalent op, so this write cannot be
migrated. Feature request: expose identityVerify document create/broadcast.

## 10. No external asset-lock intake via the unified JNI (blocks flagless L1 → shielded)

`shieldedFundFromAssetLock` only builds the asset lock from the SDK wallet's OWN Core UTXOs and
broadcasts it over the SDK's own SPV peers (`AssetLockFunding::FromWalletBalance`;
`FromExistingAssetLock` requires the lock to already be tracked by the AssetLockManager). The
only external-transaction entry (`asset_lock_manager_recover`, rs-platform-wallet-ffi
`asset_lock/sync.rs`) is not exposed through the unified JNI the Kotlin SDK uses. A host app
whose synced L1 wallet is dashj therefore cannot hand over a dashj-built lock — the Android
wallet ships an evidence-gated SDK-built pipeline instead (the shadow-SPV parity gate in
`ShieldedBalanceServiceImpl.shieldFromWallet`). Request: expose external asset-lock intake
(transaction bytes + IS/CL proof) through the JNI. **Live-verified (architecture).**

## 11. `createWallet` is not idempotent — no lookup-by-mnemonic; already-exists is an error, not the id

Re-binding the same mnemonic requires the host app to dedup by reading every stored phrase back
from `WalletStorage` (Keystore) and comparing. When that read transiently fails, `createWallet`
(same seed → same derived id) throws
`DashSdkError…Generic("Wallet already exists: <hex id>")` instead of returning the existing id.
The Android wallet now message-matches and extracts the 64-hex id from the error
(`walletIdFromAlreadyExistsError`). Requests: (a) a `findWalletByMnemonic`/lookup op, or
(b) make `createWallet` return the existing id (or a typed AlreadyExists carrying it).
**Live-verified.**

## 12. `shieldedFundFromAssetLock` lacks a chainlocked-only input constraint

The Rust side selects funding UTXOs from the SDK wallet's whole spendable balance. The Android
wallet's product rule is that only ChainLocked funds may be shielded — enforceable app-side for
display/amount validation (a chainlocked-only `CoinSelector` caps the amount), but the SDK's
internal coin selection can still pick a non-chainlocked UTXO for the lock itself. Request: an
input-selection constraint (e.g. `chainLockedOnly: Boolean` or a min-conf/locked filter) on
`shieldedFundFromAssetLock`.

## 14. Core-send broadcast rejection flattens to ErrorUnknown(99)

`PlatformWalletError::TransactionBroadcast` (the broadcaster's definitive "never reached any
peer" rejection, reservation released) reaches Kotlin as `Generic(nativeCode=99)` with a message
prefix, forcing integrators to message-match to classify it as safely-not-broadcast. A dedicated
error code would make the no-double-pay classification robust.

## 15. shieldedFundFromAssetLock coin selection only reaches one account (CoinJoin/other-account funds unspendable)

Live (S22, testnet): a wallet with total balance 1.534 DASH (SDK get_balance == dashj, exact
parity) failed to shield with "Coin selection error: Insufficient funds: available 8999527
(0.09 DASH), required 20000000". The ~1.44 DASH gap is previously-mixed CoinJoin-derivation-path
funds. shieldedFundFromAssetLock's asset-lock builder selects only from the standard BIP44
account, so funds counted in the wallet balance but held on the CoinJoin (or any non-default)
account cannot be shielded. Requests: (a) asset-lock coin selection should span all spendable
accounts (incl. the DIP-9 CoinJoin account), or (b) expose a per-account spendable-for-asset-lock
balance so integrators can validate the amount and show the correct "available" figure — the total
balance overstates what's shieldable. Also: the error is a WalletOperation with the reason in the
message string; a typed InsufficientFunds error (with available/required) would let integrators
classify + display it without message-matching (relates to #14).

## 16. DIP-15 contact address pools never grow — the 21st payment from any contact is invisible

> **FILED** as dashpay/rust-dashcore#1032 (2026-09-16). The defect is in the `key-wallet` crate
> in rust-dashcore, not in platform, so it went there rather than on dashpay/platform. Note the
> root cause is narrower than first written: `AccountTypeToCheck` is a fieldless enum, so
> `extended_public_key_for_account_type` structurally cannot identify which contact account is
> meant. The fix belongs in `wallet_checker.rs`, which already holds the fully-keyed
> `AccountType`.

Live (testnet, two emulators, `rust-dashcore` rev `af88edf`): a DashPay contact chain watches
exactly 20 addresses, indices 0-19, and **never extends**. The 21st payment a contact sends
lands on index 20, which is never derived, so it is never matched by the filter scan and never
enters the balance. It is unrecoverable by rescanning, because the address does not exist
client-side. Money-visibility bug, not latency.

Three things compose:

- `key-wallet/src/wallet/helper.rs:612` — `extended_public_key_for_account_type` returns `None`
  for `DashpayReceivingFunds` and `DashpayExternalAccount`, commented "Currently not retrieved
  via this helper". So `key_source_for_account_type` yields `KeySource::NoKeySource`.
- `key-wallet/src/transaction_checking/wallet_checker.rs:357` — `if matches!(key_source,
  KeySource::NoKeySource) { continue; }` bails **before** `maintain_gap_limit`. Note
  `mark_address_used` runs *above* the guard, so used flags advance normally while the window
  does not. That divergence is what makes the bug hard to see.
- `key-wallet/src/managed_account/managed_account_type.rs:726` and `:745` — both DashPay pools
  are built via `single_pool(..., 20, ...)` with a **hardcoded literal**, not a named constant.
  `maintain_gap_limit` with `highest_used == None` targets `gap_limit - 1` = 19, which is
  exactly the observed pool.

Measured on a wallet with 14 contact accounts: every one sits at top index 19 regardless of
use. Driving six payments onto one chain advanced `isUsed` to index 5 while the top index
stayed 19. A sliding pool (`highest_used + gap_limit`, as `maintain_gap_limit` documents)
would have reached 0-25.

Requests:

- Make the checker reach `maintain_gap_limit` for the two DashPay pools. The material is
  already persisted — every contact account row carries `accountExtendedPubKeyBytes`
  (104 bytes) — and friend-chain receiving addresses derive from that xpub alone, so no seed
  and no device unlock is needed. This is unfinished wiring, not a cryptographic limit.

  Resolve it in `wallet_checker.rs`, **not** by teaching
  `extended_public_key_for_account_type` to return the contact xpub. That helper takes
  `AccountTypeToCheck`, which is a FIELDLESS enum: given only `DashpayReceivingFunds` it
  cannot say *which* contact account is being asked about, so there is no correct value for it
  to return. `wallet_checker.rs` already holds the fully-keyed `AccountType`, so either it
  does the lookup itself, or it passes that keyed value down — but the fieldless helper cannot
  be the place the decision is made.

  (An earlier revision of this section asked for exactly that helper change. The note at the
  top of this issue corrected the root cause; this bullet had not been brought in line, so the
  issue as filed still points implementers at an API change that cannot fix the defect.
  rust-dashcore#1032 needs a comment saying so.)
- Replace the hardcoded `20` with a named constant. Note `DEFAULT_CONTACT_GAP_LIMIT = 10`
  exists in `rs-platform-wallet/src/wallet/identity/crypto/dip14.rs:254` (quoting DIP-15's
  recommendation) but has **no consumer** — neither it nor `derive_contact_payment_address`
  is called outside its own module. Decide which value is intended and wire one of them.
- Consider whether `mark_address_used` should stay above the `NoKeySource` guard. Advancing
  used-state for a pool that can never be maintained produces a database that looks healthy
  while the watch set is stale.

Also affects `dashpayExternalAccount`, so a wallet stops tracking its own outgoing contact
payments past index 19.

**Reproduced end to end (testnet, 2026-09-16).** Twenty sequential contact payments filled
indices 0-19 and were all received. The 21st, `b837bcb2f4f50c29502cd32549b3c369f28157b4ead872b7f41351cebb121ab0`
(29,000 duffs to `yR3kpYnbcPipq4982WcEK2mCBrQXh7P39K`, block 1555216, 2 confirmations), was
never seen. With the receiving wallet's service running and synced through block 1555217,
its database holds zero `transactions` rows, zero `txos` rows, and — decisively — zero
`core_addresses` rows for the destination address. The address was never derived, so the
BIP158 scan could not match it. Coins are on-chain and spendable by the seed, but invisible
to the wallet permanently and unrecoverable by rescan.
