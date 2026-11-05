# 9. Working with thepastaclaw and coderabbitai

## How the reviewers behave
- **thepastaclaw** (an LLM review bot)
  - Severities: Blocking / Suggestion / Nitpick.
  - Approves only at zero findings and carries every open finding forward across pushes, even if it does not re-post it.
  - Has a ~30 min push debounce; the "Request priority review" checkbox in its gate comment skips the queue.
  - Its reviews are static ("no builds or tests were run"). An approval is a source review, not device validation.
  - A "⚠️ DEGRADED" review ran on stand-in models: treat it as provisional; it is re-run at full strength.
  - It often verifies claims against the pinned SDK source and checks the PR's base commit before it accepts a
    deferral.
- **coderabbitai**
  - Severities: Critical / Major / Minor / Nitpick.
  - Also lists "Outside diff range" and "Nitpick" items in review bodies.
  - Applies `.coderabbit.yaml` path instructions (UIState, `!!`) without checking the file's existing shape.
  - Its severity is often inflated on cosmetics: a file-name typo was rated "Critical".
  - Boilerplate ("review availability", "Fix CodeRabbit comments", shout-out requests) carries no findings.
- The two bots often find the same issue. Answer once and cross-reference the other thread.

## What the 87 v12 PRs taught about the loop
- **Long chains came from fixes, not from bot noise.** In #1621 (25+ findings), #1570 (~44), #1616 (~40 rounds) and
  #1576 (~20 rounds), each narrow fix opened the next interleaving. Almost all blocking findings were real.
- **The chains ended by**
  1. fixing the *class* of bug: one choke point, tri-state reads, a generation on every marker, ownership tokens;
  2. removing the unsafe consumer instead of proving it safe (the shielded cache in #1621);
  3. replacing a per-call guard with an invariant (the monotonic protocol latch replaced the approval cap in #1584);
  4. deferring to the SDK with a named missing primitive and an issue (dash-wallet#1635).
- **Treat each fix round as a new change.** Before replying "fixed", trace the whole path the fix touches. Several
  blockers in #1564 and #1555 came from the author's own previous round.
- **Rebase onto the current base before review.** A stale base produces phantom findings (#1595). Say "fixed" only once
  the fix is in the pushed head.

## Our rules for the loop (Andrei, 2026-10-22)
1. Fix only code the PR touches. A finding on code outside the PR gets an answer explaining why it is not this change's
   concern; it is not silently fixed.
2. At most two local `/pre-review` rounds per push. A third only when the owner asks for it. If we are digging in (each round finds holes in the previous fix),
   get out with the smallest change or ask. The second round is not optional once the first found a blocker: the fix
   is new code the bot reads with the same eyes. On #1670 round 1 found three blockers, the fix went out unreviewed,
   and the bot's first blocker sat on a line of that fix commit (`ConnectAvailability.kt`, `1d216ed3e`).
   A pre-review finding deferred in the PR description is not deferred for the bot: on #1662 four of its eight
   blockers were the "open items (known, not addressed here)" the description listed. Deferring needs a tracking
   issue linked on the PR, or the item gets fixed.
3. Locally, fix every blocking finding and every suggestion the GPT-6.1 Sol lane raised that is a few lines of
   existing code. The bot approves only at zero findings of every severity, and its verifier does not share our
   severity scale: on #1645, #1654 and #1666 (2026-10-27 … 11-03) four of its seven blockers were suggestions in our
   own pre-review report, twice after we had downgraded what the Sol lane rated blocking. A larger suggestion is
   deferred with a tracking issue linked on the PR, in the same push. Nitpicks wait for the bot's own round.
4. Pushing back on thepastaclaw is allowed and expected when a comment does not concern our code or is immaterial in
   our case.
5. Fix only what the bot found, the narrowest way, with no new mechanism. A suggestion is fixed only when it is a line
   or two of existing code; otherwise it is answered and deferred. Reverting is not the default: the bot carries
   findings forward.
6. Once the bot reports no blocking findings, stop fixing. Report the remaining suggestions and nitpicks; take a new
   blocker to the owner first.
7. A fix that needs a new field, flag, capture or latch is a design decision, not a fix round. Write the alternative
   down and ask the maintainer on the PR before coding it; prefer the design the maintainer stated from the first
   commit. Five of the seven blockers on #1645/#1654/#1666 sat on code an earlier fix round wrote, and #1654 went from
   "round the fiat line to two decimals" to 21 commits one mechanism at a time (`maxSelected` → `pinnedAmount` → a
   captured amount → validating the captured amount); the maintainer's `maxSelected` design was the one that held.
8. A refutation cites the dependency the module resolves, not the newest jar on disk: `./gradlew
   :wallet:dependencyInsight --dependency <artifact>`, then `javap` on that jar, version in the reply. #1666's
   "placeholder" family was refuted twice from `paging-runtime 3.3.2` while `wallet/build.gradle` resolves `3.1.1`,
   whose `PagingData.empty()` does dispatch load states; the bot blocked on it after the push. A failing test the
   finding's sequence cannot make pass is the other acceptable evidence. An argument is not.

## Pushback that works
| Situation | What to say (with evidence) | Example |
|---|---|---|
| The defect exists unchanged in the base | Name the base commit; say it is not introduced by the diff; link a tracking issue | #1616 → #1626, #1576, #1575, #1571 |
| The proper fix needs the SDK | Name the missing primitive, say why every app-side heuristic leaves a gap, link an issue | #1621 → dash-wallet#1635; #1607 `broadcastRawTransaction` |
| Behaviour is already correct | Show the current code or the library (e.g. `javap` shows both exceptions extend `CompletionException`) — from the jar `dependencyInsight` resolves for the module, with its version | #1608, #1566; the wrong jar on #1666 |
| Not reachable | Name the production gate that makes it unreachable | #1607 `shouldPrompt()` |
| Recovery already exists | Name the code path that recovers (`MainActivity.onResume()` restarts the service) | #1568 |
| Inherited mechanism handles it | E.g. `BaseConfig` clears all keys on wallet wipe | #1595 |
| Kotlin language misread | Quote the rule; cite the green build | #1591, #1581 |
| Format/upstream compatibility | Name the upstream commit plus on-device evidence | #1593 |
| Stated product requirement | Put the requirement in the PR description, which stops the finding before it is raised | #1624 |
| Different, stronger fix | Explain why it closes the window for every path | #1623, #1585, #1601 |
| Wrong mechanism, right invariant | Take the fix, correct the stated cause | #1555 |
| Scope (refactor) | "Pre-existing structure; consolidation is its own change" | #1621, #1605, #1551 |

**Pushback that did not work.** "Ported from master, defer" was rejected when the port *introduced* the shortcut and an
app-side alternative existed (#1616, rejecting the deferral to #1629). Arguing rarity for stateful UI also failed;
removing the stateful UI succeeded (#1606).

**Deferral is a maintainer decision.** The bot keeps a deferred SDK limitation blocking (#1570 change allocator). The PR
can still merge with it explicitly deferred.

## Reply format
- Fixed: `Fixed in <sha>: <what changed>. Covered by <TestClass.testName>.` The bots re-verify at that sha and mark the
  thread Resolved. Post a correction if a rebase changes the sha.
- Pushback: the claim, the evidence (file:line, base commit, upstream commit, device check), and the tracking issue if
  any.
- Our replies posted by an agent are prefixed `claude:`.
- Treat suggestion blocks as drafts. Bots have proposed code that does not compile, that inverts the logic, or that calls
  APIs that do not exist (#1568).
