# App Connect v2

This implementation follows the [Yappr App Connect profile](https://github.com/PastaPastaPasta/yappr/blob/staging/docs/APP_CONNECT_PROFILE.md)
and the iOS implementation in dashpay/dashwallet-ios PRs 1202, 1204 and 1210.
`wallet/test/app-connect-vectors.json` is the published interoperability fixture,
not a real user's keys or a pinned production app ID.

## Requirements

- The rebuilt `org.dashj.platform` Android SDK `5.0.0-SNAPSHOT`, including V1 key
  limits, DIP-13 application derivation, group-owner and remaining-budget queries,
  identity updates and index-only document deletion.
- A non-mainnet network running protocol version 14 or later. Testnet and devnets
  must match the scanned request's network selector. The SDK must be configured
  for the actual devnet, not merely for testnet coin derivation.
- Wallet-owned unbound master and HIGH document-signing keys.

## Lifecycle

Preparation proves app/target ownership, encryption opt-in, identity hint and
request age. The approval sheet shows each exact grant, optional binding toggles,
budget and lifetime presets, app ID and callback host. Unknown app labels are
unverified; no label-based branding or silent approval is used.

Requested budgets are capped at 5 DASH per key without dropping a requested
limit or inventing an absent budget. Missing expiry defaults to 90 days; requested
expiry is capped at one year and must remain over an hour beyond proved block
time. Presets only narrow those terms. The primary binding cannot be unchecked.

For each selected auth binding the derivation leaf is
`hash256(appEphPub || u8(originalBindingIndex))`. V1 uses `hash256(appEphPub)`.
The on-chain response lookup is always `hash160(appEphPub)`. Shared encryption
keys use DIP-13 sub-feature 7, rotation 0, and are never silently rotated.

Before broadcasting, the wallet stores a scoped pending grant with exact public
keys, approved limits, selected binding indices, and encrypted response triple.
No seed or derived private key is persisted. A master-signed update adds keys
and disables previous auth keys for the same identity/app/connection ID. Without
a connection ID, older keys are not disabled. All exact keys must be confirmed
before publishing the triple to system contract
`H8F9mP1BM55TE1ShsxPZHzhyinaMdY9bMmP85mkDhcJJ` / `loginKeyResponse`.
Previous responses are deleted using their complete original values, not replaced.

An uncertain registration retains its record. A retry re-derives the same keys,
preserves approved limits and settles from fresh identity/response reads. If an
unknown registration is still absent, revocation refuses to claim success while
it could still confirm. A fresh QR may be needed after expiration or a key-ID
collision. This implementation does not silently recover an expired QR by
publishing its response in the background.

Revocation disables recorded auth keys and deletes recorded responses. Shared
encryption keys remain. Records are retained as a revocation audit trail. Legacy
connections without key IDs remain visible but cannot claim on-chain revocation.
Tap a connection to query its current key budgets and expiry. Callback websites
open only after a separate, explicit host confirmation.

## Verification

The focused test suite covers published parser/encryption vectors, malformed
TLVs and labels, u64 budgets, freshness, digest separation, consent limits,
ownership, the exact DashPay profile exception, encryption opt-in and PV14 gating.
Live registration, renewal, revocation and response cleanup still require a
funded test identity on the target network before this draft is merge-ready.
