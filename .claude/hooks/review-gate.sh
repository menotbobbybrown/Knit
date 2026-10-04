#!/usr/bin/env bash
# Stop hook: an uncommitted change under a reviewer's paths gets that reviewer before Claude finishes the turn.
#
# Each reviewer persona (`.agents/personas/<name>.md`) hunts one class of defect from a catalogue of the ones
# this codebase has already shipped and fixed. review-gate.conf maps each to the paths it owns; this script
# asks for every reviewer whose paths moved since its last review, in one message, so they run in parallel.
#
# no uncommitted change under a reviewer's paths, or the same one already asked for  -> not owed.
# same HEAD and files as its last stamp, and within its follow-up lines of that diff  -> not owed: the caller
#                                                                                       is applying its findings.
# anything else                                                                       -> owed.
# A stamp covers one review round: a commit since, or a file the review never saw, needs a fresh one — else a
# small new change would ride an old stamp's slack.
# nothing owed -> exit 0, Claude stops normally; else exit 2 with the instruction on stderr, which Claude
# Code feeds back.
#
# `--stamp <name>` records what a review approved: each reviewer runs it as its last step, so the few-line
# edits that apply its findings don't ask for a review of the review. The state lives in the git dir, so each
# worktree keeps its own. Honors `stop_hook_active`, so the enforced round can always end.

set -uo pipefail

conf="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/review-gate.conf"
project_dir="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$project_dir" || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0
state="$(git rev-parse --git-dir)/claude-review"

reviewers() { grep -v -e '^#' -e '^[[:space:]]*$' "$conf"; }

# What a reviewer reads: the tracked diff plus every untracked file's bytes.
snapshot() {
  git diff HEAD -- "$@"
  git ls-files --others --exclude-standard -- "$@" | sort | while read -r f; do
    printf '%s\n' "$f"
    cat "$f"
  done
}

changed_files() { git status --porcelain --untracked-files=all -- "$@" | awk '{print $NF}' | sort; }

fingerprint() { printf '%s\n' "$1" | sha256sum | cut -d' ' -f1; }

# Content lines only: a follow-up edit shifts the line numbers in every later hunk header and the blob ids in
# each `index` line, which would otherwise count as changes of their own.
content() { grep -v -e '^index ' -e '^@@ '; }

if [ "${1:-}" = "--stamp" ]; then
  name="${2:?usage: review-gate.sh --stamp <reviewer>}"
  line=$(reviewers | awk -F'\t' -v n="$name" '$1 == n')
  if [ -z "$line" ]; then
    echo "review-gate: no reviewer named '$name' in $conf" >&2
    exit 1
  fi
  IFS=$'\t' read -r _ _ paths _ <<<"$line"
  read -ra specs <<<"$paths"
  current=$(snapshot "${specs[@]}")
  mkdir -p "$state"
  printf '%s\n' "$current" >"$state/$name.snapshot"
  changed_files "${specs[@]}" >"$state/$name.files"
  git rev-parse HEAD >"$state/$name.head"
  fingerprint "$current" >"$state/$name.asked"
  echo "$name: review stamped ($(wc -l <"$state/$name.snapshot") lines of diff)"
  exit 0
fi

input=$(cat)
if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]; then
  exit 0
fi

owed=""
relays=""
while IFS=$'\t' read -r name follow_up paths relay <&3; do
  read -ra specs <<<"$paths"
  changed=$(changed_files "${specs[@]}")
  [ -n "$changed" ] || continue

  current=$(snapshot "${specs[@]}")
  print=$(fingerprint "$current")
  [ "$(cat "$state/$name.asked" 2>/dev/null)" = "$print" ] && continue
  if [ -f "$state/$name.snapshot" ] && [ "$(cat "$state/$name.head" 2>/dev/null)" = "$(git rev-parse HEAD)" ] &&
    [ -z "$(comm -23 <(printf '%s\n' "$changed") "$state/$name.files" 2>/dev/null)" ]; then
    delta=$(diff <(content <"$state/$name.snapshot") <(printf '%s\n' "$current" | content) | grep -c '^[<>]')
    [ "$delta" -le "$follow_up" ] && continue
  fi
  mkdir -p "$state"
  printf '%s\n' "$print" >"$state/$name.asked"

  owed+="  $name:"$'\n'"$(printf '%s\n' "$changed" | sed 's/^/    /')"$'\n'
  relays+="  $name: $relay"$'\n'
done 3< <(reviewers)

[ -n "$owed" ] || exit 0
{
  echo "Stop blocked: these changes have no review from the reviewer that owns them:"
  printf '%s' "$owed"
  echo
  echo "Spawn each reviewer named above (Agent tool, subagent_type = its name) on its files, all in one message so"
  echo "they run in parallel. Each reads the change against its catalogue (.agents/personas/<name>.md), runs its"
  echo "checks, and stamps what it reviewed. Fix each finding, or say why it does not hold, then give the user"
  echo "the findings and, from each report:"
  printf '%s' "$relays"
} >&2
exit 2
