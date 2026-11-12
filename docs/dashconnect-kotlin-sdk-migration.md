# DashConnect: moving from kotlin-platform to the Kotlin SDK

Status: plan, 2026-10-28. Branch: `docs/dashconnect-sdk-migration` (based on `feat/kotlin-sdk-phase-1`).

Updated 2026-11-12 for SDK 5.1.0-int36. `feat/kotlin-sdk-phase-1`, and so this branch, pins
`dashSdkVersion = '5.1.0-int36-20261106.165805-1'`. The `dash-st:` decoder (PR-1) ships in int36, so PR-1 is done and
the int32 backport question is settled. PR-2 and PR-3 still block. Key registration through
`BlockchainIdentity.addMissingKeys` and the identity-key backfill are now covered too.

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
| `client.getCurrentContractKeyId` (:344, :539) | `Identities.fetchContractKeys(identityIds, contractId, purposes, documentTypeName)` | Probably equivalent; confirm on testnet that it returns the current proved key ID. **PR-4** if not. |
| `client.getDataContract` (:261, :271, :705, :722) | `queries.Contracts.fetch` / `fetchWithSerialization` | None. |
| `client.getProtocolVersion` (`requireProtocolVersion`, :104; called at :120, :219, :315) | `queries.SystemQueries.refreshProtocolVersion` | None. |
| `platform.documents.get` (:512) | `queries.Documents.search` / `fetch` | None. |
| `platform.names.getByOwnerId` (repository :188) | `queries.Dpns.usernames(identityId, limit)` | None. |
| `client.getContractGroup` (:266) | — | **PR-2**. int36's `queries.Groups` has only `info` / `actions` / `actionSigners`, which are for token multisig groups, not contract-group members. |
| `client.getContractGroupOwner` (:709) | — | **PR-2** |
| `client.getPlatformTime` (13 calls) | — | **PR-3**. int36's `SystemQueries` has `epochsInfo` and `refreshProtocolVersion` but no block time. |
| `dashsdk.prepareTokenPurchaseSdk` (:121, :152) | `identity.StateTransitionParser.parse(ByteArray): ParsedStateTransition` | Done in int36 (PR-1). See below. |

`StateTransitionParser.parse` returns a `ParsedStateTransition` with `isSigned`, `complete`,
`ownerId` and `kind`. The kinds are `Batch`, `CreditTransfer`, `DataContractCreate`,
`DataContractUpdate`, `IdentityUpdate` and `Other`. `Batch.getTransitions()` returns
`ParsedBatchedTransition.Document` / `.Token`, and `Token` exposes `tokenId`,
`tokenContractPosition`, `action`, `amount`, `tokenCount`, `recipientId`, `dataContractId`,
`complete` and `details`.

It is a parser only. The wallet still enforces the rules `prepare_token_purchase_sdk`
enforces today: unsigned, a single direct purchase, no trailing bytes (`complete`), and a
token ID that matches the request. The agreed total price has no typed field on `Token`; it
may only be in the `details` string. Check that before relying on it, and add a typed field
upstream if it is missing.

### Signing

Today every write passes a kotlin-platform `Signer`. Login (:392), revoke (:618) and
shared-profile revoke (:687) wrap dashj's `WalletSignerCallback`; the token purchase (:162)
signs directly with the key it finds on dashj's identity chain. Between them they sign with
the identity keys on dashj's BLOCKCHAIN_IDENTITY chain and with the per-app keys DashConnect
derives (DIP-13 sub-features 6 and 7).

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

## Platform PRs needed (`dashpay/platform`)

The wallet's base branch builds against `dash-sdk-android` 5.1.0-int36 (recipe
`HashEngineering/dash-wallet-qa` `recipes/platform/5.1.0-int36.yaml`). Each open PR below
needs to land on the line the next int build is cut from.

| PR | What | Where to start | Status upstream |
|---|---|---|---|
| **PR-1** | The `dash-st:` state-transition decoder for Kotlin. | — | **Done.** Ships in int36 as `identity.StateTransitionParser`. The wallet still adds its own checks (see [Reads](#reads)). Open item: whether the agreed total price is exposed other than in `details`. |
| **PR-2** | Contract-group queries for Kotlin: the owner, and a proof-verified snapshot of members and administration at one block. | Port #5421 (`rs-sdk-ffi/src/group/queries/contract_group_info.rs`, Swift-only today) to `rs-unified-sdk-jni` and `queries.*`. Snapshot logic: kotlin-platform `platform-mobile/src/app_connect.rs` `fetch_contract_group_snapshot_with_sdk`, `fetch_group_members`, `fetch_contract_group_owner_with_sdk`. Server queries from #4792. | New work. No open PR. Still missing in int36. |
| **PR-3** | Platform block time. | kotlin-platform `app_connect.rs` `fetch_platform_time_with_sdk`: `Identity::fetch_with_metadata(...)` returning `metadata.time_ms`. Either expose it as `Identities.fetchWithTime` (or similar), or return response metadata from existing queries. | New work. No open PR. Still missing in int36. |
| **PR-4** (only if needed) | The current proved contract-key ID for an identity, contract and purpose. | kotlin-platform `app_connect.rs` `fetch_current_contract_key_id_with_sdk`. | Only if `Identities.fetchContractKeys` doesn't return the same thing. |
| **PR-5** (only if needed) | Let the caller choose the signing key on `IdentityUpdates.update` and `DocumentTransactions.create`. | `DocumentTransactions.delete` already takes `signingKeyId`; follow that. | Only if the SDK's own key choice is wrong for DashConnect (MASTER for identity updates, HIGH for the response document). Neither takes a key in int36. |

PR-2 and PR-3 block the migration. PR-4 and PR-5 depend on what the checks in phase 0 find.

## Phases

### Phase 0: confirm the gaps (wallet, no Platform PR)

- Check `Identities.fetchContractKeys` against `getCurrentContractKeyId` on testnet (decides PR-4).
- Check which key `IdentityUpdates.update` and `DocumentTransactions.create` sign with (decides PR-5).
- Check whether `StateTransitionParser` exposes the agreed total price other than in `details`.
- Check whether the SDK already has DIP-13 sub-feature (app key) derivation. If it does, a later step can move app-key derivation out of dashj.

### Phase 1: Platform PRs and SDK bump

Open PR-2 and PR-3 (and PR-4 and PR-5 if phase 0 calls for them) in `dashpay/platform` and
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
SDK does not hold. It keeps today's per-sign `authorize()` and `checkScope()` hooks and is
closed after each operation.

### Phase 4: move reads, then writes

1. Reads: identity, contracts, protocol version, documents, DPNS name, key budget, contract key ID, and the `dash-st:` decoder (`StateTransitionParser` plus the wallet's checks).
2. PR-2 and PR-3 reads: contract group, contract-group owner, Platform time.
3. Writes, smallest blast radius first: delete the response document, create it, identity update (login, then revoke), key registration (`addKey` with the wallet's own missing-key check), token purchase.

Each step changes one `SdkConnectPlatform` method and keeps the legacy one for the
pre-cutover path. The retry, receipt and "outcome unknown" handling in
`AppConnectSession` stays as it is; only the transport changes. Map the new SDK's errors
(`DashSdkError` and others) onto the same "submitted / unknown / failed" outcomes.

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

- Who owns PR-2 and PR-3 in `dashpay/platform`? Roman (romchornyi) has no open PR for
  them; #5421 (contract-group owner, Swift) is PastaPastaPasta's.
- Should DashConnect keep running before the cutover? If not, phase 2 needs no legacy
  implementation and phase 5 is immediate. If it does, its writes stay on kotlin-platform
  until then, because SDK writes need the SDK `walletHandle`.
