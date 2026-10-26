# App Connect v2

This implementation follows the [Yappr App Connect profile](https://github.com/PastaPastaPasta/yappr/blob/staging/docs/APP_CONNECT_PROFILE.md)
and the iOS implementation in dashpay/dashwallet-ios PRs 1202, 1204 and 1210.
`wallet/test/app-connect-vectors.json` is the published interoperability fixture,
not a real user's keys or a pinned production app ID.

## Requirements

- The rebuilt `org.dashj.platform` Android SDK `5.0.0-SNAPSHOT`, including V1 key
  limits, DIP-13 application derivation, complete proof-verified contract-group
  scope and remaining-budget queries,
  identity updates and index-only document deletion.
- A non-mainnet network running protocol version 14 or later. Testnet and devnets
  must match the scanned request's network selector. The SDK must be configured
  for the actual devnet, not merely for testnet coin derivation.
- Wallet-owned unbound master and HIGH document-signing keys. New identities also
  include an unbound CRITICAL authentication key. Existing identities without it
  require a separate signing-key setup action that discloses the Platform-credit
  network fee. Setup never purchases tokens; purchase terms are refreshed and
  require a second, separate approval afterward. Background maintenance excludes
  CRITICAL authentication keys, including when other default keys are missing.
  Bound, disabled, expiring or foreign CRITICAL keys do not suppress explicit
  provisioning; the pinned SDK checks wallet ownership and these key properties.

CI builds the exact kotlin-platform commit in `platform-sdk-revision.txt`, and
that SDK pins its Ferment-patched Platform rc.7 commit. The resulting artifacts
are consumed from an exclusive, isolated Maven repository; old central snapshots
and the runner's Maven-local contents cannot satisfy Platform dependencies.

The wallet and Kotlin SDK use DashJ `24.0.0-SNAPSHOT` for networks running Core 24.

## Lifecycle

Only `dash-key:` v2 login requests are supported. Legacy v1 links are rejected
at parsing and again before session preparation or approval, including retries.
There is no synthesized legacy grant or legacy response-encryption fallback.
This does not affect the separate `dash-st:` v1 token-purchase format or
record-based revocation of previously registered application keys.

Preparation proves app/target ownership, encryption opt-in, identity hint and
request age. The approval sheet shows each exact grant, optional binding toggles,
budget and lifetime presets, app ID and callback host. Unknown app labels are
unverified; no label-based branding or silent approval is used.

Connection records have explicit loading, ready and unavailable states. A failed
audit read hides stale rows, blocks scanning and connection/purchase actions, and
shows Retry without implying that any on-chain access has been revoked. Only a
successful new observation restores the ready state.

Contract-group approvals require a complete proof-verified snapshot: owner,
admins, whole-contract members, document-type members and token positions. The
group owner must match the proved app-contract owner. An administrator-managed
group is supported; the approval sheet identifies who can expand it and lists
every current member. Consent explicitly covers future members and, for whole
contracts, future document types and tokens. Token purchase payments credit the
purchased token's contract owner, which can differ from the app owner.

The SDK verifies every page at one block, retries a mixed-block read up to three
times and rejects incomplete or oversized scope instead of truncating it. The
wallet compares the selected groups against the displayed snapshot before seed
access, immediately before identity-update submission, and before publishing the
response. Changed scope stops approval and refreshes the sheet for another
explicit approval. Proof failures remain failures. Pending/confirmed records and
revocation controls survive refresh errors; existing keys are not claimed to have
been rolled back. The approved owner/admin/member sets are persisted with the
exact grant keys. A newer block without a semantic change does not invalidate
consent. Revocation of recorded keys still uses the existing audit path.

Recovery also decodes the persisted approved group scope instead of treating a
newly fetched scope as the old approval. Changed or missing prior metadata shows
a scope-change warning and requires a new, initially unchecked acknowledgement
before Approve is enabled. The session enforces that acknowledgement before seed
access, independently of the UI, and checks that the stored approval still matches
the snapshot captured during preparation. A refreshed sheet resets acknowledgement.
Only an explicit approval can persist the new scope; opening or refreshing the
sheet does not overwrite the previous approval. Malformed stored metadata fails
closed, while recorded-key revocation does not depend on decoding this metadata.

### Reason For Restoring Group Approvals

The temporary group rejection added in `75cca51d7` addressed the valid concern
that owner-only validation could authorize a broader scope than the sheet showed
([review](https://github.com/dashpay/dash-wallet/pull/1604#pullrequestreview-5636671846)).
However, it also broke the actual Yappr v2 login: its primary CRITICAL key uses
contract-group bounds. Asking the app to substitute a single-contract binding is
not functionally equivalent for that CRITICAL profile.

Platform already had proof-verifying membership/admin queries; our Android bridge
discarded everything except the owner. The fix exposes that information and adds
informed dynamic-group consent and final scope checks. It does not bypass proof
verification, weaken the requested security level or change Yappr's QR protocol.
The SDK's `docs/contract-group-scope.md` documents pagination and JNI build-order
guarantees. The latter was added after verification caught a stale ARM64 JNI
wrapper being packaged before SWIG generation completed.

### Grant Lifecycle

Requested budgets are capped at 5 DASH per key without dropping a requested
limit or inventing an absent budget. Missing expiry defaults to 90 days; requested
expiry is capped at one year and must remain over an hour beyond proved block
time. Finite presets also cap an otherwise unlimited request; resetting to the
requested preset restores the original terms. The primary binding cannot be unchecked.

For each selected auth binding the derivation leaf is
`hash256(appEphPub || u8(originalBindingIndex))`. V1 uses `hash256(appEphPub)`.
The on-chain response lookup is always `hash160(appEphPub)`. Shared encryption
keys use DIP-13 sub-feature 7, rotation 0, and are never silently rotated.
Selection uses the proved current contract-key index, not numeric key order.
Disabled, unavailable, or foreign indexed keys are omitted; the index is checked
again before the encrypted response is published.

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
unknown registration is still absent, revocation reserves a missing authentication
key ID with a wallet-only key. Once confirmed, non-reusable IDs fence the entire
old atomic update. A race or failed fence retains the record for another attempt.
The explicit close action leaves that audit record intact and never emits a
declined callback or claims access was revoked. A fresh QR may be needed after expiration or a key-ID
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

Revocation permanently disables recorded auth keys, including expired-but-enabled
keys, and deletes recorded responses. Expiry alone is not revocation: authorized
limit updates can extend it. Shared
encryption keys remain. Records are retained as a revocation audit trail. Legacy
connections without key IDs offer an explicitly local-only removal with a warning
that Platform authorization is unchanged; real grant records cannot be removed this way.
Updates use at most ten disables and six additions per transaction, refreshing and
confirming each batch before the next. A partial failure retains the audit for retry.
Unresolved registrations do not block disabling other confirmed keys. An occupied
key ID with different immutable fields proves that atomic registration cannot
land; truly unresolved records remain pending rather than claiming revocation.
Disable/fence confirmation is polled three times with 2s/4s backoff. Complete
disconnect also checks for live app-scoped keys missing from local history.
Those keys are not automatically disabled: ambiguous recovered keys (including
shared profile bounds) require identity review. The connection remains partial,
with the surviving key IDs reported, rather than claiming all access was revoked.
Failed or absent target-ownership lookups never count as proof of unrelated ownership.
In the details UI, failed supplementary ownership or budget reads show an explicit
unavailable-check warning rather than hiding Revoke. Cancellation and wallet-scope
changes still propagate. The revocation audit itself remains strict: failed
ownership checks cannot confirm complete revocation. Earlier disconnect records
without app-wide revocation confirmation require one re-confirmation before the UI
reports them disconnected.
Request-specific revocation and superseding a session confirm only those request
keys. Neither operation confirms an app-wide disconnect or clears previous
partial-revocation evidence. Only a successful whole-app audit can clear that
evidence and mark every settled grant as app-wide revoked. The UI requires both
per-request permanent revocation and app-wide confirmation to show Disconnected.
Expiry also does not settle an unconfirmed registration: its recovery record and
wallet-only fence requirement remain until the old update is permanently blocked.
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

The scoped Connect authorization and purchase-replay audit survives the app's
wallet reset/wipe, while unscoped legacy rows and ordinary wallet settings are cleared.
Restoring the same network, wallet watching key and identity restores its audit;
another wallet cannot see or use it. This preserves existing authorizations and
unknown purchase outcomes rather than treating reset as revocation. Uninstalling
the app or clearing app storage still removes this local audit; this is not a
cross-device recovery mechanism, and cannot reconstruct history lost before this fix.

## Verification

Sensitive Connect jobs are cancelled on wallet lock, independently of the screen's
resumed lifecycle. Native submissions and signer callbacks recheck cancellation,
the initiating unlock generation and wallet/identity scope. Unlocking again cannot
reactivate captured authorization. Already-started network operations cannot be
unsent: grant recovery records and uncertain purchase receipts remain durable,
and interrupted operations never automatically resume or offer a success callback.

The focused test suite covers published parser/encryption vectors, malformed
TLVs and labels, u64 budgets, freshness, digest separation, consent limits,
ownership, the exact DashPay profile exception, encryption opt-in and PV14 gating.
It also covers uncertain approval/revocation, frozen retry terms, conflicting key
IDs, partial revocation policy, and approval fragment saved-state restoration.
On 2026-10-25, the focused regression run passed 499 wallet tests across devnet,
testnet and production (68 conditionally skipped), plus 17 SDK and three Rust
tests. Devnet and testnet APKs built, and their two native libraries matched the
verified Android SDK package byte-for-byte.

The user then completed a Yappr testnet login on the Pixel 9 API 36 AOSP emulator
using 11.9.2-connect. Logs showed IdentityUpdate submission at 21:38:05, a
successful identity-update response at 21:38:07, and system-contract response
submission followed by lookup through 21:38:09. No Connect approval, proof, enum
or crash errors appeared in that run. Missing Google Play services/Firebase and
AppsFlyer HTTP 403 errors were unrelated emulator/service issues.

This validates the tested login happy path, not all live renewal, revocation,
response cleanup, large-group or configuration-change race cases. Those paths
still need their own target-network testing before claiming exhaustive coverage.

The subsequent persisted-recovery scope fix passed 525 wallet tests across the
same three flavors, with 72 conditionally skipped and no failures. Its added
cases exercise new-session preparation from prepared/pending/confirmed/active
records, each authorization-scope field, missing and malformed saved metadata,
pre-seed acknowledgement enforcement, stale saved approval, refresh reset and
revocation while acknowledgement is unchecked. Both APKs and formatting checks
passed. The new recovery acknowledgement has not been exercised in a live funded
scope-change scenario; the earlier Pixel 9 login evidence remains a happy-path test.

### Foreground authorization boundary

Connect authorization now also requires a resumed Connections view, an interactive
device and an unlocked device keyguard. Pausing/stopping/destroying that view or
receiving screen-off synchronously invalidates its authorization generation and
cancels approval, purchase-key setup, purchase and revocation jobs. Returning to
the view permits another explicit action, never continuation with a captured old
signer. This does not change wallet-wide auto-logout settings or force a PIN on
every activity transition. System-only screen-off registration follows Android's
documented receiver exception and the wallet's existing AutoLogout pattern.

Already-submitted native transitions cannot be rolled back by cancellation.
Persisted grant and purchase receipts remain available for recovery; interruption
must not report successful revocation or offer a success callback. Lifecycle and
device tests complement the prior lock-flow tests, which did not exercise the
real foreground-to-background event boundary. Live interrupted/funded operations
still require emulator/device testing.

Connection list names explicitly mark QR branding as unverified and include the
app contract identity, including for older saved grants. Grant status transitions
refresh updatedAt without changing it on an ordinary refresh or identical state
write. The largest finite budget preset is 2 DASH, still capped by the requested
budget. CI exports SUPPORT_EMAIL only after the external SDK build completes.

The follow-up pins DashJ in the wallet and SDK to the published timestamped build
`24.0.0-20261024.220449-1`, keeping Core 24 support. Wallet resolution forces that
version across transitive dependencies as well. Gradle resolved it from the public
Central Portal snapshots repository without Maven Local. Its JAR SHA-256 is:

```text
1f2afb88ca62ab910554e3aa9bf40c240111fc3d3379da152bf59bc19e7828bd
```

This records the verified bytes, not checksum enforcement. Snapshot retention may
still remove that exact build; a retained release remains preferable long-term.
See the SDK's docs/dashj-build-pin.md for publication provenance.

Yappr's profile and callback consumer already support `ac=failed`:
https://github.com/PastaPastaPasta/yappr/blob/staging/docs/APP_CONNECT_PROFILE.md#callback
https://github.com/PastaPastaPasta/yappr/blob/staging/app/connect/page.tsx

After an approved request fails or is interrupted, the wallet retains the failure
status with the pending request. Only after the user ends recovery and revocation
is confirmed does a URL-origin request offer `ac=failed`. A successful retry offers
`ac=ok`; denying an untouched request offers `ac=declined`. Uncertain approval or
revocation never offers a terminal callback, and closing unresolved recovery does
not offer one. Scanner requests never launch a website callback. Failure status is
retained by the pending ViewModel across view recreation, not inferred from an old
grant after process death; durable grants still require confirmed revocation.

The idle expiry projection derives its displayed status timestamp from the last
authentication-key expiry across active grants when none remain live, ignoring
encryption-key expiry and preserving later persisted status changes. This is
derived on every projection, so it survives process restart without saving a
fabricated revocation or rewriting grant records.

The foreground/suggestion regression run passed 559 tests across devnet, testnet
and production, with 77 conditional skips and no failures. Lifecycle observer
tests run on Android API 28 and 34. Both debug APK builds and ktlintCheck passed.
This includes the review follow-up where app-wide revocation confirmation changes
the displayed status while the stored state remains disabled. Confirmation changes
refresh the timestamp; repeating identical settlement preserves it.

The three follow-ups passed 568 wallet tests across the same flavors, with 80
conditional skips and no failures, plus all 17 targeted SDK tests. Both APK builds
and ktlintCheck passed using the publicly resolved timestamped DashJ publication,
without the earlier Maven Local DashJ override. Runtime dependencyInsight confirmed
that the wallet and transitive SDK requests select the same timestamped build.
Live terminal failure callbacks and funded interruption/recovery still need
device testing; the earlier Pixel 9 success is not evidence for those new paths.

### Connection Scope Availability

An unavailable wallet or identity scope is not a valid scope with zero grants.
Scope lookup errors now propagate to the existing retryable unavailable state.
While identity initialization is pending, connection records remain loading;
initialization failure reports unavailable rather than ready/empty. Initialization
and identity state changes restart observation automatically, clearing stale rows
before a new scope is projected. Retry re-reads the current initialization state,
scope and records; it does not itself initialize identities or submit transitions.
Only successful scope and record reads can report a ready empty connection list.
Persisted grants and revocation evidence are unchanged by these UI transitions.
The scope-availability regression run passed 584 tests across devnet, testnet and
production, with 85 conditional skips and no failures. Both debug APK builds and
ktlintCheck passed, including a real repository/ViewModel integration test.
