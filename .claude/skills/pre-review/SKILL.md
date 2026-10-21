---
name: pre-review
description: Review this branch the way thepastaclaw will, before pushing it — GPT-6.1 "Sol" (the bot's own model) through the Codex CLI with the bot's public review prompts over merge_base..HEAD, then verify each finding against the code and fix the whole class of each one. Use before every push of a PR branch, when the user says "pre-review", "review before push", "will PastaClaw approve this", or when a push is denied by the pre-review gate.
---

# pre-review

thepastaclaw re-reviews the whole PR range on every pushed commit and approves only when nothing is left at any
severity: blocking, suggestion or nitpick. Most later-round findings sit on code written to fix the earlier ones.
So review the range with the same model and the same rules first, and push only when it is clean.

The review runs in `.claude/skills/pre-review/prereview.sh`. It caches the verdict per commit, so a push right
after a clean review is instant. The push gate (below) checks that cache.

## Run it

1. Commit first. The review covers `merge-base(origin/master, HEAD)..HEAD`, committed work only. For a PR into
   another branch, set `PREREVIEW_BASE=origin/<branch>`.
2. Start the review **in the background**, because it takes a few minutes and is longer than a Bash call may wait:
   ```bash
   .claude/skills/pre-review/prereview.sh
   ```
   Use `run_in_background: true` and wait for it to finish. Do not poll it.
3. If it exits 2 (not reviewed), the message says why: the Codex CLI is missing (`npm i -g @openai/codex`),
   it is not logged in (`codex login`), or the login does not serve the model. Tell the user and stop. Do not
   review the range yourself under its name: a Claude review shares Claude's blind spots, which is why this runs
   on the bot's model.

## Act on it

The report is printed and kept in `~/.cache/prereview/<sha>.md`; `--status` shows the verdict of HEAD.

- **Verify every finding** against the code at HEAD: open the lines and walk the sequence it describes. Drop a
  finding the code does not support, and say why. Keep one even if it looks unlikely.
- **Fix the class, not the line.** Name the rule each finding breaks, then `git grep` every other place it must
  hold: callers, legacy / resume / retry / restore / background paths, the other screen that shows the same value.
  A sibling the review did not name is the next round's finding.
- **Prefer narrowing or reverting to adding a mechanism.** A new cap, latch, mutex or persistence layer is
  reviewed again at funds-safety depth.
- **Suggestions and nitpicks count.** Any open one withholds the bot's approval, and most are cheap.
- Show the user a table (severity · file:line · finding · verified? · fix) before you change code, and follow the
  repository's conventions (CLAUDE.md, `.claude/agents/DEVELOPMENT-PATTERNS.md`).
- After the fixes, commit and run the review again. The new commit is a new range. Repeat until it is clean,
  or until only findings the user decides to leave remain.
- A pre-existing problem the change neither introduced nor is responsible for is not a finding: the bot leaves
  those out too, so drop it. A finding that IS in scope but that the user decides to defer needs a tracking issue
  linked on the PR, or the bot keeps reporting it. Draft that issue for the user; do not open it yourself.

Pushing is the user's call. Push only when asked.

## The push gate (per clone, opt-in)

```bash
.claude/skills/pre-review/prereview.sh --install     # on: a git pre-push hook (a copy under .git/hooks)
.claude/skills/pre-review/prereview.sh --uninstall   # off
```
With the gate on, a push to dash-wallet needs, for every pushed commit, a review with no blocking findings over a
range that covers its merge base with `origin/master` (or `PREREVIEW_BASE`). From a terminal the hook runs a
missing review itself. From a non-interactive shell (Claude Code included) it refuses at once and says to run the
review first: then run this skill and push again. `git push --no-verify` is the deliberate bypass. Run
`--install` again after this script changes, because the hook is a copy.
