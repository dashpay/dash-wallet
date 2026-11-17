# DashConnect: moving from kotlin-platform to the Kotlin SDK

Status: plan, 2026-10-28. Branch: `docs/dashconnect-sdk-migration` (based on `feat/kotlin-sdk-phase-1`).

Updated 2026-11-12 for SDK 5.1.0-int36. `feat/kotlin-sdk-phase-1`, and so this branch, pins
`dashSdkVersion = '5.1.0-int36-20261106.165805-1'`. The `dash-st:` decoder (PR-1,
`StateTransitionParser` from v5.1-dev `97f51a2bb`, Rust #4273/#4584) is in the int36 AAR, so the int32 backport is
no longer needed. PR-1 is available but unverified: nobody has run it on a real `dash-st:` link or on the test
vectors, and it closes only once phase 0 shows it exposes the agreed price as a typed field and parses the vectors
correctly. PR-2 and PR-3 still block. Key registration through
`BlockchainIdentity.addMissingKeys` and the identity-key backfill are now covered too.

Updated 2026-11-17 for recent `dashpay/platform` PRs (see
[Upstream status](#upstream-status-2026-11-17)). int36 is built from `v5.1-dev @ 9766bab24a` and already has
#5491 (`fetchContractKeys` returns the proved keys, so PR-4 is probably not needed) and #5649 (the SDK derives
DashConnect app keys). PR-2 shrinks to a JNI binding of #5421 plus the member query. PR-3 still has no PR.

Updated 2026-11-17 again from how dashwallet-ios does DashConnect (`develop` @ `b2f006996`, SwiftDashSDK pinned
to platform `dabbec09c9`; see [How dashwallet-ios does it](#how-dashwallet-ios-does-it-2026-11-17)). The Rust for
PR-3 already exists (#5715, which returns `blockTimeMs`), so PR-3 becomes a Kotlin/JNI binding. New **PR-6**: the
response document is index-only, so it is deleted by its values (#5419), which Kotlin has no binding for. PR-2 can
shrink to the #5421 binding alone if Android adopts iOS's consent model (an open question). PR-5 is probably not
needed for the calls it named.

Updated 2026-11-17 with the maintainer's answers to the three open questions (see [Decisions](#decisions-2026-11-17)).
HashEngineering writes the Kotlin/JNI bindings for PR-2 (#5421), PR-3 (#5715) and PR-6 (#5419). Android adopts
iOS's contract-group consent model, so PR-2 is the #5421 binding alone and there is no member query. DashConnect does
not run before the cutover: one SDK implementation, no legacy path, and the kotlin-platform calls go in the same
change.

DashConnect v2 (merged from #1604) sends every Platform read and write through the legacy
kotlin-platform SDK (`org.dashj.platform.*`, `libsdklib.so`, dpp `5.0.1`). The new Kotlin SDK
(`org.dashfoundation.dashsdk`, `dash-sdk-android` 5.1.0-int36) is not called by any
DashConnect code. This plan moves DashConnect onto the new SDK and marks the points where
`dashpay/platform` needs a PR first.

## Decisions (2026-11-17)

The maintainer answered the plan's three open questions, and settled how DashConnect looks before the cutover:

1. **Bindings.** HashEngineering writes the Kotlin/JNI bindings: PR-2 (#5421), PR-3 (#5715) and PR-6 (#5419).
   PR-1, PR-4 and PR-5 stay as they are; they depend on the phase 0 checks.
2. **Contract-group consent: the iOS model.** The wallet reads the group's owner and `adminIds` only (#5421). A
   group with admins, or whose admins can't be read, is treated as administered, and the approve sheet warns that
   others can add contracts the key will act in, as iOS does. There is no member query. Android's consent code and
   approve sheet move from listing members to owner/admins plus that warning (see
   [Phase 4](#phase-4-move-reads-then-writes)).
3. **DashConnect does not run before the cutover.** There is one SDK implementation and no legacy path, and the kotlin-platform calls in
   `ui/more/connections` go away in the same change that ships it. The cutover itself will eventually be removed or
   greatly simplified, so this gate is temporary: it is a single check that is easy to delete (see
   [Phase 2](#phase-2-one-sdk-implementation-and-the-cutover-gate)).
4. **Before the cutover DashConnect is hidden** (decided 2026-11-17). Its entry points are hidden entirely: no
   disabled row and no explanatory copy. So a pre-cutover state needs no design and no new strings, with one
   exception (decided 2026-11-17): a DashConnect link opened from a browser or another app shows a short toast that
   the link isn't supported yet ([Phase 2](#phase-2-one-sdk-implementation-and-the-cutover-gate)). No existing
   string fits: `send_coins_error_not_supported` (`wallet/res/values/strings.xml:79`) is about payments and funds,
   `dash_connect_unavailable_message` (`wallet/res/values/strings_app_connect.xml:4`) gives the wrong reason, and
   `invalid_link` (`wallet/res/values/strings-dashpay.xml:430`) says the link is invalid. So the toast needs one new
   string in every locale, with its wording taken from the designer's Figma copy, not written by us.

## Scope

All Platform calls live in `wallet/src/de/schildbach/wallet/ui/more/connections/`:

| File | Lines | kotlin-platform use |
|---|---|---|
| `AppConnectSession.kt` | 857 | Almost all of it: reads, writes, signer callbacks |
| `PlatformDashConnectRepository.kt` | 212 | `platform.names.getByOwnerId` for the display name |
| `AppConnectKeyState.kt`, `AppConnectGroupConsent.kt`, `AppConnectPlan.kt`, `IdentityUpdateBatches.kt` | 429 | Types only: `IdentityPublicKey`, `Identifier`, `Purpose`, `SecurityLevel`, contract bounds |

Outside that folder, DashConnect also reaches two wallet services that sign through the old
SDK and dashj: `IdentityRepository.ensureCriticalAuthenticationKey` (key registration, see
[Key registration](#key-registration)) and `PlatformRepo.requireIdentityChainKeys` (the
identity-key backfill, see [Signing](#signing)).

The protocol code (`protocol/`, about 840 lines: QR parsing, ECDH and AES-GCM, DIP-13
derivation) uses dashj and BouncyCastle, not an SDK. The UI, ViewModel and grant store
(`DashConnectConfig`) use neither SDK. None of this changes.

## Call map

The "new SDK" column was read from the int36 AAR's class signatures (javap). Behaviour is
inferred from names and parameter names and has not been run.

### Writes

| Today (`AppConnectSession.kt`) | New SDK | Gap |
|---|---|---|
| `dashsdk.platformMobilePutPutIdentityUpdateSdk`: add app keys at login (:426), disable them at revoke and shared-profile revoke (`submitIdentityUpdate`, :651) | `identity.IdentityUpdates.update(walletHandle, identityId, addKeys, disableKeyIds, signerHandle)` (also `addKey`, `disableKeys`, `updateKeyLimits`) | Today the MASTER key is chosen by the caller (:418, :647); the SDK call has no key parameter. Rust picks the MASTER key itself (`rs-platform-wallet .../network/update.rs:150`), as on iOS, so **PR-5** is probably not needed here. The new app keys must also sign: a delegating signer (phase 3). `IdentityPubkey` already carries `ContractBounds.ContractGroup` / `SingleContract` / `SingleContractDocumentType`, `totalBudget` and `expiresAt`. |
| `dashsdk.platformMobilePutPutDocumentSdk`: login response (:553) | `documents.DocumentTransactions.create(walletHandle, ownerId, contractId, type, propertiesJson, signerHandle, maxContestFund)` | Still no `signingKeyId` in int36; today the HIGH key is chosen (:529). Rust `select_document_signing_key` picks an AUTH ECDSA key at the document type's security level, and iOS relies on it, so **PR-5** is probably not needed here. |
| `dashsdk.deleteDocumentSdk`: response cleanup (:572), which passes the whole document (`doc.toNative()`) | `DocumentTransactions.delete(walletHandle, ownerId, contractId, type, documentId, signingKeyId, signerHandle)`: delete by ID only | **PR-6**. `loginKeyResponse` is index-only; iOS deletes it by its values (#5419, `platform_wallet_document_delete_by_values`), which has no Kotlin binding in int36. |
| `dashsdk.submitTokenPurchaseSdk` (:186) | `tokens.Tokens.purchase(identityId, tokenContractId, tokenPosition, amount, expectedTotalCost, signingKeyId, signerHandle)` | None. The wallet rebuilds the purchase from decoded fields, as v2 requires. |
| `IdentityRepository.ensureCriticalAuthenticationKey` (:206), which ends in `BlockchainIdentity.addMissingKeys` | `IdentityUpdates.addKey` / `update`, after the wallet's own missing-key check | See [Key registration](#key-registration). |

### Reads

| Today | New SDK | Gap |
|---|---|---|
| `client.getIdentity` (:84) | `queries.Identities.fetch` / `fetchPublicKeys` | None. |
| `client.getIdentityKeyRemainingBudget` (:797, :833) | `Identities.fetchKeysRemainingBudgets(identityId, keyIds)` | None. |
| `client.getCurrentContractKeyId` (:344, :539) | `Identities.fetchContractKeys(identityIds, contractId, purposes, documentTypeName)` | Returns the proved contract keys since #5491 (in int36; before it always returned `{}`). Probably equivalent; the phase 0 testnet check still decides it. **PR-4** only if not. |
| `client.getDataContract` (:261, :271, :705, :722) | `queries.Contracts.fetch` / `fetchWithSerialization` | None. |
| `client.getProtocolVersion` (`requireProtocolVersion`, :104; called at :120, :219, :315) | `queries.SystemQueries.refreshProtocolVersion` | None. |
| `platform.documents.get` (:512) | `queries.Documents.search` / `fetch` | None. |
| `platform.names.getByOwnerId` (repository :188) | `queries.Dpns.usernames(identityId, limit)` | None. |
| `client.getContractGroup` (:266) | The owner and `adminIds` read in the next row | **PR-2**. With the iOS consent model ([Decisions](#decisions-2026-11-17)) the wallet no longer reads the group's members; consent uses the owner and `adminIds` from #5421. |
| `client.getContractGroupOwner` (:709) | — | **PR-2** (binding only). #5421's proved `dash_sdk_contract_group_get_info` returns `{ownerId, adminIds, name?, description?}` in rs-sdk-ffi and Swift; it has no Kotlin/JNI binding. |
| `client.getPlatformTime` (13 calls) | — | **PR-3** (binding only). int36's `SystemQueries` has `epochsInfo` and `refreshProtocolVersion` but no block time. #5715's `platform_wallet_fetch_identity_public_keys` returns `blockTimeMs`, which is what iOS uses; it has no Kotlin binding. |
| `dashsdk.prepareTokenPurchaseSdk` (:121, :152) | `identity.StateTransitionParser.parse(ByteArray): ParsedStateTransition` | Available in int36, unverified (PR-1). No typed field for the agreed price yet. See below. |

`StateTransitionParser.parse` returns a `ParsedStateTransition` with `isSigned`, `complete`,
`ownerId` and `kind`. The kinds are `Batch`, `CreditTransfer`, `DataContractCreate`,
`DataContractUpdate`, `IdentityUpdate` and `Other`. `Batch.getTransitions()` returns
`ParsedBatchedTransition.Document` / `.Token`, and `Token` exposes `tokenId`,
`tokenContractPosition`, `action`, `amount`, `tokenCount`, `recipientId`, `dataContractId`,
`complete` and `details`. All of this comes from the class signatures; the parser has not
been run on a real `dash-st:` link or on the vectors in `wallet/test/app-connect-vectors.json`.

It is a parser only. The wallet still enforces the rules `prepare_token_purchase_sdk`
enforces today: unsigned, a single direct purchase, no trailing bytes (`complete`), and a
token ID that matches the request. That is wallet work in phase 4. The agreed total price,
which `prepareTokenPurchaseSdk` returns today, has no typed field on `Token`; it may only be
in the `details` string. PR-1 closes only when the parser exposes the agreed price as a
typed field and parses the test vectors correctly. If the price is missing, PR-1 becomes a
small Platform PR that adds it.

### Signing

Today every write passes a kotlin-platform `Signer`. Login (:392), revoke (:618) and
shared-profile revoke (:687) wrap dashj's `WalletSignerCallback`; the token purchase (:162)
signs directly with the key it finds on dashj's identity chain. Between them they sign with
the identity keys on dashj's BLOCKCHAIN_IDENTITY chain and with the per-app keys DashConnect
derives (DIP-13 sub-features 6 and 7). Since #5649 (in int36) the SDK can derive those app
keys too: `PlatformWalletManager.deriveConnectKey` (`ConnectSubFeature`, over
`IdentityNative.deriveConnectKeyV2WithResolver`), plus `deriveLegacyAppEncryptionKey` for the
pre-rotation path.

Because those signers only find keys issued on dashj's identity chain, approve (:313),
revoke (:604), purchase (:150) and `revokeSharedProfileKeys` (:683) run the identity-key
backfill before signing, through `AppConnectSession.readSigningIdentity` (:90). It calls
`PlatformRepo.requireIdentityChainKeys` (`PlatformRepo.kt:603`, which runs
`issueIdentityChainKeys`, :606, serialized by `identityChainBackfillLock`, :133).
`setupPurchaseSigningKey` (:193) reads the identity through `readIdentity` instead; its
backfill runs inside `addMissingKeys` only when keys look missing (see
[Key registration](#key-registration)). The backfill came from dash-wallet #1693, now merged into `feat/kotlin-sdk-phase-1`. An identity
the Kotlin SDK created reaches dashj without its keys issued on that chain.

The new SDK takes a `signerHandle`. `ffi.NativeSignerBridge` is public and abstract
(`signAsync(keyData, keyType, data, requestHandle)` and `canSignWith(keyData, keyType)`). A
host-side signer is registered with `SignerNative.createSigner(bridge)` and answers with
`completeSign`. The SDK's own `KeystoreSigner` already holds the identity keys the SDK
created, so DashConnect on the SDK path does not need the backfill. It does not know
DashConnect's per-app keys. An identity update that adds a key also needs a signature from
the new key, so a custom signer must still hold the app key's private key while a login
runs.

DashConnect stops signing through dashj in the change that ships it on the SDK, because the
kotlin-platform calls in `ui/more/connections` go away with it ([Decisions](#decisions-2026-11-17)).
From then on DashConnect does not need the backfill. The backfill itself can be removed only
once nothing else signs identity keys through dashj: DashPay still reaches it through
`addMissingKeys` (see [Key registration](#key-registration)) until that path moves too.

### Key registration

`IdentityRepository.addMissingKeys` (`IdentityRepository.kt:666`) registers the missing
DashPay keys (transfer, encryption) and, for a purchase, the CRITICAL authentication key.
It reaches the old SDK's `BlockchainIdentity.addMissingKeys` (:715), an old-SDK identity
update signed through `WalletSignerCallback`. Two paths reach it:

- DashPay's `upgradeIdentity` (:642).
- DashConnect purchase setup: `AppConnectSession.setupPurchaseSigningKey` (:193) calls
  `ensureCriticalAuthenticationKey` (:197, `IdentityRepository.kt:655`).

On the SDK path this becomes `IdentityUpdates.addKey` (or `update` for several keys at
once). The wallet decides which keys are missing itself, against the identity it has just
refetched, and builds the `IdentityPubkey`s from its own derivation.

That also removes the race in
[kotlin-platform#43](https://github.com/dashpay/kotlin-platform/issues/43): the old SDK
refetches the identity and decides from keys not yet issued on the dashj chain, so it can
register a duplicate paid key. Today's guard is the strict backfill before the check:
`addMissingKeys` calls `requireIdentityChainKeys` only when keys look missing
(`IdentityRepository.kt:680`), then checks again. The SDK path does not need it.

Upstream, #5683 (open, v5.2-dev) makes the SDK refetch the identity before it builds an
identity update. It is the same multi-device class of problem as kotlin-platform#43, on the
SDK side: another device may have added or disabled keys since the wallet last read the
identity. The wallet's own refetch-then-check above still applies; #5683 would cover the
SDK's half once it lands.

## How dashwallet-ios does it (2026-11-17)

dashpay/dashwallet-ios `develop` @ `b2f006996` (2026-11-17), on SwiftDashSDK pinned to platform `dabbec09c9`.
Almost all of it is in `DashWallet/Sources/Models/DashConnect/PlatformDashConnectDataSource.swift` (PDS below) and
`AppConnectV2Login.swift` (V2L). Most rows come from reading the Swift and Rust code; the exceptions are marked.
The Platform PR facts (#5715 and #5419 merged and in int36's base `v5.1-dev @ 9766bab24a`, neither bound in
Kotlin) were checked against the int36 sources.

| Operation | iOS | Kotlin int36 |
|---|---|---|
| Identity update (login, disconnect) | `ManagedPlatformWallet.updateIdentity(..., signer:)` → `platform_wallet_update_identity_with_signer` (PDS:2204-2209 login, PDS:3481-3490 disconnect). Rust picks the MASTER key (`rs-platform-wallet .../network/update.rs:150`). Wrapped in `signer.withAdditionalSigningKeys(derivedKeys)` (platform#4273) so the new app keys can sign; they are never stored. | `IdentityUpdates.update` reaches the same Rust, so MASTER is chosen the same way. `KeystoreSigner` has no `withAdditionalSigningKeys`, but `NativeSignerBridge` is public, so the wallet can write a delegating signer without an SDK change. |
| Response document create | No key ID passed; Rust `select_document_signing_key` picks AUTH ECDSA at the document type's security level. | `DocumentTransactions.create` is the same FFI. |
| Response document delete | Delete by values (`platform_wallet_document_delete_by_values`, #5419), because `loginKeyResponse` is `indexOnly` (PDS:2490-2498). The key is chosen in app code: `selectDocumentSigningKeyId` (PDS:3884-3934), enabled AUTH ECDSA, HIGH before CRITICAL, lowest ID. | Delete by ID only. Android passes the whole document to kotlin-platform `deleteDocumentSdk` today (`AppConnectSession.kt` ~:572). **New gap, PR-6.** |
| Platform time | No time endpoint. The proof-metadata block time of the keys read, `FetchedIdentityPublicKeys.blockTimeMs` (`platform_wallet_fetch_identity_public_keys`, #5715), becomes `snapshot.platformTime` (PDS:1054). It feeds request freshness, binding terms and expiry, and connection expiry. | No `blockTimeMs` anywhere; #5715 is not bound. |
| Contract group | Owner and `adminIds` only, through `dash_sdk_contract_group_get_info` (#5421). No member list. A group with admins, or whose admins can't be shown, is treated as administered (sentinel owner, V2L:49-52, PDS:725-730 and 816-823), and the sheet warns that others can add contracts the key will act in (`DashConnectPermissionText.swift:103-107`). | #5421 not bound (PR-2). Android's approve sheet lists members today (`VerifiedContractGroup` / `AppConnectGroupConsent`); it moves to iOS's model ([Decisions](#decisions-2026-11-17)). |
| Current contract key | Raw `dash_sdk_identities_fetch_contract_keys` (PDS:4117-4126), retried up to 3 times for a lagging node. | `fetchContractKeys` wraps the same call. |
| App keys | SDK `deriveConnectKey` (sub-features 6 and 7) and `deriveLegacyAppEncryptionKey`. The rotation search is app code (V2L:496-540): rotation 0, 1, 2… until a key is not on the identity, capped at that purpose's key count, then the legacy path on testnet/devnet only. | Same SDK calls (#5649). The rotation search is wallet work. |
| Contract bounds | Not in the proved keys read. Read from iOS's persisted key rows (PDS:3727-3757). | Not in the proved keys read either; read them from the SDK's persisted rows. |
| Token purchase (`dash-st:`) | `wallet.parseStateTransition` (PDS:265-318). Accepts only a complete batch with one token DirectPurchase, checks the owner and `calculateTokenId`, reads decimals from the live contract, requires a username, then calls `tokenPurchase(..., expectedTotalCost:)` (PDS:2665-2831). `signingKeyId` is left at its default (0) and the signer picks a CRITICAL key: **inferred from the comment at PDS:2809-2811**, not from the Rust. | Matching APIs: `StateTransitionParser.parse`, `Tokens.purchase`. |
| Outcome handling | Does not use #5823. Records an intent before submit; writes have a 60 s timeout and never retry (`AppConnectWriteExecutor`). A consensus error means Refused; any other failure leads to reading again (the identity, or the response document by `appEphemeralPubKeyHash`). A failed purchase is reported `outcomeUnknown`, with no retry. | `DashSdkError.consensusError` exists, so the same design ports. |

Takeaways:

- The remaining Platform work is mostly Kotlin/JNI bindings of Rust that already exists: #5421 (PR-2), #5715
  (PR-3) and #5419 (PR-6).
- For identity update and document create, the SDK chooses the key, on iOS and Kotlin alike. What DashConnect needs
  is a delegating signer that adds the app keys (wallet code, phase 3) and an app-chosen key for delete (which the
  by-values delete takes). PR-5 is probably not needed for those calls.
- #5715 also gives a typed keys read with limits and `expired_at_proof_time`. The Swift KDoc notes that freshness
  needs the SDK built with a proof-time tolerance; check that for the Android build.
- iOS's `fetchContractKeys` use supports PR-4 being unnecessary; the testnet check in phase 0 still decides it.
- Android adopts iOS's consent model, so PR-2 needs no member query ([Decisions](#decisions-2026-11-17)).
- The rotation search and the token-purchase checks are app code on iOS and will be wallet code on Android.
- iOS-style outcome handling (no #5823, no retry, read again on a non-consensus failure) ports with what int36 has.

## Platform PRs needed (`dashpay/platform`)

The wallet's base branch builds against `dash-sdk-android` 5.1.0-int36 (recipe
`HashEngineering/dash-wallet-qa` `recipes/platform/5.1.0-int36.yaml`). Each open PR below
needs to land on the line the next int build is cut from.

| PR | What | Owner | Where to start | Status upstream |
|---|---|---|---|---|
| **PR-1** | The `dash-st:` state-transition decoder for Kotlin. | — (depends on phase 0) | `identity.StateTransitionParser` (v5.1-dev `97f51a2bb`, Rust #4273/#4584). If the price is missing, add a typed agreed-price field to `ParsedBatchedTransition.Token`. | **Available in int36, unverified.** Not yet run on a real `dash-st:` link or the test vectors. **Closes only when the parser exposes the agreed total price as a typed field and parses `wallet/test/app-connect-vectors.json` correctly** (phase 0). If the price is missing, this becomes a small Platform PR that adds it. The wallet's own checks are wallet work in phase 4 (see [Reads](#reads)). |
| **PR-2** | Contract-group owner and admins for Kotlin: bind #5421. | HashEngineering | Bind #5421 (`rs-sdk-ffi/src/group/queries/contract_group_info.rs`, `dash_sdk_contract_group_get_info`, merged 2026-10-15, Swift-only today) in `rs-unified-sdk-jni` and `queries.*`. No new Rust. | Rust merged (#5421); no Kotlin binding. No open PR for the binding. The binding alone, since Android adopts iOS's consent model (owner and admins plus a warning, no member list; see [Decisions](#decisions-2026-11-17)). |
| **PR-3** | Platform block time: bind #5715 in Kotlin/JNI. | HashEngineering | #5715's `platform_wallet_fetch_identity_public_keys` returns `blockTimeMs` from the proof metadata; iOS uses it as `snapshot.platformTime` (PDS:1054). No new Rust. It also gives a typed keys read with limits and `expired_at_proof_time`. Freshness needs the SDK built with a proof-time tolerance (Swift KDoc). | Rust merged (#5715, in int36's base); no Kotlin binding. No open PR for the binding. |
| **PR-4** (only if needed) | The current proved contract-key ID for an identity, contract and purpose. | — (depends on phase 0) | kotlin-platform `app_connect.rs` `fetch_current_contract_key_id_with_sdk`. | Probably not needed: #5491 (in int36) makes `fetchContractKeys` return the proved keys, and iOS reads the current key from the same raw call (PDS:4117-4126, up to 3 tries). Still decided by the phase 0 testnet check. |
| **PR-5** (probably not needed) | Let the caller choose the signing key on `IdentityUpdates.update` and `DocumentTransactions.create`. | — (depends on phase 0) | If ever needed, it lands on #5588 (open: public, protocol-14-aware signing-key selection) and #5961. | Probably not needed for these two calls: Rust picks MASTER for the identity update and AUTH ECDSA at the document type's security level for create, on iOS and Kotlin alike. The real needs are a delegating signer (wallet code, phase 3) and an app-chosen key for delete (PR-6). Phase 0 still confirms the keys on testnet. |
| **PR-6** | Delete an index-only document by its values, for Kotlin: bind #5419. | HashEngineering | #5419's `platform_wallet_document_delete_by_values` (merged, in int36's base); iOS passes the document's properties and a `signingKeyId` (PDS:2490-2498), and chooses the key in app code (`selectDocumentSigningKeyId`, PDS:3884-3934). | Rust merged; no Kotlin binding in int36. `DocumentTransactions.delete` is delete-by-ID only, so the SDK path cannot delete `loginKeyResponse` until this lands. No open PR. |

The remaining Platform work is now mostly Kotlin/JNI bindings of Rust that already exists:
#5421 (PR-2), #5715 (PR-3) and #5419 (PR-6), all three written by HashEngineering. All three
block the migration. PR-1
is conditional on the phase 0 price and vector check. PR-4 and PR-5 are probably not needed
and depend on what the other checks in phase 0 find.

### Upstream status (2026-11-17)

| PR | State | What it means here |
|---|---|---|
| #5491 (romchornyi) | Merged 2026-10-23, in int36 | `fetchContractKeys` returns the proved contract keys (before: always `{}`). PR-4 probably not needed; phase 0 decides. |
| #5649 (PastaPastaPasta) | Merged 2026-10-29, in int36 | `PlatformWalletManager.deriveConnectKey` / `ConnectSubFeature` (`wallet/PlatformWalletManager.kt` :651–:717) derives app keys at `m/9'/coin'/5'/<subFeature>'/0'/identityId'/leaf'[/purpose'/rotation']`: 6 is session authentication, 7 is app encryption with a hardened `rotation'`. `deriveLegacyAppEncryptionKey` covers the old 8-level path. Answers phase 0's derivation question; see phase 0 and phase 3. |
| #5421 (PastaPastaPasta) | Merged 2026-10-15, rs-sdk-ffi and Swift only | Proved `dash_sdk_contract_group_get_info` returns `{ownerId, adminIds, name?, description?}`. PR-2 is its Kotlin/JNI binding. |
| #5715 | Merged, in int36's base | `platform_wallet_fetch_identity_public_keys` returns the keys and `blockTimeMs`. iOS's Platform time. PR-3 is its Kotlin binding. |
| #5419 | Merged, in int36's base | `platform_wallet_document_delete_by_values`: delete an index-only document by its values. iOS deletes `loginKeyResponse` with it. PR-6 is its Kotlin binding. |
| #5823 (QuantumExplorer) | Merged 2026-11-10, **after** int36 (needs an SDK bump) | Proven `Sdk::state_transition_outcome` and unproven DAPI `getRecentStateTransitionStatus`. Not one of the PRs above, and iOS does not use it (see phase 4). An option only. Kotlin bindings not checked. |
| #5588 | Open, v5.2-dev | Public, protocol-14-aware signing-key selection. The likely basis for PR-5. |
| #5961 | Open, stacked on #5588 | Don't pick a budgeted or expiring key for data contract create or update. |
| #5683 | Open, v5.2-dev | Refetch the identity before building an identity update; see [Key registration](#key-registration). |
| PR-2, PR-3, PR-6 | Rust merged | #5421, #5715 and #5419 are merged; no open PR binds them in Kotlin yet. HashEngineering writes the bindings. |

## Phases

### Phase 0: confirm the gaps (wallet, no Platform PR)

- Check `Identities.fetchContractKeys` against `getCurrentContractKeyId` on testnet (decides PR-4). Since #5491 it returns the proved keys, so PR-4 is probably not needed.
- Check on testnet which key `IdentityUpdates.update` and `DocumentTransactions.create` sign with. From the Rust and iOS, expect MASTER for the update and an AUTH ECDSA key at the document type's security level for create, which would mean PR-5 is not needed.
- Check that a delegating `NativeSignerBridge` can add the app keys for one identity update, the way iOS's `withAdditionalSigningKeys` does (phase 3).
- Check that after the cutover `KeystoreSigner` holds the identity keys of an identity dashj created before it, since `ConnectSigner` has no dashj fallback (phase 3).
- Check whether Android's SDK build has the proof-time tolerance that #5715's freshness check needs (see PR-3).
- Run `StateTransitionParser` on a real `dash-st:` link and on the vectors in `wallet/test/app-connect-vectors.json`, and check that it exposes the agreed total price as a typed field, not only in `details` (decides PR-1). PR-1 closes only if both hold; if the price is missing, PR-1 becomes a small Platform PR that adds it.
- The SDK does derive app keys (#5649, `PlatformWalletManager.deriveConnectKey`). Check instead that it derives the same keys the wallet derives today for sub-features 6 and 7, at the same rotation level, and that keys registered on the legacy (pre-rotation) path are still found through `deriveLegacyAppEncryptionKey`. If both hold, phase 3 can take app keys from the SDK.

### Phase 1: Platform PRs and SDK bump

Open PR-2, PR-3 and PR-6 (HashEngineering's bindings; and PR-1, PR-4 and PR-5 if phase 0 calls for them) in `dashpay/platform` and
publish a new `dash-sdk-android` build from the 5.1 line, then bump `dashSdkVersion` and
`gradle/verification-metadata.xml`.

### Phase 2: one SDK implementation and the cutover gate

DashConnect does not run before the cutover ([Decisions](#decisions-2026-11-17)), so there is
no two-implementation seam and no `LegacyConnectPlatform`. `AppConnectSession` calls the SDK
through a `ConnectPlatform` covering the roughly 15 operations in the call map, with one
implementation, `SdkConnectPlatform`, or calls the SDK directly. If `ConnectPlatform` stays an
interface, its only reason is testing: unit tests run `AppConnectSession` against a fake.

Every new-SDK write needs the SDK `walletHandle`, and the SDK wallet exists only after the
cutover. Before the cutover DashConnect is hidden. The cutover itself will eventually be removed
or greatly simplified, so this gate is temporary. Make it a single check in one place
(`ConnectAvailability`, which already gates DashConnect on the build and the Platform protocol
version, is the natural home) that is easy to delete with the cutover.

The gate hides every entry point, not just the screen:

- The Connections row in Tools (`ToolsScreen.kt:332`). It already shows only when
  `ConnectAvailability.availability` is true (`ToolsViewModel.kt:189`), so the gate goes into
  that value.
- Scanned DashConnect QR codes (`dash-key:`, `dash-st:`, `dashpay://connect`). Three scan paths
  send them to the Connections screen: home (`WalletFragment.kt:324`), the address input
  (`DashAddressInputFragment.kt:45`) and Payments' Pay (`PaymentsPayFragment.kt:163`). Each
  checks `Constants.SUPPORTS_CONNECT` today; each should check the gate instead.
- Links opened from a browser or another app. The manifest routes `dash-key:`, `dash-st:` and
  `dashpay://connect` to `MainActivity` (`AndroidManifest.xml:131-152`), and `handleIntent`
  sends them to the Connections screen (`MainActivity.kt:546-557`). The intent filters stay,
  since the manifest can't follow a runtime gate, so `handleIntent` checks it.

Before the cutover a scanned or opened DashConnect URI is treated as unsupported, not routed
to a screen that says DashConnect is off. A scanned QR falls through to the normal
unrecognised-QR error, as it already does where the build has DashConnect off. An opened link
opens the wallet as usual and shows a short toast that the link isn't supported yet (the one
new string, see [Decisions](#decisions-2026-11-17)); it is not routed anywhere.

### Phase 3: signer

Add `ConnectSigner : NativeSignerBridge` for what `KeystoreSigner` cannot sign: the
DashConnect app keys (DIP-13 sub-features 6 and 7), including the new key's own signature
during login. Identity keys are signed by `KeystoreSigner`. There is no fallback to the dashj
identity chain and no `requireIdentityChainKeys` call: DashConnect runs only after the cutover,
on the SDK alone. Once the phase 0 derivation check passes, `ConnectSigner` takes the app
keys from the SDK (`PlatformWalletManager.deriveConnectKey`, #5649) instead of dashj. It keeps today's per-sign `authorize()` and `checkScope()` hooks and is
closed after each operation.

`ConnectSigner` is a delegating signer, like iOS's `signer.withAdditionalSigningKeys(derivedKeys)`
(platform#4273): it answers for the app keys derived for this operation and passes every other
key to `KeystoreSigner`. The app keys are held only for the operation and never stored.
`KeystoreSigner` has no such method in int36, but `NativeSignerBridge` is public, so this
needs no SDK change.

The rotation search is wallet code, as on iOS (V2L:496-540): derive rotation 0, 1, 2… until a
key is not on the identity, capped at that purpose's key count, then try the legacy path
(`deriveLegacyAppEncryptionKey`) on testnet and devnet only.

### Phase 4: move reads, then writes

1. Reads: identity, contracts, protocol version, documents, DPNS name, key budget, contract key ID, and the `dash-st:` decoder (`StateTransitionParser` plus the wallet's checks).
2. PR-2 and PR-3 reads: contract-group owner and `adminIds` (#5421), Platform time (the `blockTimeMs` of the keys read, as iOS does). Contract bounds come from the SDK's persisted key rows, not the proved keys read.
3. Contract-group consent (wallet work from the consent decision): `AppConnectGroupConsent`, the `VerifiedContractGroup` usage in `AppConnectSession` and `AppConnectPlan` (including `requireGroupScopes` and the approve sheet's `consentText`), and the stored group scopes move from listing members to the owner and admins plus a warning. A group with admins, or whose admins can't be read, is treated as administered, and the sheet warns that others can add contracts the key will act in, as iOS does. The sheet change needs a design or copy decision against the Figma first (iOS's wording is `DashConnectPermissionText.swift:103-107`); see [Open questions](#open-questions).
4. Writes, smallest blast radius first: delete the response document (needs PR-6: by values, with a key the wallet chooses as iOS's `selectDocumentSigningKeyId` does), create it, identity update (login, then revoke), key registration (`addKey` with the wallet's own missing-key check), token purchase.

The steps are an order of work, not separate releases. There is no legacy path to keep, so
DashConnect ships on the SDK only once every call has moved, and the kotlin-platform calls
are removed in the same change (phase 5). Map the new SDK's errors (`DashSdkError` and others) onto the same
"submitted / unknown / failed" outcomes. iOS shows a design that ports with int36 as it is:
record an intent before submit, a 60 s timeout per write and no retry
(`AppConnectWriteExecutor`); a consensus error (`DashSdkError.consensusError` in Kotlin)
means Refused, any other failure means read again (the identity, or the response document
by `appEphemeralPubKeyHash`), and a failed purchase is reported "outcome unknown" with no
retry. #5823's proven `state_transition_outcome` (or the unproven
`getRecentStateTransitionStatus`, after an SDK bump past int36) is not needed for that;
iOS does not use it. Its Kotlin bindings have not been checked.

### Phase 5: remove the legacy path

This ships with phase 4, not later: DashConnect does not run before the cutover, so there is
nothing to keep. The change that moves DashConnect onto the SDK also removes the
kotlin-platform calls and the `org.dashj.platform` imports from `ui/more/connections`,
including DashConnect's call into `IdentityRepository.ensureCriticalAuthenticationKey`.
`libsdklib.so` stays in the APK while DashPay (`PlatformRepo`, `PlatformSyncService` and
others) still uses kotlin-platform.

DashConnect then no longer needs the identity-key backfill (`requireIdentityChainKeys`). The
backfill may still be needed for DashPay, which reaches it through `addMissingKeys`
(`upgradeIdentity`), until that path moves to the SDK too.

The cutover gate from phase 2 is deleted when the cutover itself is removed or simplified,
and the pre-cutover toast and its string go with it.

## Testing

- Unit: the existing DashConnect suites against a fake `ConnectPlatform`; a `ConnectSigner`
  test that signs with an app key and passes identity keys to `KeystoreSigner`; decoder
  vectors from `wallet/test/app-connect-vectors.json` through `StateTransitionParser` plus
  the wallet's checks; a key-registration test that adds nothing when the refetched
  identity already has the key; consent tests that a group with admins, and a group whose
  admins can't be read, are both treated as administered and carry the warning; a gate test
  that DashConnect is unavailable before the cutover.
- Device (testnet, PV14): login, the Connections list, revoke, reconnect, Reset Wallet and
  same-seed restore. These passed on the legacy path on 2026-10-28 (Pixel 9 emulator,
  identity created by the Kotlin SDK) and must pass again on the new one, after the cutover.
  Before the cutover, check that the Connections row is hidden, that a scanned DashConnect QR
  gets the unrecognised-QR error, and that an opened DashConnect link lands on the home screen and shows the not-supported toast.
- Token purchase has never run on a live network on either path. It needs a testnet token
  with a direct-purchase price and a `dash-st:` link generator.

## Open questions

The three questions of 2026-10-28 are answered (see [Decisions](#decisions-2026-11-17)). What
is left is design, not architecture:

- The approve sheet under the iOS consent model: its layout and copy for the owner, the admins
  and the "others can add contracts the key will act in" warning need a decision against the
  Figma. iOS's wording is `DashConnectPermissionText.swift:103-107`; do not port it without
  that decision.
