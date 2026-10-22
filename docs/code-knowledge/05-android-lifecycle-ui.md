# 5. Android lifecycle and UI observation

## Scopes and screens

**5.1 Do not run a multi-step handoff in a `viewModelScope` whose screen navigates away.**
- Move the handoff to the application scope.
- Publish "submitting" only after the handoff is accepted.
- Surface failures as retryable errors (#1626).

**5.2 Activity-scoped ViewModels outlive a sheet or a flow.**
- Re-resolve inputs (quotes, fees, amounts) on every flow entry, not only in `init` (#1584, #1607).
- Clear the previous result before a new creation flow, so that observers cannot replay it. In #1619 the previous
  invitation link was shareable for the new invite, which is a bearer-link leak.
- Reset the terminal state on Done (#1607).

**5.3 Observe in `onViewCreated` with `viewLifecycleOwner`.**
- `viewLifecycleOwner` in `onCreateView` can throw.
- Fragment fields must not outlive the view; reset them at observer setup.
- Every value that affects the rendered UI must be observed. A fee that is read once and never observed shows a stale
  maximum (#1584, #1581, #1504).

**5.4 View-creation side effects must be idempotent for nav-graph-scoped ViewModels.** Seed once, guarded by "already
seeded / asset changed". In #1504, `onCreateView` called `createBuyOrder` again on every recreation and rotated the
deposit address.

**5.5 A click-owned request goes in `viewLifecycleOwner.lifecycleScope`.** Async error UI checks cancellation and
attachment before touching the context. Use the `toastIfStillAttached()` helper; never `requireContext()` in a coroutine
catch (#1570).

**5.6 ViewPager2 children** (#1570):
- They can be created offscreen (STARTED). Never dismiss the parent NavController from one.
- Load in `repeatOnLifecycle(RESUMED)`, not one-shot in `onViewCreated`.
- Do not latch success.

**5.7 A configuration change is neither backgrounding nor dismissal.**
- Exclude `isChangingConfigurations()` from auto-logout.
- Finish a host from the sheet's dismiss result, not from `onFragmentDestroyed` (#1555, #1486).

**5.8 Don't keep activity-capturing callbacks in application-scoped lists.** Remove them on destroy (#1594
`SafeModeRetryWaiters`).

## StateFlow pitfalls

**5.9 A `StateFlow` does not re-emit an equal value.** Do not render an optimistic UI state that only a new emission can
undo. Bound it in time and re-render from `.value` (#1568). A sheet dropped under the lock screen needs a pending flag,
retried from `onResume` / `onLockScreenDeactivated`. This follows the existing `pendingCutoverUpgradeNotice` pattern.

**5.10 An observing flow that catches and completes never recovers.** Retry with bounded backoff and emit
`null` = unknown/blocked in the meantime (#1616, #1608).

**5.11 Do not read success from a marker disappearing.** Derive success from a verified set→cleared transition.
`BaseConfig.observe()` re-emits on every unrelated write, because it has no `distinctUntilChanged()` (#1607).

**5.12 Re-check every disabling flag on every path that enables submit** (#1564 duplicate secondary name). When a
stale-result guard drops a completion, reset the in-flight UI state at the point that invalidated the request (#1564
stuck spinner).

## Services and the wallet load gate

**5.13 Never block the main thread on the `DeferredWalletLoad` gate.** Seven separate reachable paths were found in #1585
and #1594: activity pre-create, onboarding, `LockScreenActivity`, a CrowdNode notification, widgets and Report issue.
- Recovery-UI checks skip the wait during a safe-mode retry.
- Mutators and savers wait with no bound and ignore interrupts: `awaitDeferredWalletLoadForMutation()`. A timeout must
  never grant access to an unfinished wallet (#1585).

**5.14 `LockScreenActivity` subclasses.** Override `onCreateWithWallet(savedInstanceState)`; do not continue after
`super.onCreate()`. A `return` in the parent's `onCreate` does not stop the subclass. Check `finishedForNoWallet` (#1576,
#1594).

**5.15 Foreground services** (`BlockchainServiceImpl`):
- Record the start ID only after `startForeground` has succeeded (#1623).
- Every refusal path releases the init latch and does not `stopSelf()` ahead of a pending wipe. `stopSelf()` does not
  destroy a service while an activity is bound (#1576).
- Restart paths either do work or call `stopSelf()` (#1615).
- Teardown must not cancel a restart alarm that this instance just armed (#1616).
- Decide demotion with one live hold predicate at decision time (`sdkHoldsService(now)`). Promoting again from the
  background throws on Android 12+ (#1591).
- Every wake-lock acquirer has its own owner flag and releases once per owner on every exit path (#1568). A wake lock
  with no timeout must be bounded by the issuing service's lifetime.
- Re-evaluate holds from events (a `StateFlow`), not from `ACTION_TIME_TICK`, which is not delivered while the CPU
  sleeps (#1632).
- Seed connectivity state: register the callback first, then read the current state, and apply the seed only if no
  callback arrived in between (#1632).

**5.16 Show Toasts and other UI only from the main thread**, including on deferred and recovery worker paths (#1585).

## Compose and resources

**5.17 Undo Compose side effects on disposal** (a `DisposableEffect`-style reset). Base empty states on the list
actually rendered (`displayItems`, not `items`) (#1500, #1493).

**5.18 Dark mode.**
- Set `cursorBrush = SolidColor(LocalDashColors.current.textPrimary)` on `BasicTextField`; its default is black.
- QR codes stay dark-on-light with a quiet zone (#1620, #1622).
- Use semantic tokens (`content_primary`, `MyTheme.Colors`), never hex.
- Full guide: `.claude/agents/DEVELOPMENT-PATTERNS.md` → Dark Mode Compatibility.

**5.19 Accessibility and strings.**
- Every clickable control has a `contentDescription`.
- Every user-facing string is a resource with correct placeholders, and callers and tests pass every `%s`.
- No `@SuppressLint("SetTextI18n")` debug text, and no internal ids as nav labels.
- Use plural-aware strings (#1504, #1433, #1605).

**5.20 User-facing copy claims only what the engine knows.** "Transactions before that date may be missing" is right;
"never downloaded" is wrong, because the scan starts at a checkpoint before the date (#1573). The same applies to log
lines and error messages (#1568).
