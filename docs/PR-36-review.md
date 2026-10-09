# PR #36 — supplier master and purchase-order communication

PR title: `fix(procurement): complete supplier and PO communication review (SCRUM-115/118)`

Supplier profiles and PO communication now include the validation, permissions, deployment
configuration and delivery evidence requested in the October review. A PO without a supplied
description resolves its product name when created; an unknown SKU fails before an uneditable
APPROVED order can be created. Prices retain their exact value or return a field error.

## Review disposition

Based on Tú's [4 October review](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/36#issuecomment-5978947684)
and [7 October follow-up](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/36#issuecomment-6037687599):

| Item | Resolution |
|---|---|
| A1 | Merged develop through `43a84fa` (#59); retained both sets of message keys. No rebase or force-push. |
| A2 | Upgrade tests check required migration versions instead of a fixed migration count. |
| A3 | Included Tú's supplier-spend fixture fix from develop. |
| A4 | Added all required buyer variables and supplier API allowlist to deployment Compose and its example environment. Buyer configuration uses `stockflow.procurement.buyer.*`. VPS values remain an operator step below. |
| A5 | DB-design README matches develop. C4 mapping is deferred to the schema owner. |
| B1 | Descriptions are resolved and persisted at creation; catalog changes before sending cannot rewrite them. |
| B2, C1–C3 | Bounds on quantity, description and monetary precision; valid ISO currency and SKU; aggregate overflow check; field paths in HTTP errors. Service validation also guards callers bypassing HTTP. |
| C4 | Stable delivery failure codes; detailed transport errors remain in server logs. Redirects are API rejection, not delivery success. |
| D1 | The first delivery date needs no reason; changing an existing date still does. |
| D2 | PO create/detail/list/action responses carry `supplierCode` and `supplierName`; list enrichment uses a batch query. |
| D3 | New error codes are grouped by HTTP status. Supplier form errors use DTO field validation; the existing fallback `SUPPLIER_PROFILE_INVALID` remains compatible with current clients. |
| D4 | URI and CommercialTerms use imports. |
| D5 | Vietnamese error mapping and FE instructions are recorded in the backend contract; no message has been sent to Hưng. |
| E | A new migration grants all permissions to SYSTEM_ADMIN and increments its role version in the same transaction. Existing migration checksums are unchanged. |
| Retry observations | Default retry interval/grace period are each one minute. Persistent `attemptNumber` distinguishes transport attempts from manual recovery generations, including historical backfill. |
| Supplier resource constant | SupplierController uses `SupplierResources.SUPPLIERS`. |

All six review comments (24/09, 29/09, 01/10, 04/10 and both 07/10 comments) were included in
the independent audit. GitHub returned no formal reviews or inline threads at that check.
The earlier comments are [24/09](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/36#issuecomment-5814150650),
[29/09](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/36#issuecomment-5892128195),
[01/10](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/36#issuecomment-5935037646)
and [07/10 retry](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/36#issuecomment-6037554140).

The completion pass fixed additional reproduced gaps: the first-date SQL constraint, damaged
Vietnamese messages, UTF-8 idempotency replay, unintended staff APPROVE, status field validation
and successful CREATE audit IDs. Feature-touched procurement/notification code is formatted;
unrelated MinIO/DB README edits match develop. Supplier writes use an aggregate and domain port.
Delivery-log Spring Data access remains inside notification for append-only transport evidence
and status projections; it is not an aggregate exposed to another module. Its public Java
visibility is retained for services in the sibling internal package; module boundaries still
exclude external access. This is an explicit design disposition of the older advisory comment,
not a claim that the repository became package-private.

The independent audit also reproduced a historical upgrade failure missed by fresh-DB tests.
The new fixture pins all 69 published SQL filenames and SHA-256 hashes from `9fbb90f`, reproduces
the missing develop prerequisites, and tests a controlled one-off recovery. The new review SQL
is `V20260930000500`, preserving published SQL and leaving #38's canonical version distinct.
See [the upgrade runbook](SCRUM-115-118-backend.md#database-upgrade); production histories need
identification before selecting a recovery procedure.

## Validation

Verified on 8 October 2026 with Java 21, Maven 3.9.9 and Docker Desktop:

- Local `clean verify` after develop #58: **527 tests**, zero failures/errors/skips, packaged JAR.
  After develop #59: **66 targeted tests plus packaging**, including new order-list and supplier
  integration, architecture and module boundaries.
- GitHub [build 37709035864](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/actions/runs/37709035864)
  passed for code commit `1671584`: **538 tests**, zero failures/errors/skips. Docker/deploy skipped.
- Static checks, whitespace and both Compose configurations pass. Each of the six required
  buyer/address variables was individually omitted to verify deployment configuration rejection.
- Real HTTP with security enabled: **64 checks** across five JWT roles. Mailpit captured original
  and cancellation notices. SMTP interruption produced failure attempt 1; after restoration,
  scheduler delivery attempt 2 succeeded after 113 seconds. No real supplier mail was sent.
- PostgreSQL QA: 166 checks passed; orphan and supplier preflight checks were clean.
- Both language bundles retain develop and prior-PR keys; published SQL hashes remain unchanged.

The earlier 493-test handoff described `1aafb96`, not the final pushed implementation.
The latest PR check is authoritative for any later documentation-only commit.

Commands:

```text
mvn -B -ntp clean verify
python -X utf8 tools/verify.py
docker compose --env-file deploy/.env.example -f deploy/docker-compose.yml config --quiet
git diff --check
```

For the Compose check, set `IMAGE_TAG=review-check` in the command environment. The environment
example contains placeholders, not production credentials. A negative configuration check also
confirms Compose rejects a missing `PO_BUYER_COMPANY_NAME`.

Tests cover PostgreSQL upgrades from develop and the existing feature schema, full SYSTEM_ADMIN
permissions, HTTP field errors, immutable descriptions, precise totals, concurrent transitions,
delivery/retry/recovery/cancellation, attempt ordering, and architecture/module boundaries.
See `target/surefire-reports` and `target/site/jacoco` for the local verification artifacts.

## Deployment and frontend handoff

Before deploying, the VPS operator must set `PO_BUYER_COMPANY_NAME`, `PO_BUYER_COMPANY_ADDRESS`,
`PO_BUYER_CONTACT_NAME`, `PO_BUYER_PHONE`, `PO_BUYER_EMAIL` and `PO_RECEIVING_ADDRESS` in
`/opt/stockflow/.env`, then run `docker compose config --quiet`. Those server values have not
been inspected or changed in this work. Mailpit captures development mail; external delivery
still depends on the deployed SMTP provider and sender configuration.

FE should use the PO's supplier identity fields, display `attemptNumber` for delivery attempts,
and map the documented stable failure/error codes. See
[the backend contract](SCRUM-115-118-backend.md#frontend-handoff-for-the-october-review).

The API intentionally still uses the legacy supplier table. GOVIET in the expanded schema is
not an API fixture; create a supplier through POST `/api/v1/suppliers` for demonstrations.
