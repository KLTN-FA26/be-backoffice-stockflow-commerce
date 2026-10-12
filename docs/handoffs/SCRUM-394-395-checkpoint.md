# SCRUM-394/395 — working checkpoint (2026-10-12, phase A)

Short resume notes. The full report, API guide, READY_FOR_448 and criterion table are in
`SCRUM-394-395.md`.

- develop integrated: `1de8f42` (merge `3957b0a`), then `74d624d` (merge `8940181`).
- Commits: `fc6055e` runtime on the production schema; phase-A commit = migration
  `V20261014000200` (CUSTOMER read+answer, SALES answer, QC no UPDATE), answer = APPROVE, customer
  eligibility, workshop contract `SampleProductionProgress`.
- Passed before the commit: production unit + Modularity/Architecture/MessageBundle,
  `ProductionGrantsMigrationUpgradeTest`, `ProductionIntegrationTest` 10, `ProductionSecurityIntegrationTest` 5,
  `OrderReleaseIntegrationTest` 3, identity grant tests, `tools/verify.py`.
- Full `mvn -o test` of the phase-A tree: RUNNING at commit time, no result yet.
- Next: SCRUM-448 on its own branch/worktree based on the phase-A commit; then back to 394/395
  integration as coordinated by the owner.
