# Before you push: the v12 checklist

These are the questions thepastaclaw and coderabbitai asked most often on v12 PRs (87 PRs into
`feat/kotlin-sdk-phase-1`, #1414–#1632). Walk your diff through them, then run `/pre-review`. Each item links to the
rule with its reasoning and PR examples.

## Unknown state ([01](01-fail-closed.md))
- [ ] Can any read in this diff fail, and what happens then? A failure must not become `0`, `false`, empty, "absent",
      or "not cut over" on a path that gates money, deletion, address reuse or a destructive cleanup.
- [ ] Safety flags and markers are read with `observePreservingErrors` / `*OrThrow`, never `BaseConfig.get()`.
- [ ] Persisted markers are validated on decode: malformed ≠ absent; IDs have their exact format; deadlines are
      computed without overflow.
- [ ] Absence is confirmed with no-follow NIO, not `File.exists()`. Every delete/clear result is checked.

## Concurrency ([02](02-concurrency.md))
- [ ] Every shared-state replace, cancel or latch has an ownership token (a generation, lifetime or ticket), checked
      under the same lock, and the publish happens inside that lock.
- [ ] Every suspension point between a check and the act it guards is covered by that check.
- [ ] `CancellationException` is rethrown before each broad catch, including outer ones. No `runCatching` around
      suspend calls.
- [ ] Every job the change launches on a long-lived scope is stored, cancelled by `stop()`, and joined before
      destructive cleanup.
- [ ] Must-complete steps run under `NonCancellable`, and ownership is cleared only after success.

## Durability ([03](03-durability-and-recovery.md))
- [ ] For each irreversible step (broadcast, commit, delete, expose): is the intent/recovery record durable *before*
      it? What does a process kill between any two writes leave behind?
- [ ] Multi-key state is written in one transaction (`editPreferences {}` / one `commit()`).
- [ ] Every latch set has a success-gated clear. Every obligation stays owed until durable confirmation.
- [ ] The change scopes everything it reads or writes in `dash-sdk.db` to the active wallet id. The wallet wipe stops
      every new writer.

## Money, SDK, Platform ([04](04-money-sdk-platform.md))
- [ ] Amount variables carry their unit (`Duffs`/`Credits`), and fixtures use the right scale.
- [ ] Unknown amounts are `null`, not zero. No fabricated rate, fee, route or `halted=false`.
- [ ] The wallet identity, destination and context are captured before the first suspension and re-checked before
      broadcast.
- [ ] No fallback to the frozen dashj wallet after cutover. Self-transfers never go to the advertised address.
- [ ] Fresh spends require authentication. Resuming an in-flight spend does not.
- [ ] `Ambiguous` / uncertain outcomes are terminal "may have gone through", never a retry button.

## Android ([05](05-android-lifecycle-ui.md))
- [ ] No main-thread wait on `DeferredWalletLoad`.
- [ ] Observers use `viewLifecycleOwner` in `onViewCreated`, and nothing latches across view recreation.
- [ ] Activity-scoped ViewModels re-resolve inputs on each flow entry and clear the previous result.
- [ ] Rotation is neither backgrounding nor dismissal.
- [ ] No optimistic state that only a new `StateFlow` emission can undo.
- [ ] Every new string is a resource, and every new control has a `contentDescription`.
- [ ] Dark mode: semantic colours, `cursorBrush`, QR codes dark-on-light.

## Security ([06](06-security-and-logging.md))
- [ ] No secrets, links, addresses, amounts or input-bearing exceptions in logs or in `toString()`.
- [ ] Production dependencies are pinned to immutable versions.

## Tests ([07](07-testing.md))
- [ ] Every fix has a regression test that fails on the old code, and no test still asserts the old bug.
- [ ] Race tests park the step with gates, not sleeps. Latches are released in `finally`.
- [ ] Tests ran locally on the flavoured variant (`:wallet:test_testNet3DebugUnitTest`); CI green does not prove it.

## Style and docs ([08](08-code-style.md))
- [ ] No `!!`. The change matches the file's existing patterns and reuses existing helpers.
- [ ] KDoc sits directly above its declaration and was updated with the behaviour. Comments and logs claim only what
      the code knows.

## Review loop ([09](09-review-bots.md))
- [ ] The branch is rebased on the current base. The PR description states product requirements and known limits.
- [ ] Bot findings: verify each one; fix the class, not the line; at most two local rounds per push; stop at zero blockers.
