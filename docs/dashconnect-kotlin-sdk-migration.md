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

DashConnect v2 (merged from #1604) sends every Platform read and write through the legacy
kotlin-platform SDK (`org.dashj.platform.*`, `libsdklib.so`, dpp `5.0.1`). The new Kotlin SDK
(`org.dashfoundation.dashsdk`, `dash-sdk-android` 5.1.0-int36) is not called by any
DashConnect code. This plan moves DashConnect onto the new SDK and marks the points where
`dashpay/platform` needs a PR first.

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
| `dashsdk.platformMobilePutPutIdentityUpdateSdk`: add app keys at login (:426), disable them at revoke and shared-profile revoke (`submitIdentityUpdate`, :651) | `identity.IdentityUpdates.update(walletHandle, identityId, addKeys, disableKeyIds, signerHandle)` (also `addKey`, `disableKeys`, `updateKeyLimits`) | Today the MASTER key is chosen by the caller (:418, :647); the SDK call has no key parameter. Check which key it signs with (**PR-5** if it cannot be pinned). `IdentityPubkey` already carries `ContractBounds.ContractGroup` / `SingleContract` / `SingleContractDocumentType`, `totalBudget` and `expiresAt`. |
| `dashsdk.platformMobilePutPutDocumentSdk`: login response (:553) | `documents.DocumentTransactions.create(walletHandle, ownerId, contractId, type, propertiesJson, signerHandle, maxContestFund)` | Still no `signingKeyId` in int36; today the HIGH key is chosen (:529). **PR-5** if the SDK's choice differs. |
| `dashsdk.deleteDocumentSdk`: response cleanup (:572) | `DocumentTransactions.delete(walletHandle, ownerId, contractId, type, documentId, signingKeyId, signerHandle)` | None. |
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
| `client.getContractGroup` (:266) | — | **PR-2** (member list). int36's `queries.Groups` has only `info` / `actions` / `actionSigners`, which are for token multisig groups, not contract-group members. Nothing upstream returns the group's contracts and tokens yet. |
| `client.getContractGroupOwner` (:709) | — | **PR-2** (binding only). #5421's proved `dash_sdk_contract_group_get_info` returns `{ownerId, adminIds, name?, description?}` in rs-sdk-ffi and Swift; it has no Kotlin/JNI binding. |
| `client.getPlatformTime` (13 calls) | — | **PR-3**. int36's `SystemQueries` has `epochsInfo` and `refreshProtocolVersion` but no block time. |
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
created, so on the SDK path most of the backfill is no longer needed. It does not know
DashConnect's per-app keys. An identity update that adds a key also needs a signature from
the new key, so a custom signer must still hold the app key's private key while a login
runs.

The backfill can be removed only once nothing signs identity keys through dashj: not
DashConnect, not key registration, and not DashPay.

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

## Platform PRs needed (`dashpay/platform`)

The wallet's base branch builds against `dash-sdk-android` 5.1.0-int36 (recipe
`HashEngineering/dash-wallet-qa` `recipes/platform/5.1.0-int36.yaml`). Each open PR below
needs to land on the line the next int build is cut from.

| PR | What | Where to start | Status upstream |
|---|---|---|---|
| **PR-1** | The `dash-st:` state-transition decoder for Kotlin. | `identity.StateTransitionParser` (v5.1-dev `97f51a2bb`, Rust #4273/#4584). If the price is missing, add a typed agreed-price field to `ParsedBatchedTransition.Token`. | **Available in int36, unverified.** Not yet run on a real `dash-st:` link or the test vectors. **Closes only when the parser exposes the agreed total price as a typed field and parses `wallet/test/app-connect-vectors.json` correctly** (phase 0). If the price is missing, this becomes a small Platform PR that adds it. The wallet's own checks are wallet work in phase 4 (see [Reads](#reads)). |
| **PR-2** | Contract-group queries for Kotlin: a binding for the owner read, plus the member query (the group's contracts and tokens, which consent needs), as a proof-verified snapshot at one block. | Bind #5421 (`rs-sdk-ffi/src/group/queries/contract_group_info.rs`, `dash_sdk_contract_group_get_info`, merged 2026-10-15, Swift-only today) in `rs-unified-sdk-jni` and `queries.*`; the owner half needs no new Rust. Snapshot logic: kotlin-platform `platform-mobile/src/app_connect.rs` `fetch_contract_group_snapshot_with_sdk`, `fetch_group_members`, `fetch_contract_group_owner_with_sdk`. Server queries from #4792. | Owner read exists in Rust (#5421) but has no Kotlin binding; the member list has nothing upstream. Now a binding plus the member query. No open PR. |
| **PR-3** | Platform block time. | kotlin-platform `app_connect.rs` `fetch_platform_time_with_sdk`: `Identity::fetch_with_metadata(...)` returning `metadata.time_ms`. Either expose it as `Identities.fetchWithTime` (or similar), or return response metadata from existing queries. | New work. No open PR. Still missing in int36. |
| **PR-4** (only if needed) | The current proved contract-key ID for an identity, contract and purpose. | kotlin-platform `app_connect.rs` `fetch_current_contract_key_id_with_sdk`. | Probably not needed: #5491 (in int36) makes `fetchContractKeys` return the proved keys. Still decided by the phase 0 testnet check. |
| **PR-5** (only if needed) | Let the caller choose the signing key on `IdentityUpdates.update` and `DocumentTransactions.create`. | `DocumentTransactions.delete` already takes `signingKeyId`; follow that. Likely built on #5588 (open: public, protocol-14-aware signing-key selection) and #5961. | Only if the SDK's own key choice is wrong for DashConnect (MASTER for identity updates, HIGH for the response document). Neither takes a key in int36. |

PR-2 and PR-3 block the migration. PR-1 is conditional on the phase 0 price and vector
check. PR-4 and PR-5 depend on what the other checks in phase 0 find.

### Upstream status (2026-11-17)

| PR | State | What it means here |
|---|---|---|
| #5491 (romchornyi) | Merged 2026-10-23, in int36 | `fetchContractKeys` returns the proved contract keys (before: always `{}`). PR-4 probably not needed; phase 0 decides. |
| #5649 (PastaPastaPasta) | Merged 2026-10-29, in int36 | `PlatformWalletManager.deriveConnectKey` / `ConnectSubFeature` (`wallet/PlatformWalletManager.kt` :651–:717) derives app keys at `m/9'/coin'/5'/<subFeature>'/0'/identityId'/leaf'[/purpose'/rotation']`: 6 is session authentication, 7 is app encryption with a hardened `rotation'`. `deriveLegacyAppEncryptionKey` covers the old 8-level path. Answers phase 0's derivation question; see phase 0 and phase 3. |
| #5421 (PastaPastaPasta) | Merged 2026-10-15, rs-sdk-ffi and Swift only | Proved `dash_sdk_contract_group_get_info` returns `{ownerId, adminIds, name?, description?}`. PR-2's owner half needs only a Kotlin/JNI binding. |
| #5823 (QuantumExplorer) | Merged 2026-11-10, **after** int36 (needs an SDK bump) | Proven `Sdk::state_transition_outcome` and unproven DAPI `getRecentStateTransitionStatus`. Not one of PR-1 to PR-5, but an option for `AppConnectSession`'s "outcome unknown" handling on the SDK path (see phase 4). Kotlin bindings not checked. |
| #5588 | Open, v5.2-dev | Public, protocol-14-aware signing-key selection. The likely basis for PR-5. |
| #5961 | Open, stacked on #5588 | Don't pick a budgeted or expiring key for data contract create or update. |
| #5683 | Open, v5.2-dev | Refetch the identity before building an identity update; see [Key registration](#key-registration). |
| PR-3 | Nothing | No merged or open PR for Platform block time. |
| PR-2 member list | Nothing | Nothing beyond #5421. |

## Phases

### Phase 0: confirm the gaps (wallet, no Platform PR)

- Check `Identities.fetchContractKeys` against `getCurrentContractKeyId` on testnet (decides PR-4). Since #5491 it returns the proved keys, so PR-4 is probably not needed.
- Check which key `IdentityUpdates.update` and `DocumentTransactions.create` sign with (decides PR-5).
- Run `StateTransitionParser` on a real `dash-st:` link and on the vectors in `wallet/test/app-connect-vectors.json`, and check that it exposes the agreed total price as a typed field, not only in `details` (decides PR-1). PR-1 closes only if both hold; if the price is missing, PR-1 becomes a small Platform PR that adds it.
- The SDK does derive app keys (#5649, `PlatformWalletManager.deriveConnectKey`). Check instead that it derives the same keys the wallet derives today for sub-features 6 and 7, at the same rotation level, and that keys registered on the legacy (pre-rotation) path are still found through `deriveLegacyAppEncryptionKey`. If both hold, phase 3 can take app keys from the SDK.

### Phase 1: Platform PRs and SDK bump

Open PR-2 and PR-3 (and PR-1, PR-4 and PR-5 if phase 0 calls for them) in `dashpay/platform` and
publish a new `dash-sdk-android` build from the 5.1 line, then bump `dashSdkVersion` and
`gradle/verification-metadata.xml`.

### Phase 2: a seam in the wallet

Add a `ConnectPlatform` interface covering the roughly 15 operations in the call map, with
two implementations:

- `LegacyConnectPlatform`: today's kotlin-platform calls, moved out of `AppConnectSession`
  unchanged.
- `SdkConnectPlatform`: the new SDK calls.

`AppConnectSession` only talks to `ConnectPlatform`. Choose the implementation by cutover
state, because every new-SDK write needs the SDK `walletHandle` and the SDK wallet exists
only after the cutover. Before the cutover, writes stay on the legacy path. Unit tests run
`AppConnectSession` against a fake `ConnectPlatform`.

### Phase 3: signer

Add `ConnectSigner : NativeSignerBridge` for what `KeystoreSigner` cannot sign: the
DashConnect app keys (DIP-13 sub-features 6 and 7), including the new key's own signature
during login. Identity keys the SDK created are signed by `KeystoreSigner`; `ConnectSigner`
falls back to the dashj identity chain (after `requireIdentityChainKeys`) only for keys the
SDK does not hold. Once the phase 0 derivation check passes, `ConnectSigner` takes the app
keys from the SDK (`PlatformWalletManager.deriveConnectKey`, #5649) instead of dashj. It keeps today's per-sign `authorize()` and `checkScope()` hooks and is
closed after each operation.

### Phase 4: move reads, then writes

1. Reads: identity, contracts, protocol version, documents, DPNS name, key budget, contract key ID, and the `dash-st:` decoder (`StateTransitionParser` plus the wallet's checks).
2. PR-2 and PR-3 reads: contract group, contract-group owner, Platform time.
3. Writes, smallest blast radius first: delete the response document, create it, identity update (login, then revoke), key registration (`addKey` with the wallet's own missing-key check), token purchase.

Each step changes one `SdkConnectPlatform` method and keeps the legacy one for the
pre-cutover path. The retry, receipt and "outcome unknown" handling in
`AppConnectSession` stays as it is; only the transport changes. Map the new SDK's errors
(`DashSdkError` and others) onto the same "submitted / unknown / failed" outcomes.
An option after an SDK bump past int36: #5823's proven `state_transition_outcome` (or the
unproven `getRecentStateTransitionStatus`) could back the "unknown" case on the SDK path.
Its Kotlin bindings have not been checked.

### Phase 5: remove the legacy path

When DashConnect no longer runs before the cutover, delete `LegacyConnectPlatform` and the
`org.dashj.platform` imports from `ui/more/connections`. `libsdklib.so` stays in the APK
while DashPay (`PlatformRepo`, `PlatformSyncService` and others) still uses kotlin-platform.
The identity-key backfill (`requireIdentityChainKeys`) stays until nothing signs identity
keys through dashj.

## Testing

- Unit: the existing DashConnect suites against a fake `ConnectPlatform`; a `ConnectSigner`
  test that signs with an app key and falls back to a seeded dashj identity chain; decoder
  vectors from `wallet/test/app-connect-vectors.json` through `StateTransitionParser` plus
  the wallet's checks; a key-registration test that adds nothing when the refetched
  identity already has the key.
- Device (testnet, PV14): login, the Connections list, revoke, reconnect, Reset Wallet and
  same-seed restore. These passed on the legacy path on 2026-10-28 (Pixel 9 emulator,
  identity created by the Kotlin SDK) and must pass again on the new one.
- Token purchase has never run on a live network on either path. It needs a testnet token
  with a direct-purchase price and a `dash-st:` link generator.

## Open questions

- Who owns PR-2 and PR-3 in `dashpay/platform`? Nobody has an open PR for them as of
  2026-11-17; #5421 (contract-group owner, rs-sdk-ffi and Swift) is PastaPastaPasta's.
- Should DashConnect keep running before the cutover? If not, phase 2 needs no legacy
  implementation and phase 5 is immediate. If it does, its writes stay on kotlin-platform
  until then, because SDK writes need the SDK `walletHandle`.
