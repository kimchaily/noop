#!/usr/bin/env bash
# PreToolUse/Bash guard for the repo convention in CLAUDE.md:
# a feature branch picks up newer `main` by REBASING onto it, never by merging
# `main` into the branch. Blocks the merge-shaped commands and points at the
# rebase instead. Landing a branch *into* main is untouched — that happens via
# a PR merge on GitHub, not a local `git merge` from a feature branch.
#
# Deliberately fails open: no jq, no git, unreadable payload -> allow. This is a
# convention guard against an easy mistake, not a security boundary.
#
# Known blind spots (documented, not bugs to chase): it only sees Bash, so the
# GitHub UI's "Update branch" button and the update_pull_request_branch MCP tool
# still produce a merge commit; and `git -C <path> merge main` is not matched,
# since the subcommand is not the second token.
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

deny_merge() {
  deny "Blocked by .claude/hooks/rebase-not-merge.sh: this repo brings \`main\` into a branch by rebasing, not by merging it in (see CLAUDE.md). You are on '$branch'. Use instead:
  git fetch origin main && git rebase origin/main
then push with: git push --force-with-lease -u origin $branch"
}

deny_pull() {
  deny "Blocked by .claude/hooks/rebase-not-merge.sh: a plain \`git pull\` on branch '$branch' can create a merge commit. This repo rebases instead (see CLAUDE.md) — re-run it as \`git pull --rebase ...\` (or \`--ff-only\` if you only mean to fast-forward)."
}

# Inspect each shell segment on its own, and only where `git <subcommand>` is
# what the segment actually runs. Anchoring this way keeps prose that merely
# mentions the commands — a commit message, an echo, a heredoc — from tripping
# the guard, and stops a branch named `feature/main-screen` from looking like
# `main`.
while IFS= read -r segment; do
  # shellcheck disable=SC2086 # deliberate word-splitting into argv tokens
  set -- $segment
  [ "${1:-}" = "git" ] || continue
  subcommand=${2:-}
  shift 2 2>/dev/null || continue

  case "$subcommand" in
    merge)
      for arg in "$@"; do
        case "$arg" in
          main | master | origin/main | origin/master | upstream/main | upstream/master)
            deny_merge
            ;;
        esac
      done
      ;;
    pull)
      for arg in "$@"; do
        case "$arg" in
          --rebase | --rebase=* | --ff-only) continue 2 ;;
        esac
      done
      deny_pull
      ;;
  esac
done < <(printf '%s\n' "$cmd" | tr ';&|\n' '\n\n\n\n')

exit 0
