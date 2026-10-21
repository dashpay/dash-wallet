#!/usr/bin/env bash
# Tests prereview.sh with a fake codex on PATH (no quota, no network). Run: bash <this file>; non-zero on failure.
set -u
# setup must never fall through into the caller's checkout: a failed cd there would put the fixture's commits in it
cd() { builtin cd "$@" || { echo "FAIL: cannot cd $*" >&2; exit 1; }; }
need() { "$@" || { echo "FAIL: setup: $*" >&2; exit 1; }; }
P=${1:-"$(cd "$(dirname "$0")" && pwd)/prereview.sh"}; T=$(mktemp -d "${TMPDIR:-/tmp}/prtest.XXXXXX") || exit 1; export XDG_CACHE_HOME="$T/cache"; FAILS=0
trap 'chmod -R u+w "$T" 2>/dev/null; rm -rf "$T"' EXIT   # this run's folder only, writable again first (the chmod tests)
# the cases set these themselves: an inherited API key would pass the logged-out case, a base or timeout would move others
unset OPENAI_API_KEY CODEX_API_KEY PREREVIEW_BASE PREREVIEW_TIMEOUT PREREVIEW_MODEL
need mkdir -p "$T/bin"
cat > "$T/bin/codex" <<'EOF'
#!/usr/bin/env bash
[[ "$1" == login ]] && exit "${FAKE_LOGIN_RC:-0}"
while [[ $# -gt 0 ]]; do [[ "$1" == -o ]] && { out=$2; shift; }; [[ "$1" == -C ]] && { cdir=$2; echo "$2" > "$FAKE_CDIR"; shift; }; shift; done
echo "${CODEX_API_KEY:-}" > "${FAKE_CDIR}.key"; echo "${GIT_DIR:-unset}" > "${FAKE_CDIR}.gitdir"
git -C "$cdir" rev-parse HEAD > "${FAKE_CDIR}.head" 2>/dev/null   # what git inside the review's folder sees
printf 'finding text\n%s\n' "$FAKE_LAST" > "$out"
EOF
chmod +x "$T/bin/codex"; export PATH="$T/bin:$PATH" FAKE_CDIR="$T/cdir"
PCSHA=$(sed -n 's/^PC_SHA=\([0-9a-f]*\).*/\1/p' "$P"); mkdir -p "$XDG_CACHE_HOME/pastaclaw/$PCSHA"   # prompts seeded: no network
for f in review-agent verifier-agent security-auditor review project; do echo "# $f" > "$XDG_CACHE_HOME/pastaclaw/$PCSHA/$f.md"; done
# tty <cmd…>: run with a terminal on stdin/stdout/stderr (script(1): BSD/macOS and util-linux take different arguments);
# its stdin stays open a while, so an early EOF cannot close the pty under the command
tty() {
  if script --version 2>/dev/null | grep -q util-linux; then script -qec "$(printf '%q ' "$@")" /dev/null < <(sleep 15)
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
ok "  … and the worktree is gone after" "$(git worktree list | wc -l | tr -d ' ')" 1
FAKE_LAST='garbled' "$P" >/dev/null 2>&1; ok "a failed re-review drops the earlier clean verdict" "$(V)" none
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
FAKE_LOGIN_RC=1 "$P" >/dev/null 2>&1; ok "a re-review with Codex logged out → exit 2" "$?" 2
ok "  … and drops the earlier clean verdict too" "$(V)" none
FAKE_LOGIN_RC=1 CODEX_API_KEY=k-codex FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "logged out, CODEX_API_KEY set → reviewed with it" "$?:$(cat "$T/cdir.key")" 0:k-codex
FAKE_LOGIN_RC=1 OPENAI_API_KEY=k-openai FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
ok "logged out, OPENAI_API_KEY set → copied into CODEX_API_KEY for codex" "$?:$(cat "$T/cdir.key")" 0:k-openai
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1
H=$(git rev-parse HEAD); python3 -c 'import fcntl,sys,time; f=open(sys.argv[1],"a"); fcntl.flock(f,fcntl.LOCK_EX); time.sleep(300)' \
  "$XDG_CACHE_HOME/prereview/.$H.lock" & LIVE=$!; sleep 1
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "a review of the same commit already running → exit 2, nothing touched (the running one owns the record)" "$?:$(V)" 2:clean
kill -9 $LIVE; wait $LIVE 2>/dev/null
FAKE_LAST='{"blocking": 1, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "its holder was killed → the kernel released the lock, reviewed" "$?:$(V)" 1:blocking
# the lock lives as long as the review: hold it from inside a running review (a slow fake codex), then try another
printf '#!/usr/bin/env bash\n[[ "$1" == login ]] && exit 0\nsleep 4; while [[ $# -gt 0 ]]; do [[ "$1" == -o ]] && { echo "{\\"blocking\\": 0, \\"suggestion\\": 0, \\"nitpick\\": 0}" > "$2"; shift; }; shift; done\n' > "$T/bin/codex.slow"
chmod +x "$T/bin/codex.slow"; mkdir -p "$T/slow"; cp "$T/bin/codex.slow" "$T/slow/codex"
PATH="$T/slow:$PATH" "$P" >/dev/null 2>&1 & SLOW=$!; sleep 1
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "a second review while the first is inside codex → exit 2" "$?" 2
wait $SLOW; ok "  … the first one finishes and publishes" "$(V)" clean
# Ctrl-C signals the whole foreground process group: run the review in a group of its own and signal that
set -m; PATH="$T/slow:$PATH" "$P" >/dev/null 2>&1 & SLOW=$!; set +m; sleep 2; kill -INT -- -$SLOW 2>/dev/null; wait $SLOW 2>/dev/null; sleep 1
ok "a review interrupted inside codex leaves no worktree behind" "$(git worktree list | wc -l | tr -d ' ')" 1
ok "  … and no verdict (it was dropped when the review started)" "$(V)" none
PATH="$T/slow:$PATH" "$P" >/dev/null 2>&1 & SLOW=$!; sleep 2; kill -TERM $SLOW 2>/dev/null; wait $SLOW 2>/dev/null; sleep 1
ok "TERM to the script's own pid alone → the review stops too (no worktree, no verdict)" "$(git worktree list | wc -l | tr -d ' '):$(V)" 1:none
FAKE_LAST='{"blocking": 0, "suggestion": 0, "nitpick": 0}' "$P" >/dev/null 2>&1; ok "  … and a new review starts at once (the lock is free)" "$?" 0
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
g checkout -q other3
PREREVIEW_BASE=origin/release git push -q origin feat:refs/heads/feat2 2>/dev/null; ok "reviewed against master, PREREVIEW_BASE=release → refused (stale)" "$?" 1
cp "$P" "$T/moved.sh"; "$P" --uninstall >/dev/null 2>&1; "$T/moved.sh" --install >/dev/null 2>&1; mv "$T/moved.sh" "$T/gone.sh"
git push -q origin other3 2>/dev/null; ok "the installed script's source is gone → still gated" "$?" 1
echo '#!/bin/sh' > .git/hooks/pre-push; "$P" --install >/dev/null 2>&1; ok "someone else's pre-push hook → install refuses (exit 2)" "$?" 2
grep -q 'prereview' .git/hooks/pre-push; ok "  … and leaves it alone" "$?" 1
echo '#!/bin/sh' > .git/hooks/pre-push.keep; mv .git/hooks/pre-push.keep .git/hooks/pre-push; "$P" --uninstall >/dev/null 2>&1
ok "uninstall leaves a foreign hook" "$(head -1 .git/hooks/pre-push)" '#!/bin/sh'
rm -f .git/hooks/pre-push; chmod a-w .git/hooks; "$P" --install >/dev/null 2>&1; ok "unwritable hooks folder → install fails (exit 2)" "$?" 2
chmod u+w .git/hooks; "$P" --install >/dev/null 2>&1; ok "  … writable again → installed" "$?" 0
chmod a-w .git/hooks; "$P" --uninstall >/dev/null 2>&1; ok "unremovable hook → uninstall fails (exit 2)" "$?" 2; chmod u+w .git/hooks
echo "fails: $FAILS"; exit $FAILS
