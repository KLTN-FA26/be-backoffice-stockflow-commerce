# SCRUM-394/395 — working checkpoint (2026-10-12)

Short resume notes. The full report, API guide and criterion table are in `SCRUM-394-395.md`.

- Base: merged `origin/develop` `1de8f42612be534539d98bbcf105d8b39c6d74a3` (merge commit `3957b0a`,
  no conflicts). Schema/grants used as-is: `V20261010000200`, `...0300`, `...0500`.
- Implementation is in the working tree, **uncommitted** (commit needs the owner's go-ahead).
- Passed: production unit + Modularity/Architecture/MessageBundle (67), `ProductionIntegrationTest` 8,
  `ProductionSecurityIntegrationTest` 4, `OrderReleaseIntegrationTest` 3, `tools/verify.py` clean.
- Full `mvn -o test`: 953 tests, 0 failures, 4 errors — all the pre-existing receipt-time trigger failure (reproduced on clean develop `1de8f42`).
- Resume order if interrupted: rerun full suite → fix only regressions caused here → commit on request.
- Open for owners: CUSTOMER grant on `production-sample-requests`; quote table (SCRUM-448); workshop
  transitions/`ProductionCompleted` (SCRUM-396..401); C02 customer eligibility; Jira live re-check after
  reconnecting the Atlassian connector.
