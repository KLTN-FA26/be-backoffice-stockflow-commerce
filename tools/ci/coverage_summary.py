#!/usr/bin/env python3
"""
Turns JaCoCo's CSV report into a Markdown coverage table, one row per business module.

WHY THIS EXISTS
---------------
CI already produced target/site/jacoco/ and uploaded it as an artifact. Nobody opened it: reading
the number meant downloading a zip from a finished run. Written to $GITHUB_STEP_SUMMARY, the
table shows on the run's own page, next to the result it explains.

Rows are modules, not packages, because the module is the unit this codebase is organised and
reviewed by (ADR-0005). A package-level table is 150 rows that nobody reads; a module row says at
a glance that `order` is covered and `chat` is a skeleton.

The CSV counts per class, so a line shared by a class and an anonymous class inside it is counted
twice; the line totals can differ from index.html (which counts per source file) by a handful.
The percentages agree to the first decimal.

It reports and never fails the build. A threshold is a team decision, and a gate added before the
team agrees on the number trains everyone to ignore a red build.

    python3 tools/ci/coverage_summary.py                        # target/site/jacoco/jacoco.csv
    python3 tools/ci/coverage_summary.py path/to/jacoco.csv

Exit code 0 always; with no report it prints a one-line note, so a build that failed before the
report was written still gets a readable summary.
"""
import collections
import csv
import os
import sys

BASE_PACKAGE = "com.stockflow."
DEFAULT_REPORT = os.path.join("target", "site", "jacoco", "jacoco.csv")


def module_of(package):
    """com.stockflow.order.internal.service -> order; the base package itself -> (root)."""
    if not package.startswith(BASE_PACKAGE):
        return "(root)"
    return package[len(BASE_PACKAGE):].split(".", 1)[0]


def percent(covered, missed):
    total = covered + missed
    return None if total == 0 else 100.0 * covered / total


def cell(value):
    return "-" if value is None else f"{value:.1f}%"


def summarise(rows):
    """{module: [line_cov, line_missed, branch_cov, branch_missed]} plus a TOTAL entry."""
    totals = collections.defaultdict(lambda: [0, 0, 0, 0])
    for row in rows:
        counts = (int(row["LINE_COVERED"]), int(row["LINE_MISSED"]),
                  int(row["BRANCH_COVERED"]), int(row["BRANCH_MISSED"]))
        for key in (module_of(row["PACKAGE"]), "TOTAL"):
            acc = totals[key]
            for i, n in enumerate(counts):
                acc[i] += n
    return totals


def render(totals):
    overall = totals.pop("TOTAL", [0, 0, 0, 0])
    lines = [
        "## Test coverage",
        "",
        f"**Lines {cell(percent(overall[0], overall[1]))}** · "
        f"branches {cell(percent(overall[2], overall[3]))} "
        f"({overall[0]:,} of {overall[0] + overall[1]:,} lines)",
        "",
        "| Module | Lines | Branches | Lines covered |",
        "|---|---:|---:|---:|",
    ]
    # Least covered first: the table is read to find where tests are missing.
    order = sorted(totals.items(),
                   key=lambda item: (percent(item[1][0], item[1][1]) or 0.0, item[0]))
    for module, (lc, lm, bc, bm) in order:
        lines.append(f"| `{module}` | {cell(percent(lc, lm))} | {cell(percent(bc, bm))} "
                     f"| {lc:,} / {lc + lm:,} |")
    lines.append("")
    lines.append("Full report: the `test-results` artifact, `target/site/jacoco/index.html`.")
    return "\n".join(lines) + "\n"


def main(argv):
    report = argv[1] if len(argv) > 1 else DEFAULT_REPORT
    if not os.path.exists(report):
        print(f"## Test coverage\n\nNo JaCoCo report at `{report}` - the build stopped before "
              f"the tests produced one.\n")
        return 0
    with open(report, newline="", encoding="utf-8") as handle:
        print(render(summarise(csv.DictReader(handle))), end="")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
