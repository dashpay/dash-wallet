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
npm i -g @openai/codex@latest      # 0.163.0 or newer (the script checks): permission profiles and the model on a ChatGPT plan
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
printenv CODEX_API_KEY | codex login --with-api-key   # store it in Codex's own login (~/.codex/auth.json), then unset it
```
`CODEX_API_KEY` in the environment works too (`OPENAI_API_KEY` is copied into it), but prefer the stored login. Keep
the key out of your shell profile (a login shell would put it back), this repository, `local.properties`,
`service.properties`, commits, issues and chats.

**What the review may touch.** The branch under review is untrusted code: it can ask the model to run `printenv`
or `cat ~/.codex/auth.json`. So the commands the model runs get a bare environment with the key names excluded,
and a Codex permission profile instead of the read-only sandbox (whose reads cover the whole disk): they may read
the review's own clone of the commit (not a linked worktree: Codex would resolve its project trust through this
checkout), the repository's object store it borrows, the pinned prompts and the minimal system paths, and write only
under a scratch folder of the review's own. Your MCP servers (Codex starts those outside the sandbox) are switched off
by name for the review and the roster is read back; if one stays enabled, the script does not review. They do not
see your git configuration either (a PAT in a `url.insteadOf` or an `extraheader` would be readable): git in there
runs with an empty global config, and the commands' environment is an explicit allowlist. Codex runs with `--ignore-rules`: your execpolicy allow rules
(`~/.codex/rules`, a matching command may skip the sandbox) stay out of the review. Your `config.toml` does load (it
may choose a keyring login store); what it could open is switched off and read back from inside the review clone,
the same context the review runs in. `~/.codex` is an explicit deny in the profile, and the tree is checked out under
none of your git configuration (no hooks, no `filter.*` commands). Codex's other ways out of the sandbox — the implicit Apps connector, browser and computer use,
web search — are switched off for the review and read back. The report is scrubbed of the key's value as well.

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
| `CODEX_API_KEY` | unset | option B above; prefer the stored login (`OPENAI_API_KEY` is copied into it when set) |

Reports and verdicts are kept per commit in `~/.cache/prereview/<sha>.{md,json}`. The pinned review prompts are
cached in `~/.cache/pastaclaw/<sha>/`. `test_prereview.sh` tests the script offline with a fake `codex`.
