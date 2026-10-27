#!/usr/bin/env bash
# Pre-review a branch the way thepastaclaw will, before it is pushed: OpenAI's GPT-6.1 "Sol" (the model of the bot's
# second phase) through the Codex CLI, with the bot's own public prompts, over merge_base..HEAD.
#
# Why: the bot reviews the whole range on its first pass, then every pushed commit against the findings it carries
# forward, and approves only at zero findings, so every push with a finding we could have seen costs a round. One
# review of the whole range (the net diff is what lands) before the push is the cheap round.
#
#   prereview.sh                    review HEAD against origin/master (PREREVIEW_BASE=origin/<branch> for another base;
#                                   PREREVIEW_EFFORT=xhigh for a deeper, slower pass, default high)
#   prereview.sh --status           the cached verdict of HEAD
#   prereview.sh --install          turn the push gate on for this clone (a git pre-push hook)
#   prereview.sh --uninstall        turn it off again
#   (as .git/hooks/pre-push)        (git)
#
# Access to ChatGPT (README.md, "Setup") is Codex's own login, never a key in this repository:
#   npm i -g @openai/codex@latest, then ONE of
#     codex login                    a ChatGPT plan (browser); `codex login --device-auth` without a browser
#     printenv CODEX_API_KEY | codex login --with-api-key    an OpenAI API key, stored in Codex's own login (preferred:
#                                    CODEX_API_KEY / OPENAI_API_KEY in the environment work too, but see README.md)
#   check: codex exec --skip-git-repo-check -m gpt-6.1-sol "Reply: OK"
#
# The gate is per clone and off by default. With it on, a push to dash-wallet needs, for each pushed ref's tip, a
# cached review of exactly merge_base(base, tip)..tip with no blocking findings (one review covers the commits in
# between) — a base that moved means a new review: an older range hides what the branch undoes of the base's later
# changes.
# From a terminal the hook runs a missing review itself; from a non-interactive push (an agent's shell) it refuses
# at once and says to run the review first. Reviews are cached per commit in ~/.cache/prereview/<sha>.{json,md}. The
# base is PREREVIEW_BASE, else origin/master — set it for the push too when the PR targets another branch. The
# hook is a copy under .git/, so checking out a branch without this script keeps the gate; --install again after
# the script changes. A deliberate bypass is `git push --no-verify`.
set -uo pipefail
# git exports these to its hooks; left in place they make every git command inside the review's worktree (and the
# model's) resolve the ORIGINAL checkout's HEAD and index. Cleared first, as githooks(5) says a hook must.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_COMMON_DIR GIT_PREFIX GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES

PC_SHA=65ecda75023b16e72406963bebd2ac141223df43   # thepastaclaw/skills, pinned; move it in a commit of its own
MODEL="${PREREVIEW_MODEL:-gpt-6.1-sol}"
EFFORT="${PREREVIEW_EFFORT:-high}"   # the bot's phase 2 runs the model at high; codex exec's default is lower
MIN_CODEX=0.163.0                     # permission profiles (0.138+) and the model on a ChatGPT plan (0.163+)
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}"
PC="$CACHE/pastaclaw/$PC_SHA"
VERDICTS="$CACHE/prereview"
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
export PREREVIEW_VERDICTS="$VERDICTS"
say() { printf 'pre-review: %s\n' "$*" >&2; }
# python3 does the verdict lookup, the lock, the JSON parsing and publication: without it nothing here is reliable
command -v python3 >/dev/null || { say "python3 is required (README.md, Setup)"; exit 2; }
# a path as a TOML basic string: JSON string escapes (\" \\ \uXXXX …) are valid TOML ones, and json.dumps leaves / alone
toml_str() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1], ensure_ascii=False))' "$1"; }   # literal UTF-8: TOML rejects \ud83d-style surrogate escapes
# every child that can outlive a cancelled review (git fetch and clone, curl, codex) starts without the review's lock
# descriptor (review() takes it, see PREREVIEW_LOCK_FD): the lock is the review's own, and nothing left behind keeps it
nolock() { ( [[ "${PREREVIEW_LOCK_FD:-}" =~ ^[0-9]+$ ]] && eval "exec $PREREVIEW_LOCK_FD>&-"; exec "$@" ); }
codex_() { nolock codex "$@"; }
# a child in the background + wait, so a signal reaches this shell's trap at once (bash defers traps while a foreground
# child runs) and the trap can stop the child; a stalled fetch must not keep a cancelled review, and its lock, alive
CHILD_PID=""
waitfor() {
  local rc; nolock "$@" & CHILD_PID=$!
  wait "$CHILD_PID"; rc=$?
  while [[ $rc -gt 128 ]] && kill -0 "$CHILD_PID" 2>/dev/null; do wait "$CHILD_PID"; rc=$?; done
  CHILD_PID=""; return $rc
}

is_dash_wallet() { [[ "$1" =~ [/:]dash-wallet(\.git)?/?$ ]]; }
base_ref() { printf '%s' "${PREREVIEW_BASE:-origin/master}"; }
# the base as origin has it now: a verdict is checked against the CURRENT merge base, so a stale local copy of the
# base must not stand in (the base may have moved since the last fetch). Fetched INTO its remote-tracking ref: a
# single-branch clone's fetch mapping covers only its own branch, and a plain `fetch origin <branch>` there updates
# FETCH_HEAD, not origin/<branch>, which merge-base reads.
refresh_base() {
  local base b; base=$(base_ref); [[ "$base" == origin/* ]] || return 0   # a local ref as the base is taken as it is
  b=${base#origin/}; waitfor git -C "$1" fetch -q origin "+refs/heads/$b:refs/remotes/origin/$b" 2>/dev/null
}

# verdict <repo> <sha> → clean | suggestions | blocking | stale (reviewed, but not over merge_base(base, sha)..sha
# against the base's current tip: another base, or the base moved) | none
read -r -d '' VERDICT_PY <<'PY'
import json, os, subprocess, sys
repo, sha = sys.argv[1:3]
base = os.environ.get("PREREVIEW_BASE") or "origin/master"
def git(*a):
    r = subprocess.run(["git", "-C", repo, *a], capture_output=True, text=True)
    return r, r.stdout.strip()
try:
    rec = json.load(open(os.path.join(os.environ["PREREVIEW_VERDICTS"], sha + ".json")))
except Exception:
    print("none"); sys.exit()
_, need = git("merge-base", base, sha)
_, tip = git("rev-parse", "--verify", "-q", base + "^{commit}")
# the review must have been of exactly need..sha AND against the base as it is now. An older base is not enough even
# when it is an ancestor of need: a branch that undoes a change the base made after it shows the undo in need..sha
# only. And a base that moved on without changing the merge base was never checked against this branch.
ok = bool(need) and bool(tip) and (rec.get("base") or "") == need and (rec.get("base_tip") or "") == tip
print((rec.get("verdict") or "none") if ok else "stale")
PY
verdict() { python3 -c "$VERDICT_PY" "$@"; }
# roster <file>: the enabled servers of a `codex mcp list --json` answer, one "name<TAB>transport" per line; exits 1
# unless it is a list of entries each with a name (not blank: every enabled server prints a visible line, which the
# read-back's emptiness check relies on) and a boolean enabled. Both MCP probes read through it: an answer
# that does not parse is no answer, and an unknown enabled state is not "off"
read -r -d '' ROSTER_PY <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
if not isinstance(r, list) or not all(isinstance(e, dict) and isinstance(e.get("name"), str) and e["name"].strip()
                                      and isinstance(e.get("enabled"), bool) for e in r):
    sys.exit(1)
print("\n".join(e["name"] + "\t" + str((e.get("transport") or {}).get("type") or "stdio") for e in r if e["enabled"]))
PY
roster() { python3 -c "$ROSTER_PY" "$@"; }
# a review's own success: its fresh verdict, read back as the gate reads it. Stale (the base moved meanwhile) or none
# is no verdict, so not 0
passed() {   # <repo> <sha>; the base is fetched again first, as the push gate does: a review takes minutes, and a pass
  # read against the base as it was when the review began would differ from the gate's answer. No fetch, no pass
  refresh_base "$1" || { say "cannot fetch $(base_ref) to check the verdict against it: not a pass"; return 1; }
  case "$(verdict "$@")" in clean|suggestions) return 0 ;; *) return 1 ;; esac
}

fetch_prompts() {
  mkdir -p "$PC"
  local f ok=1
  for f in prompts/review-agent.md prompts/verifier-agent.md prompts/security-auditor.md \
           skills/dash-wallet/review.md skills/dash-wallet/project.md; do
    [[ -s "$PC/$(basename "$f")" ]] && continue
    # to a temporary name of its own (reviews of other commits may fetch at the same time), renamed only when
    # complete: a cut-off download must not pass for the pinned prompt later
    local part; part=$(mktemp "$PC/.$(basename "$f").XXXXXX") || { ok=0; continue; }
    if waitfor curl -fsSL --max-time 30 "https://raw.githubusercontent.com/thepastaclaw/skills/$PC_SHA/$f" -o "$part" \
       && mv -f "$part" "$PC/$(basename "$f")"; then :; else rm -f "$part"; ok=0; fi
  done
  return $((1 - ok))
}

# review <repo> <sha>: writes $VERDICTS/<sha>.json + .md; 0 = no blocking findings, 1 = blocking, 2 = not reviewed.
# One review per commit at a time, so a slower, older review cannot publish over a newer one: python takes an exclusive
# flock(2) on $VERDICTS/.<sha>.lock and then BECOMES this script (exec, the descriptor inherited), so the lock lives
# exactly as long as the review itself; the kernel releases it when that process exits or dies.
review() {
  mkdir -p "$VERDICTS"
  local pid rc
  # a child, so the hook loop can go on; a signal to THIS process is forwarded to it, or a cancelled review
  # would run on, hold the lock and publish a verdict nobody waits for
  python3 -c '
import fcntl, os, sys
fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT, 0o644)
try:
    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
except OSError:
    print("pre-review: a review of this commit is already running", file=sys.stderr); sys.exit(2)
os.set_inheritable(fd, True)
os.environ["PREREVIEW_LOCK_FD"] = str(fd)   # so the review can keep the lock to itself (closed for codex)
os.execv(sys.argv[2], sys.argv[2:])' "$VERDICTS/.$2.lock" "$SELF" --locked-review "$@" & pid=$!
  trap 'kill -TERM "$pid" 2>/dev/null' INT TERM HUP
  wait "$pid"; rc=$?
  while [[ $rc -gt 128 ]] && kill -0 "$pid" 2>/dev/null; do wait "$pid"; rc=$?; done   # wait was interrupted, not done
  trap - INT TERM HUP
  return $rc
}

review_locked() {
  local repo=$1 sha=$2 base mb out log json rc
  # cleanup and cancellation from the first step on: what is allocated below is recorded as it comes
  CLEANUP_WT="" CLEANUP_OUT="" CLEANUP_LOG="" CLEANUP_PROBE="" CODEX_PID=""
  trap '[[ -n "$CLEANUP_WT" ]] && rm -rf "$CLEANUP_WT"; [[ -n "$CLEANUP_OUT" ]] && rm -f "$CLEANUP_OUT" "$CLEANUP_LOG"; [[ -n "$CLEANUP_PROBE" ]] && rm -f "$CLEANUP_PROBE"' EXIT
  trap 'kill -TERM "${CODEX_PID:-}" "${CHILD_PID:-}" 2>/dev/null; exit 130' INT TERM HUP
  # first: a re-review that fails at any step must not leave an earlier verdict standing — removed, or at least emptied
  # (an unwritable cache folder), and if neither works the record stays and so does the push gate's trust in it: say so
  local old
  for old in "$VERDICTS/$sha.json" "$VERDICTS/$sha.md"; do
    rm -f "$old" 2>/dev/null; [[ -e "$old" ]] && : > "$old" 2>/dev/null
    [[ -s "$old" ]] && { say "cannot drop the earlier record $old (permissions?): the push gate still trusts it — fix that first"; return 2; }
  done
  base=$(base_ref)
  refresh_base "$repo" || { say "cannot fetch $base from origin, so the range to review is unknown: not reviewed"; return 2; }
  mb=$(git -C "$repo" merge-base "$base" "$sha") || { say "no merge base of $sha and $base"; return 2; }
  mkdir -p "$VERDICTS"
  if [[ "$mb" == "$sha" ]]; then   # the commit is on the base: nothing of ours in the range (the record keeps that base)
    { printf '{"verdict": "clean", "counts": {}, "base": "%s", "base_tip": "%s", "base_ref": "%s", "model": "none: no commits over the base"}' \
      "$mb" "$(git -C "$repo" rev-parse --verify -q "$base^{commit}")" "$base" > "$VERDICTS/.$sha.json.$$" && mv "$VERDICTS/.$sha.json.$$" "$VERDICTS/$sha.json"; } \
      2>/dev/null || { rm -f "$VERDICTS/.$sha.json.$$" 2>/dev/null; say "cannot write $VERDICTS/$sha.json: not reviewed"; return 2; }
    say "${sha:0:10} has no commits over $base, nothing to review"; passed "$repo" "$sha"; return
  fi
  # an older codex accepts the permission-profile settings below without applying them: full-disk reads again
  # probes run under waitfor into files, never in $(…): a command substitution is a foreground subshell, and bash
  # defers the cancellation trap until it returns — a stalled probe would keep the lock
  command -v codex >/dev/null || { say "the Codex CLI is not installed: npm i -g @openai/codex@latest (README.md, Setup)"; return 2; }
  local probe ver; probe=$(mktemp); CLEANUP_PROBE=$probe
  waitfor codex --version > "$probe" 2>/dev/null || { say "codex --version failed: not reviewing"; return 2; }
  ver=$(grep -oE '[0-9]+\.[0-9]+\.[0-9]+' "$probe" | head -1)
  [[ -n "$ver" ]] || { say "codex --version gave no version: not reviewing"; return 2; }
  if [[ "$(printf '%s\n' "$ver" "$MIN_CODEX" | sort -t. -k1,1n -k2,2n -k3,3n | head -1)" != "$MIN_CODEX" ]]; then
    say "codex-cli $ver is older than $MIN_CODEX, which ignores the permission profile this review relies on: npm i -g @openai/codex@latest"; return 2
  fi
  # codex exec (0.163) reads an API key from CODEX_API_KEY, not OPENAI_API_KEY; the familiar name is accepted and copied
  [[ -n "${OPENAI_API_KEY:-}" && -z "${CODEX_API_KEY:-}" ]] && export CODEX_API_KEY="$OPENAI_API_KEY"
  waitfor codex login status >/dev/null 2>&1 || [[ -n "${CODEX_API_KEY:-}" ]] \
    || { say "Codex has no access to ChatGPT: codex login (a ChatGPT plan) or CODEX_API_KEY (an API key); README.md, Setup"; return 2; }
  # the bot's own rules or no verdict: a review by other rules must not be cached as one the gate trusts
  fetch_prompts || { say "cannot fetch the pinned review prompts (thepastaclaw/skills@${PC_SHA:0:10}): not reviewed, try again"; return 2; }
  local rules="these files exactly: $PC/review-agent.md (scope, required checks, severities), $PC/review.md and \
$PC/project.md (dash-wallet rules), and for money, keys, identities, persistence or dependency changes $PC/security-auditor.md"
  local wt; wt=$(mktemp -d "${TMPDIR:-/tmp}/prereview.XXXXXX"); out=$(mktemp); log=$(mktemp)
  # this runs as its own process (review() execs it), so process-level traps clean up on Ctrl-C and kill too
  CLEANUP_WT=$wt CLEANUP_OUT=$out CLEANUP_LOG=$log   # one path each, always quoted: TMPDIR may hold spaces
  # a clone of its own, not a linked worktree: codex resolves a linked worktree's project trust through the main
  # checkout, and a trusted one would let the reviewed tree's .codex/config.toml into the configuration. --shared:
  # the objects stay in the repository (read through alternates, granted below), only the files are checked out
  # no hooks while the tree is materialized: a relative core.hooksPath in the user's config (.githooks) would resolve
  # inside the review clone, and the reviewed commit's own post-checkout hook would run here, outside every restriction
  # and none of the user's git configuration: a filter.<name>.smudge/process registered there runs on checkout for
  # every path the reviewed commit's .gitattributes points at it — the commit's own code, outside every restriction
  waitfor env -u GIT_CONFIG_COUNT -u GIT_CONFIG_PARAMETERS GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 \
      git -c core.hooksPath=/dev/null clone -q --shared --no-checkout "$repo" "$wt/tree" 2>/dev/null \
    && env -u GIT_CONFIG_COUNT -u GIT_CONFIG_PARAMETERS GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 \
      git -c core.hooksPath=/dev/null -C "$wt/tree" checkout -q --detach "$sha" 2>/dev/null \
    || { say "cannot check out ${sha:0:10} to review it"; return 2; }
  # the clone's origin/<base> would be the checkout's LOCAL branch of that name (behind, or absent in a single-branch
  # checkout); the review needs the base as origin has it, fetched above: set that in the clone, name it in the prompt
  local base_tip; base_tip=$(git -C "$repo" rev-parse --verify -q "$base^{commit}") \
    && git -c core.hooksPath=/dev/null -C "$wt/tree" update-ref "refs/remotes/origin/${base#origin/}" "$base_tip" 2>/dev/null \
    || { say "cannot resolve $base in the checkout"; return 2; }
  # what the model's commands may read: the tree, the object stores it borrows (alternates, recursively), the pinned
  # prompts and the minimal system paths; writes only in its own scratch folder (macOS git's xcrun cache). Not the user's
  # git configuration: a PAT in a url.insteadOf or an http.extraheader would be readable — git in there gets an empty
  # global config of its own instead (GIT_CONFIG_GLOBAL below)
  : > "$wt/gitconfig"
  # the user's MCP servers would start outside the sandbox (a filesystem server for ~ hands auth.json over), and
  # `-c mcp_servers={}` merges instead of clearing: every effective server (plugins' included) is switched off by name,
  # and the roster is read back — anything still enabled means no review. Listed and verified from the review clone
  # (its working directory: codex's -C does not move `mcp list`'s configuration context), as codex exec runs: a
  # trusted source checkout's own .codex/config.toml could mask a server there, or point an HTTP one at an attacker
  local mcp_off=() mcp_tab="" name
  waitfor sh -c 'cd "$1" && shift && exec "$@"' sh "$wt/tree" codex mcp list --json > "$probe" 2>/dev/null \
    || { say "codex mcp list failed, so the servers to switch off are unknown: not reviewing"; return 2; }
  # parsed in a checked step (roster): a silent empty answer would leave every server on, and the read-back alone
  # proves nothing when the first answer was garbage. Kept in $wt, which every exit path removes
  local transport
  roster "$probe" > "$wt/roster" 2>/dev/null \
    || { say "codex mcp list gave a roster that does not parse: not reviewing"; return 2; }
  while IFS=$'\t' read -r name transport; do
    [[ -n "$name" ]] || continue
    # a complete entry, not `.enabled=false` alone: a server another layer provides (the desktop app's) is not in
    # config.toml, and a partial override of it leaves an entry without a command — codex then fails to load at all.
    # Of the server's own transport: the entry merges with the existing one, and a `command` next to an http
    # server's `url` is rejected ("url is not supported for stdio")
    # one `mcp_servers` table with quoted keys, not `-c mcp_servers.<name>=…`: names may hold `.` `:` `@` `/`, and codex
    # splits a dotted -c path on every dot; the table merges into the existing servers like the per-server form did
    if [[ "$transport" == stdio ]]; then mcp_tab+="${mcp_tab:+, }$(toml_str "$name") = { command = \"/bin/sh\", enabled = false }"
    else mcp_tab+="${mcp_tab:+, }$(toml_str "$name") = { url = \"http://127.0.0.1:9/\", enabled = false }"; fi
  done < "$wt/roster"
  [[ -n "$mcp_tab" ]] && mcp_off=(-c "mcp_servers={ $mcp_tab }")
  waitfor sh -c 'cd "$1" && shift && exec "$@"' sh "$wt/tree" codex ${mcp_off[@]+"${mcp_off[@]}"} mcp list --json > "$probe" 2>/dev/null \
    || { say "codex mcp list failed on the read-back: not reviewing"; return 2; }
  roster "$probe" > "$wt/roster" 2>/dev/null || { say "codex mcp list read-back does not parse: not reviewing"; return 2; }
  [[ -s "$wt/roster" && -n "$(tr -d '[:space:]' < "$wt/roster")" ]] && { say "an MCP server stays enabled for codex: not reviewing"; return 2; }
  # codex's other ways out of the sandbox: the implicit Apps connector (never in `mcp list`), browser and computer use,
  # web search (a query can carry data out), lifecycle hooks. Off for the review, and read back: each feature must show
  # false. web_search is a config value, not a feature: `features list` has no row for it, so it is set (on the command
  # line, the highest-precedence layer) and not read back
  local feat_off=(-c features.apps=false -c features.browser_use=false -c features.browser_use_external=false \
    -c features.browser_use_full_cdp_access=false -c features.computer_use=false -c features.in_app_browser=false -c 'web_search="disabled"' \
    -c features.hooks=false)   # lifecycle hooks: run outside the sandbox, in the clone, from the user's trusted config
  waitfor sh -c 'cd "$1" && shift && exec "$@"' sh "$wt/tree" codex "${feat_off[@]}" features list > "$probe" 2>/dev/null \
    || { say "codex features list failed, so the capabilities' state is unknown: not reviewing"; return 2; }
  # each of the seven exactly once, and off: a count of "false" rows alone passes seven copies of one of them
  if ! awk 'BEGIN { split("apps browser_use browser_use_external browser_use_full_cdp_access computer_use in_app_browser hooks", w, " ")
                    for (i in w) want[w[i]] = 1 }
            $1 in want { if ($NF != "false" || ++seen[$1] > 1) bad = 1 }
            END { for (k in want) if (seen[k] != 1) bad = 1; exit bad }' "$probe"; then
    say "a codex capability outside the sandbox (apps, browser or computer use, lifecycle hooks) does not read back as off: not reviewing"; return 2
  fi
  # the model's scratch: its own folder (TMPDIR below), not the shared temp dirs where other reviews keep their files
  mkdir -m 700 "$wt/tmp" || { say "cannot create $wt/tmp"; return 2; }
  local fs alt queue=("$wt/tree/.git/objects") seen=" "
  # an explicit deny on the codex home as well: codex skips the sandbox for a command an allow rule matches unless the
  # profile holds an explicit Deny (an implicit one, a path simply not granted, does not count)
  fs="\":minimal\" = \"read\", $(toml_str "$wt/tmp") = \"write\", $(toml_str "$wt/gitconfig") = \"read\", $(toml_str "$PC") = \"read\", $(toml_str "${CODEX_HOME:-$HOME/.codex}") = \"deny\""
  while [[ ${#queue[@]} -gt 0 ]]; do
    local objs=${queue[0]}; queue=("${queue[@]:1}")
    [[ -f "$objs/info/alternates" ]] || continue
    while IFS= read -r alt; do
      [[ -n "$alt" && "${alt:0:1}" != "#" ]] || continue
      [[ "$alt" == /* ]] || alt=$(cd "$objs" && cd "$alt" 2>/dev/null && pwd)
      [[ -n "$alt" && "$seen" != *" $alt "* ]] || continue
      seen+="$alt "; fs+=", $(toml_str "$alt") = \"read\""; queue+=("$alt")
    done < "$objs/info/alternates"
  done
  fs="{ $fs, \":workspace_roots\" = { \".\" = \"read\" } }"
  say "reviewing $(git -C "$repo" rev-list --count "$mb..$sha") commit(s) ${mb:0:10}..${sha:0:10} with $MODEL (a few minutes)"
  local tmo=(); command -v timeout >/dev/null && tmo=(timeout "${PREREVIEW_TIMEOUT:-1500}")
  # in a subshell that first closes the lock's descriptor: the lock is this review's, not codex's — a child codex
  # leaves behind (a timeout that could not kill the group, a stray helper) must not keep the commit locked
  ( [[ "${PREREVIEW_LOCK_FD:-}" =~ ^[0-9]+$ ]] && eval "exec $PREREVIEW_LOCK_FD>&-"
    # the branch under review is untrusted code, so the commands the model runs get (a) an environment that is an
    # explicit allowlist, never the reviewer's credentials (codex's default hands them everything: printenv
    # CODEX_API_KEY would answer; include_only is applied last, so it also drops a `set` entry from the user's own
    # codex config), with git pointed at the empty config above, and (b) a permission profile instead of the read-only
    # sandbox, whose reads cover the whole disk, ~/.codex/auth.json included
    # --ignore-rules: the user's execpolicy allow rules (~/.codex/rules) let a matching command run OUTSIDE the sandbox.
    # The user's config.toml does load (it may select a keyring login store); what it could open is closed above and
    # read back in this same context: MCP servers off by name, apps/browser/computer use off (web search set off), environment
    # an allowlist. Started from inside the clone, the context the probes read.
    cd "$wt/tree" || exit 2
    exec ${tmo[@]+"${tmo[@]}"} codex exec --ignore-rules ${mcp_off[@]+"${mcp_off[@]}"} "${feat_off[@]}" -m "$MODEL" -c model_reasoning_effort="$EFFORT" -C "$wt/tree" -o "$out" \
      -c shell_environment_policy.inherit=core -c shell_environment_policy.ignore_default_excludes=false \
      -c 'shell_environment_policy.exclude=["CODEX_API_KEY","OPENAI_API_KEY","*_API_KEY","*TOKEN*","*SECRET*","*PASSWORD*"]' \
      -c "shell_environment_policy.set={ GIT_CONFIG_GLOBAL = $(toml_str "$wt/gitconfig"), GIT_CONFIG_NOSYSTEM = \"1\", TMPDIR = $(toml_str "$wt/tmp") }" \
      -c 'shell_environment_policy.include_only=["HOME","PATH","USER","LOGNAME","SHELL","TERM","LANG","LC_ALL","LC_CTYPE","TMPDIR","TMP","TEMP","PWD","GIT_CONFIG_GLOBAL","GIT_CONFIG_NOSYSTEM"]' \
      -c default_permissions=prereview -c "permissions.prereview.filesystem=$fs" \
      "$(cat <<EOF
You review a pull request range of dashpay/dash-wallet: $mb..$sha (run git diff $mb $sha). Inspect the entire range
independently. Follow $rules. Do all required checks: the premise of the change, every sibling path a changed rule must
also hold on (callers, legacy / resume / retry / restore / background paths, other screens showing the value), states
and sequences (re-entry, retry, process death, cancellation, wallet replaced mid-flow, concurrent submissions,
out-of-order async results, unknown treated as zero), and what changed on the base ($base, at $base_tip in this
checkout) since $mb. Read any file you need.
Report every finding as: severity (blocking|suggestion|nitpick), file:line, a one-line title, why (the concrete failing
sequence) and the sibling paths you checked. Static review only: read and reason, do not run builds or test suites
(the reviewer bot does not either, and they would not finish in this sandbox). No code changes. End with ONE last
line, exactly this JSON shape:
{"blocking": <n>, "suggestion": <n>, "nitpick": <n>}
EOF
)" ) </dev/null >"$log" 2>&1 &   # codex exec reads stdin until EOF; in the pre-push hook that is git's ref list
  CODEX_PID=$!; wait "$CODEX_PID"; rc=$?   # in the background + wait: bash runs a trap at once, not after a foreground child
  while [[ $rc -gt 128 ]] && kill -0 "$CODEX_PID" 2>/dev/null; do wait "$CODEX_PID"; rc=$?; done
  if [[ $rc -ne 0 || ! -s "$out" ]]; then
    if [[ $rc -eq 124 ]]; then say "codex did not finish within ${PREREVIEW_TIMEOUT:-1500}s (PREREVIEW_TIMEOUT) — not reviewed"; else say "codex failed (exit $rc):"; tail -5 "$log" >&2; fi
    [[ $rc -ne 124 ]] && grep -q "not supported when using Codex with a ChatGPT account" "$log" && say "this login does not serve $MODEL: \
update the CLI (npm i -g @openai/codex@latest) and log in with a ChatGPT account that has it (README.md, Setup)"
    rm -f "$out" "$log"; return 2
  fi
  # belt and braces: a key that reached the answer by any other route is redacted before the report is shown or kept
  python3 - "$out" <<'PY'
import os, sys
p = sys.argv[1]; t = open(p).read()
for v in ("CODEX_API_KEY", "OPENAI_API_KEY"):
    k = os.environ.get(v, "")
    if len(k) >= 8 and k in t:
        t = t.replace(k, "[REDACTED " + v + "]")
open(p, "w").write(t)
PY
  cat "$out"; echo
  json=$(grep -v '^[[:space:]]*$' "$out" | tail -1)   # the counts must be the LAST line
  python3 - "$VERDICTS/$sha.json" "$json" "$mb" "$base" "$MODEL" "$out" "$base_tip" <<'PY' || { rm -f "$out" "$log"; return 2; }
import json, os, shutil, sys, tempfile, datetime
path, line, mb, base, model, report, base_tip = sys.argv[1:8]
try:
    c = json.loads(line)
    if not (isinstance(c, dict) and all(isinstance(c.get(k), int) and not isinstance(c.get(k), bool) and c[k] >= 0
                                        for k in ("blocking", "suggestion", "nitpick"))):
        raise ValueError("counts")   # not assert: PYTHONOPTIMIZE from the caller would strip it
except Exception:
    print("pre-review: the review did not end with a valid counts line, so it counts as not reviewed", file=sys.stderr)
    sys.exit(1)
v = "blocking" if c["blocking"] else ("suggestions" if c["suggestion"] or c["nitpick"] else "clean")
def publish(target, write):  # a temporary file in the same folder, then one rename: readers never see half a record
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(target), prefix="." + os.path.basename(target) + ".")
    with os.fdopen(fd, "w") as fh:
        write(fh)
    os.replace(tmp, target)
publish(path[:-5] + ".md", lambda fh: fh.write(open(report).read()))
publish(path, lambda fh: json.dump({"verdict": v, "counts": c, "base": mb, "base_tip": base_tip, "base_ref": base, "model": model,
                                    "at": datetime.datetime.now(datetime.timezone.utc).isoformat()}, fh))
print(f"pre-review: verdict {v} {c} (report: {path[:-5]}.md)", file=sys.stderr)
PY
  rm -f "$out" "$log"
  passed "$repo" "$sha"
}

if [[ "${1:-}" == --locked-review ]]; then   # review() runs this under the lock
  shift; review_locked "$@"; exit $?
fi

if [[ "$(basename "$0")" == pre-push ]]; then
  is_dash_wallet "${2:-}" || exit 0
  repo=$(git rev-parse --show-toplevel)
  zero=0000000000000000000000000000000000000000; refused=0; shas=()
  while read -r _lref lsha _rref _rsha; do
    [[ "$lsha" == "$zero" ]] && continue          # a deletion sends no code
    # an annotated tag arrives as the tag object: the code it carries is its commit
    lsha=$(git -C "$repo" rev-parse -q --verify "$lsha^{commit}") || { say "a pushed ref is not a commit"; exit 1; }
    shas+=("$lsha")
  done
  [[ ${#shas[@]} -eq 0 ]] && exit 0              # deletions only: nothing to review, and no need to reach origin
  refresh_base "$repo" || { say "cannot fetch $(base_ref) from origin to check the pushed range: not pushing"; exit 1; }
  for lsha in ${shas[@]+"${shas[@]}"}; do
    v=$(verdict "$repo" "$lsha")
    if [[ ( "$v" == none || "$v" == stale ) && -t 2 ]]; then
      review "$repo" "$lsha" >&2; rc=$?
      [[ $rc -gt 128 ]] && { say "cancelled: not pushing"; exit 1; }   # a cancelled review must not start the next ref's
      v=$(verdict "$repo" "$lsha")
    fi
    case "$v" in
      clean) ;;
      suggestions) say "${lsha:0:10}: suggestions only (the bot will comment, not approve); pushing" ;;
      blocking) say "${lsha:0:10}: blocking findings, see $VERDICTS/$lsha.md"; refused=1 ;;
      stale) say "${lsha:0:10}: reviewed over another range, or before $(base_ref) moved: run the review again \
(/pre-review, or .claude/skills/pre-review/prereview.sh), then push"; refused=1 ;;
      *) say "${lsha:0:10}: not reviewed: run /pre-review (or .claude/skills/pre-review/prereview.sh) on it, then push \
(git push --no-verify pushes anyway, on purpose)"; refused=1 ;;
    esac
  done
  # the base may have moved since an earlier ref was accepted against it, or during a review above: fetched once
  # more, and every tip checked once more against the base as it is now
  refresh_base "$repo" || { say "cannot fetch $(base_ref) from origin for the final check: not pushing"; exit 1; }
  for lsha in ${shas[@]+"${shas[@]}"}; do
    v=$(verdict "$repo" "$lsha")
    [[ "$v" == clean || "$v" == suggestions ]] || { say "${lsha:0:10}: the base moved while this push was being reviewed ($v): run the review again, then push"; refused=1; }
  done
  exit $refused
fi

case "${1:-}" in
  --install|--uninstall)
    repo=$(git rev-parse --show-toplevel) || exit 2
    hooks=$(git -C "$repo" rev-parse --path-format=absolute --git-path hooks)
    mkdir -p "$hooks" || { say "cannot create $hooks"; exit 2; }
    mark="# installed by .claude/skills/pre-review/prereview.sh --install"
    if [[ -n "$(git -C "$repo" config --get core.hooksPath 2>/dev/null)" ]]; then   # a shared hooks folder is not this clone's
      say "core.hooksPath is set, so the hooks folder is shared with other clones; the gate is per clone — unset it here first"; exit 2
    fi
    if [[ "$1" == --uninstall ]]; then
      grep -qF "$mark" "$hooks/pre-push" 2>/dev/null && rm -f "$hooks/pre-push"
      grep -qF "$mark" "$hooks/pre-push" 2>/dev/null && { say "could not remove $hooks/pre-push: the gate is still ON"; exit 2; }
      say "push gate off for $repo"; exit 0
    fi
    is_dash_wallet "$(git -C "$repo" remote get-url origin 2>/dev/null)" || { say "origin is not dash-wallet"; exit 2; }
    if [[ -e "$hooks/pre-push" ]] && ! grep -qF "$mark" "$hooks/pre-push"; then
      say "$hooks/pre-push exists and is not this script: not replacing it, so the gate is NOT on"; exit 2
    fi
    if ! { { head -1 "$SELF"; echo "$mark"; tail -n +2 "$SELF"; } > "$hooks/pre-push.tmp" && chmod +x "$hooks/pre-push.tmp" \
           && mv -f "$hooks/pre-push.tmp" "$hooks/pre-push" && cmp -s <(tail -n +3 "$hooks/pre-push") <(tail -n +2 "$SELF"); }; then
      rm -f "$hooks/pre-push.tmp"; say "could not write $hooks/pre-push: the gate is NOT on (or is an older copy)"; exit 2
    fi
    say "push gate on for $repo ($hooks/pre-push, a copy: run --install again after this script changes)"
    ;;
  --status)
    repo=$(git rev-parse --show-toplevel) || exit 2; sha=$(git -C "$repo" rev-parse HEAD)
    refresh_base "$repo" || say "note: could not fetch $(base_ref); the verdict below is against the local copy of it"
    hook=$(git -C "$repo" rev-parse --path-format=absolute --git-path hooks)/pre-push
    printf 'gate: %s\nbase: %s\nHEAD %s: %s\n' "$(grep -qF 'prereview.sh --install' "$hook" 2>/dev/null && echo on || echo off)" \
      "$(base_ref)" "${sha:0:10}" \
      "$(verdict "$repo" "$sha")"
    [[ -s "$VERDICTS/$sha.md" ]] && echo "report: $VERDICTS/$sha.md"
    exit 0   # a status query that found no report is still a successful query
    ;;
  "")
    repo=$(git rev-parse --show-toplevel) || exit 2
    is_dash_wallet "$(git -C "$repo" remote get-url origin)" || { say "origin is not dash-wallet"; exit 2; }
    review "$repo" "$(git -C "$repo" rev-parse HEAD)"
    ;;
  *) sed -n 2,31p "$0"; exit 2 ;;
esac
