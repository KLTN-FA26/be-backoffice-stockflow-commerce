# The Production Coordinator owns production — design

Date: 2026-11-20 · Size: project · Status: draft, for the owner's approval

## Intent

The Production Coordinator is the one person between the business and its producers. They already draw up production
orders, keep the producers, record what producers say and upload print samples. Two decisions still sit with the Sales
Manager and slow the chain down without adding control:

- the **technical and content review of a customer's design** (kltn-docs 12 §4 step 3), and
- the **approval of a production order** before it is sent (17 §4.3 step 7, BR-23: approver ≠ drawer).

The owner's decision (2026-11-20): the coordinator reviews designs, and sends production orders **without an approval
step**. Producers stay outside the system: they do not approve, accept or refuse anything in it. The coordinator agrees
the work with the producer by phone / Zalo / e-mail first, sends the order, and records the producer's acceptance by
hand (with an optional note). The evidence that matters — what was sent, when, by whom — the system already keeps.

The two goals, in the owner's words: **less typing for the coordinator, and evidence**.

### Why approval goes (the case the owner accepted)

BR-23's four-eyes check guards against over-ordering, the wrong design or file, an order with no demand behind it, and
money committed without authority. In this system each is already enforced by data, more strictly than a reviewer:

| Risk | Already enforced by |
|---|---|
| ordering more than needed | `ProductionOrder.requireWithin`: never past what the delivery batch still lacks after held stock |
| wrong design / print file | lines carry the customer-approved design snapshot; `verifyForPrinting` re-hashes every file at send |
| no demand behind it | a production order is drawn up only from a signed contract's delivery batch (17 BR-05, trigger `check_production_order`) |
| money committed | a production order carries no price since `c15413c4` (`V20261207000100`) |
| late goods | the AT_RISK alert compares the producer's expected day with the need-by day (§4.12) |

What remained was a reviewer with nothing left to check, i.e. a rubber stamp and a delay (worse each time a producer
changes its mind). Oversight moves from *before* (a gate) to *after* (traceability): who sent what to which producer and
when (audit, status history, the slip as sent), and the Sales Manager notified and able to read
every production order.

## Goals

1. The coordinator reviews (approves / returns) customer designs and design-project review rounds.
2. The coordinator sends a DRAFT production order straight to its producer (DRAFT → SENT), with everything approval did
   at send time still done at send time.
3. Recording the producer's acceptance (SENT → ACCEPTED) takes an optional free-text note (who confirmed, how).

## Non-goals

- No producer account, portal, or signed *write* link. The existing read-only signed links (slip, print files,
  originals) are unchanged.
- No "producer refused" status. A producer who backs out after the order was sent is handled as today: the coordinator
  cancels with a reason (17 §4.3 step 13) and draws up a new order for another producer. A "draw up again for another
  producer" copy button is a possible later convenience, not part of this.
- No change to print sample rounds: the coordinator still uploads the photos, the customer still answers on the portal.
- No change to the print configuration of a spec (01 §4.10): stays with the Sales Manager.

## Anti-goals (would make this a failure even if it works)

- A send that skips anything approval did: the print-file check, the slip, the capacity warning, the producer's
  message, the sales order moving to AWAITING_STOCK, the warehouse manager's notice.
- Historical rows (production orders approved by a Sales Manager, `PENDING_APPROVAL` in history) breaking a check or a
  read.
- More typing for the coordinator than today.

## Constraints

- kltn-docs wins over code: 12 (roles, step 3, §2 note "người duyệt luôn là Quản lý kinh doanh"), 17 (§2 roles, §4.3
  steps 7-8, §5.3, BR-23), 00 §2.1 roles and the `open-questions` row must be changed with `✅ Chốt lại 2026-11-20`
  in a kltn-docs PR. Code and docs land together.
- Migrations only add (house rules): a new file above every merged version; a role-permission change advances
  `app_role.version` in the same file; every CHECK / trigger change gets adversarial SQL under `tools/db/qa/`; a lean
  `*MigrationUpgradeTest` because approved SENT rows exist in the demo data (no demo row stays in `PENDING_APPROVAL`:
  `V20261128000200__demo_history.sql` moves them all on; the test writes its own).
- Historical enum constants stay (`ProductionOrderStatus.PENDING_APPROVAL`).
- Lock order of a send is the approval's: production order read and version-checked, files verified and slip drawn
  with no lock held, then `customer_order → production_order → warehouse-capacity:<warehouseId>` (as approval today).
- The FE session working on SCRUM-756 (producers + production orders) is building against `/submission`, `/approval`,
  `/return`: the FE brief (`docs/superpowers/plans/2026-11-20-fe-producers-production-orders.md`) must be updated.

## Approaches considered

**Who decides on a production order**

| | Trade-off | |
|---|---|---|
| A. No approval: coordinator sends | fewest steps; control by data + traceability; needs BR-23 rewritten | **chosen** |
| B. Coordinators approve each other | keeps BR-23; blocks a business with one coordinator | rejected |
| C. Sales Manager keeps approval | no change; not what the owner wants | rejected |

**How producers take part**

| | Trade-off | |
|---|---|---|
| Producer self-service via signed write links (accept / refuse / upload samples) | self-recorded evidence; public write routes, new states, a producer page in the FE, link revocation per send | rejected by the owner: the coordinator fronts the producer |
| Coordinator records, with mandatory evidence (channel, name, files) | evidence copied in; more typing, two tables, uploads, and the question whether receiving must wait for it | rejected by the owner: the evidence that matters is already kept |
| Coordinator records, optional note | matches how the business works (agreed by phone/Zalo before sending); no public writes; no extra typing | **chosen** |

**Producer backs out after sending**: back to DRAFT and resend (rejected: the slip is one per order and immutable,
`sent_at`/approval fields are write-once, and the order code is the receiving lot printed as a QR — one lot would name
two producers) vs. cancel and draw up again (**chosen**, it is today's behaviour).

## Design

### 1. Design review moves to the coordinator

- Permission `design-designs:APPROVE` moves from `SALES_MANAGER` to `PRODUCTION_COORDINATOR` (SYSTEM_ADMIN keeps it).
  Migration: delete the SALES_MANAGER grant, insert the coordinator's, advance `app_role.version` of both roles.
- Everything keyed on the permission follows by itself: the review queue, review rounds, `DesignReviewNotificationListener`
  (`notificationRecipientsHolding("design-designs:APPROVE")`).
- The coordinator also needs to *read* what they review. Builder: check every permission the review screens call
  (design reads, artifact downloads, project names, preflight results) and grant the coordinator those it lacks.
- **Read rights follow the reviewer flag** (`DesignReadRights`, `CustomerDesignRules`: a staff member reads a customer's
  design as reviewer, i.e. by holding `design-designs:APPROVE`, or through an open quote request). So the Sales Manager
  **loses** reading designs waiting for review, keeping only the quote-request path; and the coordinator, like the
  Sales Manager before, reads PENDING_REVIEW designs as reviewer. *My call: accepted as is* — no new read right. The
  Sales Manager's remaining design work (print configuration, 01 §4.10) does not need it, and the coordinator reaches an
  approved design's files through the production order. 12 §3's reader table is updated to say so.
- Content moderation (copyright, inappropriate content) moves with it: it is the same review action.
- `PermissionMatrixTest` updated; javadoc that names the Sales Manager as reviewer updated.

### 2. Production orders are sent by the coordinator, without approval

State machine (17 §5.3) after the change:

```
DRAFT ──send──▶ SENT ──accept──▶ ACCEPTED ──▶ SAMPLE_PENDING / IN_PRODUCTION ──▶ …   (unchanged from SENT on)
DRAFT ──cancel──▶ CANCELLED
PENDING_APPROVAL: historical only; no edge leads into it
```

- **API.** New `POST /api/v1/production-orders/{id}/sending` `{version}`, `production-orders:UPDATE` (coordinator,
  admin), back office only. `/submission`, `/approval` and `/return` are removed.
- **Service.** `ProductionOrderService.send(id, version, by)` is today's `approve` without the self-approval check:
  status DRAFT and version checked before downloading, `verifyForPrinting` per design snapshot, `prepareSlip` with no
  lock held, then under `lockedWithOrder`: `requireWithin(lacking)`, `capacity.warnIfSendingTurnsNegative`,
  `production.send(by, now)`, save, `dispatch.sent(saved, slip)`. `@Auditable` as approve was (action TRANSITION or
  APPROVE — builder's call, keep one).
- **Domain.** `ProductionOrder.send(by, at)`: DRAFT → SENT, records `sentBy`, `sentAt`. `submit`, `approve`,
  `returnToDraft` are removed; reading old rows keeps `approvedBy/approvedAt/submittedAt/returnedReason` (historical).
- **Schema** (one migration):
  - `ordering.production_order.sent_by uuid` (FK to `identity.app_user`). Backfill `sent_by = approved_by` for rows
    already sent.
  - `ck_production_order_sent` / `ck_production_order_approval` rewritten: `sent_at` and `sent_by` are both set or
    both null; null in DRAFT/PENDING_APPROVAL, set in every later status **except CANCELLED, which may be either**
    (cancelled as a draft, or after sending — the exemption the current constraint has); `approved_*` may be null on
    new rows, and when set still differs from `drawn_up_by` (history stays valid). `sent_at` no longer requires
    `approved_at`.
  - `check_production_order()` rewritten (Postgres does not track function bodies): "sent once" protects
    `sent_by`/`sent_at` (and the historical approval columns) once set; the DRAFT/PENDING_APPROVAL receiving-warehouse
    rule keeps both statuses.
  - `production_order_status_transition`: add `DRAFT→SENT`; delete `DRAFT→PENDING_APPROVAL` and every edge out of
    `PENDING_APPROVAL`.
  - Leftover rows: production orders in `PENDING_APPROVAL` go back to `DRAFT` (with a status-history row, reason
    "approval step removed 2026-11-20") before the edges are dropped — the migration inserts the edge it needs or
    updates under the old edge `PENDING_APPROVAL→DRAFT` first (it exists today, and `ck_production_order_returned`
    then needs a `returned_reason`, which the migration sets).
  - Permission `production-orders:APPROVE`: grants deleted (SALES_MANAGER, SYSTEM_ADMIN), the permission row kept or
    dropped per the codebase's practice for retired permissions (builder: follow the last migration that retired one),
    `app_role.version` advanced.
- **Everything that listed PENDING_APPROVAL as "not sent yet"** keeps it (historical rows) but no longer needs it for
  new ones: `WarehouseCapacityService`, `OpenProductionCartons`, `CapacityBoard`, `SalesOrderCancellationListener`,
  `DelayAlertRepositoryAdapter` (`OPEN_STATUSES`), `chat` `ContextResolver`, `ProductionOrderProgressListener`, and
  `ProductionOrder` itself. Leave the constant in their sets; update javadoc ("approved, so sent" → "sent").
- **Notifications.** Submitting published no event and sent no notice, so nothing is removed. New: the Sales Manager is
  notified when a production order is **sent** (code, sales order, producer, cartons, need-by day) — oversight after the
  fact. Recipients are chosen by permission, as everywhere (`staff.activeHolders`), and `production-orders:READ` is
  held too widely; *my call:* a new permission `production-orders:MONITOR` ("notified of production orders sent"),
  granted to SALES_MANAGER and SYSTEM_ADMIN in the same migration. The warehouse manager's notice at send is unchanged.
- **Errors.** `PRODUCTION_ORDER_SELF_APPROVAL` stays in the enum (historical clients) but is no longer thrown; message
  bundles keep it.

### 3. The producer's acceptance takes an optional note

The owner dropped mandatory evidence (2026-11-20): what must be proven in a dispute with a producer — what was sent,
when, by whom, to whom — is already kept immutably (the slip as sent, checksummed print files, audit, status history,
the producer's mail log); the producer's "yes" lives in the coordinator's Zalo / e-mail and copying it in would only add
typing, against the first goal.

- `POST /production-orders/{id}/acceptance` gains an optional `note` (≤ 500 chars, trimmed, blank → null), e.g. "anh
  Hùng xác nhận qua Zalo". `version` and `expectedCompletionDate` are unchanged.
- Schema: `ordering.production_order.acceptance_note varchar(500)`, CHECK non-blank when set and null in
  DRAFT / PENDING_APPROVAL / SENT (it is written by the acceptance); written once (added to `check_production_order`'s
  write-once list).
- Read: the production order detail returns it next to the expected completion day.
- Receiving is unchanged: a SENT order can still be received without an acceptance recorded (03 BR-15).

### 4. Business docs (kltn-docs PR, same time as the code)

- 17: §2 roles (Coordinator: "lập, **gửi**, theo dõi"; Sales Manager: "nhận thông báo, xem" — no approval), §4.3 step 7
  rewritten ("Gửi"), step 8 (the optional note), §5.3 diagram, BR-23 rewritten with the reasoning above, BR-25
  capacity warning shown at send.
- 12: step 3 and the §2 role table — reviewer is the Production Coordinator; §2 note on reviewer ≠ creator still holds.
- 00 §2.1 role descriptions; `open-questions` row with the decision; `diagrams` if the state diagram is there.

### 5. Tests

- Domain: `send` from DRAFT only; acceptance note trimmed, blank → null, over 500 refused.
- Service / integration: send does the print-file check (mismatch refused, nothing stored), slip, capacity warning,
  sales order AWAITING_STOCK, messages; the drawer may send; a stale version refused before downloads.
- Permission matrix: design approval and production order sending by role; `/submission`, `/approval`, `/return` gone.
- `tools/db/qa/`: DRAFT→PENDING_APPROVAL refused; SENT without `sent_by` refused; a cancelled draft without `sent_at`
  accepted; blank `acceptance_note` refused; `acceptance_note` on a SENT order refused; changing it once written
  refused.
- `ProductionOrderSendMigrationUpgradeTest`: a PENDING_APPROVAL row and an approved SENT row written before the
  migration → DRAFT with history, and `sent_by = approved_by`, all checks pass.

## Left to the builder

- Endpoint path wording (`/sending` follows `/submission`, `/acceptance`), DTO names, the audit action.
- Whether `approved_by/approved_at/submitted_at/returned_reason` stay in the API response (recommended: keep, nullable,
  for historical orders).
- Exact read permissions the coordinator needs for design review screens (§1).
- How to retire `production-orders:APPROVE` (follow precedent).
