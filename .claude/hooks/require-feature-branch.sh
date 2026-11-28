#!/usr/bin/env bash
# PreToolUse guard: code is written on a branch made from develop, never on develop or main themselves.
# develop deploys, and a change that lands there without a PR skips review and the CI run (CLAUDE.md §7).
#
# Blocks (exit 2, the reason goes back to Claude) when the checkout the action lands in is on develop or main:
#   - Edit / Write / NotebookEdit of a file inside this repository: the main checkout or one of its worktrees (the
#     scratchpad and memory are not);
#   - a Bash command that runs `git commit` or `git push`.
# "The checkout the action lands in" is the one that holds the file, or, for a command, the directory it runs git in
# (`git -C <dir>`, else a leading `cd <dir>`, else the session's working directory). It used to be the main checkout
# always, so a commit on a feature branch in a worktree (git worktree add ../x -b feat/y) was refused while the main
# checkout sat on develop.
# Anything else, a detached HEAD, or no git at all passes: the guard must never break a session.
input=$(cat)

project=${CLAUDE_PROJECT_DIR:-$PWD}

# The tool input is JSON on stdin; jq is not on every teammate's machine, so read the fields by hand.
field() { printf '%s' "$input" | sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -n1; }
tool=$(field tool_name)
cwd=$(field cwd)
[ -n "$cwd" ] || cwd=$project

# The nearest existing directory of a path (a file being created does not exist yet).
existing_dir() {
  local d=$1
  while [ -n "$d" ] && [ "$d" != "/" ] && [ ! -d "$d" ]; do d=$(dirname "$d"); done
  printf '%s' "${d:-/}"
}

# Whether a directory belongs to this repository (the main checkout or a worktree of it): same git common dir.
project_common=$(cd "$project" 2>/dev/null && git rev-parse --path-format=absolute --git-common-dir 2>/dev/null)
same_repository() {
  local common
  common=$(cd "$1" 2>/dev/null && git rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || return 1
  [ -n "$project_common" ] && [ "$common" = "$project_common" ]
}

target=""
action=""
case "$tool" in
  Edit|Write|NotebookEdit|MultiEdit)
    path=$(printf '%s' "$input" | sed -nE 's/.*"(file_path|notebook_path)"[[:space:]]*:[[:space:]]*"([^"]*)".*/\2/p' | head -n1)
    path=${path//\\\\//}
    [ -n "$path" ] || exit 0
    case "$path" in
      /*|[A-Za-z]:/*) ;;
      *) path="$cwd/$path" ;;                          # a relative path is relative to the session
    esac
    target=$(existing_dir "$path")
    action="editing ${path##*/}"
    ;;
  Bash)
    command=$(field command)
    git_call='git([[:space:]]+-[^[:space:]]+([[:space:]]+[^-[:space:]][^[:space:]]*)?)*[[:space:]]+(commit|push)([[:space:]"]|$)'
    if printf '%s' "$command" | grep -Eq "$git_call"; then
      # Where git runs: the -C of that git call itself, else the last cd before it, else the session's directory.
      # Only the call and what precedes it are read: a commit message may well quote "git -C <dir>" or "cd x".
      call=$(printf '%s' "$command" | grep -oE "$git_call" | head -n1)
      before=${command%%"$call"*}
      dir=$(printf '%s' "$call" | grep -oE '[[:space:]]-C[[:space:]]+[^[:space:];&|]+' | head -n1 | awk '{print $2}')
      [ -n "$dir" ] || dir=$(printf '%s' "$before" | grep -oE '(^|[;&|(][[:space:]]*)cd[[:space:]]+[^[:space:];&|)]+' | tail -n1 \
                              | sed -E 's/.*cd[[:space:]]+//')
      dir=${dir//\"/}
      dir=${dir/#\~/$HOME}
      case "$dir" in
        "") dir=$cwd ;;
        /*) ;;
        *) dir="$cwd/$dir" ;;
      esac
      target=$(existing_dir "$dir")
      action="git commit/push"
    fi
    ;;
esac

[ -n "$target" ] || exit 0
same_repository "$target" || exit 0               # outside the repo (scratchpad, memory, another project)

branch=$(git -C "$target" symbolic-ref --quiet --short HEAD 2>/dev/null) || exit 0
case "$branch" in
  develop|main|master) ;;
  *) exit 0 ;;
esac
checkout=$(git -C "$target" rev-parse --show-toplevel 2>/dev/null)

cat >&2 <<EOF
Blocked ($action): the checkout $checkout is on '$branch'. Work happens on a branch made from the latest develop,
never on develop or main (CLAUDE.md §7). Ask the user for the branch name if it is not obvious, then either switch
this checkout:

  git fetch origin develop
  git switch -c <type>/<short-slug> origin/develop     # type: feat | fix | refactor | chore | docs | test

or work in a worktree on its own branch (git worktree add ../<dir> -b <type>/<short-slug> origin/develop) and run
git there (cd <dir> && git commit ..., or git -C <dir> ...). Uncommitted edits come along with 'git switch -c'.
EOF
exit 2
