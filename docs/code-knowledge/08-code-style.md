# 8. Code style and conventions

These conventions are what the reviewers asked for and what the code already does. They add to `CLAUDE.md`
("Architecture"), `.coderabbit.yaml` (path instructions) and `.claude/agents/DEVELOPMENT-PATTERNS.md` (MVVM, Compose
components, dark mode).

## General
- **Match the surrounding file**: its naming, comment density and idiom. Follow an existing pattern in the same file
  before inventing one. For example, `pendingSdkBindPendingSheet` copies `pendingCutoverUpgradeNotice`, and `showOnce`
  returns Boolean like `CutoverSyncNoticeDialogFragment.showOnce`.
- **Reuse existing utilities.** Examples: `maskEmail()`, `getDistanceText`, the shared `AddressField`,
  `Constants.DASH_CURRENCY`, `LocalDashColors`, `BaseConfig.editPreferences {}`.
- **A file is named after its primary public declaration.**
- **Remove dead code**: commented-out launches, dead diagnostic blocks, unreachable catches. Order range conditions from
  most to least specific: an `else if (x > 3)` under `if (x > 1)` is dead (#1431).
- **Formatting**: `.editorconfig` sets 120 columns and no trailing commas. Format with `./gradlew ktlintFormat`. CI's
  `ktlintCheck` covers only the modules that apply the plugin (exploredash, uphold, crowdnode, maya), not `wallet`,
  `common` or `coinbase`.

## Kotlin
- **No `!!` and no unsafe casts in production code**; coderabbit flags them by path instruction. Use `?.`,
  `toIntOrNull`, `toBigDecimalOrNull` on external data. When a value is required, guarantee it upstream or handle null
  explicitly.
- **Generic `Preferences.Key` matching**: compare key objects with `===` before an unchecked cast, because
  `Preferences.Key.equals` compares names only (#1578).
- **`@Volatile` on flags written and read on different dispatchers.**
- **Capture a mutable field once** and use that reference for both the guard and the action.
- **Tri-state results** (sealed/enum) instead of nullable or Boolean when "unknown" exists
  (see [01-fail-closed.md](01-fail-closed.md)).
- **Immutable snapshots** cross suspension points: `ResolvedPayee`, `DryRunResult`, `WalletSession`, purchase
  context.
- **Overflow-safe time math**: compare elapsed time, not `start + timeout`.
- **Valid Kotlin that bots misread** (pushback is correct here):
  - an `inline` function's non-`crossinline` lambda may call suspend functions;
  - `receiver.(fn)()` is valid;
  - since Kotlin 1.4 a positional argument may follow a named one in its own position;
  - no-argument `lowercase()` is locale-invariant.

## ViewModels and UI
- **One private-mutable `UIState` per ViewModel**: `_uiState` + `uiState` via `asStateFlow()`. Fold navigation
  signals in too, with no side `SingleLiveEvent`. No public `MutableStateFlow`. No plain getters for screen state,
  because they never trigger recomposition. Use `@HiltViewModel` with constructor injection.
  - *Exception, accepted by both bots:* an existing ViewModel without a UIState is migrated in its own refactor,
    not inside a feature or bug-fix PR (#1621 `MainViewModel`, #1551 `EncryptWalletLiveData`, #1605 Tools).
- **Fragments**: observe in `onViewCreated` with `viewLifecycleOwner`. Use SafeArgs-generated Args, not hardcoded
  keys.
- **Compose**: `remember` or hoist formatters (`DecimalFormat`) instead of allocating them per recomposition. Use theme
  colours from `LocalDashColors.current`, never `MyTheme.Colors` (the fixed light palette) or hex.

## Persistence
- Multi-key settings are written in one `editPreferences {}` / one `Editor.commit()`.
- Durable markers are small classes in `util/`. They have a tri-state read, an atomic write (temp in the same
  directory, fsync, `ATOMIC_MOVE`; what that does and does not survive is in 03 §3.11), a generation, and a boolean-returning compare-and-delete `complete()`. Examples:
  `RecoveryResetState`, `BackupReplacementState`, `WalletWipeState`.
- Distinct keys for distinct obligations.
- Room: index columns used in joins and subqueries. An index on a shipped DB needs a migration.
- Persisted diagnostics carry their owner, e.g. a `<ms> <walletId>` header.

## Errors and logging
- Catch narrowly. Rethrow `CancellationException`. Translate everything else into the layer's domain error with the
  cause attached, e.g. `SendException(msg, cause)` or `NotBroadcast(reason, t)`.
- When a source has a typed error, match it explicitly and keep the message-text fallback neutral.
- Log with SLF4J and include the exception. Never log secrets (see [06-security-and-logging.md](06-security-and-logging.md)).
- Emit analytics events from the branch that decides the result, not before it (#1566).
- Startup work runs inside `runStartupStage(...)`, so that an exception cannot abort `WalletApplication.onCreate()`.
- A shared constant beats a magic string, e.g. `AnalyticsService.PLACEHOLDER_FIREBASE_PROJECT_ID`.

## Comments and KDoc
- **One KDoc block per declaration, directly above it.** Kotlin attaches only the last block, so inserting a function
  between a KDoc and its target detaches the KDoc. This was flagged 4+ times.
- **Update KDoc in the same PR that changes behaviour.** Name the throwing variant and its exception so that callers do
  not rely on a display-only fallback.
- **Refer to constants, not release names** (`CutoverCoordinator.FIRST_CUTOVER_VERSION_CODE`, not "pre-11.10"). Quote
  current values (`WIDENED_GAP_LIMIT=1000`).
- **Comment a magic number with its derivation.** Example: `MARKER_FILE_MAX_BYTES` = the length of `"v1\n"`.
- **For protocol-dependent values, state them per era** (pre-v4.2 vs protocol 14).
- **Log lines and error messages say only what the code knows at that point.** Log "armed" after the arming call, not
  before the branch.
- **Issue and design docs** must ask for a fix consistent with the root cause they state.

## Build and dependencies
- Use the Compose BOM with version-less Compose dependencies, and shared version variables (`$lifecycleVersion`). No
  per-module hardcoded versions.
- In `build.gradle`, pin the SDK to the exact timestamped artifact you tested. A pin bump that changes stored semantics
  lands together with the wallet-side fix it needs (#1597). Check on-disk format compatibility (#1593). Reference only
  published QA recipes in pin comments (#1630).
- ProGuard for Gson models keeps all constructors, not just `<init>()`.
- `service.properties` values that become `BuildConfig` strings must be quoted.
