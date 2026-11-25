#!/usr/bin/env bash
# PreToolUse guard: code is written on a branch made from develop, never on develop or main themselves.
# develop deploys, and a change that lands there without a PR skips review and the CI run (CLAUDE.md §7).
#
# Blocks (exit 2, the reason goes back to Claude) while the checkout is on develop or main:
#   - Edit / Write / NotebookEdit of a file inside the repository (the scratchpad and memory are not);
#   - a Bash command that runs `git commit` or `git push`.
# Anything else, a detached HEAD, or no git at all passes: the guard must never break a session.
input=$(cat)

branch=$(git -C "${CLAUDE_PROJECT_DIR:-.}" symbolic-ref --quiet --short HEAD 2>/dev/null) || exit 0
case "$branch" in
  develop|main|master) ;;
  *) exit 0 ;;
esac

# The tool input is JSON on stdin; jq is not on every teammate's machine, so read the two fields by hand.
tool=$(printf '%s' "$input" | sed -n 's/.*"tool_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)

blocked=""
case "$tool" in
  Edit|Write|NotebookEdit|MultiEdit)
    path=$(printf '%s' "$input" | sed -nE 's/.*"(file_path|notebook_path)"[[:space:]]*:[[:space:]]*"([^"]*)".*/\2/p' | head -n1)
    path=${path//\\\\//}
    project=${CLAUDE_PROJECT_DIR:-$PWD}
    project=${project//\\//}
    case "$path" in
      "$project"/*) blocked="editing ${path##*/}" ;;
      /*|[A-Za-z]:/*|"") ;;                          # outside the repo (scratchpad, memory), or unreadable
      *) blocked="editing ${path##*/}" ;;            # a relative path is the repo's
    esac
    ;;
  Bash)
    if printf '%s' "$input" | grep -Eq 'git([[:space:]]+-[^[:space:]]+([[:space:]]+[^-[:space:]][^[:space:]]*)?)*[[:space:]]+(commit|push)([[:space:]"]|$)'; then
      blocked="git commit/push"
    fi
    ;;
esac

[ -z "$blocked" ] && exit 0

cat >&2 <<EOF
Blocked ($blocked): the checkout is on '$branch'. Work happens on a branch made from the latest develop,
never on develop or main (CLAUDE.md §7). Ask the user for the branch name if it is not obvious, then:

  git fetch origin develop
  git switch -c <type>/<short-slug> origin/develop     # type: feat | fix | refactor | chore | docs | test

Uncommitted edits already in the tree come along with 'git switch -c'. Then retry.
EOF
exit 2
