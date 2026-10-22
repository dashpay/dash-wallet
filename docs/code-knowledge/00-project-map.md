# Dash Wallet Android — project map for the v12 line

Tree studied: `feat/kotlin-sdk-phase-1` at `421514a3a` plus the `/pre-review` skill commit, read on 2026-10-22.
All paths are relative to the repo root.
Statements marked **(inferred)** are conclusions drawn from code shape rather than read directly; everything else
has a path behind it. Line numbers are as of HEAD and drift quickly on this branch (some docs already cite stale
line numbers, see §7).

---

## 0. Orientation in ten lines

1. Four Android modules carry the app: `wallet` (the APK, non-standard `src/`/`res/`/`test/` layout), `common`
   (dashj-free shared interfaces, money kit, Compose design system), `features/exploredash`, and
   `integrations/{uphold,coinbase,crowdnode,maya}`; plus legacy `integration-android` / `sample-integration-android`.
2. v12 = the migration from **dashj** (bitcoinj fork, SPV + `.wallet` protobuf) and the **old dashj-platform SDK**
   (`org.dashj.platform:dash-sdk-{java,kotlin,android}`) to the **new Dash Platform Kotlin SDK**
   (`org.dashj:dash-sdk-android`, Kotlin package `org.dashfoundation.dashsdk`, Rust core over JNI).
3. Every shipping install is treated as **CUT_OVER**: the SDK's Rust SPV owns L1 (balance, history, sends);
   dashj's peergroup does not start unless the user turns on *Tools › dashj sync* (a diagnostic).
4. The dashj `Wallet` object is still loaded on every launch and is still the **seed source of truth** (the SDK
   wallet is bound from the mnemonic decrypted out of the dashj wallet).
5. CoinJoin mixing is removed; old mixed coins are migrated by `CoinJoinFundsMigrationService`. The "replacement"
   is the **shielded (Orchard) pool** on Platform credits (`ShieldedBalanceService`).
6. Identities/usernames/DashPay are a hybrid: legacy dashj-platform paths remain, with SDK paths routed in behind
   `USE_KOTLIN_SDK_*` DataStore flags that are **seeded ON in every build, including prodRelease**.
7. Funds-moving SDK calls return `SdkWriteResult { Broadcast | NotBroadcast | Ambiguous }` — the
   no-double-broadcast contract; `Ambiguous` must never be offered as "retry".
8. MVVM + Hilt + Room (v22, migrations from 11) + DataStore configs + WorkManager; Views with **ViewBinding**
   (no DataBinding) mixed with Compose (new UI must be Compose).
9. Tests: JUnit4 + MockK in `wallet/test` (236 Kotlin files), Robolectric for ~34; most SDK logic is tested
   through **pure top-level functions** extracted for "host-testability". No Turbine, no JUnit5, no detekt.
10. CI runs `fastlane test` (all modules' debug unit tests) + a release build; ktlint runs only on modules that
    apply the plugin (not `wallet`, `common`, `coinbase`).

---

## 1. Modules and their roles

### 1.1 `settings.gradle`

```groovy
include 'wallet'
include 'integration-android'
include 'sample-integration-android'
include ':common'
include ':integrations:uphold'
include ':integrations:coinbase'
include ':integrations:crowdnode'
include ':features:exploredash'
include ':integrations:maya'
```

### 1.2 Module table

| Module | Kind / namespace | Role | Internal deps | Size (kt / java) |
|---|---|---|---|---|
| `wallet` | application, `namespace "de.schildbach.wallet_test"` (`wallet/build.gradle:283`) | The app: UI, services, dashj engine, SDK adapter layer (`service/platform/sdk/*`), Room `AppDatabase` | all others (`wallet/build.gradle:51-60`) | 828 / 115 |
| `common` | library, `org.dash.wallet.common` (`common/build.gradle:55`) | Neutral service interfaces (`common/.../services/*`, `WalletDataProvider`), money kit (`common/.../money/*`), BIP70 code copied from dashj, Compose design system (`common/.../ui/components/*`), `BaseConfig` DataStore base | none | 186 / 41 |
| `features/exploredash` | library | Explore Dash (merchants/ATMs map, own `ExploreDatabase`), DashSpend gift cards (CTX, PiggyCards) | `:common` (`features/exploredash/build.gradle:74`) | 111 / 0 |
| `integrations/uphold` | library | Uphold account/transfer | `:common` | 21 / 10 (legacy Java) |
| `integrations/coinbase` | library (Compose on) | Coinbase buy/convert/transfer | `:common` | 69 / 0 |
| `integrations/crowdnode` | library | CrowdNode staking; **senders fenced off** (see §2.9) | `:common` | 57 / 0 |
| `integrations/maya` | library (Compose on) | Maya / SwapKit / NEAR-intents swaps; swap-tx construction lives in `wallet` (`MayaBlockchainApiImpl`) | `:common` | 116 / 0 |
| `integration-android` | library | `BitcoinIntegration.java` — legacy payment-intent API for third-party apps | none | 0 / 1 |
| `sample-integration-android` | app | sample consumer of the above | `integration-android` | — |

Dependency direction: `wallet → {common, exploredash, integrations/*, integration-android}`;
every feature/integration module depends **only** on `:common`, never on `:wallet`
(stated as a rule in `integrations/crowdnode/CLAUDE.md` "Module Setup"; true for all module build files).
Feature modules talk to the wallet only through interfaces in `common/src/main/java/org/dash/wallet/common/services/`
(`SendPaymentService`, `BlockchainStateProvider`, `TransactionMetadataProvider`, `AuthenticationManager`,
`ConfirmTransactionService`, `NotificationService`, …) and `WalletDataProvider.kt`. Their implementations are bound in
`wallet/src/de/schildbach/wallet/di/*` — e.g. `AppModule.provideNeutralSendPaymentService`
(`wallet/src/de/schildbach/wallet/di/AppModule.kt:137`) and the Maya binding that keeps dashj inside `wallet`
(`AppModule.kt:181-185`).

**dashj containment is real now:** `grep -rl "import org.bitcoinj"` gives 237 files in `wallet/src`, 1 in
`wallet/src/org/bitcoinj/wallet/FriendChainAccess.java`, and **0** in `common`, `features`, `integrations`.
`DASHJ-KILL-LIST.md` (2026-07-18) still says common has 68 and crowdnode 25 — that inventory is stale on those
counts. `common` carries a self-contained port of dashj's money types instead (§4.1).

### 1.3 Build setup (root)

`build.gradle` (root) is the version catalogue:

- `kotlin_version = '2.1.0'`, `coroutinesVersion = '1.6.4'` (old), `roomVersion = '2.6.1'`, `hiltVersion = '2.53'`,
  AGP `8.13.2`, Gradle wrapper `8.13` (`gradle/wrapper/gradle-wrapper.properties`; root `CLAUDE.md` still says 8.9).
- `dashjVersion = '22.0.5'` (`build.gradle:6`).
- `dashSdkVersion = '0.1.0-v42int30-20261009.200225-1'` (`build.gradle:20`) — the **new Kotlin SDK**, pinned to an
  exact timestamped snapshot ("pin-don't-track"); the long comment above it records which platform integration
  branch it is built from.
- `dppVersion = "5.0.0-20261016.200955-3"` (`build.gradle:43`) — the **legacy** dashj-platform SDK, also pinned
  to a timestamped snapshot; its comment explains why 5.0.0 is needed (document serialization format 3 after
  Platform protocol v14).
- Repositories: `mavenLocal()` first (a locally published SDK wins), a content-filtered GitHub Packages repo for
  `org.dashj:dash-sdk-android` only, Sonatype snapshot repos (`build.gradle:115-160`).
- `gradle.properties`: configuration cache on, `android.nonTransitiveRClass=false`, Jetifier on.

`wallet/build.gradle` specifics:

- `minSdkVersion 29`, `targetSdkVersion 36`, `compileSdk 36`; `versionCode 12000019`, `versionName "12.0.0-sync"`
  overridable by `-PversionCode/-PversionName` (`wallet/build.gradle:288-292`).
- **64-bit only** (`ndk.abiFilters 'arm64-v8a','x86_64'`; `-PqaArm64` drops x86_64) because the SDK's Halo2/Orchard
  core cannot build 32-bit (`wallet/build.gradle:296-307`).
- `buildFeatures { viewBinding true; compose true; buildConfig true }` (`wallet/build.gradle:540-544`) — no
  `dataBinding`.
- `lint { abortOnError false; disable 'MissingTranslation' }` (`wallet/build.gradle:555-559`).
- Secrets come from `../service.properties` and `../local.properties` into `BuildConfig` fields per flavor
  (`wallet/build.gradle:243-251`, `396-497`).
- Legacy SDK is declared **per flavor** after `android {}` (`wallet/build.gradle:570-576`):
  ```groovy
  dppVersions.each { flavor, ver ->
      ["java", "kotlin", "android"].each { artifact ->
          "${flavor}Implementation"("org.dashj.platform:dash-sdk-$artifact:$ver")
  ```
  (all four flavors map to the same `dppVersion` today, `wallet/build.gradle:34-39`).
- A Gradle `TransformAction` (`NioCovariantBufferPatch`, `wallet/build.gradle:578-720+`) **rewrites dependency
  bytecode** (`MappedByteBuffer.position(I)` covariant calls in dashj-core 22.x) so dashj's `SPVBlockStore` does not
  crash with `NoSuchMethodError` on Android ≤ 15. It exists only because of dashj.

### 1.4 Flavors and network selection

Four flavors in one dimension (`wallet/build.gradle:393-498`):

| Flavor | applicationId | Network | Notes |
|---|---|---|---|
| `prod` | `hashengineering.darkcoin.wallet` | MainNet | `IS_PROD_BUILD = true` |
| `_testNet3` | `hashengineering.darkcoin.wallet_test` | TestNet3 | own manifest `testNet3/AndroidManifest.xml`, res `testNet3/res` |
| `staging` | `org.dash.dashpay.testnet` | TestNet3 (same branch as `_testNet3`) | built/distributed by `dashpay.yml` |
| `devnet` | `org.dash.dashpay.devnet` | `OuzoDevNetParams` | devnet name parsed from params |

Network selection is a **static initializer switch on `BuildConfig.FLAVOR`** in
`wallet/src/de/schildbach/wallet/Constants.java:80-166`:

```java
switch (BuildConfig.FLAVOR) {
    case "prod": { ... NETWORK_PARAMETERS = MainNetParams.get(); ... SUPPORTS_PLATFORM = !is32Bit; ...
    case "staging":
    case "_testNet3": { ... NETWORK_PARAMETERS = TestNet3Params.get(); ...
    case "devnet": { ... NETWORK_PARAMETERS = OuzoDevNetParams.get(); ...
    default: throw new IllegalStateException("Unsupported flavor " + BuildConfig.FLAVOR);
```

It also pushes neutral values into `common` (`org.dash.wallet.common.util.Constants`: `ADDRESS_NETWORK`,
`MAX_MONEY`, `BUILD_FLAVOR`, `FAUCET_URL`, `EXPLORE_GC_FILE_PATH`) so dashj-free modules see the network
(`Constants.java:162-165`). The new SDK's network is derived from the dashj params
(`toSdkNetwork(Constants.NETWORK_PARAMETERS)`, `service/platform/sdk/DashSdkServiceImpl.kt:60-66`). The SDK runtime is
"flavor-fixed" — no network-switch observer (`DashSdkServiceImpl.kt` KDoc "Bootstrap order").
Flavor-dependent behaviour elsewhere is sometimes keyed on the **string** `BuildConfig.FLAVOR`, e.g.
`if (BuildConfig.FLAVOR.lowercase() == "prod") realService else FakeDashSpendService(...)`
(`di/AppModule.kt:128-132`) and `!BuildConfig.DEBUG && BuildConfig.FLAVOR == "prod"` (`ui/dashpay/utils/DashPayConfig.kt:146`).

---

## 2. The v12-specific parts

Primary documents (read them before touching these areas): `docs/kotlin-sdk-migration-plan.md` (phases 0–6,
architecture), `DASHJ-KILL-LIST.md` (remaining dashj seams; some counts stale), `docs/upgrade-memory-and-sync-plan.md`
(3,247 lines; the 2026-09 upgrade/OOM incident, the "immediate cutover, no dashj fallback" policy in §12),
`docs/kotlin-sdk-issues-to-file.md` (SDK defects the app works around), `docs/payment-methodology.md` (send
pipeline; **its CoinJoin sections describe removed code**).

### 2.1 What "the Kotlin SDK" is

Three distinct stacks coexist in `wallet`:

| Stack | Coordinate | Kotlin package | Used for | Import count in `wallet/src` |
|---|---|---|---|---|
| dashj (bitcoinj fork) | `org.dashj:dashj-core:22.0.5` + `dashj-{bls,x11,scrypt}-android` | `org.bitcoinj.*` | `.wallet` file, seed, dashj `Wallet`/`Transaction` types still used across the app; SPV only as diagnostic | 237 files |
| legacy Platform SDK | `org.dashj.platform:dash-sdk-{java,kotlin,android}:$dppVersion` | `org.dashj.platform.*` | DPNS/DashPay/identity via `Platform`, `BlockchainIdentity`, `DapiClient` (`PlatformRepo`, `PlatformService`, `CreateIdentityService`, workers) | 55 files |
| **new Kotlin SDK** | `org.dashj:dash-sdk-android:$dashSdkVersion` | `org.dashfoundation.dashsdk.*` | Rust SPV L1 engine, sends, identities, DPNS reads, DashPay writes, shielded pool, asset locks | 24 files (all under `service/platform/sdk`, `payments`, a few UI) |

The new SDK is Rust (`dash-spv`, `key-wallet`, `platform-wallet`, `rs-sdk-ffi` from rust-dashcore/platform) exposed
through JNI (`libdash_sdk_jni.so`) and a Kotlin facade: `Sdk`, `PlatformWalletManager`, `ManagedPlatformWallet`,
`ManagedCoreWallet`, `WalletStorage` (Keystore-backed secret store), `DashDatabase` (the SDK's own Room DB),
`ShieldedProver`, `CoreTransactionBuilder`, `TransactionDecoder` (architecture diagram:
`docs/kotlin-sdk-migration-plan.md` §2). It is **not** dashj and not the old DPP stack.

The app talks to it through one seam, `DashSdkService` (`service/platform/sdk/DashSdkService.kt:333`, impl
`DashSdkServiceImpl.kt`, bound in `di/PlatformSdkModule.kt:53`). Key properties of `DashSdkServiceImpl`:
- construction is inert — no native load until `ensureStarted()` (KDoc above the class; verified by
  `wallet/test/.../sdk/DashSdkServiceImplTest.kt`);
- `ensureStarted()` / `stop()` / `quiesceForWalletWipe()` are serialized by a coroutine `Mutex`; binds by a second
  `bindLock` (`DashSdkServiceImpl.kt:501-504`, `566-575`, `716-720`);
- refuses to start while a wallet wipe holds the `WalletSecretWriteFence` (`DashSdkServiceImpl.kt:569-571`);
- `WalletStorage(context, KeySecurityPolicy.DEVICE_BOUND)` (`DashSdkServiceImpl.kt:1762`): the master key is
  `setUnlockedDeviceRequired`, so **binding, DashPay crypto, identity key heal and all signing fail while the device
  is locked**; SPV scanning does not (table in `docs/upgrade-memory-and-sync-plan.md` §12.2).

Where the SDK's public API is insufficient, the app reaches into SDK internals:
`service/platform/sdk/CoreSendAllNative.java` is deliberately **Java** so it can call the SDK's Kotlin-`internal`
builder via its mangled JVM name (`builder.setFeeRate$sdk_release(...)`, line 235); its KDoc calls this "a loud,
pre-runtime canary" if the pinned AAR changes. `MasternodeVotingNative.java` uses the same pattern for the `internal` `WalletManagerNative`
(its KDoc, lines 32-38).

### 2.2 Cutover: who owns L1

`service/platform/sdk/CutoverStateMachine.kt` defines `CutoverState { DUAL_RUNNING, READY_OBSERVED, CUT_OVER,
SETTLED }` and the pure `nextCutoverState(...)` / `dashjEngineMayStart(state)`. Its file header is the most
important comment on the branch:

> "SHIPPING REALITY: there is no 'pre-cutover' user … Every shipping install is effectively [CUT_OVER] from its first
> launch." (`CutoverStateMachine.kt:21-45`)

- The persisted state is `DashPayConfig.CUTOVER_STATE` (DataStore string, `ui/dashpay/utils/DashPayConfig.kt:458`).
- `CutoverCoordinator.dashjEngineMayStart()` (suspend) **always returns false** now; it only logs a WARN when
  `USE_KOTLIN_SDK_L1_SHADOW` is off, because then *no* L1 engine runs (`CutoverCoordinator.kt:69-113`).
- `BlockchainServiceImpl` ORs the Tools › dashj-sync diagnostic (`DashPayConfig.DASHJ_SYNC_DIAGNOSTIC`) onto that
  gate and starts/stops the dashj peergroup live (`service/BlockchainServiceImpl.kt:823-829`, `2231-2300`).
- New/restored wallets commit at setup (`commitForFreshWalletSetupAsync`, `CutoverCoordinator.kt:191`); upgraded
  wallets commit on the upgrade launch without bind evidence (`commitForUpgradedWalletAsync`, `:299`; policy in
  `docs/upgrade-memory-and-sync-plan.md` §4 Phase 1a item 1 and §12.1). There is **no rollback and no auto-commit
  observer** any more (`CutoverCoordinator.kt:152-157`).
- A failed SDK bind does **not** fall back to dashj; `SdkBindRetryService` retries until the keystore is usable
  (class KDoc of `CutoverCoordinator`).

"Is the cutover committed?" has **several readers with different failure semantics** — know which one you use:

| Reader | Where | On an unreadable DataStore |
|---|---|---|
| `dashjEngineMayStart(state)` (pure) | `CutoverStateMachine.kt:162` | n/a |
| `CutoverCoordinator.dashjEngineMayStart()` | `CutoverCoordinator.kt:99` | always false |
| `CutoverCoordinator.sdkOwnsL1Flow()` | `CutoverCoordinator.kt:127` | committed **and** `USE_KOTLIN_SDK_L1_SHADOW` on |
| `SdkL1SendService.cutoverCommitted()` | `SdkL1SendService.kt:1837` | **false** ("dashj rules") |
| `SdkL1SendService.cutoverCommittedOrThrow()` | `:1850` | throws (fail closed) |
| `SdkL1SendService.cutoverCommittedFlow()` | `:1869` | emits `null` = unknown, retries with backoff; callers must fail closed |

### 2.3 L1 engine, balances and history post-cutover

- **Engine:** `L1ShadowSyncService` (`service/platform/sdk/L1ShadowSyncService.kt`, 5,557 lines) runs the SDK's Rust
  SPV (compact block filters) under `filesDir/l1_shadow_spv/<network>`. Despite the "shadow" name and a KDoc that
  says it "must never ship enabled to production", it **is the L1 engine** and its flag is seeded ON everywhere
  (§2.7). It also computes the dashj-vs-SDK parity probe (`ParityBreakdown.kt`).
- **Sends:** `SdkL1SendService` (`service/platform/sdk/SdkL1SendService.kt`, 1,921 lines) — plain send, send-all
  drain (via `CoreSendAllNative`), deferred BIP70 build/broadcast/release, Maya deposits, CoinJoin drain.
  All engine calls run on `Dispatchers.IO`; send-all serializes under an app-side `coreSendMutex`
  (`SdkL1SendService.kt:686`, `907-917`); atomic select+reserve is in Rust (`finalizeAtomic`).
- **Display data:** `CutoverUiDataService` (5,909 lines) feeds the home balance, tx list (`TxDisplayCacheService`,
  Room `tx_display_cache`), notifications. Balance overlay choice is the pure
  `overlayBalanceChoice(sdk, synced, lastKnown, dashj)` (`CutoverUiDataService.kt:1376-1385`): dashj until the SDK
  publishes, SDK when synced, **last-known positive figure while scanning**, else SDK.
- **The seam into legacy code** is `WalletApplication` (Java), which implements `WalletData` and overlays SDK figures:
  - `getWalletBalance()` → `cutoverUiDataService.sdkBalanceOrNull()` first (`WalletApplication.java:3346-3353`);
  - `observeTotalBalance()` → `overlayTotalBalance(...)` (`:3403-3417`);
  - `observeBalance(type, selector)` → overlay **only** for `ESTIMATED`/`ESTIMATED_SPENDABLE` with a `null`
    selector; selector-based streams stay dashj-fed (`:3421-3446`);
  - `observeMaxOutputBalance()` → `overlayMaxSendableBalance(...)` (`:3450-3475`).
  The neutral facade for other modules is `data/WalletDataAdapter.kt` (`getWalletBalance()` at `:92`,
  `observeTotalBalance()` at `:174`).
- **Known open defect** (documented, not fixed): the SDK's in-memory ledger can publish a wrong balance after a
  re-walk, even after SYNCED (`DASHJ-KILL-LIST.md` §1a and §1a.1, measured 28x too high).

### 2.4 Sends: routing and the fail-closed rule

`payments/SendCoinsTaskRunner.kt` (1,444 lines) is the one `WalletSendPaymentService` implementation
(provided **unscoped** in `di/AppModule.kt:112-133`, wrapped by `FakeDashSpendService` on non-prod flavors).
The routing decision is pure (`SendCoinsTaskRunner.kt:204-221`):

```kotlin
fun cutoverSendRoute(cutoverCommitted: Boolean, hasCustomSelector: Boolean,
                     hasLockedOutputPredicate: Boolean, emptyWallet: Boolean): CutoverSendRoute = when {
    !cutoverCommitted -> CutoverSendRoute.DASHJ
    hasCustomSelector || hasLockedOutputPredicate -> CutoverSendRoute.FAIL_CLOSED
    emptyWallet -> CutoverSendRoute.FAIL_CLOSED
    else -> CutoverSendRoute.SDK_BRIDGED // simple pay-to-address
}
```

Rules encoded in the KDoc above it (`:94-123`): never fall through to dashj post-cutover (its peergroup is held, the
tx would queue and broadcast later); typed-overload send-all fails closed to keep CrowdNode's two-step deposit-all
atomic; only the main-UI funnel (`extractSdkRoutablePayment`, `:274`) may route send-all to the SDK drain.
Typed failures the UI maps (`SendCoinsTaskRunner.kt:124-199`, `ui/send/SendFailureMessageMapping.kt:67-76`):
`SendNotSdkRoutableException` → "not supported", `SendEngineNotSyncedException` → "not synced",
`SendSignerLockedException` → "unlock and retry", `ContactPaymentNotRecordableException`, other
`IllegalStateException` → generic (never verbatim). These exception classes live in `wallet`, not `common`.
BIP70/BIP270 post-cutover is a build/submit split with reservation release (`buildDeferredBip70Payment` `:879`,
`sendPrebuiltDirectPayment` `:996`, `releaseDeferredPayment` `:947`), test harness `scripts/bip70-test-server.py`
(root `CLAUDE.md`).

All send call sites (grep of `.sendCoins(`/`payWithDashUrl`/`sendCoinsSelected`):
`ui/send/SendCoinsViewModel.kt`, `ui/send/PaymentProtocolViewModel.kt`,
`features/exploredash/.../DashSpendViewModel.kt`, `integrations/coinbase/.../TransferDashViewModel.kt`,
`.../CoinbaseConversionPreviewViewModel.kt`, `integrations/maya/.../SwapKitApiAggregator.kt`,
`integrations/crowdnode/.../CrowdNodeBlockchainApi.kt` (fenced), plus SDK-direct paths in
`SdkL1SendService`, shielded, username and invite services.

### 2.5 The no-double-broadcast contract (`SdkWriteResult`)

```kotlin
sealed class SdkWriteResult<out T> {
    data class Broadcast<T>(val value: T) : SdkWriteResult<T>()
    data class NotBroadcast(val reason: String, val cause: Throwable? = null) : SdkWriteResult<Nothing>()
    data class Ambiguous(val cause: Throwable) : SdkWriteResult<Nothing>()
}
```
(`service/platform/sdk/SdkDashPayWrites.kt:49-53`). The decision table below it (`:55-…`) classifies SDK errors:
only provably pre-broadcast errors (`InvalidParameter`, `InvalidState`, `NotFound`, `NotImplemented`,
`ShieldedBroadcastFailed`, `ShieldedNoRecordedAnchor`, specific shielded build-input refusals) are `NotBroadcast`;
`NetworkError`/`Timeout`/`TransactionBroadcastUnconfirmed`/`ShieldedSpendUnconfirmed` and everything unknown are
`Ambiguous`. `DashSdkError.isRetryable` is deliberately **not** consulted. L1 sends have their own tables
(`classifyCoreSendFailure`, `classifyDeferredBroadcastFailure`, `SdkL1SendService.kt:136`, `:201`).
Several classifications **message-match** SDK error text because the SDK lacks typed errors
(`docs/kotlin-sdk-issues-to-file.md` items 1–2, e.g. `"no private key stored"`).

### 2.6 Identities, usernames, DashPay, contacts

- **Legacy path (still primary for creation):** `ui/dashpay/CreateIdentityService.kt` (2,138 lines, a
  `LifecycleService`) drives `IdentityCreationState` (`database/entity/BlockchainIdentityData.kt:53-79`;
  ordinal order matters — code compares with `>=`, and the comments say `NONE` must stay first and
  `DONE_AND_DISMISS` last). Resume logic matches error-message substrings
  (`"preorderDocument was not found with a salted domain hash"`, `CreateIdentityService.kt:604-616`). Identity
  state persists in `BlockchainIdentityConfig` (a DataStore `BaseConfig`, `BlockchainIdentityData.kt`), not Room.
  Legacy Platform objects come from `PlatformServiceImplementation` (`service/platform/PlatformService.kt:133-155`;
  `platform` is `platformLazy`-null on 32-bit and `_platform!!` throws).
- **SDK creation paths** (selected by flags / funding source): `SdkTransparentUsernameCreation` (L1 asset lock),
  `SdkShieldedUsernameCreation` (shielded-funded), `SdkL1InviteCreation`, `SdkShieldedInviteCreation`,
  `ShieldedInviteOverageTopUp`, top-ups `SdkTransparentTopUp` / `TopUpRepository` / `work/PerformTopUpWorker` /
  `work/ResumeTopUpsWorker`. `CreateIdentityService` chooses shielded vs L1 invite claims
  (`isShieldedEnabled()` `:947`, `isL1InviteSdkRoutable()` `:1038`).
- **Binding the app wallet into the SDK:** `SdkWalletBinder` (`service/platform/sdk/SdkWalletBinder.kt:193-283`
  KDoc): binds the BIP39 seed (decrypted from the dashj wallet via `SecurityGuardMnemonicProvider`), attaches the
  existing identity via DIP-9 discovery, and stores identity private keys (`ensureIdentityKeysSignable`).
  Single-flight (`Mutex`), idempotent latch, **never throws** (except cancellation), never prompts — callers pass a
  `WalletUnlock`.
- **Reads:** `SdkUsernameQueries`, `SdkProfileQueries`, `SdkVotingQueries`, `SdkIdentityVerifyQueries` follow one
  contract: return `null` when the SDK must not be used (flag off, bootstrap failure, native failure, malformed
  payload, **6 s timeout**) and the caller then runs the legacy dashj-platform code
  (`SdkUsernameQueries.kt:228-260`; call site `ui/dashpay/PlatformRepo.kt:189-201`). The 6 s budget and why it
  exists (mainnet DAPI nodes with expired TLS certs): `service/platform/sdk/SdkPlatformQueryBudget.kt`.
- **Writes:** `SdkDashPayWrites` (contact requests, profiles) behind `USE_KOTLIN_SDK_DASHPAY_WRITES`, called from
  `PlatformDocumentBroadcastService` (`service/platform/PlatformBroadcastService.kt:149`, `:297`, `:432`).
- **Contacts sync:** `PlatformSynchronizationService` (`service/platform/PlatformSyncService.kt`, 3,204 lines) runs
  a 15 s `TickerFlow` on its own `syncScope = CoroutineScope(Dispatchers.IO + syncJob)` (`:275`, `:488`, `:675`);
  guards concurrent contact passes with an `AtomicBoolean` + owner `Job` CAS and stale take-over (`:410-430`).
  Room tables: `DashPayProfile`, `DashPayContactRequest`, `UsernameRequest`, `UsernameVote`, `Invitation`, `TopUp`.
- **Paying a contact (DIP-15):** `ContactPaymentAddressProvider` (1,497 lines) picks the next address from the SDK's
  sending account (Room mirror), falls back to dashj's friend chain, claims each address in
  `PaidContactAddressStore` **before** sending, and refuses to hand out addresses until the SDK has covered every
  contact account after a restore (class KDoc). Introduced by #1608 (`git log`).
- **DashPay bring-up / backfill:** `DashPayBackfillGate`, `SdkDashPayKeyProvisioning`, `ContactIntegrityRepair`,
  `IdentityKeyChainBackfill`, `FriendKeyChainLookahead` (wallet/util) — large, incident-driven code; read
  `docs/upgrade-memory-and-sync-plan.md` §§16–19 before changing it.

### 2.7 Runtime feature flags (`DashPayConfig`)

`ui/dashpay/utils/DashPayConfig.kt` (1,091 lines, a `BaseConfig` DataStore) holds both feature flags and a large
amount of migration state (`CUTOVER_STATE`, `SDK_BIND_*`, `SDK_*_RESCAN_*`, `DASHPAY_BACKFILL_*`,
`MIXED_FUNDS_MIGRATION_*`, `INVITE_OVERAGE_*`, last-known balances). Flags:
`USE_KOTLIN_SDK_DPNS_READS`, `_DASHPAY_WRITES`, `_SHIELDED`, `_L1_INVITE`, `_L1_SHADOW`, `_L1_SEND` (`:300-405`).

Seeding: the class's constructor launches `seedDebugDefaultsIfUnset()` on an ad-hoc `CoroutineScope(Dispatchers.IO)`
(`DashPayConfig.kt:820-828`); it is called again after a wipe (`WalletApplicationExt.kt:335`). Despite the
"debug" name, **every variant including prodRelease** seeds the first five flags ON ("QA == mainnet == release",
`:407-436`); the mainnet and testnet branches of `debugSeedFlags` are identical lists. `USE_KOTLIN_SDK_L1_SEND` is
never seeded. Many KDocs across the SDK layer still say "default OFF" for these flags
(e.g. `PlatformRepo.kt:191`, `SdkUsernameQueries.kt:231`, `ShieldedBalanceService.kt` class KDoc) — read them as
historical.

### 2.8 Shielded balances (CoinJoin's replacement)

- Service: `ShieldedBalanceService` (interface, `service/platform/sdk/ShieldedBalanceService.kt`) /
  `ShieldedBalanceServiceImpl.kt` (2,557 lines). Lifecycle `ensureShieldedReady()` (idempotent, single-flight,
  inert when flag off), reads `observeShieldedBalance()`/`observeShieldedActivity()` over the SDK's Room store,
  writes `shieldFromCredits` (Type 15), `transferShielded` (16), `unshieldToCredits` (17), `withdrawToCore` (19),
  `shieldFromWallet` (L1 asset lock + Type 18) — all returning `SdkWriteResult`.
- `ShieldedSyncStatus { NOT_READY, SYNCING, READY }`: `Dash.ZERO` during NOT_READY/SYNCING is a placeholder, not a
  balance — UI must show syncing.
- Proving is ~30 s per spend with no progress callback; UI must show indeterminate progress and stay off main.
- `ShieldFromWalletOutcome.SHIELD_PENDING_RETRY`: L1 lock broadcast, stage (b) pending — UI must not offer
  "send again".
- Credits ↔ duffs: 1,000 credits per duff, floor on the way down (`creditsToDash`/`dashToCredits`,
  `ShieldedBalanceServiceImpl.kt:74-87`; `Math.multiplyExact` guards overflow).
- UI: `ui/shielded/*` (Compose screens + ViewModels), entry `ShieldedBalanceActivity`, gated by
  `Constants.SUPPORTS_PLATFORM` and `USE_KOTLIN_SDK_SHIELDED` (`ui/payments/PaymentsFragment.kt:103`). Home header
  total = L1 + shielded since #1621 (`ui/main/MainViewModel.kt:219-233`, `508-…`); every other screen shows L1 only.
- Note: the interface KDoc still says "it has NO production call sites yet" and "(default OFF)"; both are stale —
  13 files outside the service use it.

### 2.9 CoinJoin (removed) and CrowdNode (fenced)

- Mixing engine/UI removed (Phase 2, `docs/kotlin-sdk-migration-plan.md` "Execution status"). Remnants:
  `transactions/coinjoin/*` (grouped display of historical mixing txs), `CutoverUiDataService` mixing-group planning
  (`planMixingGroupUpdates`, `:1199`), and `CoinJoinFundsMigrationService` (`service/platform/sdk/…`), which offers
  either shielding mixed funds or draining them to a BIP44 address — **never mixing CoinJoin and BIP44 UTXOs in one
  tx** (the "privacy invariant" in its KDoc).
- CrowdNode: the six senders in `integrations/crowdnode/.../api/CrowdNodeBlockchainApi.kt` open with a guard on
  `CrowdNodeConstants.SIGNUP_AND_DEPOSITS_ENABLED` that throws `CrowdNodeServiceUnavailableException`; the dashj
  bodies are kept as a template. Flipping the flag back does **not** re-enable sends, because `cutoverSendRoute`
  fails closed on custom coin selection (KDoc `:20-50`). Observers (`waitFor*`, deposits, withdrawals) still run.

### 2.10 Threading around Platform/SDK calls (summary)

- SDK suspend APIs are called from services that own their own scopes (`PlatformSynchronizationService.syncScope`,
  `CutoverCoordinator(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))` default, the injected app scope).
- Blocking JNI work is wrapped in `withContext(Dispatchers.IO)`; CPU-heavy seed decryption in
  `Dispatchers.Default` (`SecurityGuardMnemonicProvider.getMnemonicWords`, `SecurityFunctions.decryptSeed`).
- Mutual exclusion uses `kotlinx.coroutines.sync.Mutex` (single-flight + success latch pattern in `SdkWalletBinder`,
  `ShieldedBalanceService`, `L1ShadowSyncService`, `DashSdkServiceImpl`).
- Legacy non-suspend call sites bridge into suspend SDK code with `runBlocking`
  (`SdkUsernameQueries.getUsernameOrNull`, `:304-310`, called from non-suspend `PlatformRepo.getUsername`);
  114 `runBlocking` occurrences in production code (`wallet`: `CutoverUiDataService`, `SdkSourcedQuorums`,
  `SdkVotingQueries`, `BlockchainServiceImpl`, `WalletTransactionMetadataProvider`, `SecurityGuard.java`, …).
- dashj's thread-local `Context` must be re-propagated in coroutines:
  "dashj Context does not work with coroutines well, so we need to call Context.propogate in each suspend method"
  (`service/platform/TopUpRepository.kt:561-563`); 69 `Context.propagate` call sites.

---

## 3. Architecture conventions actually in use

### 3.1 ViewModels

Stated rule (root `CLAUDE.md` "Design Pattern", `.claude/agents/DEVELOPMENT-PATTERNS.md:7-53`,
`.coderabbit.yaml` path instruction for `**/*ViewModel.kt`): one `UIState` data class, private `_uiState`
`MutableStateFlow`, public `uiState` via `asStateFlow()`, `@HiltViewModel` constructor injection, StateFlow not
LiveData.

Measured (Kotlin files containing `: ViewModel()`-style classes, 100 files): 71 use `@HiltViewModel`, 31 have a
`_uiState`, 25 still use `MutableLiveData`. No file mixes `_uiState` with LiveData. Mixed styles in key screens:
- `ui/main/MainViewModel.kt` exposes LiveData (`_totalBalance`, `_homeBalance`, `:215-226`) and StateFlows.
- `ui/send/SendCoinsViewModel.kt` uses a LiveData `_state` (`_state.postValue(State.SENT)`, ~`:643`).
- `ui/username/request/RequestUserNameViewModel.kt` (1,861 lines) has `_uiState` plus 12 `MutableStateFlow`s.
- `features/exploredash/.../ExploreViewModel.kt` has 5 MutableStateFlows + 7 MutableLiveData.
Follow the rule in new code; match the file when editing old code (`.coderabbit.yaml`: "match the surrounding
file's naming, comment density, and idiom").

### 3.2 Hilt DI

- `@HiltAndroidApp` on the Java `WalletApplication` (`WalletApplication.java:212`) with 27 `@Inject` fields.
- Modules in `wallet/src/de/schildbach/wallet/di/`: `AppModule`, `AnalyticsModule`, `DashPayModule`,
  `DataProviderModule`, `DatabaseModule`, `IntegrationModule`, `PlatformSdkModule`, `SecurityModule`; each feature
  module has its own `di/` (e.g. `features/exploredash/.../di/ExploreDatabaseModule.kt`).
- The application `CoroutineScope` is bound **unqualified**:
  `@Provides @Singleton fun providesApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`
  (`di/AppModule.kt:139-144`). Any `CoroutineScope` constructor parameter receives it (20 such injections).
- SDK-layer classes use `internal constructor` with injectable seams (clocks, dispatchers, intervals) and are
  provided from `PlatformSdkModule` (e.g. `SdkWalletBinder.kt:285-…`, `CutoverUiDataService.kt:2348`,
  `L1ShadowSyncService`).
- Test seams for dispatchers are mutable public properties, not qualifiers:
  `var ioDispatcher: CoroutineDispatcher = Dispatchers.IO` (`ui/shielded/ShieldedSendViewModel.kt:130`,
  `SdkTransparentUsernameCreation.kt:541`, `SdkShieldedUsernameCreation.kt:800`, …).
- `SecurityGuard` and `PinRetryController` are Java singletons wrapped by `@Provides`
  (`di/SecurityModule.kt:41`), `UpholdClient.getInstance()` likewise (`AppModule.kt:92`).

### 3.3 Repositories / services

- Interfaces in `common/services` for cross-module needs; wallet-internal services are mostly concrete
  `@Singleton` classes under `service/`, `service/platform/`, `service/platform/sdk/`.
- Legacy "repo" classes: `ui/dashpay/PlatformRepo.kt` (legacy Platform client + SDK-first wrappers),
  `IdentityRepository` (`service/platform/IdentityRepository.kt`), `TopUpRepository`, `ExchangeRatesRepository`.
- Wallet object access goes through `WalletData` (`data/WalletData.kt:43`, implemented by `WalletApplication`);
  feature modules get `WalletDataProvider` (neutral types) via `WalletDataAdapter`.

### 3.4 Persistence: Room, DataStore, files

- Room `AppDatabase` (`database/AppDatabase.kt`), `version = 22`, `exportSchema = true`, 20 entities incl.
  `TxDisplayCacheEntry`, `TxGroupCacheEntry`, `SwapOrder`, `InstantSendLockEntry`. Schemas in `wallet/schemas/`
  (`room.schemaLocation` set via `ksp` arg, `wallet/build.gradle:309-311`).
- Migrations are hand-written `Migration(n, n+1)` objects with raw SQL in `database/AppDatabaseMigrations.kt`
  (e.g. `migration21to22` creates `instant_send_locks`), registered in `di/DatabaseModule.kt:40-57`:
  ```kotlin
  .addMigrations(AppDatabaseMigrations.migration11To12, … AppDatabaseMigrations.migration21to22)
  // destructive migrations are used from versions 1 to 11
  .fallbackToDestructiveMigration()
  ```
  `fallbackToDestructiveMigration()` is **unconditional**: a missing step for a future version silently wipes
  tx/address metadata (the `version` comment warns "we need migrations to preserve tx/addr metadata").
- Migration test: `wallet/androidTest/de/schildbach/wallet/database/DatabaseMigrationTest.kt` (instrumented,
  `MigrationTestHelper`) — its `migrations` array stops at `migration18to19`; 19→22 are untested there, and
  instrumented tests do not run in CI.
- `features/exploredash/ExploreDatabase.kt` has its own Room DB (downloaded explore data; also uses
  `fallbackToDestructiveMigration`).
- DataStore configs subclass `common/.../data/BaseConfig.kt`: `DashPayConfig`, `BlockchainServiceConfig`,
  `WalletUIConfig`, `BlockchainIdentityConfig`, `ExchangeRatesConfig`, `CTXSpendConfig`, `MayaConfig`,
  `CrowdNodeConfig`, `SecurityBackupConfig`, … `get()`/`observe()` **rescue IOException as empty preferences**
  (a corrupt store reads as "unset"); fail-closed callers must use `observePreservingErrors()` (`common/.../data/BaseConfig.kt:142`, `:146`).
- Legacy `Configuration` (SharedPreferences, `common/.../Configuration.java`) is still injected
  (`AppModule.kt:107-109`); `WalletApplication.getConfiguration()` is `@Deprecated("Inject Configuration instead")`.
- The SDK has its own Room DB (`DashDatabase`) and Keystore-backed `WalletStorage`; the app reads the SDK's tables
  (e.g. `ShieldedNoteEntity`, `TransactionEntity`) directly in a few places (imports listed in §2.1 grep).
- Files: dashj `.wallet` (autosave every 5 s, `Constants.java:180`, a TODO for size-aware autosave at
  `WalletApplication.java:2012`), `PaidContactAddressStore` file, wipe markers (`util/WalletWipeState.kt`).

### 3.5 WorkManager

- 11 `@HiltWorker`s; base classes `service/work/BaseWorker.kt` (progress 0→100, `errorResult(message)`, a 100 ms
  delay workaround) and `BaseForegroundWorker`. Operation classes wrap enqueueing
  (`service/platform/work/ResumeTopUpsOperation.kt`: one unique work name + `ExistingWorkPolicy.KEEP`, network
  constraint, exponential backoff).
- Workers: `PerformTopUpWorker`, `ResumeTopUpsWorker`, `RestoreIdentityWorker` (1,112 lines),
  `PublishTransactionMetadataWorker`, `ShieldedInviteOverageWorker` (`service/platform/work/`);
  `SendContactRequestWorker`, `UpdateProfileWorker`, `BroadcastUsernameVotesWorker`,
  `BroadcastIdentityVerifyWorker`, `GetUsernameVotingResultsWorker`, `SendInviteWorker` (`ui/dashpay/work/`);
  `ExploreSyncWorker`, `CrowdNodeWorker` in modules.
- Progress observed via `SingleWorkStatusLiveData` (LiveData).

### 3.6 Navigation and UI stack

- 31 Activities, ~100 Fragments (Kotlin+Java), 92 files with `@Composable`, 22 hosting Compose via
  `ComposeView`/`setContent`. No Compose `NavHost`; navigation is Jetpack Navigation XML with Safe Args
  (`wallet/res/navigation/nav_{home,send,username,voting,verify,create_invite,invite_history,quick_receive}.xml`;
  module graphs `nav_explore`, `nav_coinbase`, `nav_crowdnode`, `nav_maya`) and `Fragment.safeNavigate(directions)`
  (`common/.../util/NavigationExtensions.kt:30`, 168 call sites).
- ViewBinding via `by viewBinding(...)` delegate (`common/.../ui/FragmentViewBindingDelegate.kt:81`). **No
  DataBinding anywhere** (root `CLAUDE.md` "Android Views with Data Binding" is inaccurate).
- Design system in `common/src/main/java/org/dash/wallet/common/ui/components/` (`DashButton`, `ListItem`,
  `TopNavBase`, `SheetButtonGroup`, `EnterAmount`, `TextField`, `MyTheme`, …), documented exhaustively in
  `.claude/agents/DEVELOPMENT-PATTERNS.md` (Figma → component mapping, typography, bottom-sheet patterns).
- Dark mode rule: `val colors = LocalDashColors.current` once per composable, never `MyTheme.Colors.*`; root wrapped
  in `DashWalletTheme` (`DEVELOPMENT-PATTERNS.md:84-125`). Compliance: 341 `LocalDashColors.current` vs 10
  `MyTheme.Colors.` uses in 3 files.

### 3.7 Logging

- SLF4J API + `logback-android` (`wallet/build.gradle:102-105`); 362 `LoggerFactory.getLogger(X::class.java)` in a
  `companion object { private val log = … }`; 12 `android.util.Log` stragglers.
- Configured in code, `WalletApplication.initLogging()` (`WalletApplication.java:1504-1600`): root **INFO** (debug
  lines are dropped), rolling `files/log/wallet.log` (10 MB, 20 days, 200 MB cap), logcat appender,
  `LogMarkerFilter`, noisy dashj quorum/InstantSend loggers forced to WARN, `PeerTimeoutDumpThrottle` turbo filter,
  and `NativeLogBridge` copying the Rust `DashSDK` logcat tag into `wallet.log` so support reports include it.
- Level use in `wallet/src`: info 1,274, warn 859, error 342, debug 71. Use `{}` placeholders.
- Hygiene rules stated in KDocs: never log seed words, amounts, addresses or memos from shielded code
  (`ShieldedBalanceService` KDoc), "Nothing seed-derived is ever logged" (`SdkWalletBinder`).

### 3.8 Analytics

`AnalyticsService` (`common/.../services/analytics/AnalyticsService.kt`), impl `FirebaseAnalyticsServiceImpl`
bound in `di/AnalyticsModule.kt:15`; event names in `AnalyticsConstants.kt`; `AnalyticsTimer` for process timings.
367 `logEvent(` and 81 `logError(` call sites. Since MO-1065 (#1606, `git show e18275c33`): user opt-out via
`setEnabled`, and **events no longer carry Dash or fiat amounts**. `logError` (Crashlytics) is unaffected.

### 3.9 Error handling and result types

- No project-wide `Result`/`Either`. In use:
  - `SdkWriteResult` (§2.5) for anything that may have broadcast;
  - `BridgedTxResult` (`service/platform/sdk/SdkBridgedTransactionFactory.kt:46`);
  - `ResponseResource` (sealed, `common/.../data/ResponseResource.kt:23`, 21 files, integrations' network calls);
  - `Resource<T>(status, data, message, exception)` — **two copies**: `wallet/src/.../livedata/Resource.kt:21` and
    `common/.../data/Resource.kt:19`;
  - nullable "fall back to legacy" returns (`Sdk*Queries.*OrNull`).
- Typed exceptions for UI mapping (§2.4); "unknown" values are modelled explicitly (`Boolean?` from
  `cutoverCommittedFlow()`, `ShieldedSyncStatus`).
- Broad catches are everywhere (494 `catch (e: Exception)`, 407 `catch (_: Throwable)` in Kotlin). The project
  convention for suspend code is to rethrow cancellation first — 395 occurrences of
  `catch (e: CancellationException) { throw e }` / `is CancellationException`, e.g. `DashSdkServiceImpl.kt:584-586`,
  `DashPayConfig.seedDebugDefaultsIfUnset` (`:859-865`), `retryWhen { cause, _ -> if (cause is CancellationException …) false }`
  (`SdkL1SendService.kt:1876-1886`). `runCatching` (161 uses) swallows cancellation unless followed by a rethrow
  helper (`rethrowCancellation(it)` in `WalletApplicationExt.kt:99-101`).
- Error text from SDK/Platform is frequently **string-matched** (identity resume, signing failures, funding-gate
  reasons: `reason.contains(L1_FUNDING_GATE_CLOSED_REASON)`, `SendCoinsTaskRunner.kt:186-199`).

### 3.10 Coroutines: scopes and dispatchers

- UI: `viewModelScope` (335), `lifecycleScope` (321).
- App-wide: injected app scope (Default dispatcher), service-owned scopes (`syncScope`), 37 ad-hoc
  `CoroutineScope(SupervisorJob()…)`/`CoroutineScope(Dispatchers…)` constructions, and 3 files still using
  `GlobalScope` (`CreateIdentityService.kt:414` in the exception handler, `PaymentProtocolViewModel.kt`,
  `common/.../BaseConfig.kt`).
- Teardown must survive cancellation: `withContext(NonCancellable)` in wipe finish (`WalletApplicationExt.kt:107`)
  and BIP70 post-ack (`SendCoinsTaskRunner.kt:1062`).
- Coroutines version is 1.6.4 (`build.gradle:4`) — `runTest` exists but newer test APIs do not.

---

## 4. Money and keys safety patterns

### 4.1 Amount types

Two parallel families, chosen by module:

| Type | Package | Where | Notes |
|---|---|---|---|
| `Coin`, `Fiat`, `ExchangeRate`, `MonetaryFormat` | `org.bitcoinj.*` | `wallet` only (89 files import dashj `Coin`) | dashj originals |
| `Coin`, `Fiat`, `ExchangeRate`, `MonetaryFormat`, `Monetary` | `org.dash.wallet.common.money` | `common` + modules (17 files import common `Coin`) | "Self-contained port of `org.bitcoinj.core.Coin` (dashj 22.0.3) … Behavior is identical" (`common/.../money/Coin.java` class doc) |
| `Dash` (value class over duffs), `FiatValue` | `org.dash.wallet.common.money` | neutral APIs (120 files) | delegates arithmetic/parsing to the common `Coin` (`Dash.kt`) |

Conversions: `common/.../money/MoneyAdapters.kt` (`Dash.toCoin()` → **common** `Coin`; `ExchangeRate.dashToFiat`,
`fiatToDash`) and `wallet/src/.../util/DashjMoney.kt` (`Dash.toCoin()` → **dashj** `Coin`, `toDashjCoin()`,
`toNeutralCoin()`, `Sha256Hash.toTxId()`). The two `Dash.toCoin()` extensions have the same name and different
return types — the import decides which one you get. `util/MoneyShims.kt` holds further bridges.

Rounding / conversion rules seen in code:
- Fiat↔coin via `BigInteger` multiply-then-divide with overflow checks (`common/.../money/ExchangeRate.java:55-75`,
  same as dashj); `MonetaryFormat` default `HALF_UP`; `Coin.parseCoin` rounds `DOWN` at 8 decimals.
- Send-screen local-currency display rounds `UP` (`common/.../util/MonetaryExt.kt:98`); gift-card fiat amounts
  `setScale(2, UP)` (`DashSpendViewModel.kt:745`, `PurchaseGiftCardConfirmDialog.kt:213`); Maya duff conversion
  `HALF_UP` (`MayaBlockchainApiImpl.kt:286`); "More" balance `DOWN` (`MoreFragment.kt:150`).
- Platform credits: 1,000 per duff; credits→Dash floors (`ShieldedBalanceServiceImpl.kt:74-87`). The constant is
  defined **three times** (`ShieldedUi.kt:105` private, `ShieldedBalanceServiceImpl.kt:74` internal,
  `data/CreditBalanceInfo.kt:13` as `Int`).
- `Fiat.toDouble()` exists (`MonetaryExt.kt:43`) — use only for display/analytics, never arithmetic.

### 4.2 Fees

- dashj path: `feePerKb = Constants.ECONOMIC_FEE` = 1,000 duffs/kB (`common/.../util/Constants.kt:47`,
  `SendCoinsTaskRunner.kt:1167`, `:1187`); `MaxOutputAmountCoinSelector` estimates `DEFAULT_TX_FEE * size / 1000`
  (`payments/MaxOutputAmountCoinSelector.kt:41`); BIP70 preview hard-codes a 261-byte estimate
  (`ui/send/PaymentProtocolViewModel.kt:60`); static fee file loader `data/StaticFeeLoader.java`.
- SDK path: fee chosen by Rust; send-all drain forces 2,000 duffs/kB (`CoreSendAllNative.java:120`, `:235`);
  fee reserves for send-all and Maya max (`SEND_ALL_FEE_RESERVE_DUFFS = 10_000L`, `sendAllFeeReserveDuffs`,
  `mayaMaxFeeReserveDuffs`, `SdkL1SendService.kt:223-349`). The two engines' fee policies differ — the probe
  `L1SendProbeService` exists to measure that (`:159-187`).
- Send-all semantics (SDK): one output whose value the engine overwrites to `total − fee`; the passed amount is a
  **deliver-at-least floor**; shortfall triggers an adjust-down retry under the same lock (`CoreSendAllNative.java`
  KDoc). The drain cannot see dashj output locks, so the Kotlin caller must refuse it while any exist.
- Contested-username fee is gated on the live Platform protocol version (`ContestedUsernameFees.kt`,
  `ProtocolVersionLatch` keeps the version monotonic per network; #1584).

### 4.3 PIN, biometrics, wallet encryption

- `SecurityGuard` (`security/SecurityGuard.java`, Java singleton, `synchronized` methods): stores a random wallet
  password and the UI PIN under Keystore aliases `wallet_password_key` / `ui_pin_key`, with **dual fallback**
  (PIN-derived and mnemonic-derived encryption) — `DualFallbackEncryptionProvider`, `ModernEncryptionProvider`,
  `PinBasedKeyProvider`, `MnemonicBasedKeyProvider`; docs in `docs/security/README.md` and siblings.
- The dashj wallet is encrypted with a scrypt key derived from that password (`SecurityFunctions.deriveKey`,
  `security/SecurityFunctions.kt:223`; iteration target lowered on low-RAM/32-bit, `:80-84`).
- Canonical seed decrypt: `wallet.keyChainSeed.decrypt(wallet.keyCrypter, null, deriveKey(wallet, password))`
  on `Dispatchers.Default` (`SecurityFunctions.decryptSeed` `:154-160`; mirrored in
  `SecurityGuardMnemonicProvider` with a KDoc "kept in lock-step").
- `AuthenticationManager.authenticate(activity, pinOnly)` (`common/.../services/AuthenticationManager.kt`), impl
  `SecurityFunctions.authenticate` (`:87-151`): `PinRetryController` lockout first, biometric unless `pinOnly`,
  PIN dialog fallback; suspend variant via `suspendCancellableCoroutine`.
- Biometric spending limit: `configuration.biometricLimit` (Advanced Security screen), applied as
  `authenticate(activity, !allowBiometric)` in `ui/send/SendCoinsFragment.kt:235-236` and inline in
  `ui/send/PaymentProtocolFragment.kt:106`. Other spend paths call `authenticate(activity)` without the limit
  (`PurchaseGiftCardConfirmDialog.kt:265`, coinbase `TransferDashFragment.kt:138`, `ShieldedBalanceActivity.kt:213`,
  `UsernameSubmitAuth.kt:39`) — see §7.
- SDK side: the SDK holds its own copy of the mnemonic in `WalletStorage` (`DEVICE_BOUND` Keystore policy); signing
  fails with a typed `L1_SIGNER_LOCKED_REASON` when the Keystore auth window is closed (`SdkL1SendService.kt:270`).
- `SecureActivity` / `AutoLogout` / `LockScreenActivity` (857 lines) implement auto-lock.

### 4.4 Where the wallet object is touched and how threads are guarded

- `WalletApplication.getWallet()` **may block**: it waits on `DeferredWalletLoad` (a background-start wallet parse
  moved off the main thread to avoid bind-application ANRs; `util/DeferredWalletLoad.kt`). Readers on the main
  thread run the completion inline; off-main readers wait unbounded for the parse, bounded for the completion.
  Writers (`setWallet`, `saveWallet`, wipe, `resetBlockchain`) use `awaitDeferredWalletLoadForMutation()`
  (`WalletApplication.java:740-768`, `1609-1612`).
- `WalletApplication.observeWallet()` is a `MutableStateFlow<Wallet>` (`:1614-1620`).
- dashj listeners are registered with `Threading.SAME_THREAD`/`USER_THREAD` (25 sites, e.g.
  `ui/WalletBalanceLoader.java:60-62`); dashj's internal `ReentrantLock` is not taken by app code directly
  (no `wallet.lock()` calls found); app-level locks: `FriendKeyChainLookahead` `ReentrantLock`
  (`util/FriendKeyChainLookahead.kt:250-930`), coroutine `Mutex`es in SDK services.
- `org.bitcoinj.core.Context.propagate(wallet.context)` at the top of suspend functions that use dashj (§2.10).
- Output locking for reservations: `wallet.lockOutput(outpoint)` mirrors SDK BIP70 reservations into dashj
  (`SendCoinsTaskRunner.kt:925-945`), `ReservationLockMirror.kt`, `SeamOutputLockRegistry.kt`.

### 4.5 Backup, restore, wipe

- Backup: seed display/verify (`ui/verify/*`, `BackupWalletToSeedDialogFragment.java`), file backup
  (`ui/backup/BackupWalletDialogFragment.java`), Google Drive for tx metadata (`DashPayModule.provideGoogleDrive`).
- Restore: from seed (`ui/RestoreWalletFromSeedViewModel.kt:201 restoreWalletFromSeed`), from file
  (`ui/RestoreWalletFromFileViewModel.kt:68-74`; `setWallet` on the main thread triggers
  `commitForFreshWalletSetupAsync`). The restore date the user picks feeds the SDK birth height
  (`SdkWalletBinder` ctor comment; `BirthHeightResolver.kt`).
- Wipe ("Reset Wallet") is ordered by `util/WalletWipeSequence.kt`: `begin()` persists a marker and hands the UI off
  **before** anything is destroyed; `finish()` detaches the wallet, destroys, and clears the marker only if destroy
  returned — a throw leaves the marker and the next launch resumes (`resumeInterruptedWipe`). Orchestration in
  `WalletApplicationExt.kt:63-180`: pause metadata saves, raise `WalletSecretWriteFence`, quiesce the SDK
  (`DashSdkService.quiesceForWalletWipe`), erase SDK secrets (`SdkWalletSecretsWipe`), confirm sources destroyed,
  then `withContext(NonCancellable)` cleanup. `CutoverCoordinator.resetForWalletWipe()` resets cutover state
  (`CutoverCoordinator.kt:573`).

---

## 5. Testing

### 5.1 Locations and frameworks

| Module | Location | Count | Frameworks |
|---|---|---|---|
| wallet | `wallet/test/` (not `src/test`; `wallet/build.gradle:368-371`) | 236 `.kt` + 5 `.java` | JUnit 4.13.2, **MockK 1.13.8** (96 files, 0 Mockito), kotlinx-coroutines-test 1.6.4, Robolectric 4.15.1 (34 files), mockwebserver, hilt-android-testing |
| wallet (instrumented) | `wallet/androidTest/` | 6 tests | Room `MigrationTestHelper`, Espresso, Hilt testing |
| common | `common/src/test/java/` | 6 | JUnit4, mockito-kotlin |
| exploredash | `features/exploredash/src/test/` | 5 | JUnit4, mockito-kotlin, Robolectric, room-testing |
| coinbase | `integrations/coinbase/src/test/` | 4 | MockK, hamcrest |
| crowdnode / maya / uphold | `src/test/` | 6 / 11 / 3 | mockito-kotlin (uphold: mockito-core + mockwebserver) |

No Turbine, no JUnit 5, no Compose UI tests found. `wallet/test/robolectric.properties`: `sdk=35`,
`conscryptMode=OFF`. `testOptions.unitTests.returnDefaultValues = true` and `includeAndroidResources = true`
(`wallet/build.gradle:548-554`) — Android stubs return defaults instead of throwing, which can mask missing mocks.

**Important history:** until #1609 (2026-10-22, `git show 421514a3a`) **251 tests in 25 Robolectric classes of the
wallet module were not running** (Conscrypt native load failed in Robolectric setup). Robolectric tests written
before that date were never executed by CI; expect some to fail or to assert stale behaviour.

### 5.2 What is tested and how

- The SDK adapter layer is the best-tested area: 65 test files in `wallet/test/de/schildbach/wallet/service/platform/sdk/`
  (cutover, send routing, write classification, shielded, binder, wipe, parity, backfill, …).
- Dominant technique: **extract the decision as a pure top-level `internal fun`** (KDoc says "Pure —
  host-testable") and test it with plain JUnit, e.g. `nextCutoverState` (`CutoverStateMachineTest.kt`),
  `cutoverSendRoute`, `overlayBalanceChoice`, `classifySendFailure`, `WalletWipeSequence.begin/finish`,
  `planL1DisplaySync`. Services take clock/dispatcher/interval seams in `internal constructor`s for the rest.
- Coroutine tests mix `runTest` (41 files) and `runBlocking` (74 files); `Dispatchers.setMain` in 12; shared rules:
  `wallet/test/.../util/viewModels/MainViewModelTest.kt` and module-local rules
  (`ExploreViewModelTest.kt`, `CrowdNodeViewModelTest.kt`) define their own main-dispatcher rules.
- Naming: camelCase with underscores separating scenario parts (`observeReadiness_advisoryEdge_bothDirections`,
  `commit_requiresReadyObservedAndReady`).
- `@Ignore` appears once in `wallet/test`.

### 5.3 CI (`.github/workflows/`)

| Workflow | Trigger | Steps |
|---|---|---|
| `dashwallet.yml` "Dash Wallet CI" | push `master`; PR into `master, feature-*, bugfix-*, feat/*, fix/*` | decrypt secrets → `bundle exec fastlane test flavor:"" type:"debug"` → PR: `fastlane build _testNet3 release`; push: build + Firebase App Distribution for `_testNet3` and `prod` |
| `dashpay.yml` | push `dashpay`; PR into `dashpay, dashpay-*` | same with `staging` |
| `ktlint.yml` | PRs (same branches as dashwallet) | `./gradlew ktlintCheck` |
| `manual_distribution.yml` | `workflow_dispatch` | test + build/distribute chosen flavor |

`fastlane/Fastfile:171-175`: `lane :test` runs `gradle(task: "test#{flavor}#{type}UnitTest")` → `testDebugUnitTest`,
i.e. every module's debug unit tests, all wallet flavors **(inferred: the aggregate task covers all four flavors)**.
No instrumented tests, no lint gate (lint `abortOnError false`), no detekt, no coverage. The v12 branch
`feat/kotlin-sdk-phase-1` matches `feat/*`, so PRs into it run CI.

---

## 6. Code style and repository rules

### 6.1 Formatting / static checks

- `.editorconfig`: `max_line_length = 120`, **no trailing commas** (declaration and call site), imports layout
  `*,java.**,javax.**,kotlin.**,^`, wildcard imports allowed, `package-name`, colon-spacing and
  annotation-spacing rules disabled.
- ktlint plugin `org.jlleitschuh.gradle:ktlint-gradle:11.3.1` is applied only in `features/exploredash`,
  `integrations/uphold`, `integrations/crowdnode`, `integrations/maya` — **not** in `wallet`, `common` or
  `integrations/coinbase`, so `ktlintCheck` in CI never looks at them. `./gradlew ktlintFormat` is the documented
  formatter (root `CLAUDE.md`).
- No detekt configuration anywhere.
- `.coderabbit.yaml`: profile `chill`; auto-review also for base branches `feat/kotlin-sdk-phase-.*`; excludes
  translated `values-*/strings.xml`, `market/**`, `fastlane/**`; path instructions: ViewModels must follow the
  single-UIState/StateFlow rule; flag `!!` and unsafe casts in production Kotlin (there are 707 `!!` in production
  `.kt` today); "match the surrounding file's naming, comment density, and idiom".

### 6.2 Naming and layout

- Kotlin packages keep the legacy roots: `de.schildbach.wallet.*` in `wallet` (flat `src/de/schildbach/wallet/...`),
  `org.dash.wallet.common.*`, `org.dash.wallet.features.exploredash.*`, `org.dash.wallet.integrations.<name>.*`
  (standard `src/main/java`).
- SDK-adapter classes are prefixed `Sdk*` (`SdkL1SendService`, `SdkWalletBinder`, `Sdk*Queries`, `Sdk*Writes`),
  dashj bridges `Dashj*` (`DashjMoney.kt`, `DashjSelectors.kt`, `DashjWalletResetGuard.kt`).
- Every source file starts with a GPL-3 header ("Copyright 2026 Dash Core Group." on new files).
- Very long KDoc is the house style in the SDK layer: rationale, incident history (device, date, log line),
  issue/PR numbers, and "why not the obvious fix". New code in that layer is expected to explain *why*
  (inferred from density and from `.coderabbit.yaml` "comment density").
- Commits: Conventional Commits with scope and PR number, e.g. `fix(dashpay): give each contact payment a new address
  after the cutover (#1608)`, bodies explain cause and evidence (`git log`).

### 6.3 Strings and translations

- Transifex project `dash-mobile-wallets` (`.tx/config`): sources `wallet/res/values/strings.xml`,
  `strings-dashpay.xml`, `strings-extra.xml`, plus each module's `strings*.xml`; translations land in
  `values-<lang>/` via Transifex sync (`.github/CONTRIBUTING.md`). Edit only the English source; translated files are
  excluded from CodeRabbit review. `lint` disables `MissingTranslation`.
- `translations/` holds scripts comparing Android sources to the iOS wallet's strings on Transifex
  (`translations/README.md`).
- `check_xml_references.sh` / `check-alignment.sh` at the root are helper scripts (not wired into CI).

### 6.4 Repo rules for agents

- Root `CLAUDE.md`: build commands (`./gradlew :wallet:compile_testNet3DebugKotlin` as the fastest check,
  `:wallet:test_testNet3DebugUnitTest`), MVVM rules, BIP70 test server recipe, and "Before pushing a PR branch":
  PRs are reviewed by the **thepastaclaw** bot which approves only at zero findings; run `/pre-review` first.
  Some facts in it are stale (Gradle 8.9, "Data Binding", "Uses JUnit", dashj as the crypto library).
- `wallet/CLAUDE.md`: the non-standard source-set layout and the four flavors.
- `integrations/crowdnode/CLAUDE.md`: module boundary rule (only `:common`), layering; states `minSdk 24`/`targetSdk 35`
  while the build file says 29/36 (stale).
- `.claude/agents/DEVELOPMENT-PATTERNS.md` (1,829 lines): ViewModel/UIState template; **new or updated UI must be
  Compose**; shared components belong in `common/.../ui/components`; full Figma→component mapping; dark-mode golden
  rule; bottom-sheet factory vs owned-ViewModel patterns; screen structure (stateful wrapper with `hiltViewModel()`
  + stateless screen taking `uiStateFlow` and callbacks).
- `.claude/agents/figma-to-compose.md`: agent that builds Compose screens from Figma and asks before creating or
  modifying shared components. `update-maya-currencies.md` / `update-swapkit-currencies.md`: agents that refresh
  `MayaCurrencyList` from live APIs.
- `.claude/skills/pre-review/SKILL.md`: runs the bot's model (via Codex CLI) over `merge-base..HEAD`; rules: verify
  each finding, **fix the class not the line** (grep every sibling path: legacy/resume/retry/restore/background, the
  other screen showing the same value), prefer narrowing over new mechanisms (a new cap/latch/mutex is reviewed at
  funds-safety depth), at most two local rounds per push, blocking/major only locally, out-of-scope findings are
  answered not fixed. Optional pre-push gate: `prereview.sh --install`.
- `.github/PULL_REQUEST_TEMPLATE.md`: issue/feature, related PRs (dashj, dpp, …), screenshots, testing, QA checkbox.

---

## 7. Gotchas and sharp edges

### 7.1 Migration-state traps

1. **There is no pre-cutover user.** Code, comments and enum values still model `DUAL_RUNNING`/rollback; the header
   of `CutoverStateMachine.kt:21-45` says not to design for it. A change that only works "pre-cutover" or keeps a
   dashj fallback is wrong for shipping installs.
2. **Several "committed?" readers with different failure behaviour** (§2.2 table). `cutoverCommitted()` fails *open*
   to "not committed"; send UI must use the flow/`OrThrow` variants and treat `null` as unknown.
3. **Comments that say "default OFF" are stale** for `USE_KOTLIN_SDK_*` (seeded ON in all builds,
   `DashPayConfig.kt:407-436`). Same for `ShieldedBalanceService` ("NO production call sites yet") and
   `L1ShadowSyncService` ("must never ship enabled to production"); also `CutoverCoordinator`'s comment that the
   engine-start gate "fails safe (dashj runs)" (`:188`) contradicts `dashjEngineMayStart()` returning false.
4. **dashj is held but still loaded.** The `.wallet` is parsed every launch (482 MB idle on a large wallet,
   `docs/upgrade-memory-and-sync-plan.md` §3), remains the seed source for the SDK bind, and its balances freeze
   post-cutover. Any **direct** dashj balance read bypasses the SDK overlay:
   `service/BootstrapReceiver.java:210` (inactivity reminder), `ui/send/SendCoinsViewModel.kt:655-656`
   (`getPendingBalance`), `service/platform/TopUpRepository.kt:225`, `:568` (emptyWallet detection for asset locks),
   `ui/WalletBalanceLoader.java:95`, `payments/SendCoinsTaskRunner.kt:1170`. **(inferred)** these show or use a
   frozen dashj figure post-cutover unless the path itself is dashj-only.
5. **Balance overlay covers only some streams** (`WalletApplication.java:3403-3475`): total, selector-less
   `ESTIMATED`/`ESTIMATED_SPENDABLE`, and max-output. Selector-based `observeBalance(type, selector)` stays dashj.
   A new balance consumer must pick a covered stream or go through `CutoverUiDataService`.
6. **The SDK balance itself can be wrong** after a re-walk (`DASHJ-KILL-LIST.md` §1a.1); the overlay's last-known
   hold only covers the unsynced window.
7. **Locked device = no signing, no DashPay crypto, no bind** (`DEVICE_BOUND` keystore, §2.1). Every new SDK write
   path needs a "signer locked → retry after unlock" outcome, not a hard failure (`SendSignerLockedException`,
   `KeystoreDeviceLockedException`, `AccountBuildDrainTracker`'s device-locked classification).
8. **Two Platform stacks answer the same questions.** SDK reads fall back to legacy on `null` (including a 6 s
   timeout); writes are SDK-first behind flags with legacy paths still present. A fix to one stack's behaviour
   (normalization, contested names, profile parsing) usually needs the sibling in the other
   (`SdkDpnsMapping`, `PlatformRepo`, `IdentityRepository`, `PlatformSyncService`).
9. **SDK internals via Java.** `CoreSendAllNative.java` (and `MasternodeVotingNative.java`) call SDK `internal`
   members by mangled name; bumping `dashSdkVersion` can break compilation there — by design.
10. **Version pins are exact snapshots** (`dashSdkVersion`, `dppVersion`); do not switch to `-SNAPSHOT` names
    (comments at `build.gradle:7-43`, `wallet/build.gradle:84-90`).

### 7.2 Rules that must hold on several sibling paths

1. **Send routing and failure mapping.** Main send, BIP70 (`PaymentProtocolViewModel`), DashSpend gift cards,
   Coinbase transfer, Maya/SwapKit, CrowdNode, username/invite funding, shielded — each must respect
   `cutoverSendRoute`/`SdkWriteResult` semantics. The typed send exceptions and `classifySendFailure` live in
   `wallet` (`ui/send/SendFailureMessageMapping.kt`); feature modules see only `common` interfaces, so
   **(inferred)** they render these failures generically or verbatim.
2. **`Ambiguous` must be terminal "may have gone through"** in every UI that can receive it (shielded screens,
   username creation, invites, top-ups); never a retry button.
3. **Biometric spending limit** is enforced on `SendCoinsFragment.kt:235-236` and re-implemented inline in
   `PaymentProtocolFragment.kt:106`, but not on gift cards, Coinbase transfer, shielded, username payment
   (`authenticate(activity)` with default `pinOnly = false`). Either intentional or a gap — check before adding a
   new spend path.
4. **Home vs everywhere else:** the home header shows L1 + shielded; all other screens show L1 only
   (`MainViewModel.kt:219-233`). A new "total" display must decide which.
5. **Cancellation rethrow** before broad `catch (e: Exception)` in suspend code, and `NonCancellable` for teardown.
6. **Wipe ordering and fences.** Anything that persists wallet secrets (SDK keys, mnemonic, identity keys,
   metadata saves) must check `WalletSecretWriteFence` (`checkOpen`) and be cancelled/joined by the wipe
   (`WalletApplicationExt.destroyWalletData`). New producers of secrets need to be added to that list.
7. **Credits/duffs constant** is defined three times (§4.1); a change must touch all of them.
8. **DataStore "unset" vs "unreadable":** `BaseConfig.get()/observe()` turn IO errors into `null`; money/identity
   gates must use `observePreservingErrors()` or fail closed.
9. **IdentityCreationState ordinal order** is semantic (`>=` checks); inserting a value in the middle changes
   behaviour of every comparison.

### 7.3 Legacy and duplication

- 115 Java files remain in `wallet/src` (39 `ui`, 26 `rates`, 16 `util`, 6 `payments`, 6 root incl.
  `WalletApplication.java` 3,639 lines and `Constants.java`), 41 in `common` (BIP70 `Protos.java` 4,727 lines,
  `Configuration.java`, money ports), 10 in `uphold`. `SecurityGuard.java` is Java and uses `runBlocking`.
- God files: `CutoverUiDataService.kt` 5,909; `L1ShadowSyncService.kt` 5,557; `BlockchainServiceImpl.kt` 4,306;
  `WalletApplication.java` 3,639; `PlatformSyncService.kt` 3,204; `SdkWalletBinder.kt` 2,681;
  `ShieldedBalanceServiceImpl.kt` 2,557; `CreateIdentityService.kt` 2,138.
- Duplicate types: two `Resource<T>` classes; two `Coin`/`Fiat`/`ExchangeRate`/`MonetaryFormat` families;
  two `Dash.toCoin()` extensions with different return types.
- Ad-hoc scopes and `GlobalScope` (§3.10); `DashPayConfig` launches seeding from its constructor on an unowned scope.
- `runBlocking` bridges from non-suspend legacy APIs into SDK calls (up to the 6 s query budget when the SDK path is
  slow) — **(inferred)** risky if any caller is on the main thread.
- TODO clusters: `TopUpRepository.kt` (10: iOS big-endian workaround, InstantSend lock determinism, chainlock
  proofs), `LockScreenActivity.kt:417-451` (commented-out keystore-break test UI), `WalletApplication.java`
  (autosave cap `:2012`, wallet creation placement `:1114`, `:1229-1230`), `Constants.java:81`
  ("remove when 32-bit bugs are fixed"). 121 TODO/FIXME/HACK in total.
- `docs/payment-methodology.md` still documents "CoinJoin Mode" and the "CoinJoin Greedy Algorithm" in the send
  path; `SendCoinsTaskRunner.kt` has one remaining CoinJoin mention and `SendCoinsViewModel.kt` none.
- Docs cite line numbers that have moved (e.g. `docs/upgrade-memory-and-sync-plan.md` §12.2 cites
  `DashSdkServiceImpl.kt:1117` for `DEVICE_BOUND`; it is at `:1762` now). Treat doc line references as hints.
- `FakeDashSpendService` (non-prod flavors) redirects `dashspend://` URIs to a hard-coded testnet address
  (`payments/FakeDashSpendService.kt:90-97`) — **(inferred)** fails or misbehaves on `devnet`, whose params do not
  accept that address.
- `SecurityFunctions.authenticate` (`security/SecurityFunctions.kt:104-124`): when biometrics fail and the user
  dismisses the "switch to PIN" dialog, the callback is never invoked — **(inferred)** the suspend
  `authenticate()` then never resumes for that caller.
- Orphaned KDoc: the "exception for a post-cutover NotBroadcast" comment block in `SendCoinsTaskRunner.kt:158-170`
  sits directly above another KDoc (for `isCompleteTxFailure`) and documents nothing.
- Instrumented `DatabaseMigrationTest` stops at 18→19 while production uses `fallbackToDestructiveMigration()`;
  migrations 19→22 have no automated test.
- `testOptions.unitTests.returnDefaultValues = true` hides unmocked Android calls in unit tests.

### 7.4 Quick index of files to read before touching an area

| Area | Read first |
|---|---|
| Who owns L1 / cutover | `service/platform/sdk/CutoverStateMachine.kt`, `CutoverCoordinator.kt`, `docs/upgrade-memory-and-sync-plan.md` §12 |
| Sending | `payments/SendCoinsTaskRunner.kt` (top 400 lines), `service/platform/sdk/SdkL1SendService.kt`, `CoreSendAllNative.java`, `ui/send/SendFailureMessageMapping.kt` |
| Balances / history | `WalletApplication.java:3340-3480`, `service/platform/sdk/CutoverUiDataService.kt` (pure helpers at top), `DASHJ-KILL-LIST.md` §1a |
| SDK lifecycle / keys | `service/platform/sdk/DashSdkService.kt`, `DashSdkServiceImpl.kt`, `SdkWalletBinder.kt`, `SecurityGuardMnemonicProvider.kt` |
| Platform writes | `service/platform/sdk/SdkDashPayWrites.kt` (decision table), `PlatformBroadcastService.kt` |
| Identity / username | `ui/dashpay/CreateIdentityService.kt`, `SdkTransparentUsernameCreation.kt`, `SdkShieldedUsernameCreation.kt`, `database/entity/BlockchainIdentityData.kt` |
| Contacts / DIP-15 | `ContactPaymentAddressProvider.kt`, `PaidContactAddressStore.kt`, `PlatformSyncService.kt`, plan §§16–19 |
| Shielded | `ShieldedBalanceService.kt` (interface KDoc), `ShieldedBalanceServiceImpl.kt`, `ui/shielded/*` |
| Flags / migration state | `ui/dashpay/utils/DashPayConfig.kt`, `common/.../data/BaseConfig.kt` |
| Wipe | `util/WalletWipeSequence.kt`, `WalletApplicationExt.kt`, `WalletSecretWriteFence.kt`, `SdkWalletSecretsWipe.kt` |
| Persistence | `database/AppDatabase.kt`, `AppDatabaseMigrations.kt`, `di/DatabaseModule.kt` |
| UI conventions | `.claude/agents/DEVELOPMENT-PATTERNS.md`, `common/.../ui/components/` |
