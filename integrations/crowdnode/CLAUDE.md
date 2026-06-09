# CrowdNode Integration Module

This document describes the `integrations/crowdnode` module: a self-contained Android library
that integrates [CrowdNode](https://crowdnode.io) staking into the Dash Wallet app. It lets a user
sign up for a CrowdNode account, deposit/withdraw Dash, and optionally link an online (email-based)
CrowdNode account.

## Module Setup

- **Type**: Android library (`com.android.library`), `namespace org.dash.wallet.integrations.crowdnode`
- **Structure**: Standard Android layout — `src/main/java/...`, `src/main/res/...`, `src/test/java/...`
  (this differs from the `wallet/` module, which uses a non-standard `src/` layout)
- **Language/JVM**: Kotlin, Java 17 (`sourceCompatibility`/`targetCompatibility` = 17), `coreLibraryDesugaring` enabled
- **SDK**: `minSdk 24`, `compileSdk`/`targetSdk 35`
- **Plugins**: kotlin-android, KSP, navigation-safeargs, Hilt, kotlin-parcelize, ktlint
- **Only dependency on internal modules**: `:common`. It does **not** depend on `:wallet` — the wallet
  app depends on this module, not the other way around. Keep it that way: communicate with the app
  only through interfaces declared in `:common` (e.g. `WalletDataProvider`, `SendPaymentService`,
  `TransactionMetadataProvider`, `NotificationService`, `AnalyticsService`, `BlockchainStateProvider`).
- **UI**: Android Views with **ViewBinding** (not Data Binding, not Compose), Navigation Component
  (`res/navigation/nav_crowdnode.xml`).

## Build & Test Commands

```bash
# Compile the module
./gradlew :integrations:crowdnode:compileDebugKotlin

# Run unit tests
./gradlew :integrations:crowdnode:testDebugUnitTest

# Lint/format
./gradlew :integrations:crowdnode:ktlintFormat
```

## Architecture

The module follows the app-wide **MVVM** convention (see root `CLAUDE.md`): `@HiltViewModel`
ViewModels exposing `StateFlow`/`LiveData`, Hilt for DI. The CrowdNode-specific layering is:

### 1. API layer (`api/`) — the core of the module
- **`CrowdNodeApi`** (interface) / **`CrowdNodeApiAggregator`** (impl, `@Singleton`, bound in
  `CrowdNodeModule`). This is the orchestrator and the main entry point for all CrowdNode behavior.
  It owns the observable state the UI reacts to:
  - `signUpStatus: StateFlow<SignUpStatus>`
  - `onlineAccountStatus: StateFlow<OnlineAccountStatus>`
  - `balance: StateFlow<Resource<Coin>>`
  - `apiError: MutableStateFlow<Exception?>`
  It composes the two lower-level APIs below, persists progress to `CrowdNodeConfig`, drives
  notifications, and reports analytics/errors.
- **`CrowdNodeBlockchainApi`** — on-chain operations via `:common`'s `SendPaymentService`: building
  and sending deposit/top-up transactions to the CrowdNode address, locking specific outputs
  (`lockAccountAddressOutput`) so concurrent sends don't spend reserved funds, and waiting for
  matching transactions via `waitToMatchFilters` + the tx filters in `transactions/`.
- **`CrowdNodeWebApi`** — the off-chain REST side. `CrowdNodeEndpoint` is the Retrofit interface
  (CrowdNode OData endpoints: `GetFunds`, `GetBalance`, `GetWithdrawalLimits`, `GetFeeJson`, …).
- **`RemoteDataSource`** — builds the Retrofit client (Gson converter, body logging in debug only).
- **`CrowdNodeWorker`** — a `HiltWorker`/`CoroutineWorker` that runs the sign-up flow as a
  foreground service (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`) so it survives the app being backgrounded.
  Enqueued via WorkManager with `WORK_NAME = "CrowdNode.WORK"`.
- **`CrowdNodeConfirmationTxHandler`** — handles the API confirmation transaction.

### 2. Transactions (`transactions/`)
Transaction **filters** and **response/tx wrappers** that identify the stages of the CrowdNode
sign-up handshake on-chain. The protocol is a sequence of small Dash transactions with sentinel
amounts (see `CrowdNodeConstants`: `API_CONFIRMATION_DASH_AMOUNT = 54321` satoshis, etc.). Examples:
`CrowdNodeSignUpTx`, `CrowdNodeAcceptTermsResponse`, `CrowdNodeWelcomeToApiResponse`,
`CrowdNodeDepositTx`/`CrowdNodeDepositReceivedResponse`, `CrowdNodeWithdrawal*`.
**`FullCrowdNodeSignUpTxSet`** / `…Factory` group a full sign-up into one logical set.

### 3. Models (`model/`)
Plain data/enums: `SignUpStatus`, `OnlineAccountStatus` (⚠️ **order matters** — these are persisted
by ordinal via `ApiCode`; only append, and map old ordinals if you must reorder), `CrowdNodeBalance`,
`CrowdNodeTx`, `WithdrawalLimit`, `ApiCode`/`ApiStatuses`, `CrowdNodeException` + subtypes.

### 4. Utils (`utils/`)
- **`CrowdNodeConstants`** — network-aware addresses (mainnet/testnet), base/login URLs, and all the
  magic Coin amounts (minimum deposit, signup amount, API offset, leftover balance, withdrawal limits).
  Always resolve addresses/URLs through here using `walletDataProvider.networkParameters` so the
  module works on both mainnet and testnet.
- **`CrowdNodeConfig`** — a `BaseConfig` (DataStore Preferences, store name `"crowdnode"`) holding
  persisted flags/values: shown-dialog flags, `ONLINE_ACCOUNT_STATUS`, `LAST_BALANCE`, withdrawal
  limits, fee percentage, etc.
- **`CrowdNodeBalanceCondition`** — balance-related guard logic (unit-tested).

### 5. UI (`ui/`)
Fragments driven by the `nav_crowdnode.xml` graph and a shared **`CrowdNodeViewModel`**:
- `entry_point/` — `EntryPointFragment` (choose new vs. existing account), `NewAccountFragment`,
  `FirstTimeInfoFragment`
- `online/` — email-based online account linking flow
- `portal/` — `PortalFragment` (main dashboard), `TransferFragment` (deposit/withdraw)
- `ResultFragment`, plus `dialogs/` (confirmation, staking, QR, withdrawal limits, etc.)

## Dependency Injection

`di/CrowdNodeModule` (`@InstallIn(SingletonComponent::class)`):
- `@Binds` `CrowdNodeApiAggregator` → `CrowdNodeApi` as a `@Singleton`.
- `@Provides` the `CrowdNodeEndpoint` Retrofit service, with the base URL chosen by network via
  `CrowdNodeConstants.getCrowdNodeBaseUrl(networkParameters)`.

## Concurrency Notes

`CrowdNodeApiAggregator` uses several dedicated `CoroutineScope`s rather than `viewModelScope`,
because work must outlive any single screen:
- `configScope` (`Dispatchers.IO`) — config persistence
- `responseScope` / `statusScope` — single-threaded executors that serialize response handling and
  status transitions (important: keep status mutations on `statusScope` to avoid races)
Long-running sign-up runs through `CrowdNodeWorker` (WorkManager) so it survives process/background.

## Testing

Unit tests live in `src/test/java/...`:
- `CrowdNodeApiAggregatorTest` — orchestration / state-machine behavior (uses mockito-kotlin +
  kotlinx-coroutines-test)
- `CrowdNodeViewModelTest`
- `CrowdNodeTxFilterTest` — transaction filter matching
- `CrowdNodeBalanceConditionTest`

When changing the sign-up/deposit/withdraw flow, add or update a `CrowdNodeTxFilterTest` case if the
on-chain transaction shape changes, and an aggregator test for new `SignUpStatus`/`OnlineAccountStatus`
transitions.

## Conventions & Gotchas

- **Never hardcode addresses, URLs, or magic amounts** in UI/API code — add them to
  `CrowdNodeConstants` and resolve by `NetworkParameters`.
- **Address marking**: when a CrowdNode transaction targets an address, the flow calls
  `TransactionMetadataProvider.maybeMarkAddressWithTaxCategory(...)` (see `CrowdNodeApi.kt`) with
  `ServiceName.CrowdNode` so the address is tagged with the correct tax category. This is idempotent.
- **Enum ordinals are persisted** (`SignUpStatus`, `OnlineAccountStatus` via `ApiCode`) — append only.
- **No `:wallet` dependency** — if you need something from the app, expose it through a `:common`
  interface and inject it.
- Strings live in `res/values/strings-crowdnode.xml` (with many translated `values-*` variants);
  keep new user-facing strings there.

## Temporarily Disabled Functionality (withdrawals only)

CrowdNode has been intentionally **restricted to withdrawals only**. Deposits, linking an existing
account, and creating an online account are hidden in the UI so that users can still get their funds
**out**, but cannot put new funds **in** or set up new account linkages. Withdrawals are deliberately
left fully working.

This was done purely at the **UI layer** (hiding buttons), not by removing any API/blockchain logic —
so the disabled flows can be restored by reverting two files. Introduced in commit
`a66664761 "feat (crowdnode): disable all functions except withdraw for existing accounts"`.

### What was changed

1. **`ui/entry_point/EntryPointFragment.kt`** — the *Link existing account* button is hidden, and its
   click listener (which navigated to `entryPointToNewAccount(true)`) was removed:
   ```kotlin
   // CrowdNode functionality is limited: linking an existing account isn't supported
   binding.existingAccountBtn.isVisible = false
   ```
   (The *New account* button `newAccountBtn` itself was left untouched in this commit.)

2. **`ui/portal/PortalFragment.kt`** — in `onViewCreated`, the *Deposit* button is hidden (its
   navigation listener removed) and the *Online account* button is hidden:
   ```kotlin
   // CrowdNode functionality is limited: deposits aren't supported. Only withdrawals are allowed.
   binding.depositBtn.isVisible = false
   // hidden unless the online account is fully set up - see setOnlineAccountStatus
   binding.onlineAccountBtn.isVisible = false
   ```
   And in `setOnlineAccountStatus(...)` the online-account button is only shown once an online
   account is fully set up (`OnlineAccountStatus.Done`), instead of during linking/creation:
   ```kotlin
   // CrowdNode functionality is limited: creating an online account isn't
   // supported. The button is only shown for fully set up online accounts.
   binding.onlineAccountBtn.isVisible = status == OnlineAccountStatus.Done
   ```

3. The **Withdraw** button (`withdrawBtn` → `continueWithdraw()`) was **not** modified — withdrawals
   remain fully enabled.

### How to undo (re-enable deposits / account linking)

Because the change is UI-only and confined to two files, the cleanest revert is:

```bash
# Revert just the disabling commit (re-enables everything)
git revert a66664761

# …or, if other work is stacked on top, restore the two files to their pre-disable state:
git checkout a66664761~1 -- \
  integrations/crowdnode/src/main/java/org/dash/wallet/integrations/crowdnode/ui/entry_point/EntryPointFragment.kt \
  integrations/crowdnode/src/main/java/org/dash/wallet/integrations/crowdnode/ui/portal/PortalFragment.kt
```

To re-enable manually instead, reverse each change above:

- **EntryPointFragment.kt** — replace `binding.existingAccountBtn.isVisible = false` with the original
  click listener:
  ```kotlin
  binding.existingAccountBtn.setOnClickListener {
      viewModel.logEvent(AnalyticsConstants.CrowdNode.LINK_EXISTING)
      safeNavigate(EntryPointFragmentDirections.entryPointToNewAccount(true))
  }
  ```
- **PortalFragment.kt (`onViewCreated`)** — remove `binding.depositBtn.isVisible = false` and
  `binding.onlineAccountBtn.isVisible = false`, and restore the deposit click listener:
  ```kotlin
  binding.depositBtn.setOnClickListener {
      viewModel.logEvent(AnalyticsConstants.CrowdNode.PORTAL_DEPOSIT)
      safeNavigate(PortalFragmentDirections.portalToTransfer(false))
  }
  ```
- **PortalFragment.kt (`setOnlineAccountStatus`)** — delete the added
  `binding.onlineAccountBtn.isVisible = status == OnlineAccountStatus.Done` line so the button's
  visibility is governed only by the original linking-progress logic.

> Note: deposit/withdraw share the same `TransferFragment` (`portalToTransfer(isWithdraw)`), and all
> the underlying API/blockchain logic for deposits and account linking is still present and untouched.
> Re-showing the buttons is sufficient to fully restore those flows — no API changes are required.