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

After approval starts (or a persisted grant is recovered), Deny becomes Revoke
access. Website callbacks are offered only for requests that opened the app via
an ACTION_VIEW URL, never for requests from the wallet scanners. URL-opened
requests still require confirmation before opening the callback website.
A declined callback is offered only after this request's revocation
finishes; failures retain the request and error for retry. Approved terms stay
frozen. The approval fragment restores from saved state without treating view
disposal as denial, including when an in-flight operation subsequently fails.

Revocation disables recorded auth keys and deletes recorded responses. Shared
encryption keys remain. Records are retained as a revocation audit trail. Legacy
connections without key IDs remain visible but cannot claim on-chain revocation.
Unresolved registrations do not block disabling other confirmed keys. An occupied
key ID with different immutable fields proves that atomic registration cannot
land; truly unresolved records remain pending rather than claiming revocation.
Tap a connection to query its current key budgets and expiry. Callback websites
open only after a separate, explicit host confirmation.

## Wallet Operation Links

`dash-key:<payload>?n=d&v=2` and `dash-st:<payload>?n=d&v=1` are registered as
Android browsable links and are also accepted by the wallet scanners. `n=t`
selects testnet; the network must match the running wallet. Mainnet remains
disabled. These are custom URI schemes, not verified HTTPS App Links, and do
not authenticate the calling app.

`dash-st:` currently accepts only a single unsigned token direct purchase.
The native decoder rejects trailing bytes, extra operations, grouped actions,
fee increases, signed payloads, and mismatched token IDs. Other operations,
including legacy login key registration, are rejected; login remains one QR.
The wallet displays the payer identity, token and contract IDs, quantity using
proof-verified token decimals, and maximum cost in Platform credits. Approval
rechecks the wallet/identity and denomination, then rebuilds the operation with
a fresh nonce and the wallet's unbound CRITICAL key. Incoming bytes are never
signed. Network fees are additional to the purchase-price ceiling.

Before submission the wallet persists a scoped receipt. Neither an SDK error
after submission starts nor an affected-state proof justifies retrying a credit
debit. The UI reports submitted or outcome unknown, not proof of execution,
and the same request is blocked across restarts. Denial before approval never
signs or broadcasts anything. Tips, key revocation and limit raises via
`dash-st:` need their own parsers and consent flows; they are not yet supported.

## Verification

The focused test suite covers published parser/encryption vectors, malformed
TLVs and labels, u64 budgets, freshness, digest separation, consent limits,
ownership, the exact DashPay profile exception, encryption opt-in and PV14 gating.
It also covers uncertain approval/revocation, frozen retry terms, conflicting key
IDs, partial revocation policy, and approval fragment saved-state restoration.
Live registration, renewal, revocation and response cleanup still require a
funded test identity on the target network before this draft is merge-ready.
