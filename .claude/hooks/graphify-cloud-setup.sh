#!/usr/bin/env bash
# Claude Code on the web only: installs graphify and builds the code graph in the background.
#
# graphify-out/ is not committed: the full graph is about 100 MB, at GitHub's per-file limit, and would be
# rewritten on every build. A cloud session starts from a fresh clone, so it builds its own graph
# (AST only, no LLM, about a minute). The build runs detached so the hook returns at once; until it
# finishes graphify-out/graph.json does not exist and the graphify hooks stay silent.
# Log: ~/.cache/graphify-cloud-setup.log
[ "$CLAUDE_CODE_REMOTE" = "true" ] || exit 0

VERSION=0.9.119
LOG="$HOME/.cache/graphify-cloud-setup.log"
mkdir -p "$(dirname "$LOG")"
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
export PATH="$HOME/.local/bin:$PATH"

if ! command -v graphify >/dev/null 2>&1; then
  # A PEP 668 image refuses a plain `pip install --user`, so try the tool installers first.
  { uv tool install "graphifyy==$VERSION" && echo "installed with uv"; } >>"$LOG" 2>&1 \
    || { pipx install "graphifyy==$VERSION" && echo "installed with pipx"; } >>"$LOG" 2>&1 \
    || { pip install --quiet --user --break-system-packages "graphifyy==$VERSION" && echo "installed with pip"; } >>"$LOG" 2>&1 \
    || { echo "graphify install failed; the graph hooks stay silent" >>"$LOG"; exit 0; }
fi

# Bash tool commands see this PATH; hooks go through .claude/hooks/graphify.sh, which looks in ~/.local/bin.
[ -n "$CLAUDE_ENV_FILE" ] && echo 'export PATH="$HOME/.local/bin:$PATH"' >>"$CLAUDE_ENV_FILE"

# A resumed session keeps its graph.
[ -f graphify-out/graph.json ] && exit 0

# setsid: a new session, so the build outlives the hook's process group.
DETACH=$(command -v setsid || true)
nohup $DETACH graphify update . >>"$LOG" 2>&1 </dev/null &
exit 0
