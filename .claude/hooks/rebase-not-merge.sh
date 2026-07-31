#!/usr/bin/env bash
# PreToolUse/Bash guard for the repo convention in CLAUDE.md:
# a feature branch picks up newer `main` by REBASING onto it, never by merging
# `main` into the branch. Blocks the merge-shaped commands and points at the
# rebase instead. Landing a branch *into* main is untouched — that happens via
# a PR merge on GitHub, not a local `git merge` from a feature branch.
set -uo pipefail

payload=$(cat)
cmd=$(printf '%s' "$payload" | jq -r '.tool_input.command // ""' 2>/dev/null) || exit 0
[ -n "$cmd" ] || exit 0

# Cheap bail-out first: this hook sees every Bash call, and all but a handful
# are none of its business.
printf '%s' "$cmd" | grep -Eq '\bgit +(merge|pull)\b' || exit 0

repo=${CLAUDE_PROJECT_DIR:-$PWD}
branch=$(git -C "$repo" rev-parse --abbrev-ref HEAD 2>/dev/null) || exit 0
case "$branch" in main | master | HEAD | '') exit 0 ;; esac

deny() {
  jq -n --arg reason "$1" '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$reason}}'
  exit 0
}

# `git merge <main-ish>` — the merge commit the convention forbids.
if printf '%s' "$cmd" | grep -Eq '\bgit +merge\b[^&|;]*\b(origin/|upstream/)?(main|master)\b'; then
  deny "Blocked by .claude/hooks/rebase-not-merge.sh: this repo brings \`main\` into a branch by rebasing, not by merging it in (see CLAUDE.md). You are on '$branch'. Use instead:
  git fetch origin main && git rebase origin/main
then push with: git push --force-with-lease -u origin $branch"
fi

# `git pull` without --rebase/--ff-only can silently create the same merge commit.
if printf '%s' "$cmd" | grep -Eq '\bgit +pull\b' &&
  ! printf '%s' "$cmd" | grep -Eq '\bgit +pull\b[^&|;]*(--rebase|--ff-only)\b'; then
  deny "Blocked by .claude/hooks/rebase-not-merge.sh: a plain \`git pull\` on branch '$branch' can create a merge commit. This repo rebases instead (see CLAUDE.md) — re-run it as \`git pull --rebase ...\` (or \`--ff-only\` if you only mean to fast-forward)."
fi

exit 0
