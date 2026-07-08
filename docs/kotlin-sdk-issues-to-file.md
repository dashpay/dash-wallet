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
