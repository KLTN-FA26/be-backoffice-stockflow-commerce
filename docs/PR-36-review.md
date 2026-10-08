# PR #36 — supplier master and purchase-order communication

Suggested title: `fix(procurement): complete supplier and PO communication review (SCRUM-115/118)`

Supplier profiles and PO communication now include the validation, permissions, deployment
configuration and delivery evidence requested in the October review. A PO without a supplied
description resolves its product name when created; an unknown SKU fails before an uneditable
APPROVED order can be created. Prices retain their exact value or return a field error.

## Review disposition

Based on Tú's [4 October review](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/36#issuecomment-5978947684)
and [7 October follow-up](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/36#issuecomment-6037687599):

| Item | Resolution |
|---|---|
| A1 | Merged `origin/develop` at `2c68457`; retained both sets of message keys. No rebase or force-push. |
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

The recent RBAC, procurement report, deployment, integrity-handler and SYSTEM_ADMIN changes
(#39–42 and #52) are included through develop. The open warehouse PRs form a separate series;
their pending module changes are not merged into this branch. The Windows path fix in
`tools/verify.py` matches the same change already proposed in #49.

## Validation

Verified on 8 October 2026 with Java 21, Maven 3.9.9 and Docker Desktop:

- `clean verify`: **BUILD SUCCESS ? 493 tests, 0 failures, 0 errors, 0 skipped**, with the executable JAR packaged.
- Static checks: **822 Java files, 125 tables, all checks passed**.
- Deployment Compose: valid with the example environment; missing buyer configuration is rejected.
- No conflicting message keys, whitespace errors or modifications to migrations already in develop.

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
