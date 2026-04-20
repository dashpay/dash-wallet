# Proposal: Async Wallet Loading for Faster Home Screen Display

## Problem

`WalletProtobufSerializer.readWallet()` takes approximately 5–6 seconds on first launch
because it deserializes all transactions from disk. This call happens synchronously inside
`WalletApplication.onCreate()` via `fullInitialization()`, which blocks the main thread and
prevents any Activity from starting until the wallet is fully loaded.

As a result, even though the app has a display cache (`TxDisplayCacheEntry`) that can render
cached transaction rows in ~100 ms, users still see a blank screen for 5–7 seconds before
`MainActivity` starts and `MainViewModel` can show the cached data.

Profiling breakdown (measured on a testnet device with ~200 transactions):

| Step | Duration |
|------|----------|
| `WalletProtobufSerializer.readWallet()` | ~5,300 ms |
| `SecurityGuard` fallback setup | ~168 ms |
| `initDash()` (dashj system init) | ~12 ms |
| Integration inits (Uphold, Coinbase, etc.) | ~465 ms |
| **Total `fullInitialization()`** | **~7,000 ms** |

## Proposed Solution

Move `loadWalletFromProtobuf()` (which includes `finalizeInitialization()`) to a background
thread so that Activities can start immediately and show cached transaction data while the
wallet loads in parallel.

### Architecture

1. **`WalletApplication.onCreate()`** calls `initEnvironment()` synchronously (~36 ms — sets
   up StrictMode, bitcoinj Context, etc.) then starts a `"wallet-init"` background thread for
   `loadWalletFromProtobuf()`.

2. **`WalletReadyFlow`** — a new Kotlin `object` backed by `MutableStateFlow<Boolean>`.
   Exposes `observe(): Flow<Unit>` that emits once when the wallet finishes loading, and
   `setReady()` called at the end of the background thread.

3. **`WalletDataProvider.observeWalletReady(): Flow<Unit>`** — new interface method with a
   default implementation of `flowOf(Unit)` (emits immediately; suitable for tests).
   `WalletApplication` overrides it to delegate to `WalletReadyFlow`.

4. **`MainViewModel`** — uses `combine(_transactionsDirection, walletData.observeWalletReady())`
   instead of just `_transactionsDirection.flatMapLatest` so that `rebuildWrappedList()` waits
   for the wallet before trying to access `walletData.wallet!!`.

5. **`LockScreenActivity.onCreate()`** — when `walletData.wallet == null`, initializes
   `binding`, shows `displayedChild = 1` (main content) immediately, and defers
   `initView()` / `initViewModel()` / `applyLockScreenState()` to a `lifecycleScope` coroutine
   that collects from `observeWalletReady()`. The original `finish()` call is replaced so
   `MainActivity` can start and show cached transactions.

6. **`OnboardingActivity`** — the Android `LAUNCHER` Activity. When `walletFileExists()` but
   `wallet == null` (still loading), calls `regularFlow()` immediately instead of waiting, so
   `MainActivity` starts right away.

7. **`MainActivity.onCreate()`** — `upgradeWalletKeyChains()` and `upgradeWalletCoinJoin()`
   (which call `walletData.wallet!!`) are deferred inside `lifecycleScope.launch { observeWalletReady().collect { ... } }`.

### Expected Result

- `MainActivity` starts in ~200–500 ms (after `initEnvironment()` and Activity inflation).
- Display-cache rows appear at ~1–2 s (after `MainViewModel` reads `TxDisplayCacheEntry` from Room).
- Live wallet data replaces cached rows at ~6–8 s (after background thread finishes).
- Lock screen (PIN/biometrics) is applied once the wallet is ready — acceptable since the
  auto-logout timer is typically ≥1 minute and the wallet cannot be spent while loading.

## Why It Was Reverted

This approach was implemented and then reverted because it requires touching every code path
that assumes `walletData.wallet` is non-null at the time of first use. The app has many such
call sites across ViewModels, Activities, and Fragments. Introducing null-safety for the wallet
globally is a larger refactor than the scope of this fix warranted.

The two most critical blockers encountered during the experiment:

1. **`CheckPinViewModel`** accesses `walletData.wallet!!` at construction time via `by
   viewModels<>()` in `LockScreenActivity`. Deferring `initViewModel()` to
   `observeWalletReady()` sidesteps this, but creates subtle ordering risks.

2. **Many other ViewModels and call sites** use `walletData.wallet!!` without null checks and
   would crash during the window between app start and wallet ready (~5–7 s). A full audit and
   defensive null-check pass would be required before this approach is safe.

## Prerequisites for Revisiting

Before attempting this optimization again, the following work should be completed:

- [ ] Audit all `walletData.wallet!!` call sites; replace with `walletData.wallet ?:` guards
      or restructure to be triggered from `observeWalletReady()`.
- [ ] Move wallet-access logic out of ViewModel constructors and into `init {}` blocks that
      wait for `observeWalletReady()`.
- [ ] Add integration tests that cover the "wallet null at startup" window to prevent
      regressions.
- [ ] Consider splitting `WalletDataProvider` into a "wallet-ready" interface that is only
      available after initialization, and a "pre-wallet" interface for display-cache reads.