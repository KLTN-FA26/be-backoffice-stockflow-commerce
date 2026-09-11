---
name: git-commits
description: Use when committing to this repository — staging changes, writing a commit message, amending, or preparing a PR. Use when about to add a tool/AI attribution line, a Co-Authored-By trailer, or when unsure what belongs in a commit.
---

# Git commits — project content only, no tooling attribution

## Overview

A commit is part of the project's permanent record. It carries the change and the reason for it,
in the project's own terms — nothing about who or what typed it.

**Core rule:** the message describes the project change. It never advertises the tool that made it.
No "Generated with Claude Code", no "Co-Authored-By" trailer, no "made by Anthropic / AI". The
author of the commit is the human whose repository this is.

## When to use

- Staging files and writing any commit message in this repo.
- Amending or rewording a commit.
- Any time you're about to append a trailer or a footer to a message.

## Forbidden in every commit message — never add these

```
🤖 Generated with Claude Code
Co-Authored-By: Claude <noreply@anthropic.com>
Co-authored-by: anthropic ...
"made with AI", "written by an assistant", or any tool/vendor credit
```

If a template or a default would insert one of the above, remove it before committing. The commit
message ends with the last line of the project explanation — there is no tooling footer.

## What a commit contains — project-relevant changes only

- Only files that belong to the change. Never sweep in editor config (`.idea/`, `.vscode/`), OS
  files (`.DS_Store`), build output (`target/`), scratch files, or an unrelated edit that happened
  to be in the tree. The repo `.gitignore` already excludes most of these — do not force-add them.
- One logical change per commit. If the diff needs the word "and" to describe it, it is probably
  two commits.
- Never commit a secret. `.env` is ignored; `.env.example` (the template) is the only env file that
  is committed.

## Message style

- A short imperative subject line (≈50 chars): `Add procurement purchase-order aggregate`.
- A blank line, then a body that explains **why**, wrapped at ~72 chars — matching this codebase's
  habit of javadoc/comments that justify a decision rather than restate the diff.
- Conventional-commit prefixes (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`) are fine and
  consistent; pick one style and keep it.

## Committer identity — set it per-repository, not globally

This repo moves between machines (Windows → Mac), so pin the identity **in the repo**, not in a
global config that does not travel:

```bash
git config user.name  "Uong Thanh Tu"
git config user.email "uongthanhtu5@gmail.com"
```

(No `--global`. Run these once in the repo after it is initialised.)

## Stronger enforcement than a skill (optional)

A skill is guidance; the Co-Authored-By trailer is more reliably suppressed by a **project setting**
that travels with the repo. Add `.claude/settings.json` at the project root:

```json
{ "includeCoAuthoredBy": false }
```

With that in place the harness will not add the trailer at all — belt and braces alongside this
skill.

## Red flags — STOP before committing

- About to paste a `Co-Authored-By:` line → delete it.
- About to add "🤖 Generated with…" → delete it.
- `git add .` with untracked scratch/agent files in the tree → stage only the project files.
- One commit touching several unrelated areas → split it.

## Checklist

- [ ] Message describes the project change and its reason — no tool/vendor credit, no trailer.
- [ ] Only project-relevant files staged; no build output, editor config, scratch, or secrets.
- [ ] One logical change.
- [ ] Committer identity is the repo owner (set per-repo, see above).
