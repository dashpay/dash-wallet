# Kotlin SDK Migration Plan — Dash Android Wallet

**Date:** 2026-07-07
**Scope:** Migrate the Android wallet off dashj (+ dashj-platform) onto the new Kotlin SDK (`platform` repo, branch `feat/kotlin-sdk-and-example-app`), and replace CoinJoin with shielded balances.

## Execution status (updated 2026-07-07)

- ✅ **Phase 2 — CoinJoin removal: DONE** (this branch). Mixing engine, UI, config, analytics, and
  resources removed; CoinJoin keychain still provisioned on load/create/restore so previously mixed
  funds remain visible and spendable via the standard coin selector; historical mixing transactions
  keep their grouped display. Verified: `assemble_testNet3Debug` builds and the full wallet unit-test
  suite passes.
- ✅ **Phase 1 — seam neutralization: SUBSTANTIALLY DONE.** Neutral `SyncStage`;
  `BlockchainStateProvider` dashj-free; dead APIs removed. New neutral money kit in
  `org.dash.wallet.common.money` (`Dash`, `FiatValue`, `MoneyFormat`, `DashAddressValidator`,
  `TxIds`, `DashUri`, entity-`ExchangeRate` conversions) mirroring the dashj APIs and delegating to
  dashj internally, so behavior is identical. **uphold, exploredash, maya, and coinbase no longer
  depend on dashj-core at all** (their dashj-transaction internals — Maya swap builder,
  FakeDashSpendService — moved into the wallet module behind neutral interfaces).
  `SendPaymentService` has neutral send/estimate overloads. **crowdnode:** UI/ViewModel/API surface
  fully neutral; only its transaction-protocol layer (tx filters, confirmation handshake — the code
  that moves real funds and will be rewritten against the SDK in Phase 5) keeps dashj deliberately.
  dashj-file counts: uphold/exploredash/maya/coinbase 0 (was 3/15/20/22), crowdnode 25→protocol-only
  (was 33 incl. UI), wallet 209 (expected — it keeps dashj until Phase 5), common 65 (kit adapters,
  by design). Verified: full `assemble_testNet3Debug` + unit tests of every module green.
- ⬜ Phases 0, 3–6: not started (Phase 0 lives in the platform repo).

---

## 1. Verdict: can we switch now, and how much?

**We cannot fully cut over today, but roughly 65–70% of the functionality the app gets from dashj/dashj-platform has a working counterpart in the Kotlin SDK, and ~100% of the preparatory app-side work can start immediately** (it doesn't even require the SDK as a dependency).

### Coverage by area (SDK readiness today)

| Area | SDK coverage | Notes |
|---|---|---|
| L1 wallet core (create/restore, addresses, balances, tx history, send, SPV sync) | ~80% | Real Rust SPV (`dash-spv` via rust-dashcore) with compact block filters (BIP157/158). Gaps: no fee estimator, no BIP70, no per-UTXO coin control, no arbitrary watched addresses, coarse tx-confidence (mempool / InstantSend / inBlock / ChainLocked only), no `spendable` balance field. |
| Platform / DashPay (identities, DPNS, contacts, profiles, credits, top-ups) | ~90% | Richer than the current `org.dashj.platform:dash-sdk-*` stack. First-class DashPay contacts/profiles, DPNS incl. contested-name voting, credit transfer/withdraw. Kills the per-flavor dpp version matrix. |
| Shielded balances (CoinJoin's conceptual replacement) | ~80% | Orchard/Halo2 shielded pool over Platform credits. Fund from L1 asset lock or credits (Type 15), transfer (16), unshield to credits (17), withdraw to L1 (19), seed-pool anonymity filler. All UI/UX is new app work. ~30s proving time per spend. |
| Wallet migration from dashj `.wallet` protobuf | **0%** | Does not exist. Only path: re-import BIP39 mnemonic + SPV rescan from a birth height. Must be built app-side. |
| Productization (Maven artifact, versioning, API stability, L1 test depth) | Missing | Currently a GitHub-release AAR, pinned to a rust-dashcore git rev, thin L1 automated test coverage. |
| CoinJoin mixing | N/A (by design) | Not implemented in the SDK — only a CoinJoin *account derivation type* exists (`key-wallet::get_coinjoin_account`). This aligns with removing mixing. |
| Exchange rates / fiat | None (by design) | Stays app-side; already sourced from CTX/BitPay/etc. |

### Hard blockers for full cutover
1. **No `.wallet` → SDK migration path** — must be designed and built (Phase 5).
2. **SDK productization** — no Maven coordinates, unpinned/rev-pinned rust-dashcore, preview-quality L1 test coverage (Phase 0).
3. **minSdk 29 and 64-bit-only** (arm64-v8a + x86_64; Halo2 cannot build 32-bit) vs. the app's current minSdk 24 and 32-bit ABIs. Note `Constants.SUPPORTS_PLATFORM = !is32Bit` already excludes 32-bit devices from Platform.
4. **Restore must discover CoinJoin-account funds** — previously mixed UTXOs live on the DIP-9 CoinJoin derivation path inside the wallet; the SDK's restore/rescan must scan that account or those funds are orphaned. Must be verified/implemented in `platform-wallet` (top risk, Phase 0 item).
5. **BIP70 / fee estimation / coin-control gaps** — need app-side implementations or product decisions to drop.

---

## 2. Architecture recommendation

**Use the Kotlin SDK directly as a dependency of this app — do not create another intermediate library. But consume it only through the app's own internal service seam.**

```
UI (fragments/compose, viewmodels)
        │
common module: service interfaces + NEUTRAL types      ← the insulation layer
  (WalletDataProvider, SendPaymentService,
   BlockchainStateProvider, TransactionWrapper, …)
        │
adapter implementations (in :wallet, or a new :wallet-sdk-adapter module)
        │
Kotlin SDK (AAR: PlatformWalletManager, ManagedPlatformWallet, Sdk)
        │  JNI (rs-unified-sdk-jni → libdash_sdk_jni.so)
Rust: platform-wallet / rs-sdk-ffi / key-wallet / dash-spv (rust-dashcore)
```

Rationale:
- The stack is already three layers deep below the app (Kotlin SDK → JNI → Rust). A separately-versioned wrapper library adds a fourth release train and version-skew surface while the SDK is churning daily — maximum friction exactly when you need fast iteration.
- The insulation a wrapper would provide **already has a home**: the Hilt-injected interfaces in `common/services` + `WalletDataProvider`. Today those interfaces leak dashj types (`Coin`, `Address`, `Transaction`, `Wallet`, `SendRequest`, `PeerGroup.SyncStage`) into all 7 modules — fixing that (Phase 1) gives identical swap-ability with zero artifact overhead.
- There is no second consumer for a shared wrapper: iOS uses swift-sdk; `integration-android` doesn't touch dashj. If a second Android consumer appears later, extracting a clean internal module into a library is cheap *after* the seam is neutral.
- Things that stay app-side regardless: exchange rates/fiat, BIP70 (if kept), tx metadata, analytics, PIN security model.

Implication for dashj: dashj eventually disappears entirely (core, bls, x11, scrypt artifacts). During transition the app runs a **flag-gated cutover** (per build flavor / rollout cohort), not both SPV engines at once for a user.

---

## 3. Phased plan

### Phase 0 — SDK productization & decisions (platform repo; parallel to Phases 1–2)

**Goal:** make the SDK consumable and confirm the app's non-negotiables.

- [ ] Maven publishing for the AAR (`maven-publish`, groupId/artifactId/version, publish to Maven Central or GitHub Packages) — today the only artifact is `sdk-release.aar` attached to GitHub releases.
- [ ] Versioning/stability policy; cadence for updating the pinned rust-dashcore rev (currently a raw git rev in `Cargo.toml`).
- [ ] **Verify/implement CoinJoin-account discovery on restore** in `platform-wallet` (funds on the DIP-9 CoinJoin path must be found during rescan and spendable). *Blocking for Phase 5.*
- [ ] API gaps the app needs (file issues now):
  - `spendable` balance field (Kotlin `Balance` lacks it; PARITY.md flags it),
  - fee-rate policy suitable for Dash (static default fine; expose clearly),
  - coin-selection needs for integrations (CrowdNode uses `ByAddressCoinSelector` / `ExactOutputsSelector` semantics),
  - richer tx metadata for history UI if needed (confidence depth),
  - decision: BIP70 support (SDK-side, app-side over the signer, or drop).
- [ ] App decisions with data: raise minSdk 24 → 29 (measure user %), drop 32-bit ABIs (Play has required 64-bit since 2019; Platform features already excluded on 32-bit), accept compact-filter sync model (no Bloom filters).
- [ ] Broaden L1 send/broadcast automated test coverage in the SDK (currently thin; the DashPay path itself is flagged as compile+Robolectric-only verified).

**Exit criteria:** published versioned AAR; coinjoin-account restore verified; minSdk/ABI sign-off; issue list for API gaps triaged.

### Phase 1 — Neutralize the seam (this repo; **starts now**, no SDK dependency)

**Goal:** only the `wallet` module (and adapter impls) knows about dashj. This is the largest de-risking step and is 100% executable today.

Current leakage: 225 files in `wallet`, 54 in `common`, 33 crowdnode, 22 coinbase, 20 maya, 15 exploredash, 3 uphold import `org.bitcoinj`/`org.dashj`. Most pervasive: `Coin` (151), `Transaction` (90), `Sha256Hash` (80), `Address` (74), `Wallet` (64), `MonetaryFormat` (52), `NetworkParameters` (50), `Fiat` (38).

- [ ] Define neutral core types in `common` (no dashj imports): a Dash amount value class over duff `Long` (+ formatting), address wrapper (string + network), `TxId`, a neutral transaction view type for history/UI, `ExchangeRate` without `org.bitcoinj.utils.Fiat`.
- [ ] Refactor interface signatures to neutral types: `WalletDataProvider`, `SendPaymentService` (drop `SendRequest`/`InsufficientMoneyException` surface), `BlockchainStateProvider` (drop `AbstractBlockChain`/`PeerGroup.SyncStage`), `TransactionWrapper`/`TransactionWrapperFactory`, `ConfirmTransactionService`, `TransactionMetadataProvider`.
- [ ] Migrate integrations to the neutral types and remove their `dashj-core` Gradle dependency: uphold (trivial) → exploredash → maya → coinbase → crowdnode (hardest: models CrowdNode API responses as dashj `Transaction` subtypes; rework to neutral tx views + wallet-side filters).
- [ ] Keep dashj-backed implementations of everything (behavior unchanged); `util/DashJExt.kt`-style adapters live with the implementation, not the interface.

**Exit criteria:** `grep org.bitcoinj|org.dashj` ≈ 0 outside `wallet` + designated adapter files; integration modules build without dashj; app behaves identically.

### Phase 2 — Remove CoinJoin mixing (**starts now**, independent of the SDK)

**Goal:** no mixing capability; previously mixed balances remain fully spendable.

Delete (self-contained):
- [ ] `service/CoinJoinService.kt` (~785 lines), `data/CoinJoinConfig.kt` (DataStore `"coinjoin"`), DI binding in `DashPayModule.kt`.
- [ ] `ui/coinjoin/*` (activity, info/level fragments, viewmodel), `nav_coinjoin.xml`, manifest entry, `mixing_anim.json`.
- [ ] `MixingStatusCard.kt` (home), `MixDashFirstDialogFragment` + viewmodel, coinjoin settings row (`SettingsFragment`, `SettingsScreen`/`SettingsViewModel`, `MoreFragment`).
- [ ] `MaxOutputAmountCoinJoinCoinSelector.kt`; the `coinJoinSend` path in `SendCoinsTaskRunner`/`SendCoinsViewModel`/`SendCoinsFragment` (always use `ZeroConfCoinSelector` → old mixed UTXOs are ordinary coins to it).
- [ ] Mixing notification path in `BlockchainServiceImpl` (`createCoinJoinNotification`, `ForegroundService.COINJOIN_MIXING` promotion).
- [ ] `getMixedBalance`/`observeMixedBalance` from `WalletDataProvider`/`WalletApplication`/`WalletBalanceObserver`/`MainViewModel`; `WalletUIConfig.LAST_MIXED_BALANCE`.
- [ ] `useCoinJoin` threading in Platform top-ups (`CreateIdentityService`, `TopUpRepository`, `RequestUserNameViewModel` `COINJOIN_SPENDABLE` usage).
- [ ] Analytics `CoinJoinPrivacy` events; ~38 base strings + ~18 locales; 6 drawables; 4 layouts; time-skew coinjoin dialog variant.

Keep (critical for old funds + history):
- [ ] **Wallet loads as `WalletEx` and `initializeCoinJoin(...)` still runs on load** so the CoinJoin keychain (DIP-9 path) is recognized and its UTXOs are spendable. Do not strip the keychain from the wallet file.
- [ ] Historical transaction labeling: keep `CoinJoinTxResourceMapper`, `CoinJoinMixingTxSet`, `CoinJoinTxWrapperFactory` (read-only) so old mixing tx groups still render sensibly. (Alternative: flatten to generic rows — product call.)
- [ ] Update tests: `SendCoinsTaskRunnerTest`, `MainViewModelTest`, BIP70 test, `coinjoin.wallet` fixture (repurpose as the "old mixed funds stay spendable" regression test).

**Exit criteria:** no mixing UI/service; regression test proves the `coinjoin.wallet` fixture's mixed UTXOs are spendable via the normal send flow; settings/home clean.

### Phase 3 — Introduce the SDK; replace the Platform/DashPay stack

**Goal:** drop `org.dashj.platform:dash-sdk-{java,kotlin,android}` (and its per-flavor `dppVersions` matrix); DashPay/identity/DPNS runs on the Kotlin SDK. dashj-core still owns L1.

- [ ] Add the SDK dependency (Maven from Phase 0); init `Sdk` + `WalletManagerStore` for the app's network; **do not start SDK SPV** in this phase.
- [ ] Reconcile storage/security: SDK owns its own Room DB + Keystore-backed secret store (`org.dashfoundation.wallet.secrets`); feed it the mnemonic via `MnemonicResolverAndPersister` from the app's existing PIN-encrypted seed at first use.
- [ ] Port `service/platform/*` (12 files: `PlatformService`, `PlatformSyncService`, `PlatformBroadcastService`, `IdentityRepository`, `TopUpRepository`, workers) and `ui/dashpay/PlatformRepo` to SDK namespaces (`identities`, `dpns`, `documents`, `dashpay`, `credits`).
- [ ] Bridge L1↔L2: identity funding asset-locks are still created by the dashj wallet in this phase — wire dashj-built asset locks into SDK identity registration/top-up (SDK accepts asset-lock funding), or route top-ups through SDK funding APIs.
- [ ] Contested username voting, invites, profiles, contact requests — port `ui/dashpay/` viewmodel data sources one flow at a time behind the existing `Constants.SUPPORTS_PLATFORM` gate.

**Exit criteria:** dashj-platform artifacts removed from `wallet/build.gradle`; all DashPay flows pass on testnet against the SDK.

### Phase 4 — Shielded balances (the CoinJoin replacement, user-facing)

**Goal:** ship the new privacy model: shield L1 funds/credits into the Orchard pool; spend/unshield/withdraw.

- [ ] Lifecycle wiring: `configureShielded(dbPath)` per network, `bindShielded(walletId)`, shielded sync loop (`startShieldedSync` / interval), following `AppContainer` in the example app.
- [ ] Balance model & home UI: shielded balance shown alongside (or inside) the main balance; replaces the old mixed/unmixed split.
- [ ] Flows (reference: example app `SendTransactionScreen` — `CORE_TO_CORE`, `PLATFORM_TO_SHIELDED`, `SHIELDED_TO_SHIELDED`, `SHIELDED_TO_PLATFORM`, `SHIELDED_TO_CORE`):
  - Shield: from L1 via asset lock (`shieldedFundFromAssetLock`) and from Platform credits (`shieldedShield`, only when credits > 0),
  - Send shielded→shielded (with ≤32-byte memo),
  - Unshield to credits (`shieldedUnshield`) and withdraw to L1 (`shieldedWithdraw`, 1000:1 credits→duffs, Fibonacci fee constraint).
- [ ] UX for ~30s Halo2 proving per spend (progress state, cancel semantics) and the non-retryable `ShieldedSpendUnconfirmed` ambiguous-broadcast outcome (needs explicit "check before retry" UX).
- [ ] Shielded activity in transaction history (`ShieldedActivityEntity` → history rows); seed-pool participation policy (anonymity-set filler notes).
- [ ] Migration UX for former mixers: their mixed coins are now just L1 funds — first-run prompt offering "shield your balance".
- [ ] Settings: shielded on/off + sync status replaces the CoinJoin settings entry.

**Exit criteria:** shield → transfer → unshield → withdraw round-trip on testnet with failure-mode handling; design-approved UI.

### Phase 5 — L1 cutover (the big one; gated on Phase 0 hardening)

**Goal:** SDK SPV replaces dashj `PeerGroup`/`SPVBlockStore`/`WalletEx`; dashj no longer runs.

- [ ] **Wallet migration flow:**
  - Unlock seed with the user's PIN (existing `SecurityGuard`), call `createWallet(mnemonic, birthHeight = earliest-key-time)` to bound the rescan.
  - Verify discovery of: BIP44 account funds, **CoinJoin-account funds** (Phase 0 prerequisite), Platform/identity keys (`AuthenticationGroupExtension` equivalents re-derived by the SDK).
  - Legacy wallets that are not seed-derivable (pre-BIP39 random keys, if any remain in the fleet): build a sweep-to-new-wallet flow instead.
  - Carry over app-level data that the wallet file won't: tx metadata Room DB, address labels, fiat-at-time-of-tx records.
  - Keep the old `.wallet` file untouched as an escape hatch; migration behind a flag with rollback for N releases.
- [ ] Replace `BlockchainServiceImpl` internals: `startSpv`/`stopSpv` + `spvProgress` (headers / filter headers / filters / masternode phases) mapped into the existing blockchain-state UI and sync notification; delete `PeerGroup`, `BlockChain`, `SPVBlockStore`, `MasternodeSync`, bloom-filter and peer-management code paths.
- [ ] Replace send path: `SendCoinsTaskRunner`/`SendCoinsOfflineTask` → `CoreTransactionBuilder` + `sendToAddresses` (per-wallet mutex already handles the double-spend window); map coin-selection strategy choices; leftover-balance rules (CrowdNode) on neutral types; fee policy.
- [ ] Replace balances/history/receive: `WalletBalanceObserver` → SDK balance + Room flows; `TransactionWrapper` factories over SDK `TransactionEntity` (`context` enum drives InstantSend/ChainLock badges); receive addresses from `core_addresses` pools.
- [ ] BIP70: implement app-side over SDK signing, or drop (per Phase 0 decision). NFC/`dash:` URI flows re-pointed at neutral types (done in Phase 1).
- [ ] Security-model reconciliation: app PIN remains the auth gate; SDK Keystore storage holds the mnemonic; define wipe/reset and backup-reveal flows against SDK storage.
- [ ] Rollout: flavor-gated (`_testNet3` first) → staging → prod staged % with migration telemetry (rescan duration, discovered-balance match vs dashj, failure rates). Battery/network benchmarking of compact-filter sync vs current bloom sync.

**Exit criteria:** migration success (balance parity incl. old mixed funds and identities) on a corpus of real wallet files; sync/battery/crash parity; dashj not initialized at runtime.

### Phase 6 — dashj removal & cleanup

- [ ] Remove `org.dashj:dashj-core`, `dashj-bls-android`, `dashj-x11-android`, `dashj-scrypt-android` from all modules; remove bitcoinj packaging excludes and `Context` propagation.
- [ ] Delete `WalletEx`/protobuf load-save code after the migration horizon (keep the migration reader for N more releases).
- [ ] Finalize minSdk 29 / 64-bit-only; ProGuard rules for the SDK; update CLAUDE.md/README; delete dead strings/resources across locales.

---

## 4. Sequencing & parallelism

```
now ──────────────────────────────────────────────────────▶
Phase 0 (platform repo) ─────────────┐ (publishing, restore-coinjoin, hardening)
Phase 1 seam neutralization ──┐      │
Phase 2 coinjoin removal ──┐  │      │
                           ▼  ▼      ▼
                    Phase 3 platform swap ──▶ Phase 4 shielded ──▶ Phase 5 L1 cutover ──▶ Phase 6 cleanup
```

Phases 1 and 2 are pure app work and can ship to production on dashj long before the SDK is ready — they make the app better regardless. Phase 3 needs only SDK publishing. Phase 5 is last and gated on Phase 0 hardening + the coinjoin-account restore guarantee.

## 5. Top risks

1. **CoinJoin-account discovery on SDK restore** — if the rescan doesn't cover the DIP-9 CoinJoin derivation path, previously mixed funds vanish at migration. Verify in `platform-wallet` before any Phase 5 work.
2. **Non-seed-derivable legacy wallets** — need fleet data on how many pre-BIP39 wallets exist; sweep flow if > 0.
3. **SDK preview quality on L1** — send/broadcast paths have thin automated coverage; the whole L1 surface is intentionally minimal ("Platform-first" SDK). Budget hardening time in the platform repo.
4. **Compact-filter sync performance** on mobile radios/battery vs the current bloom-filter model — benchmark early (can be done with the example app today).
5. **minSdk 29 + 64-bit-only** — user-base cut needs product sign-off.
6. **Shielded UX physics** — ~30s proving per spend and `ShieldedSpendUnconfirmed` ambiguity are UX problems, not bugs; design for them from the start.
7. **Two persistence worlds during transition** — dashj `.wallet` + app Room vs SDK Room + Keystore. The flag-gated cutover (never both SPV engines live for one user) keeps this manageable; dual-running would not be.

## 6. Open questions to resolve early

- Does `platform-wallet` restore scan the CoinJoin account path? (blocking)
- Keep or drop BIP70? (CTX/DashDirect dependencies?)
- Keep historical mixing-tx grouping UI or flatten old mixing txs to plain rows?
- Fleet stats: 32-bit devices, API 24–28 devices, pre-BIP39 wallets.
- Where does the app's PIN sit relative to SDK Keystore/biometric gating (one gate or two)?

## Phase 0 status (updated 2026-07-08)

- ✅ **CoinJoin-restore verdict: SAFE at the pinned rust-dashcore rev (`647fa982`, 2026-07-06), with
  conditions.** Restore-from-mnemonic auto-creates the CoinJoin account at the dashj-matching DIP-9
  path `m/9'/coin_type'/4'/account'` (external + internal branches, gap limit 30) and its addresses
  are in the SPV compact-filter watch set from registration — no explicit binding needed.
  **Conditions:** (1) never regress the rust-dashcore pin below `647fa982` — the March rev derived
  the WRONG CoinJoin path (`m/9'/coin'/account'`, missing `4'`) and would silently lose funds; add a
  CI pin guard + a dashj-vs-SDK address-derivation test vector. (2) The migration must pass
  `birthHeight = 0` (or the wallet's creation height) to `createWallet` — default resolves to the
  SPV tip and scans nothing historical. (3) Verify heavy mixers don't exceed the 30-address CoinJoin
  gap limit; raise it for migration scans if needed.
- ✅ **Maven publishing added** to `packages/kotlin-sdk/sdk/build.gradle.kts` on platform branch
  `feat/kotlin-sdk-maven-publish` (local worktree): `org.dashfoundation:dash-sdk-android`,
  release AAR + sources + POM; `publishToMavenLocal` verified resolving. Remote repo block pending a
  hosting decision.
- ✅ **App prerequisites applied:** minSdk 29 (all modules), 64-bit-only ABIs (arm64-v8a + x86_64),
  BIP70 vendored from dashj-core 22.0.3 sources into `org.dash.wallet.common.payments.bip70`
  (all usages repointed; 15 BIP70 tests green).
- 🔄 **Native SDK build** (cargo-ndk, NDK r28, both ABIs) running locally; on completion:
  `:sdk:publishToMavenLocal`, then Phase 3 wiring can begin against the local artifact.

## Phase 3 status (updated 2026-07-08)

- ✅ **3a — SDK bootstrap scaffold**: `DashSdkService` (lazy `ensureStarted()`: Sdk init → Room →
  WalletStorage → WalletManagerStore.activate → loadPersistedWallets, mirroring the example app's
  AppContainer). No production invocation by default.
- ✅ **3b — seed bridge**: `SecurityGuardMnemonicProvider` over the canonical
  `SecurityFunctions.decryptSeed` path (caller owns auth); `bindAppWallet` idempotently
  creates/rehydrates the SDK wallet (birthHeight=0 until Phase 5 maps creation time → height).
- ✅ **3c — first production flow on the SDK**: DPNS reads (`PlatformRepo.getUsername` resolve;
  `IdentityRepository.searchUsernames` prefix/exact) routed through `SdkUsernameQueries` behind
  `DashPayConfig.USE_KOTLIN_SDK_DPNS_READS` (default OFF; re-read per lookup; any SDK failure
  falls back to the dashj path automatically).
- ✅ **3d — contested-name vote state + profile reads**: `SdkVotingQueries`
  (`getVoteContenders` via `sdk.voting.contestedResourceVoteState`) and `SdkProfileQueries`
  (`profiles.get`/`getList` via `sdk.documents.search` on the DashPay contract), same flag,
  same auto-fallback.
- ✅ **3e — key-derivation parity gate + remaining DPNS reads + first WRITE seam**:
  - **Task A verdict — CONDITIONAL PARITY.** dashj (dashj-core 22.0.3 bytecode:
    `DerivationPathFactory`, `AuthenticationGroupExtension`, `BlockchainIdentity`) registers
    identity auth key `i` at `m/9'/coin'/5'/0'/0'/0'/i'` (ECDSA secp256k1, all hardened;
    coin 5' main / 1' test; 4 keys at i=0–3: MASTER/AUTH, HIGH/AUTH, MEDIUM/ENCRYPTION,
    CRITICAL/TRANSFER; funding `m/9'/coin'/5'/1'`, topup `…/2'`, invitations `…/3'`).
    The Kotlin SDK (rust-dashcore @647fa982 `key-wallet/src/dip9.rs`,
    `rs-platform-wallet .../identity_handle.rs`) derives
    `m/9'/coin'/5'/0'(auth)/0'(ECDSA)/identity_index'/key_index'` — identical trees for
    `identity_index = 0`, the only chain dashj creates. So the SDK CAN sign for a
    dashj-registered identity once that identity is discovered/managed by the SDK wallet.
    Registration ROLE tables differ (SDK: MASTER/CRITICAL/HIGH/TRANSFER at 0–3) — irrelevant
    for signing existing identities, relevant if the SDK ever registers new ones.
    NOT yet verified: DIP-15 friendship/payment derivation parity (see 3e gaps below).
  - `names.getByOwnerId`/`names.getList` routed via `sdk.dpns.usernames` in
    `SdkUsernameQueries` (same read flag, same fallback; per-identity loop replaces dashj's
    100-id `whereIn(records.identity)` batches).
  - **Write seam** `SdkDashPayWrites` behind NEW flag `USE_KOTLIN_SDK_DASHPAY_WRITES`
    (default OFF): `PlatformBroadcastService.sendContactRequest` + `broadcastUpdatedProfile`
    route through the SDK's wallet-bound dashpay ops with a three-valued
    no-double-broadcast contract (`Broadcast` / `NotBroadcast` = provably nothing submitted →
    dashj fallback / `Ambiguous` = may have landed → surface error, NEVER dashj retry).
    Preflights (wallet bound via `bindAppWallet`, identity managed by SDK wallet) fail fast
    to `NotBroadcast`; since nothing binds the wallet in production yet, the path is inert
    even with the flag on. On SDK success, local state reconciles from Platform via dashj
    reads (`watchContactRequest` / `profiles.get`) and the unchanged bookkeeping tail
    (DIP-15 keychain add, DB rows, listeners).
- **SDK issues to file**: (1) `dpns.resolve` returns InternalError with a message instead of a
  NotFound code/null for unregistered names; (2) DPNS projections lack `$createdAt`/document
  id/alias records; (3) `dpns.usernames(limit=0)` defaults to 10 — callers must pass an
  explicit limit for dashj parity; (4) `Dashpay.createOrUpdateProfile` takes raw
  `avatarBytes` only (recomputes hash+fingerprint Rust-side) — profiles that carry
  `avatarHash`/`avatarFingerprint` without raw bytes cannot be routed; (5) no public
  "is identity managed" probe (Phase 3e uses `dashpay.syncState(id) != null`).
- **3e gaps / 3f next**:
  - Wire `bindAppWallet` + SDK **identity discovery** into a production flow so the app's
    dashj-registered identity becomes a managed identity (the write path's preflight
    currently always falls back). `PlatformWalletManager.identityRegistration` has the
    discovery bridge.
  - **Verify DIP-15 parity** (friendship xpub + accountReference derivation, dashj
    `FEATURE_PURPOSE_DASHPAY 15'` vs rust-dashcore dip9.rs `FEATURE_PURPOSE_DASHPAY = 15`)
    before enabling `USE_KOTLIN_SDK_DASHPAY_WRITES` anywhere real: an SDK-sent contact
    request whose embedded xpub dashj cannot re-derive would watch wrong friendship
    addresses.
  - Then: ~~accept-contact-request~~ (3g: verified covered by the sendContactRequest
    routing — see the Phase 3g section), identity registration/topup via the SDK
    asset-lock bridge, and the DashPay sync loops.

## Phase 4 design references (added 2026-07-08)

Shielded-balances UX designs (iOS Figma, "DashPay – iOS" file — convert to Android/Compose as
appropriate, mapping to the app's existing design system and Common Components):
- https://www.figma.com/design/O6RLY0jppyI1SSMY6kttS1/DashPay---iOS?node-id=1693-15911&m=dev
- https://www.figma.com/design/O6RLY0jppyI1SSMY6kttS1/DashPay---iOS?node-id=231-200&m=dev
- https://www.figma.com/design/O6RLY0jppyI1SSMY6kttS1/DashPay---iOS?node-id=1746-18462&m=dev
- https://www.figma.com/design/O6RLY0jppyI1SSMY6kttS1/DashPay---iOS?node-id=1746-18478&m=dev
Implementation should go through the figma-to-compose flow (fetch design context, map to existing
components, vector drawables for missing icons).

## Phase 3e/3f verdicts (updated 2026-07-08)

- **DIP-13 identity-key parity: VERIFIED byte-identical** for identity index 0 (the only chain
  dashj creates) — the SDK can derive and sign with dashj-registered identity keys.
- **DIP-15 friendship-key parity: FUNDS-SAFE (PARTIAL).** The friendship xpub a contact request
  carries and the derived/watched payment addresses are byte-identical across dashj and the SDK
  (same path m/9'/coin'/15'/0'/idA/idB, same 69-byte compact xpub, same ECDH+AES-256-CBC).
  One non-funds mismatch: `accountReference` extracts a different 28-bit HMAC slice
  (dashj: u32_LE(hmac[0..4])>>4; SDK rs-platform-encryption: u32_BE(hmac[28..32])>>4, "iOS
  convention"). Impact: possible duplicate contact-request documents / rotation-detection noise if
  both stacks author for the same channel — file an SDK issue to reconcile
  rs-platform-encryption/src/account_reference.rs (confirm iOS's deployed convention first).
- **3f production wiring done**: `SdkWalletBinder` binds the app wallet + attaches the existing
  identity via `identityRegistration.discoverIdentities` (no SDK gap) at two key-in-scope call
  sites (PlatformSynchronizationService.init, PlatformDocumentBroadcastService writes),
  fire-and-forget, single-flight, provably inert with flags off. 171 tests green.

## Phase 3g — accept-contact-request routing (verified 2026-07-08)

- **Verdict: already covered by the 3e routing — no new seam needed.** In this app there is no
  dedicated dashj "accept" broadcast: accepting an incoming contact request IS the reciprocal
  `sendContactRequest`. Traced every accept entry point (NotificationsFragment `onAcceptRequest`,
  ContactsFragment, DashPayUserActivity accept button, SendCoinsFragment) →
  `DashPayViewModel.sendContactRequest` → `SendContactRequestOperation`/`SendContactRequestWorker`
  → `PlatformDocumentBroadcastService.sendContactRequest(toUserId = requester)` — the method
  already routed through `SdkDashPayWrites` (same preflight / three-valued no-double-broadcast
  contract). Flag off ⇒ byte-identical dashj behavior.
- **SDK's dedicated `Dashpay.acceptContactRequest`/`acceptIncomingRequest` deliberately NOT
  used**: it requires the incoming request in the SDK wallet's LOCAL contact state (returns
  false otherwise — the app doesn't keep that synced), and its Rust-side external-account
  registration would duplicate/diverge from the app's dashj DIP-15 keychain bookkeeping. The
  Platform document it broadcasts is the same reciprocal `contactRequest`.
- **Reconciliation-tail parity review (Broadcast case, accept direction)**: complete.
  - Incoming half (sending-to-requester DIP-15 keychain via `addPaymentKeyChainToContact` +
    `fromContactRequest` DB row) is done by `PlatformSyncService.updateContactRequests` /
    `checkAndAddReceivedRequest` when the incoming request syncs — independent of which stack
    broadcasts the reciprocal.
  - Outgoing half is `finalizeSentContactRequest`, shared verbatim by the dashj and SDK paths:
    receiving keychain (`addPaymentKeyChainFromContact` reads xpub/accountReference back from the
    watched document — works for the SDK-authored document too, modulo the already-documented
    DIP-15 accountReference-slice mismatch), bloom-filter refresh, `DashPayContactRequest` DB row,
    contact profile refresh, contacts-updated listeners. "Established" state is derived from
    having both DB rows; the dashj path has no additional accept-only bookkeeping.
- **Remaining unrouted DashPay-adjacent writes** (inventory of `PlatformBroadcastService` + repos):
  - `broadcastIdentityVerify` (live via `BroadcastIdentityVerifyWorker`) — the Kotlin SDK has NO
    identityVerify surface yet; stays on dashj. File an SDK feature request if it should route.
  - `broadcastUsernameVotes` — masternode contested-resource votes signed with masternode voting
    keys, not a wallet-identity DashPay write; out of the `USE_KOTLIN_SDK_DASHPAY_WRITES` scope.
    (The SDK does expose `voting/VoteCasting.castVote` if this is ever migrated separately.)
  - `PlatformRepo.createDashPayProfile` — `@Deprecated`, zero callers; dead code, nothing to route.
  - Identity registration / username preorder+register / topups / invitations — identity writes,
    tracked as their own later phase (SDK asset-lock bridge), unchanged here.

## Live testnet validation (2026-07-08, Galaxy S22 Ultra, testnet)

Verified on-device with the debug flags ON:
- SDK bootstrap + native lib load + Room/Keystore storage + wallet persistence across restarts.
- Seed bridge: PIN-derived key → bindAppWallet → SDK wallet from the app's mnemonic (idempotent).
- **Identity discovery found and attached the dashj-registered identity — empirical DIP-13 parity.**
- Phase 2 regression: correct balance incl. previously-mixed funds; grouped mixing history intact.
- DashPay flows: username search, profile view, contact request sent → received → accepted.
- Write fallback contract validated live: SDK attempt → pre-broadcast signing rejection →
  clean dashj fallback (request delivered), no double-broadcast.
- Bugs found live and fixed: Firebase-less builds crashed at startup (3 spots); `syncState`
  throws on not-managed identity; signing failure misclassified as ambiguous; discovered
  identities lacked signable keys (SDK persistence bridge silently skips key storage outside
  the 30s auth-gated Keystore window) — now healed with byte-verified derivation + retry.
- Remaining friction for full SDK-path writes: the SDK's auth-gated key alias (30s window);
  needs an SDK-side policy option or app-supplied auth gate.
