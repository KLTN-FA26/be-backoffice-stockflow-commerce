#!/usr/bin/env bash
# Runs graphify from wherever it is installed, or does nothing. The hooks in settings.json call this
# instead of an absolute path, because the path differs per machine and a hook whose command is missing
# reports an error on every tool call. No graphify (a teammate who never installed it, a cloud session
# whose install failed) means no graph hints, never a failing hook.
G=$(command -v graphify 2>/dev/null)
[ -z "$G" ] && [ -x "$HOME/.local/bin/graphify" ] && G="$HOME/.local/bin/graphify"
[ -z "$G" ] && exit 0
"$G" "$@" || exit 0
