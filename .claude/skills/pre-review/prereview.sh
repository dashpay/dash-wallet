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
#     export CODEX_API_KEY=…         an OpenAI API key, in your shell profile — the variable `codex exec` reads
#                                    (OPENAI_API_KEY is copied into it; or: printenv CODEX_API_KEY | codex login --with-api-key)
#   check: codex exec --skip-git-repo-check -m gpt-6.1-sol "Reply: OK"
#
# The gate is per clone and off by default. With it on, a push to dash-wallet needs, for every
# pushed commit, a cached review of exactly merge_base(base, commit)..commit with no blocking findings — a base that
# moved means a new review: an older range hides what the branch undoes of the base's later changes.
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
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}"
PC="$CACHE/pastaclaw/$PC_SHA"
VERDICTS="$CACHE/prereview"
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
export PREREVIEW_VERDICTS="$VERDICTS"
say() { printf 'pre-review: %s\n' "$*" >&2; }

is_dash_wallet() { [[ "$1" =~ [/:]dash-wallet(\.git)?/?$ ]]; }
base_ref() { printf '%s' "${PREREVIEW_BASE:-origin/master}"; }
# the base as origin has it now: a verdict is checked against the CURRENT merge base, so a stale local copy of the
# base must not stand in (the base may have moved since the last fetch). Fetched INTO its remote-tracking ref: a
# single-branch clone's fetch mapping covers only its own branch, and a plain `fetch origin <branch>` there updates
# FETCH_HEAD, not origin/<branch>, which merge-base reads.
refresh_base() {
  local base b; base=$(base_ref); [[ "$base" == origin/* ]] || return 0   # a local ref as the base is taken as it is
  b=${base#origin/}; git -C "$1" fetch -q origin "+refs/heads/$b:refs/remotes/origin/$b" 2>/dev/null
}

# verdict <repo> <sha> → clean | suggestions | blocking | stale (reviewed, but not over merge_base(base, sha)..sha:
# another base, or the base moved) | none
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
# the review must have been of exactly need..sha. An older base is not enough even when it is an ancestor of need:
# a branch that undoes a change the base made after that older base shows the undo in need..sha, not in older..sha
ok = bool(need) and (rec.get("base") or "") == need
print((rec.get("verdict") or "none") if ok else "stale")
PY
verdict() { python3 -c "$VERDICT_PY" "$@"; }

fetch_prompts() {
  mkdir -p "$PC"
  local f ok=1
  for f in prompts/review-agent.md prompts/verifier-agent.md prompts/security-auditor.md \
           skills/dash-wallet/review.md skills/dash-wallet/project.md; do
    [[ -s "$PC/$(basename "$f")" ]] && continue
    # to a temporary name of its own (reviews of other commits may fetch at the same time), renamed only when
    # complete: a cut-off download must not pass for the pinned prompt later
    local part; part=$(mktemp "$PC/.$(basename "$f").XXXXXX") || { ok=0; continue; }
    if curl -fsSL --max-time 30 "https://raw.githubusercontent.com/thepastaclaw/skills/$PC_SHA/$f" -o "$part" \
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
os.execv(sys.argv[2], sys.argv[2:])' "$VERDICTS/.$2.lock" "$SELF" --locked-review "$@" & pid=$!
  trap 'kill -TERM "$pid" 2>/dev/null' INT TERM HUP
  wait "$pid"; rc=$?
  while [[ $rc -gt 128 ]] && kill -0 "$pid" 2>/dev/null; do wait "$pid"; rc=$?; done   # wait was interrupted, not done
  trap - INT TERM HUP
  return $rc
}

review_locked() {
  local repo=$1 sha=$2 base mb out log json rc
  rm -f "$VERDICTS/$sha.json" "$VERDICTS/$sha.md"   # first: a re-review that fails at any step must not leave an earlier verdict standing
  base=$(base_ref)
  refresh_base "$repo" || { say "cannot fetch $base from origin, so the range to review is unknown: not reviewed"; return 2; }
  mb=$(git -C "$repo" merge-base "$base" "$sha") || { say "no merge base of $sha and $base"; return 2; }
  mkdir -p "$VERDICTS"
  if [[ "$mb" == "$sha" ]]; then   # the commit is on the base: nothing of ours in the range (the record keeps that base)
    printf '{"verdict": "clean", "counts": {}, "base": "%s", "base_ref": "%s", "model": "none: no commits over the base"}' \
      "$mb" "$base" > "$VERDICTS/.$sha.json.$$" && mv "$VERDICTS/.$sha.json.$$" "$VERDICTS/$sha.json"
    say "${sha:0:10} has no commits over $base, nothing to review"; return 0
  fi
  command -v codex >/dev/null || { say "the Codex CLI is not installed: npm i -g @openai/codex@latest (README.md, Setup)"; return 2; }
  # codex exec (0.163) reads an API key from CODEX_API_KEY, not OPENAI_API_KEY; the familiar name is accepted and copied
  [[ -n "${OPENAI_API_KEY:-}" && -z "${CODEX_API_KEY:-}" ]] && export CODEX_API_KEY="$OPENAI_API_KEY"
  codex login status >/dev/null 2>&1 || [[ -n "${CODEX_API_KEY:-}" ]] \
    || { say "Codex has no access to ChatGPT: codex login (a ChatGPT plan) or CODEX_API_KEY (an API key); README.md, Setup"; return 2; }
  # the bot's own rules or no verdict: a review by other rules must not be cached as one the gate trusts
  fetch_prompts || { say "cannot fetch the pinned review prompts (thepastaclaw/skills@${PC_SHA:0:10}): not reviewed, try again"; return 2; }
  local rules="these files exactly: $PC/review-agent.md (scope, required checks, severities), $PC/review.md and \
$PC/project.md (dash-wallet rules), and for money, keys, identities, persistence or dependency changes $PC/security-auditor.md"
  git -C "$repo" worktree prune 2>/dev/null   # registrations left by a review that was killed outright
  local wt; wt=$(mktemp -d "${TMPDIR:-/tmp}/prereview.XXXXXX"); out=$(mktemp); log=$(mktemp)
  # this runs as its own process (review() execs it), so process-level traps clean up on Ctrl-C and kill too
  CLEANUP_REPO=$repo CLEANUP_WT=$wt CLEANUP_OUT=$out CLEANUP_LOG=$log   # one path each, always quoted: TMPDIR may hold spaces
  trap 'git -C "$CLEANUP_REPO" worktree remove --force "$CLEANUP_WT/tree" 2>/dev/null; rmdir "$CLEANUP_WT" 2>/dev/null; rm -f "$CLEANUP_OUT" "$CLEANUP_LOG"' EXIT
  trap 'kill -TERM "${CODEX_PID:-}" 2>/dev/null; exit 130' INT TERM HUP   # stop codex too, then the EXIT trap cleans up
  git -C "$repo" worktree add -q --detach "$wt/tree" "$sha" 2>/dev/null || { say "cannot check out ${sha:0:10} to review it"; return 2; }
  say "reviewing $(git -C "$repo" rev-list --count "$mb..$sha") commit(s) ${mb:0:10}..${sha:0:10} with $MODEL (a few minutes)"
  local tmo=(); command -v timeout >/dev/null && tmo=(timeout "${PREREVIEW_TIMEOUT:-1500}")
  ${tmo[@]+"${tmo[@]}"} codex exec -m "$MODEL" -c model_reasoning_effort="$EFFORT" -s read-only -C "$wt/tree" -o "$out" "$(cat <<EOF
You review a pull request range of dashpay/dash-wallet: $mb..$sha (run git diff $mb $sha). Inspect the entire range
independently. Follow $rules. Do all required checks: the premise of the change, every sibling path a changed rule must
also hold on (callers, legacy / resume / retry / restore / background paths, other screens showing the value), states
and sequences (re-entry, retry, process death, cancellation, wallet replaced mid-flow, concurrent submissions,
out-of-order async results, unknown treated as zero), and what changed on the base since $mb. Read any file you need.
Report every finding as: severity (blocking|suggestion|nitpick), file:line, a one-line title, why (the concrete failing
sequence) and the sibling paths you checked. No code changes. End with ONE last line, exactly this JSON shape:
{"blocking": <n>, "suggestion": <n>, "nitpick": <n>}
EOF
)" </dev/null >"$log" 2>&1 &   # codex exec reads stdin until EOF; in the pre-push hook that is git's ref list
  CODEX_PID=$!; wait "$CODEX_PID"; rc=$?   # in the background + wait: bash runs a trap at once, not after a foreground child
  while [[ $rc -gt 128 ]] && kill -0 "$CODEX_PID" 2>/dev/null; do wait "$CODEX_PID"; rc=$?; done
  git -C "$repo" worktree remove --force "$wt/tree" 2>/dev/null; rmdir "$wt" 2>/dev/null
  if [[ $rc -ne 0 || ! -s "$out" ]]; then
    say "codex failed (exit $rc):"; tail -5 "$log" >&2
    grep -q "not supported when using Codex with a ChatGPT account" "$log" && say "this login does not serve $MODEL: \
update the CLI (npm i -g @openai/codex@latest) and log in with a ChatGPT account that has it (README.md, Setup)"
    rm -f "$out" "$log"; return 2
  fi
  cat "$out"; echo
  json=$(grep -v '^[[:space:]]*$' "$out" | tail -1)   # the counts must be the LAST line
  python3 - "$VERDICTS/$sha.json" "$json" "$mb" "$base" "$MODEL" "$out" <<'PY' || { rm -f "$out" "$log"; return 2; }
import json, os, shutil, sys, tempfile, datetime
path, line, mb, base, model, report = sys.argv[1:7]
try:
    c = json.loads(line)
    assert isinstance(c, dict) and all(isinstance(c.get(k), int) and not isinstance(c.get(k), bool) and c[k] >= 0
                                       for k in ("blocking", "suggestion", "nitpick"))
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
publish(path, lambda fh: json.dump({"verdict": v, "counts": c, "base": mb, "base_ref": base, "model": model,
                                    "at": datetime.datetime.now(datetime.timezone.utc).isoformat()}, fh))
print(f"pre-review: verdict {v} {c} (report: {path[:-5]}.md)", file=sys.stderr)
PY
  rm -f "$out" "$log"
  [[ "$(verdict "$repo" "$sha")" != blocking ]] || return 1
}

if [[ "${1:-}" == --locked-review ]]; then   # review() runs this under the lock
  shift; review_locked "$@"; exit $?
fi

if [[ "$(basename "$0")" == pre-push ]]; then
  is_dash_wallet "${2:-}" || exit 0
  repo=$(git rev-parse --show-toplevel)
  zero=0000000000000000000000000000000000000000; refused=0; shas=()
  while read -r _lref lsha _rref _rsha; do [[ "$lsha" == "$zero" ]] || shas+=("$lsha"); done   # a deletion sends no code
  [[ ${#shas[@]} -eq 0 ]] && exit 0              # deletions only: nothing to review, and no need to reach origin
  refresh_base "$repo" || { say "cannot fetch $(base_ref) from origin to check the pushed range: not pushing"; exit 1; }
  for lsha in ${shas[@]+"${shas[@]}"}; do
    # an annotated tag arrives as the tag object: the code it carries is its commit
    lsha=$(git -C "$repo" rev-parse -q --verify "$lsha^{commit}") || { say "a pushed ref is not a commit"; refused=1; continue; }
    v=$(verdict "$repo" "$lsha")
    if [[ ( "$v" == none || "$v" == stale ) && -t 2 ]]; then
      review "$repo" "$lsha" >&2; v=$(verdict "$repo" "$lsha")
    fi
    case "$v" in
      clean) ;;
      suggestions) say "${lsha:0:10}: suggestions only (the bot will comment, not approve); pushing" ;;
      blocking) say "${lsha:0:10}: blocking findings, see $VERDICTS/$lsha.md"; refused=1 ;;
      stale) say "${lsha:0:10}: reviewed over another range than merge_base($(base_ref), it)..it: run the review again \
(/pre-review, or .claude/skills/pre-review/prereview.sh), then push"; refused=1 ;;
      *) say "${lsha:0:10}: not reviewed: run /pre-review (or .claude/skills/pre-review/prereview.sh) on it, then push \
(git push --no-verify pushes anyway, on purpose)"; refused=1 ;;
    esac
  done
  exit $refused
fi

case "${1:-}" in
  --install|--uninstall)
    repo=$(git rev-parse --show-toplevel) || exit 2
    hooks=$(git -C "$repo" rev-parse --path-format=absolute --git-path hooks)
    mkdir -p "$hooks" || { say "cannot create $hooks"; exit 2; }
    mark="# installed by .claude/skills/pre-review/prereview.sh --install"
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
  *) sed -n 2,30p "$0"; exit 2 ;;
esac
