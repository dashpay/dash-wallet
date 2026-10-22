#!/usr/bin/env bash
# Tests prereview.sh with a fake codex on PATH (no quota, no network). Run: bash <this file>; non-zero on failure.
set -u
# setup must never fall through into the caller's checkout: a failed cd there would put the fixture's commits in it
cd() { builtin cd "$@" || { echo "FAIL: cannot cd $*" >&2; exit 1; }; }
need() { "$@" || { echo "FAIL: setup: $*" >&2; exit 1; }; }
P=${1:-"$(cd "$(dirname "$0")" && pwd)/prereview.sh"}; T=$(mktemp -d "${TMPDIR:-/tmp}/prtest.XXXXXX") || exit 1; export XDG_CACHE_HOME="$T/cache"; FAILS=0
[[ -n "${KEEP:-}" ]] || trap 'chmod -R u+w "$T" 2>/dev/null; rm -rf "$T"' EXIT   # this run's folder only, writable again first (the chmod tests); KEEP=1 leaves it for a look
# the cases set these themselves: an inherited API key would pass the logged-out case, a base or timeout would move others
unset OPENAI_API_KEY CODEX_API_KEY PREREVIEW_BASE PREREVIEW_TIMEOUT PREREVIEW_MODEL
# the caller's git configuration stays out too: a global core.hooksPath would make the fixture's --install and
# --uninstall write to, and delete from, the caller's real hooks folder
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
# the reviews' own temp dirs land here, so their removal can be checked directly
need mkdir -p "$T/tmpdir"; export TMPDIR="$T/tmpdir"
leftovers() { ls -d "$T/tmpdir"/prereview.* 2>/dev/null | wc -l | tr -d ' '; }
# nor git's repository overrides: a GIT_INDEX_FILE pointing at another checkout would take the fixture's `git add`
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_COMMON_DIR GIT_PREFIX GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES
unset GIT_CONFIG_COUNT GIT_CONFIG_PARAMETERS; for v in $(compgen -e | grep -E '^GIT_CONFIG_(KEY|VALUE)_'); do unset "$v"; done   # env config survives GIT_CONFIG_GLOBAL
need mkdir -p "$T/bin"
cat > "$T/bin/codex" <<'EOF'
#!/usr/bin/env bash
[[ "$1" == --version ]] && { echo "codex-cli ${FAKE_VERSION:-0.163.0}"; exit 0; }
[[ "$1" == login ]] && exit "${FAKE_LOGIN_RC:-0}"
if [[ " $* " == *" features list "* ]]; then   # the six capabilities, each off when its -c features.<name>=false came
  for n in apps browser_use browser_use_external browser_use_full_cdp_access computer_use in_app_browser; do
    if [[ -n "${FAKE_FEAT_STUBBORN:-}" || " $* " != *" features.$n=false "* ]]; then echo "$n stable true"; else echo "$n stable false"; fi
  done; exit 0
fi
if [[ " $* " == *" mcp list "* ]]; then   # the effective servers (FAKE_MCP), each off when a -c mcp_servers.<name>.enabled=false came
  printf '%s\n' "$@" > "${FAKE_CDIR}.mcpargs"
  python3 -c '
import json, sys
names = [n for n in sys.argv[1].split(",") if n]; args = sys.argv[3:]; stubborn = sys.argv[2] == "1"
print(json.dumps([{"name": n, "enabled": stubborn or ("mcp_servers.%s={ command = \"/bin/sh\", enabled = false }" % n) not in args} for n in names]))' "${FAKE_MCP:-}" "${FAKE_MCP_STUBBORN:-0}" "$@"
  exit 0
fi
printf '%s\n' "$@" > "${FAKE_CDIR}.args"   # before the loop below consumes them
while [[ $# -gt 0 ]]; do [[ "$1" == -o ]] && { out=$2; shift; }; [[ "$1" == -C ]] && { cdir=$2; echo "$2" > "$FAKE_CDIR"; shift; }; shift; done
echo "${CODEX_API_KEY:-}" > "${FAKE_CDIR}.key"; echo "${GIT_DIR:-unset}" > "${FAKE_CDIR}.gitdir"
git -C "$cdir" rev-parse HEAD > "${FAKE_CDIR}.head" 2>/dev/null   # what git inside the review's folder sees
git -C "$cdir" rev-parse --path-format=absolute --git-common-dir > "${FAKE_CDIR}.commondir" 2>/dev/null
git -C "$cdir" rev-parse origin/master > "${FAKE_CDIR}.base" 2>/dev/null
echo "${CODEX_HOME:-unset}" > "${FAKE_CDIR}.home"; cat "${CODEX_HOME:-/nonexistent}/auth.json" > "${FAKE_CDIR}.auth" 2>/dev/null
[[ -e "${CODEX_HOME:-/nonexistent}/config.toml" ]] && echo yes > "${FAKE_CDIR}.cfg" || echo no > "${FAKE_CDIR}.cfg"
[[ -n "${FAKE_REFRESH:-}" ]] && echo "$FAKE_REFRESH" >> "${CODEX_HOME}/auth.json"
printf 'finding text\n%s\n' "$FAKE_LAST" > "$out"
EOF
chmod +x "$T/bin/codex"; export PATH="$T/bin:$PATH" FAKE_CDIR="$T/cdir"
PCSHA=$(sed -n 's/^PC_SHA=\([0-9a-f]*\).*/\1/p' "$P"); mkdir -p "$XDG_CACHE_HOME/pastaclaw/$PCSHA"   # prompts seeded: no network
for f in review-agent verifier-agent security-auditor review project; do echo "# $f" > "$XDG_CACHE_HOME/pastaclaw/$PCSHA/$f.md"; done
# the user's codex home, with a dummy login and a config full of things the review must not load
export CODEX_HOME="$T/userhome"; need mkdir -m 700 "$CODEX_HOME"
printf '{"auth_mode":"apikey","OPENAI_API_KEY":"k-dummy-login"}\n' > "$CODEX_HOME/auth.json"; chmod 600 "$CODEX_HOME/auth.json"
printf '[mcp_servers.fs]\ncommand = "evil"\n' > "$CODEX_HOME/config.toml"
# tty <cmd…>: run with a terminal on stdin/stdout/stderr (script(1): BSD/macOS and util-linux take different arguments);
# its stdin stays open a while, so an early EOF cannot close the pty under the command
tty() {
  if script --version 2>/dev/null | grep -q util-linux; then SHELL=$(command -v bash) script -qec "$(printf '%q ' "$@")" /dev/null < <(sleep 15)   # util-linux runs the command through $SHELL; %q quoting is bash's
  else script -q /dev/null "$@" < <(sleep 15); fi
}
ok() { if [[ "$2" == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: got '$2', want '$3'"; FAILS=$((FAILS+1)); fi; }

# a fake upstream with master and release, and a clone whose origin URL looks like dash-wallet
need git init -q --bare "$T/dash-wallet.git"
need git clone -q "$T/dash-wallet.git" "$T/seed" 2>/dev/null; cd "$T/seed"
g() { git -c user.email=t@t -c user.name=t "$@"; }
echo a > a; g add a; g commit -qm a; g push -q origin HEAD:master
g checkout -q -b release; echo r > r; g add r; g commit -qm r; g push -q origin release
g checkout -q master; echo b > b; g add b; g commit -qm b; g push -q origin master
need git clone -q "$T/dash-wallet.git" "$T/app"; cd "$T/app"; need git fetch -q origin release
g checkout -q -b feat origin/master; echo c > c; g add c; g commit -qm c; FEAT=$(git rev-parse HEAD)
g checkout -q -b other origin/master; echo d > d; g add d; g commit -qm d; OTHER=$(git rev-parse HEAD)
g checkout -q feat

V() { PREREVIEW_VERDICTS="$XDG_CACHE_HOME/prereview" "$P" --status | sed -n 's/^HEAD [0-9a-f]*: //p'; }
FAKE_LAST='{"blocking": null, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "null count → not reviewed (exit 2)" "$?" 2
ok "  … and no verdict cached" "$(V)" none
FAKE_LAST='no counts at all' "$P" >/dev/null 2>&1; ok "missing counts line → exit 2" "$?" 2
FAKE_LAST=$'{"blocking": 0, "suggestion": 0, "nitpick": 0}\nreview incomplete' "$P" >/dev/null 2>&1; ok "counts not on the last line → exit 2" "$?" 2
FAKE_LAST='{"blocking": -1, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "negative count → exit 2" "$?" 2
FAKE_LAST='{"blocking": 1, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "blocking → exit 1" "$?" 1
ok "  … verdict blocking" "$(V)" blocking
FAKE_LAST='{"blocking": 0, "suggestion": 2, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "suggestions → exit 0" "$?" 0
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "clean → exit 0" "$?" 0
ok "  … verdict clean" "$(V)" clean
GIT_DIR="$T/app/.git" GIT_WORK_TREE="$T/app" FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "GIT_DIR/GIT_WORK_TREE from the caller (a hook's environment) do not reach the review" "$?:$(cat "$T/cdir.gitdir")" 0:unset
ok "  … reviewed in a separate worktree, not the working tree" "$([[ "$(cat "$T/cdir")" != "$T/app" && "$(cat "$T/cdir")" == */tree ]] && echo yes)" yes
ok "  … and the review's folder is gone after" "$(leftovers)" 0
real() { python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$1"; }
ok "  … it is a clone of its own, not a linked worktree (no trust inherited from this checkout)" "$(real "$(cat "$T/cdir.commondir")")" "$(real "$(cat "$T/cdir")")/.git"
FAKE_LAST='garbled' "$P" >/dev/null 2>&1; ok "a failed re-review drops the earlier clean verdict" "$(V)" none
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
FAKE_LOGIN_RC=1 "$P" >/dev/null 2>&1; ok "a re-review with Codex logged out → exit 2" "$?" 2
ok "  … and drops the earlier clean verdict too" "$(V)" none
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; chmod a-w "$XDG_CACHE_HOME/prereview"
FAKE_LOGIN_RC=1 "$P" >/dev/null 2>&1; ok "unwritable cache folder: a failed re-review still empties the earlier record" "$?:$(V)" 2:none
chmod u+w "$XDG_CACHE_HOME/prereview"
FAKE_LOGIN_RC=1 CODEX_API_KEY=k-codex FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "logged out, CODEX_API_KEY set → reviewed with it" "$?:$(cat "$T/cdir.key")" 0:k-codex
FAKE_LOGIN_RC=1 OPENAI_API_KEY=k-openai FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "logged out, OPENAI_API_KEY set → copied into CODEX_API_KEY for codex" "$?:$(cat "$T/cdir.key")" 0:k-openai
ok "the model's commands get a bare environment with the key excluded (codex policy flags)" "$(grep -c -e 'shell_environment_policy.inherit=core' -e 'CODEX_API_KEY","OPENAI_API_KEY' "$T/cdir.args")" 2
ok "  … and a permission profile: the tree, the repo's objects (alternates), the prompts, minimal system paths; no git config of the user's" \
  "$(grep -c 'default_permissions=prereview' "$T/cdir.args"):$(grep -c -e ":workspace_roots\" = { \".\" = \"read\" }" -e "\"$T/app/.git/objects\" = \"read\"" -e "pastaclaw/$PCSHA\" = \"read\"" "$T/cdir.args"):$(grep -c -e '~/.gitconfig" = ' -e '~/.config/git' -e '^-s$' "$T/cdir.args")" 1:1:0
ok "  … the commands' environment is an explicit allowlist, with git pointed at an empty config" \
  "$(grep -c 'shell_environment_policy.include_only=\["HOME"' "$T/cdir.args"):$(grep -c 'shell_environment_policy.set={ GIT_CONFIG_GLOBAL = ".*/gitconfig", GIT_CONFIG_NOSYSTEM = "1", TMPDIR = ' "$T/cdir.args")" 1:1
need git clone -q --shared "$T/app" "$T/sharedclone" 2>/dev/null; cd "$T/sharedclone"; git remote set-url origin "$T/dash-wallet.git"; g checkout -q feat
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "a --shared clone: its alternates and theirs are granted, recursively" "$?:$(grep -c 'sharedclone/.git/objects" = "read"' "$T/cdir.args"):$(grep -c 'app/.git/objects" = "read"' "$T/cdir.args")" 0:1:1
cd "$T/app"
FAKE_FEAT_STUBBORN=1 FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "a capability outside the sandbox that stays on (apps, browser, computer use) → not reviewed (exit 2)" "$?:$(V)" 2:none
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "apps, browser and computer use and web search are switched off for the review" "$?:$(grep -c -e '^features.apps=false$' -e '^features.computer_use=false$' -e '^web_search="disabled"$' "$T/cdir.args")" 0:3
FAKE_MCP="canaryfs,second" FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "the user's MCP servers are switched off by name for the review" "$?:$(grep -c -e '^mcp_servers.canaryfs={ command = "/bin/sh", enabled = false }$' -e '^mcp_servers.second={ command = "/bin/sh", enabled = false }$' "$T/cdir.args")" 0:2
FAKE_MCP="stubborn" FAKE_MCP_STUBBORN=1 FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "  … one that stays enabled → not reviewed (exit 2), no verdict" "$?:$(V)" 2:none
FAKE_MCP="bad.name" FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "  … a name that cannot be addressed → not reviewed (exit 2)" "$?" 2
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "exec ignores the user's execpolicy rules and config.toml; the codex home is an explicit deny in the profile" \
  "$(grep -c -e '^--ignore-rules$' -e '^--ignore-user-config$' "$T/cdir.args"):$(grep -c "\"$CODEX_HOME\" = \"deny\"" "$T/cdir.args")" 2:1
ok "MCP servers are listed and verified from the review clone, the context exec runs in" "$(grep -c -e '^-C$' "$T/cdir.mcpargs"):$(grep -c "$(dirname "$(cat "$T/cdir")")/tree" "$T/cdir.mcpargs")" 1:1
ok "the model's scratch is the review's own folder, not the shared temp dirs" \
  "$(grep -c -e "\"$(dirname "$(cat "$T/cdir")")/tmp\" = \"write\"" "$T/cdir.args"):$(grep -c 'TMPDIR = ".*/tmp" }' "$T/cdir.args"):$(grep -c -e ':tmpdir' -e ':slash_tmp' "$T/cdir.args")" 1:1:0
FAKE_VERSION=0.100.0 FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "an older codex (no permission profiles) → not reviewed (exit 2)" "$?:$(V)" 2:none
FAKE_VERSION=0.163.0 FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "  … 0.163.0 → reviewed" "$?" 0
FAKE_VERSION=" " FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "  … no version at all → not reviewed (exit 2)" "$?" 2
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
CODEX_API_KEY=k-leak-me-4471 FAKE_LAST=$'leaked: k-leak-me-4471\n{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" > "$T/rep.out" 2>&1
ok "a key that reaches the answer is redacted in the printed and in the kept report" "$(grep -l 'k-leak-me-4471' "$T/rep.out" "$XDG_CACHE_HOME/prereview/"*.md 2>/dev/null | wc -l | tr -d ' '):$(grep -c 'REDACTED CODEX_API_KEY' "$T/rep.out")" 0:1
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
H=$(git rev-parse HEAD); python3 -c 'import fcntl,sys,time; f=open(sys.argv[1],"a"); fcntl.flock(f,fcntl.LOCK_EX); time.sleep(300)' \
  "$XDG_CACHE_HOME/prereview/.$H.lock" & LIVE=$!; sleep 1
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "a review of the same commit already running → exit 2, nothing touched (the running one owns the record)" "$?:$(V)" 2:clean
kill -9 $LIVE; wait $LIVE 2>/dev/null
FAKE_LAST='{"blocking": 1, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "its holder was killed → the kernel released the lock, reviewed" "$?:$(V)" 1:blocking
# the lock lives as long as the review: hold it from inside a running review (a slow fake codex), then try another
printf '#!/usr/bin/env bash\n[[ "$1" == --version ]] && { echo codex-cli 0.163.0; exit 0; }\n[[ "$1" == login ]] && exit 0\n[[ " $* " == *" mcp list "* ]] && { echo "[]"; exit 0; }\n[[ " $* " == *" features list "* ]] && { for n in apps browser_use browser_use_external browser_use_full_cdp_access computer_use in_app_browser; do echo "$n stable false"; done; exit 0; }\nsleep 4; while [[ $# -gt 0 ]]; do [[ "$1" == -o ]] && { echo "{\\"blocking\\": 0, \\"suggestion\\": 0, \\"nitpick\\": 0}" > "$2"; shift; }; shift; done\n' > "$T/bin/codex.slow"
chmod +x "$T/bin/codex.slow"; mkdir -p "$T/slow"; cp "$T/bin/codex.slow" "$T/slow/codex"
PATH="$T/slow:$PATH" "$P" >/dev/null 2>&1 & SLOW=$!; sleep 1
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "a second review while the first is inside codex → exit 2" "$?" 2
wait $SLOW; ok "  … the first one finishes and publishes" "$(V)" clean
# Ctrl-C signals the whole foreground process group: run the review in a group of its own and signal that
set -m; PATH="$T/slow:$PATH" "$P" >/dev/null 2>&1 & SLOW=$!; set +m; sleep 2; kill -INT -- -$SLOW 2>/dev/null; wait $SLOW 2>/dev/null; sleep 1
ok "a review interrupted inside codex leaves no folder behind" "$(leftovers)" 0
ok "  … and no verdict (it was dropped when the review started)" "$(V)" none
PATH="$T/slow:$PATH" "$P" >/dev/null 2>&1 & SLOW=$!; sleep 2; kill -TERM $SLOW 2>/dev/null; wait $SLOW 2>/dev/null; sleep 1
ok "TERM to the script's own pid alone → the review stops too (no folder, no verdict)" "$(leftovers):$(V)" 0:none
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "  … and a new review starts at once (the lock is free)" "$?" 0
# cancelled while a codex probe stalls (login status here): the probe must not keep the lock either
need mkdir -p "$T/slowlogin"; printf '#!/usr/bin/env bash\n[[ "$1" == login ]] && { sleep 4; exit 0; }\nexec "%s/bin/codex" "$@"\n' "$T" > "$T/slowlogin/codex"; chmod +x "$T/slowlogin/codex"
PATH="$T/slowlogin:$PATH" "$P" >/dev/null 2>&1 & SLOW=$!; sleep 2; kill -TERM $SLOW 2>/dev/null; sleep 0.5
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "TERM during a stalled codex probe → the review stops at once, the lock is free" "$?" 0; wait $SLOW 2>/dev/null
# cancelled while still fetching the base: the fetch must not keep the lock either
REALGIT=$(command -v git); need mkdir -p "$T/slowgit"; printf '#!/usr/bin/env bash\n[[ " $* " == *" fetch "* ]] && sleep 4\nexec "%s" "$@"\n' "$REALGIT" > "$T/slowgit/git"; chmod +x "$T/slowgit/git"
PATH="$T/slowgit:$PATH" "$P" >/dev/null 2>&1 & SLOW=$!; sleep 2; kill -TERM $SLOW 2>/dev/null; sleep 0.5
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "TERM during a stalled base fetch → the review stops at once, the lock is free" "$?" 0; wait $SLOW 2>/dev/null
# a timeout that cannot kill the process group (or none at all): what codex leaves behind must not hold the lock
need mkdir -p "$T/faketimeout"; printf '#!/bin/sh\nshift; exec "$@"\n' > "$T/faketimeout/timeout"; chmod +x "$T/faketimeout/timeout"
PATH="$T/faketimeout:$T/slow:$PATH" "$P" >/dev/null 2>&1 & SLOW=$!; sleep 2; kill -TERM $SLOW 2>/dev/null; wait $SLOW 2>/dev/null; sleep 1
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "  … same, with a timeout that does not kill the group: codex's leftover child holds no lock" "$?" 0
mkdir -p "$T/tmp dir"; echo keep > "$T/tmp"; ( cd "$T" && TMPDIR="$T/tmp dir" FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1 ) </dev/null
cd "$T/app"; TMPDIR="$T/tmp dir" FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "TMPDIR with a space: cleanup removes only its own files" "$(cat "$T/tmp" 2>/dev/null):$(ls -A "$T/tmp dir" | wc -l | tr -d ' ')" keep:0
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1   # back to clean for what follows
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "reviewed against master, required against release → stale" "$(PREREVIEW_BASE=origin/release V)" stale

# a commit on the base: cached clean only for that base
g checkout -q -B onbase origin/master; "$P" >/dev/null 2>&1
ok "commit on the base → clean for that base" "$(V)" clean
ok "  … stale for a base it is not on" "$(PREREVIEW_BASE=origin/release V)" stale
# the base advances to a commit of the branch (its first commit landed): the same tip now has another net diff
MASTER=$(git rev-parse origin/master); g checkout -q -b two origin/master
echo p > p; g add p; g commit -qm p; FIRST=$(git rev-parse HEAD); echo q > q; g add q; g commit -qm q
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "two-commit branch → clean" "$(V)" clean
git push -q origin "$FIRST:master"; ok "  … master advanced on origin (not fetched here) → stale: the check refreshes the base" "$(V)" stale
git push -q -f origin "$MASTER:master"; ok "  … master back → clean again" "$(V)" clean
# the base moves on without changing the merge base: what changed on it was never checked against this branch
( cd "$T/seed" && g checkout -q master && echo m2 > m2 && g add m2 && g commit -qm m2 && git push -q origin master ) 2>/dev/null
ok "origin/master moved independently (same merge base) → stale" "$(V)" stale
git push -q -f origin "$MASTER:master"; ok "  … master back → clean again" "$(V)" clean
# the review clone must carry the base as origin has it, not this checkout's stale local master
g checkout -q -b drift origin/master; echo d2 > d2; g add d2; g commit -qm d2; git push -q origin "$FIRST:master"
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "origin's master moved, local master stale → the review clone's origin/master is the fresh tip" "$?:$(cat "$T/cdir.base")" "0:$FIRST"
git push -q -f origin "$MASTER:master"
g checkout -q -b fresh origin/master; echo z > z; g add z; g commit -qm z
"$P" --status >/dev/null 2>&1; ok "--status of an unreviewed HEAD → exit 0" "$?" 0
# a single-branch clone has no origin/master: the refresh must create it (a fetch into the tracking ref, not FETCH_HEAD)
need git clone -q --single-branch -b release "$T/dash-wallet.git" "$T/single" 2>/dev/null; cd "$T/single"
echo s > s; g add s; g commit -qm s
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "single-branch clone without origin/master → the base is fetched, reviewed" "$?:$(V)" 0:clean
cd "$T/app"
g checkout -q feat

# the git pre-push hook (a copy under .git/hooks)
git push -q origin other 2>/dev/null; ok "no gate → git push passes" "$?" 0
git config core.hooksPath "$T/sharedhooks"; "$P" --install >/dev/null 2>&1; ok "a shared hooks folder (core.hooksPath) → install refuses (exit 2), nothing written there" "$?:$([[ -e "$T/sharedhooks/pre-push" ]] && echo written || echo nothing)" 2:nothing
need mkdir -p "$T/sharedhooks"; printf '#!/bin/sh\n# installed by .claude/skills/pre-review/prereview.sh --install\n' > "$T/sharedhooks/pre-push"
"$P" --uninstall >/dev/null 2>&1; ok "  … and uninstall refuses too, another clone's shared gate stays" "$?:$([[ -e "$T/sharedhooks/pre-push" ]] && echo stays || echo gone)" 2:stays
git config --unset core.hooksPath
"$P" --install >/dev/null 2>&1; ok "install" "$?" 0
grep -q 'prereview.sh --install' .git/hooks/pre-push; ok "  … the hook is a marked copy, not a symlink" "$?$([[ -L .git/hooks/pre-push ]] && echo L)" 0
git push -q origin feat 2>/dev/null; ok "clean feat → pushed" "$?" 0
g checkout -q -b other2 origin/master; echo e > e; g add e; g commit -qm e
s=$(date +%s); FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' git push -q origin other2 2>/dev/null
ok "unreviewed, non-interactive → refused, no review run" "$?:$(V)" 1:none
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' tty git push -q origin other2 > "$T/tty.log" 2>&1
ok "unreviewed, from a terminal → the hook reviews it, clean → pushed" "$?:$(V)" 0:clean
g checkout -q -b other3 origin/master; echo f > f; g add f; g commit -qm f
FAKE_LAST='{"blocking": 2, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
git push -q origin other3 2>/dev/null; ok "blocking → refused" "$?" 1
git push -q origin --delete feat 2>/dev/null; ok "deletion → allowed" "$?" 0
# origin unreachable: a deletion to another dash-wallet remote needs no base and must pass; an update must not
need mkdir -p "$T/alt"; need git init -q --bare "$T/alt/dash-wallet.git"; git remote add alt "$T/alt/dash-wallet.git"
git push -q alt other3:refs/heads/doomed 2>/dev/null   # other3 is reviewed (blocking) → refused; put the branch there without the hook
git push -q --no-verify alt other3:refs/heads/doomed 2>/dev/null
mv "$T/dash-wallet.git" "$T/dash-wallet.git.off"
git push -q alt --delete doomed 2>/dev/null; ok "origin down: deletion-only push to another remote → allowed (no fetch needed)" "$?" 0
git push -q alt other3:refs/heads/x 2>/dev/null; ok "origin down: a push with code → refused (the range cannot be checked)" "$?" 1
mv "$T/dash-wallet.git.off" "$T/dash-wallet.git"
g checkout -q -b other4 origin/master; echo h > h; g add h; g commit -qm h; g checkout -q other3
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' tty git push -q origin other4 > "$T/tty2.log" 2>&1
ok "terminal push of a branch that is not checked out → reviewed (in its own worktree), pushed" "$?:$(g checkout -q other4; V; g checkout -q other3)" 0:clean
ok "  … and git inside that worktree saw the pushed commit, not the checkout" "$(cat "$T/cdir.head")" "$(git rev-parse other4)"
g checkout -q other4; g tag -a v-test -m "annotated"; g checkout -q other3
git push -q origin v-test 2>/dev/null; ok "an annotated tag on a reviewed commit → pushed" "$?" 0
rm -rf "${XDG_CACHE_HOME:?}/pastaclaw"; mkdir -p "$T/bin2"; printf '#!/bin/sh\nfor a; do [ "$p" = -o ] && { echo partial > "$a"; exit 28; }; p=$a; done\n' > "$T/bin2/curl"; chmod +x "$T/bin2/curl"
g checkout -q other4; PATH="$T/bin2:$PATH" FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "prompts cannot be fetched → not reviewed (exit 2), no verdict" "$?:$(V)" 2:none
ok "  … and a cut-off download leaves nothing in the cache" "$(ls -A "$XDG_CACHE_HOME/pastaclaw/"* 2>/dev/null | wc -l | tr -d ' ')" 0
need mkdir -p "$XDG_CACHE_HOME/pastaclaw/$PCSHA"; for f in review-agent verifier-agent security-auditor review project; do echo "# $f" > "$XDG_CACHE_HOME/pastaclaw/$PCSHA/$f.md"; done   # seeded again: the cases below must not reach the network
# a reviewed commit carrying its own hooks, and a user config that points core.hooksPath at a relative folder: nothing may run
g checkout -q -b hooky origin/master; need mkdir -p .githooks; printf '#!/bin/sh\ntouch "%s/HOOK_RAN"\n' "$T" > .githooks/post-checkout; chmod +x .githooks/post-checkout; g add .githooks; g commit -qm hooky
printf '[core]\n\thooksPath = .githooks\n' > "$T/hookcfg"
GIT_CONFIG_GLOBAL="$T/hookcfg" FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "the reviewed commit's own hooks do not run while its tree is checked out (relative core.hooksPath)" "$?:$([[ -e "$T/HOOK_RAN" ]] && echo RAN || echo quiet)" 0:quiet
g checkout -q other4
g checkout -q other3
PREREVIEW_BASE=origin/release git push -q origin feat:refs/heads/feat2 2>/dev/null; ok "reviewed against master, PREREVIEW_BASE=release → refused (stale)" "$?" 1
# two refs in one push: the first is accepted against the base as fetched, then the second's automatic review fetches a
# base that moved meanwhile — the first must be checked again (a git wrapper advances origin's master on the 2nd fetch)
g checkout -q -b other5 origin/master; echo i > i; g add i; g commit -qm i; g checkout -q feat
need mkdir -p "$T/racegit"; printf '#!/usr/bin/env bash\nif [[ " $* " == *" fetch "* ]]; then n=$(cat "%s/fetches" 2>/dev/null || echo 0); echo $((n+1)) > "%s/fetches"; [[ "$n" == 1 ]] && "%s" -C "%s/dash-wallet.git" update-ref refs/heads/master "%s"; fi\nexec "%s" "$@"\n' "$T" "$T" "$REALGIT" "$T" "$FIRST" "$REALGIT" > "$T/racegit/git"; chmod +x "$T/racegit/git"
# the hook is called directly with git's ref lines: git puts its own exec path first in PATH for hooks, so a wrapper
# named git on PATH never reaches a hook started by `git push`
HOOK=$(git rev-parse --path-format=absolute --git-path hooks)/pre-push; Z=0000000000000000000000000000000000000000
printf 'refs/heads/feat %s refs/heads/feat-race %s\nrefs/heads/other5 %s refs/heads/other5 %s\n' "$(git rev-parse feat)" "$Z" "$(git rev-parse other5)" "$Z" > "$T/race.refs"
PATH="$T/racegit:$PATH" FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' tty bash -c "'$HOOK' origin '$T/dash-wallet.git' < '$T/race.refs'" > "$T/race.log" 2>&1
# the refusal is read from the hook's own output: util-linux script(1) does not always hand the child's exit status on
ok "the base moved during the push's own review → the earlier ref is rechecked, push refused" "$(grep -c 'the base moved while this push' "$T/race.log"):$(cat "$T/fetches" 2>/dev/null)" 1:3   # fetches: up front, the review's, the final check's
g checkout -q other5; ok "  … the later ref itself was reviewed against the moved base" "$(V)" clean; g checkout -q feat
# two unreviewed refs, cancelled during the first's review: the hook stops, the second is never started
g checkout -q -b c1 origin/master; echo c1 > c1; g add c1; g commit -qm c1; g checkout -q -b c2 origin/master; echo c2 > c2; g add c2; g commit -qm c2; g checkout -q feat
printf 'refs/heads/c1 %s refs/heads/c1 %s\nrefs/heads/c2 %s refs/heads/c2 %s\n' "$(git rev-parse c1)" "$Z" "$(git rev-parse c2)" "$Z" > "$T/cancel.refs"
PATH="$T/slow:$PATH" tty bash -c "'$HOOK' origin '$T/dash-wallet.git' < '$T/cancel.refs'" > "$T/cancel.log" 2>&1 & HK=$!; sleep 2; pkill -TERM -f "$(printf '%s' "$HOOK origin" | sed 's/[][\\.*^$+?(){}|]/\\&/g')" 2>/dev/null; wait $HK 2>/dev/null; sleep 1
g checkout -q c2; ok "cancelled during the first ref's review → the hook stops, the second ref is never reviewed" "$(V):$(grep -c 'cancelled: not pushing' "$T/cancel.log")" none:1; g checkout -q feat
git -C "$T/dash-wallet.git" update-ref refs/heads/master "$MASTER"; FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
cp "$P" "$T/moved.sh"; "$P" --uninstall >/dev/null 2>&1; "$T/moved.sh" --install >/dev/null 2>&1; mv "$T/moved.sh" "$T/gone.sh"
git push -q origin other3 2>/dev/null; ok "the installed script's source is gone → still gated" "$?" 1
echo '#!/bin/sh' > .git/hooks/pre-push; "$P" --install >/dev/null 2>&1; ok "someone else's pre-push hook → install refuses (exit 2)" "$?" 2
grep -q 'prereview' .git/hooks/pre-push; ok "  … and leaves it alone" "$?" 1
echo '#!/bin/sh' > .git/hooks/pre-push.keep; mv .git/hooks/pre-push.keep .git/hooks/pre-push; "$P" --uninstall >/dev/null 2>&1
ok "uninstall leaves a foreign hook" "$(head -1 .git/hooks/pre-push)" '#!/bin/sh'
rm -f .git/hooks/pre-push; chmod a-w .git/hooks; "$P" --install >/dev/null 2>&1; ok "unwritable hooks folder → install fails (exit 2)" "$?" 2
chmod u+w .git/hooks; "$P" --install >/dev/null 2>&1; ok "  … writable again → installed" "$?" 0
chmod a-w .git/hooks; "$P" --uninstall >/dev/null 2>&1; ok "unremovable hook → uninstall fails (exit 2)" "$?" 2; chmod u+w .git/hooks
echo "fails: $FAILS"; [[ -n "${KEEP:-}" ]] && echo "kept: $T"; exit $FAILS
