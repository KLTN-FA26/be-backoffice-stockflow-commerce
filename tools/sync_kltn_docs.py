#!/usr/bin/env python3
"""Refresh docs/kltn-snapshot/, the assistant's knowledge corpus, from the kltn-docs repository.

The business analysis is the kltn-docs repository (GitHub KLTN-FA26/docs), not docs/business-design/ here, which
is the pre-rescope analysis kept for history. The assistant's searchKnowledge tool indexes a copy of the kltn-docs
chapters a back-office user asks about; the build copies that snapshot onto the classpath (pom.xml, the knowledge
<resource> block) and KnowledgeService indexes every file it finds there on start.

The copy is taken from a git ref of a local clone (`git show <commit>:docs/...`), so the clone's working tree and
branch are never touched, and the commit is recorded in docs/kltn-snapshot/README.md. A copy rather than a git
submodule: the build, the tests and the Docker image then need nothing but this repository.

    python -I -X utf8 tools/sync_kltn_docs.py --docs D:/01_Study/FPT/KLTN/docs
    python -I -X utf8 tools/sync_kltn_docs.py --docs ../docs --check    # exit 1 when the snapshot is not the ref's

Which chapters are copied is CHAPTERS below; a chapter left out, or removed from the list, is deleted from the
snapshot, and the next start of the application removes its passages from the index.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SNAPSHOT = REPO / "docs" / "kltn-snapshot"
README = "README.md"
INDEX = "index.txt"
DEFAULT_REF = "origin/develop"

# (path under the kltn-docs repository's docs/ folder, snapshot name, why the assistant searches it)
CHAPTERS = [
    ("glossary/README.md", "glossary",
     "terms, statuses, abbreviations, roles, and the terms no longer used (§8)"),
    ("warehouse/01-product-creation/README.md", "01-product-creation",
     "SKU master data, the carton spec, product approval (four eyes), product statuses"),
    ("warehouse/03-receipt/README.md", "03-receipt",
     "receiving finished goods against production orders, return receipts"),
    ("warehouse/05-putaway/README.md", "05-putaway",
     "putaway tasks and trips, bin rules, OVERFLOW"),
    ("warehouse/06-warehouse-map-slotting/README.md", "06-warehouse-map-slotting",
     "bins, areas and their statuses, staging, the capacity board"),
    ("warehouse/07-picking/README.md", "07-picking",
     "pick tasks by carton, staging, short picks, put-back"),
    ("warehouse/08-packing/README.md", "08-packing",
     "carton check and shipping labels"),
    ("warehouse/09-shipping/README.md", "09-shipping",
     "load plans, carriers, handover, delivery results"),
    ("warehouse/11-intra-warehouse-transfer/README.md", "11-intra-warehouse-transfer",
     "moving cartons inside one warehouse"),
    ("warehouse/20-inventory-control/README.md", "20-inventory-control",
     "stock statuses, ATP, counts, adjustments, quarantine, the movement ledger"),
    ("ecommerce/14-cart-and-order/README.md", "14-cart-and-order",
     "quotes, orders and sales contracts"),
    ("ecommerce/15-payment/README.md", "15-payment",
     "installments, payment receipts, receivables and credit, refunds, cancellation fees"),
    ("ecommerce/17-order-management/README.md", "17-order-management",
     "the order lifecycle, production orders, holds, cancellation, returns"),
    ("ecommerce/18-customer-information/README.md", "18-customer-information",
     "wholesale customers, their terms and statuses"),
    ("ecommerce/21-e-invoice/README.md", "21-e-invoice",
     "VAT e-invoices per delivery"),
]

# Left out, and why: kept here so the next person deciding does not have to rediscover it.
LEFT_OUT = [
    ("00-system-overview", "the overview: goals, module map, role registry and the permission table (§2.3) some rules "
     "cite; it does not follow the chapter template (no rules in §6), so it needs a parser of its own. The glossary §7 "
     "has the roles"),
    ("ecommerce/12-product-design", "the customer's own design tool on the portal (2D/3D, preflight, PDF/X); the Sales "
     "Manager's design review is in it too, a back-office task, but most of its rules are about the editor"),
    ("ecommerce/13-catalog", "the customer portal's catalog pages"),
    ("ecommerce/16-sales-chat", "the chat between a customer and Sales"),
    ("open-questions", "the decision log: settled questions repeat what the chapters say, open ones are not rules"),
    ("diagrams", "Mermaid diagrams, unreadable as text"),
]


def git(docs: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "-C", str(docs), *args], capture_output=True)
    if result.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} failed in {docs}: {result.stderr.decode('utf-8', 'replace').strip()}")
    return result.stdout


def lf(content: bytes) -> bytes:
    """The text with LF line ends: a Windows checkout or editor must not make the snapshot differ."""
    return content.replace(b"\r\n", b"\n")


def find_docs(given: str | None) -> Path:
    candidates = [given] if given else [os.environ.get("KLTN_DOCS"), str(REPO.parent / "docs")]
    for candidate in filter(None, candidates):
        path = Path(candidate).resolve()
        if (path / ".git").exists():
            return path
    raise SystemExit("kltn-docs clone not found: pass --docs <path to the clone> or set KLTN_DOCS")


def expected(docs: Path, ref: str) -> dict[str, bytes]:
    """Every file the snapshot should hold for ref, by file name, README and index included."""
    commit = git(docs, "rev-parse", "--verify", ref + "^{commit}").decode().strip()
    committed = git(docs, "show", "-s", "--format=%cI", commit).decode().strip()
    files: dict[str, bytes] = {}
    rows = []
    for path, name, why in CHAPTERS:
        content = lf(git(docs, "show", f"{commit}:docs/{path}"))
        files[name + ".md"] = content
        rows.append(f"| `{name}.md` | `docs/{path}` | {why} | `{hashlib.sha256(content).hexdigest()[:12]}` |")
    # What KnowledgeService indexes, and nothing else under classpath:knowledge/ (a stale build may hold more there).
    files[INDEX] = ("# The documents the assistant indexes; written by tools/sync_kltn_docs.py\n"
                    + "".join(name + "\n" for _, name, _ in CHAPTERS)).encode("utf-8")
    left_out = "\n".join(f"- `{path}`: {why}" for path, why in LEFT_OUT)
    files[README] = f"""# kltn-docs snapshot: the assistant's knowledge corpus

**Generated by `tools/sync_kltn_docs.py`; do not edit by hand.** The business analysis is the kltn-docs repository
(GitHub `KLTN-FA26/docs`); these files are a copy of the chapters the back-office assistant searches
(`searchKnowledge`, `docs/assistant-design.md` §6). The build copies every `*.md` here except this README onto the
classpath (`knowledge/`), with `index.txt`, the list `KnowledgeService` indexes on start, and only it: a file removed
here has its passages removed from the index on the next start.

- Source: `{ref}` at commit `{commit}`, committed {committed}
- Refresh: `python -I -X utf8 tools/sync_kltn_docs.py --docs <kltn-docs clone>`; check without writing: add `--check`

| File | kltn-docs path | Why the assistant searches it | SHA-256 |
|---|---|---|---|
{chr(10).join(rows)}

Left out:

{left_out}
""".encode("utf-8")
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--docs", help="the kltn-docs clone (default: $KLTN_DOCS, then ../docs next to this repo)")
    parser.add_argument("--ref", default=DEFAULT_REF, help=f"the git ref to copy (default: {DEFAULT_REF})")
    parser.add_argument("--no-fetch", action="store_true", help="use the clone's refs as they are")
    parser.add_argument("--check", action="store_true", help="write nothing; exit 1 when the snapshot differs")
    arguments = parser.parse_args()

    docs = find_docs(arguments.docs)
    if not arguments.no_fetch:
        fetched = subprocess.run(["git", "-C", str(docs), "fetch", "--quiet", "origin"], capture_output=True)
        if fetched.returncode != 0:
            print("warning: git fetch failed; using the clone's refs as they are", file=sys.stderr)
    wanted = expected(docs, arguments.ref)
    present = {path.name: lf(path.read_bytes()) for path in [*SNAPSHOT.glob("*.md"), *SNAPSHOT.glob(INDEX)]}

    changed = sorted(name for name in wanted if present.get(name) != wanted[name])
    removed = sorted(name for name in present if name not in wanted)
    if arguments.check:
        for name in changed:
            print(f"differs: docs/kltn-snapshot/{name}")
        for name in removed:
            print(f"not in the corpus: docs/kltn-snapshot/{name}")
        return 1 if changed or removed else 0

    SNAPSHOT.mkdir(parents=True, exist_ok=True)
    for name in changed:
        # Bytes, so Python never turns LF into CRLF on Windows.
        (SNAPSHOT / name).write_bytes(wanted[name])
    for name in removed:
        (SNAPSHOT / name).unlink()
    print(f"docs/kltn-snapshot: {len(changed)} written, {len(removed)} removed, {len(CHAPTERS)} documents")
    return 0


if __name__ == "__main__":
    sys.exit(main())
