# Platform SDK A: remove hidden legacy-client dependencies

**Planning baseline:** `feat/kotlin-sdk-phase-1` at `d3ca76872` (2026-11-16).
**Tracker:** [dashpay/dash-wallet#1540](https://github.com/dashpay/dash-wallet/issues/1540).
**Scope:** preparatory step 2 of retiring the legacy `org.dashj.platform.sdk.platform.Platform` client. This document is a plan, not an implementation record.

## Goal and boundary

An SDK-backed read or a local identity-cache read should not construct the legacy `Platform` object just to obtain a contract ID, decode a stored identity, or record a valid identity ID. `PlatformServiceImplementation` currently appears lazy, but its constructor's `init` block dereferences `_platform?.client`. The pinned legacy `Platform` constructor calls `System.loadLibrary("sdklib")`, so injecting the service can load the old native library before any legacy operation is requested.

Keep a working legacy-client initializer. Transaction-metadata publish/fetch and DashConnect still call the legacy client, as do other legacy fallback paths. This step must preserve their behavior and does not claim that *every* application launch can avoid `libsdklib` yet. Removing old DPP model types is separate issue [#1541](https://github.com/dashpay/dash-wallet/issues/1541).

## Concrete dependency inventory

Paths and line numbers below refer to the planning baseline.

| Dependency | Call sites | Replacement or disposition |
|---|---|---|
| DPNS contract ID from `Platform.apps` | `SdkUsernameQueries.kt:289`; `SdkVotingQueries.kt:277` | Inject a wallet-owned system-contract ID registry. The SDK query adapters must not construct the legacy client to synthesize a DPNS document or cast a vote. |
| DashPay contract ID from `Platform.apps` | `SdkProfileQueries.kt:293,296` | Use the same registry for both the SDK source's fetch and the legacy-model mapping. Keep one ID source within this adapter. |
| Identity-verify contract ID from `Platform.apps` | `SdkIdentityVerifyQueries.kt:202`; `SdkIdentityVerifyWrites.kt:265` | Use network-specific IDs from the registry; preserve the current unavailable/fallback and `NotBroadcast` results for an unsupported network. |
| Wallet-utils contract ID from `Platform.apps` | `SdkTxMetadataDecryptProbe.kt:280` | Use the registry. Keep the probe's legacy document-count comparator at `:282-283` until metadata migration; the ID lookup alone can move now. |
| Local identity decode through `Platform.dpp` | `BlockchainIdentityData.kt:329`; exposed by `PlatformService.kt:51,149` | Move `createFromBuffer` behind a wallet-owned, pure local codec. Preserve the existing serialized blob format and contract-bounds tolerance. It may temporarily use legacy DPP model classes without constructing `Platform`; replacing those types belongs to #1541. |
| Identity lookup by first public-key hash | `IdentityRepository.kt:278,1356-1370` | Use the pinned Kotlin SDK's `identities.fetchByPublicKeyHash(hashHex)` and map the result to the repository's current identity shape. Its five callers are `RestoreIdentityWorker.kt:192`, `PlatformSyncService.kt:2892`, and `CreateIdentityService.kt:777,1292,1742`. Make the repository boundary suspending as needed; do not block the main thread. |
| Identity credit balance | `IdentityRepository.kt:345`; `PlatformRepo.kt:791` | Share an adapter over the pinned SDK's `identities.fetchBalance(identityId)`. Preserve each caller's null/error contract and `CreditBalanceInfo` credit units. Retain a legacy fallback only when an actual legacy operation needs one. |
| Legacy valid-identity set | `PlatformSyncService.kt:1234,3263,3267,3272`; `DashPayUserActivityViewModel.kt:131`; `DashPayUserBottomSheetViewModel.kt:230` | Record IDs in wallet-owned state seeded from the same local identity and Room records. Replay them into the legacy state repository before its first query if the client is created. The SDK's proved queries do not need this legacy node-rejection hint. |
| Legacy identity cache write | `PlatformSyncService.kt:3275` | Keep it on the legacy path only, with the existing CBOR tolerance for contract-bound keys. It is a cache optimization, not the source of the wallet's identity record. |
| Eager client construction and quorum wiring | `PlatformService.kt:137-153,163-215,249-256`; `BlockchainServiceImpl.kt:2082,3177` | Construct one legacy client on genuine first use. Store the supplied masternode manager without creating `Platform`, then attach the context provider, manager, and Sakura address handling atomically when the legacy client is created. |
| Public state-repository accessor | `PlatformService.kt:53,150` | Remove from the public interface after the bookkeeping callers move. Keep the private legacy repository needed by the surviving client. |

The following `Platform.apps` uses are **legacy operations**, not SDK contract-ID lookups: `PlatformSyncService.kt:339-380` checks and loads the legacy cached DashPay contract; `AppConnectSession.kt:504` registers a custom app definition before its legacy document query. Likewise, `IdentityRepository.kt:1281` and `PlatformSyncService.kt:1054` use `hasApp` to guard legacy flows. Preserve those semantics during this step.

## Contract-ID source

The pinned Kotlin SDK exposes contract fetches, but no contract-ID accessor. Fetching by ID cannot supply the ID itself. Use a wallet-owned registry whose system IDs are copied from the canonical contract packages and verified against the currently pinned legacy definitions:

| Contract | Canonical base58 ID | Source |
|---|---|---|
| DPNS | `GWRSAVFMjXx8HpQFaNJMqBV7MBgMK4br5UESsB4S31Ec` | `dashpay/platform/packages/dpns-contract/lib/systemIds.js` |
| DashPay | `Bwr4WHCPz5rFVAD87RqTs3izo4zpzwsEdKPWUT1NS1C7` | `dashpay/platform/packages/dashpay-contract/lib/systemIds.js` |
| wallet-utils | `7CSFGeF4WNzgDmx94zwvHkYaG3Dx4XEe5LFsFgJswLbm` | `dashpay/platform/packages/wallet-utils-contract/lib/systemIds.js` |

These three values also appear in the pinned legacy `SystemIds` class. The legacy `Platform` constructor registers `identity-verify` separately based on `NetworkParameters.id`: testnet `Bhptm3yBDhLkRNt7ofjpwaBHhMUKjDrQoPufKzQaxmpK`, Bintang `Fds5DDfXoLwpUZ71AAVYZP1uod8S7Ze2bR28JExBvZKR`, and production `EVKMFboB3QBUa9Jo7PP5bsLyohzUz8zvw5c2gJs1SfcX`. Encode this distinction explicitly; do not infer an identity-verify ID for Sakura or another unsupported network. Check each supported flavor against the pinned definitions and a fetched SDK contract before changing an active read/write path. If unavailable, preserve the existing fail-closed/fallback result.

## Ordered implementation

1. **Introduce and validate the contract registry.** Switch all seven SDK adapter closures above. Keep the probe's legacy comparator and all legacy contract-cache operations. Confirm each adapter's flag-off, missing-ID, SDK failure, and legacy-fallback behavior.
2. **Extract the local identity codec.** Decode old and newly saved identity blobs without creating `Platform` or accessing the network. Cover no bounds and stripped contract-bound keys. Remove `PlatformService.dpp` once its sole production caller has moved.
3. **Move identity discovery and balances.** Use the SDK query APIs confirmed in the pinned `5.1.0-int36` artifact. Preserve not-found versus transient-error semantics, retry spacing, cancellation, and credit units. Route all five discovery callers through the shared implementation.
4. **Move valid-ID bookkeeping.** Seed from the current identity, incoming-contact, and profile Room records; include newly encountered contact IDs and the two UI call sites. Replay once to a legacy client before its first query. Keep the optional legacy identity cache write contained, including CBOR failure behavior. Remove the public `stateRepository` accessor after the direct callers are gone.
5. **Make legacy initialization genuinely lazy and thread-safe.** Move the context-provider setup out of `PlatformServiceImplementation.init`; record quorum manager updates without constructing `Platform`; apply the latest manager when creation becomes necessary. Preserve the Sakura configured address provider. Keep the legacy initializer itself while callers remain.

## Verification and acceptance

- **Parity:** registry values by flavor; SDK synthetic DPNS/profile/identity-verify documents; old identity blob round-trips; first-key discovery for found, absent, and network-failure cases; exact credit balances; valid-ID replay and legacy proof verification.
- **Cold start:** fresh and restored wallets, with and without a Platform identity, unlocked and locked, foreground service and background application start, before and after committed L1 cutover. Count `Platform` construction and inspect loaded native libraries. An SDK-only path must not construct `Platform` or load `libsdklib`; a path that actually uses the legacy client must initialize it exactly once.
- **Quorum/Sakura:** simultaneous first legacy callers, late masternode-manager delivery, SDK-sourced quorums while dashj is held, and Sakura HTTPS port preservation. A delayed client must still receive the correct manager and callback before any proved legacy query.
- **Build/grep gate:** assemble the affected wallet variants and run focused existing identity, SDK query, Platform sync, and quorum tests. Then grep for the seven `platformService.platform.apps` SDK-adapter lookups, production `PlatformService.dpp`, and direct UI `stateRepository` access; all should be gone. Legacy-only `Platform` uses remain expected.

## Parallel-work boundary

The transaction-metadata work owns `PlatformSyncService.kt`'s ticker (`:691-826`) and fetch/publish flows (`:1879-2598`). This plan touches startup `init()` (`:510-513`), contact bookkeeping (`:1234`), and `initializeStateRepository()` (`:3258-3275`). Develop against the same phase-1 tip, then rebase or merge and inspect the combined file diff. Do not remove the metadata ticker, the `SdkTxMetadataDecryptProbe` comparator, or its legacy fallback as part of this step.

DashConnect still calls `Platform.client` for identity, time, contract, key-budget, and protocol operations, and still registers a custom `Platform.apps` entry. Its migration can proceed separately. Until it and transaction metadata are migrated, preserve the legacy client initializer and do not declare #1540 complete solely because the hidden dependencies above are removed.
