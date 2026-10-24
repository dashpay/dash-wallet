# Code knowledge for the v12 line

This is the shared knowledge for developing dash-wallet v12 (`feat/kotlin-sdk-phase-1`). It is meant for people and
for agents (Claude, `/pre-review`).

**Sources**
- Every thepastaclaw and coderabbitai review thread on the 87 PRs into the v12 branch, #1414–#1632. PRs into `master`
  were skipped because they were built on the old stack. That is 722 bot threads (433 thepastaclaw, 289 coderabbitai),
  read together with the authors' replies and with how each thread was resolved.
- An independent read of the code at `421514a3a`.

Each rule carries the PRs it came from, so the reasoning can be checked. Some of those PRs are still open: #1548,
#1563, #1616, #1622, #1626, #1630 and #1632 (#1607 has since been merged). #1600 was closed without merging. Their
lessons apply, but a class or function named from them may not exist on the base yet. Check with `git grep` before
relying on it.
The heaviest PRs were #1607, #1616, #1584, #1570, #1568 and #1576.

| File | What it covers |
|---|---|
| [checklist.md](checklist.md) | **Start here before pushing**: the questions the review bots ask most, one line each |
| [00-project-map.md](00-project-map.md) | Modules, flavors, the three crypto stacks, cutover, sends, identities, shielded, persistence, tests/CI, gotchas, and what to read before touching each area |
| [01-fail-closed.md](01-fail-closed.md) | Unknown ≠ zero: tri-state reads, strict config reads, malformed markers, deletes, fabricated defaults |
| [02-concurrency.md](02-concurrency.md) | Ownership tokens, generations, atomic claims, cancellation, job lifecycles |
| [03-durability-and-recovery.md](03-durability-and-recovery.md) | Write the intent before the act, durable debts, markers as state machines, wipe, wallet scoping |
| [04-money-sdk-platform.md](04-money-sdk-platform.md) | Units, fees, dry runs, dashj/SDK coexistence, Platform reads, DIP-15, CoinJoin migration, shielded, swaps |
| [05-android-lifecycle-ui.md](05-android-lifecycle-ui.md) | Scopes, ViewModels, fragments, StateFlow pitfalls, services, the wallet-load gate, Compose, dark mode, a11y |
| [06-security-and-logging.md](06-security-and-logging.md) | What never to log, wipe, encryption, untrusted payloads, pinned dependencies |
| [07-testing.md](07-testing.md) | Regression tests, pure decision functions, real persistence, deterministic race tests, CI traps |
| [08-code-style.md](08-code-style.md) | Conventions the reviewers enforce: Kotlin, ViewModels, persistence, errors, KDoc, build |
| [09-review-bots.md](09-review-bots.md) | How the bots behave, our review-loop rules, pushback that works, reply format |

## The ten rules that would have prevented most v12 blockers
1. An unreadable value is *unknown*, never zero, false or absent. Only an affirmative read authorizes money movement
   or deletion.
2. Never gate safety on `BaseConfig.get()` / `observe()`. Use `observePreservingErrors` / `*OrThrow`.
3. Make the intent durable before the irreversible act, and abort if the write fails. Assume the process dies between
   any two writes.
4. Every shared-state change is guarded by an ownership token (a generation or ticket) checked and published under one
   lock.
5. Rethrow `CancellationException`. Cancellation is not a fence. `NonCancellable` does not survive process death.
6. Scope everything to the wallet it belongs to, and capture that wallet before the first suspension.
7. After cutover the SDK owns L1. Never fall back to the frozen dashj wallet, and never advertise or reuse its
   addresses.
8. Never log secrets, links, addresses, amounts, or exceptions that carry input.
9. Fix the class of bug and pin it with a regression test that fails on the old code.
10. When a design needs a cap, latch or mutex threaded through many paths, look for an invariant that holds by
    construction instead.

Related: `CLAUDE.md` (build and architecture), `.claude/agents/DEVELOPMENT-PATTERNS.md` (Compose components and dark
mode), `.claude/skills/pre-review/` (local review before pushing), `docs/kotlin-sdk-migration-plan.md`,
`DASHJ-KILL-LIST.md`.
