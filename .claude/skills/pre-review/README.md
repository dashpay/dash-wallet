# pre-review

Review a dash-wallet branch the way thepastaclaw will, before it is pushed. The review runs GPT-6.1 "Sol", the
model of the bot's second phase, through the Codex CLI, with the bot's public review prompts. In Claude Code, run
`/pre-review`. In a terminal, run `.claude/skills/pre-review/prereview.sh`. `SKILL.md` describes what Claude
does with the findings.

## Setup: give Codex access to ChatGPT

The script never reads a key from this repository and never asks for one. Codex keeps its own login, and the
script only checks that one exists (`codex login status`, or `CODEX_API_KEY` in the environment). Pick one of
the options below.

```bash
npm i -g @openai/codex@latest      # 0.163.0 or newer: older versions are refused gpt-6.1-sol on a ChatGPT plan
```

**A. A ChatGPT plan (Plus, Pro, Team).** No key: the review uses the plan's quota.
```bash
codex login                 # opens a browser; sign in with the ChatGPT account
codex login --device-auth   # on a machine without a browser (over SSH): prints a link and a one-time code
```
Codex stores the login in `~/.codex/auth.json` (mode 600) and refreshes it. The plan has to serve the model.
Check:
```bash
codex exec --skip-git-repo-check -m gpt-6.1-sol "Reply: OK"
```
"The 'gpt-6.1-sol' model is not supported when using Codex with a ChatGPT account" means the CLI is too old, or
the plan does not include the model.

**B. An OpenAI API key** from platform.openai.com. This is billed per request.
```bash
export CODEX_API_KEY=…                               # in your shell profile: the variable `codex exec` reads
printenv CODEX_API_KEY | codex login --with-api-key  # or store it in Codex's own login instead
```
`OPENAI_API_KEY` is accepted too and copied into `CODEX_API_KEY`. Keep the key in your shell profile or a password
manager. Do not put it in this repository, `local.properties`, `service.properties` or a commit, and do not paste it
into an issue or a chat.

`codex logout` removes a stored login. `codex login status` shows which one is active.

## Use

```bash
.claude/skills/pre-review/prereview.sh              # review HEAD against origin/master
PREREVIEW_BASE=origin/release-x .claude/skills/pre-review/prereview.sh   # a PR into another branch
.claude/skills/pre-review/prereview.sh --status     # the cached verdict of HEAD, and where its report is
.claude/skills/pre-review/prereview.sh --install    # turn the push gate on for this clone
.claude/skills/pre-review/prereview.sh --uninstall  # turn it off
```

| Variable | Default | |
|---|---|---|
| `PREREVIEW_BASE` | `origin/master` | the PR's base. Set it for the push too when the PR targets another branch |
| `PREREVIEW_MODEL` | `gpt-6.1-sol` | the model passed to `codex exec -m` |
| `PREREVIEW_EFFORT` | `high` | the model's reasoning effort (thepastaclaw's phase 2 runs at high); `xhigh` is deeper and slower |
| `PREREVIEW_TIMEOUT` | `1500` | seconds before a review is abandoned (needs `timeout`) |
| `CODEX_API_KEY` | unset | option B above (`OPENAI_API_KEY` is copied into it when set) |

Reports and verdicts are kept per commit in `~/.cache/prereview/<sha>.{md,json}`. The pinned review prompts are
cached in `~/.cache/pastaclaw/<sha>/`. `test_prereview.sh` tests the script offline with a fake `codex`.
