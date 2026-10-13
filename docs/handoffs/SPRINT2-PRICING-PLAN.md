# Sprint 2 lane B — customer pricing (SCRUM-428) + quote integration + SCRUM-429 follow-up

**Checkpoint 2026-10-12** on `SCRUM-428-customer-pricing` from `4a389a6`: `aca7f05` SCRUM-429 follow-up → `2c190ff`
SCRUM-428 → `0081cfa` reorder → `ed3107e` P4/P5 → `425f27d` Sales notice → `2b3c4bf` R1 → `272f347` P7 group prices
→ T1 (household identifier). Migrations `V20261014000500` (status history), `…000510` (customer prices), `…000530`
(group prices), `…000540` (12-digit identifier). Details, API and evidence: `docs/handoffs/SCRUM-428.md`.

**All lane-B business questions are decided** (P1–P7, R1 by the user; T1 from the official texts). Two narrow
questions remain and neither blocks Sprint 2 BE - see "Still open" below.

**User decision 2026-10-12 — overrides kltn-docs 14 BR-10 and an earlier same-day P2 answer (5 % limit + admin
approval), which is withdrawn:** there is **no discount limit and no internal discount approval**. Sales negotiates,
decides any valid selling price and sends the quote; no ECOMMERCE_ADMIN / SYSTEM_ADMIN approval, no new role. The
**customer still has to accept** the quote. Kept: server-side price validation and totals, audit of Sales' quote
edits, the price snapshot on quote and order, existing permissions. No floor (e.g. cost) and no other ceiling is
invented. The approval code drafted for the withdrawn answer (migration `…000520`, `QuoteDiscountApproval*`) was
never committed and never ran outside throwaway Testcontainers databases; it was deleted from the working tree.
This does not let Sales edit a customer's price list (P1 below).

**User decision 2026-10-12 on P1 (final, after a same-day answer that was withdrawn):** a negotiated price applies
**only to its own quote and order**; it is never saved as the customer's default price. (A "Sales saves the deal as
the customer's price" feature was asked for and withdrawn the same day, before any code for it was written.) The
customer price lists of SCRUM-428 stay as they are, managed by the E-commerce Admin; Sales reads them. **Reordering
a negotiated order** reuses its blanks, designs, print specifications and quantities, but never its old price and
never creates a payable order by itself: Sales quotes again and the customer accepts. Reusing an approved design
needs no new sample (plan D4). Ordering at the price list (portal quick order, SCRUM-190) is not affected. The
validity question for deal prices is void, as deals are not stored.

**User decisions 2026-10-12 on P3, P7, R1 (final):**
- **P3** — an admin-managed customer price list applies until the admin edits it; no start / end dates. Versions,
  audit and the quote / order snapshots stay; a quote keeps its own validity. Nothing in the code had to change.
- **P7** — E-commerce Admin and System Admin manage customer-group prices; Sales and the Order Coordinator read
  them, and Sales negotiates per quote. Resolution unchanged: own tiers → group price → base price.
- **R1** — a customer's reorder of a negotiated order whose blank has no current list price, or whose quantity is
  below the list's first tier, still becomes a request for Sales: a DRAFT quote whose line is explicitly unpriced
  (no tiers, `priced: false`), never the old deal or a zero. It cannot be sent - so not accepted or converted - until
  Sales prices it. Payment term: R1b below (decided 2026-10-13).

**T1, settled from official sources (not a business choice):** since 01/07/2025 the representative's 12-digit
personal identification number is the tax identifier of a household business (Thông tư 86/2024/TT-BTC art. 5(5),
art. 38(2); Luật Quản lý thuế 38/2019/QH14 art. 35(7); 12 digits: Luật Căn cước 26/2023/QH15 art. 12(1)). Accepted
next to the unchanged company rule (10, or 10-3). Official pages read: xaydungchinhsach.chinhphu.vn (Bộ Tài chính
guidance quoting the Circular), congbao.chinhphu.vn (the Circular, in force 06/02/2025), datafiles.chinhphu.vn
(Law 26/2023/QH15 gazette text).

**User decisions 2026-10-12 on P4, P5, P6 (final):**
- **P4** — below the lowest tier of the customer's list, Sales negotiates and enters a quote at the actual quantity
  and the agreed price; the customer sees and accepts it; it converts once; production follows the existing
  payment / credit / release gates. The 1,000 tier is never applied to 500. Direct checkout below the list minimum
  fails with `CUSTOMER_PRICE_BELOW_MOQ` ("ask Sales for a quote"), never a silent fall back. The list minimum is a
  pricing minimum only: no hard production minimum exists in the documents or the schema (the supplier MOQ,
  BR-PO-001, is procurement's), so none was removed or added.
- **P5** — the price break of a blank is chosen by the **total of that SKU in one order or quote of one customer**,
  every design together (600 logo A + 400 logo B = 1,000). Never across SKUs, customers or orders. Each quote line
  keeps its own tiers, so a different print can still be priced differently; no surcharge is added.
- **P6** — quantities are **pieces (cái)**, whole and ≥ 1, across quote / order / inventory / production; prices are
  VND per piece. No carton / box / pallet unit, conversion or packaging schema in this batch. A chosen scope, not a
  gap.

Branch `SCRUM-428-customer-pricing`, worktree `orca/workspaces/.../SCRUM-428-customer-pricing`, from the
integration checkpoint `4a389a6` (`SCRUM-394-395-production-foundation`: develop `74d624d` + 394/395 + 448 +
429). Migration range reserved for this lane: `V20261014000500` … `V20261014000599` (lane A: `…000400–000499`).
Develop checked 2026-10-12: still `74d624d`, newest migration `V20261013000600`.

## Sources read (2026-10-12)

- Jira live: **SCRUM-428** (To Do, 0 comments, no subtasks): "each wholesale customer can have its own price per
  SKU and quantity tier … `catalog.pricing_rule` is keyed by segment … feeds portal price display (SCRUM-172)
  and portal ordering (SCRUM-190)". SCRUM-426/448 (quotes, To Do, 0 comments). SCRUM-429/450 unchanged.
- kltn-docs main `84b8b54`: 13 §2 (E-commerce Admin configures price lists by customer and quantity tier),
  13 §3 (customer price list by sales unit and tier; **no own list → customer-group price**), 13 BR-02 (the
  logged-in customer's own price), 13 BR-05 (show MOQ and tiers, no order below MOQ), 14 step 2 (quote price
  defaults from the customer's list), 14 BR-03 (price locked into the order; a quote keeps its price while
  valid), 14 BR-10 (discount beyond a limit needs approval — overridden by the user, see above), 14 edge case (price changed between view and order →
  show the difference, customer confirms), 18 §3 input table ("bảng giá riêng" — source "Sale + người duyệt hạn
  mức"), open question D14 (proposal: per-customer list + tiers; quote overrides).
- Code: `catalog.pricing_rule` holds one `BASE:<sku>` price per SKU used by checkout and the storefront;
  `segment_id` exists but is never read; no customer key, no tiers, no sales units anywhere in orders/quotes.

## Still open (not blocking)

| # | Question, with a concrete case | Today |
|---|---|---|
| N1 | ~~Who is told when the Sales account that handled a quote is disabled or gone?~~ **Decided 2026-10-13:** every active Sales account, marked as a hand-over, each address once; no change to who may read or answer the quote. | every active Sales account e-mailed (`QuoteNotificationListener`) |
| R1b | ~~Reorder when the old payment term is no longer allowed~~ **Decided 2026-10-13:** the request is still created, on a term the customer may use today (the old one while allowed, otherwise the current default); a withdrawn credit is never restored; send, acceptance and conversion check again; the old order keeps its snapshot. | `QuoteService.reorder`; IT `aWithdrawnPaymentTermIsNeverRestoredAndIsCheckedAgainAtEachStep`, `QuoteServiceTest.aReorderWhoseCreditWasWithdrawn…` |

External configuration, not a question: real e-mail delivery needs `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` /
`MAIL_PASSWORD` / `MAIL_FROM` (and `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS`) of a real SMTP relay in the deployed
environment. The repository configures only `localhost:1025` (Mailpit in `docker compose`), which captures mail and
delivers nothing.

## Former questions (decided; kept for the record)

| # | Question | Why it matters | Decision |
|---|---|---|---|
| P3 | Validity dates on an admin price list? | effective-dated history | **no dates** (user) |
| P7 | Who manages group (segment) prices? | fallback source | **E-commerce Admin + System Admin; Sales reads** (user); API built |
| R1 | Reorder the price list cannot price? | reorder of rare blanks | **request reaches Sales unpriced** (user); payment term: R1b (decided) |
| T1 | 12-digit personal ID of a household business? | onboarding | **accepted** - the law since 01/07/2025 (official texts) |

## SCRUM-429 follow-up (separate commit) — decisions confirmed by the user

1. Customers include household businesses / small shops: **no tax identifier needed** to be created, activated
   or to order; it can be supplied later. Existing uniqueness and format rules apply when one is given.
2. A **manager reopens a closed (INACTIVE) customer** with a **mandatory reason**, kept in a status history;
   old (revoked) sessions stay invalid. Manager = **E-commerce Admin** ("Quản trị sàn", kltn-docs 18 §2 and §5),
   the role that already changes customer status; System Admin passes every role check.
3. A closed customer whose contact never activated reopens to **PENDING_ACTIVATION** (not ACTIVE), so Sales can
   resend the invitation.

## SCRUM-428 work that is already clear (implemented in this lane)

- `catalog.customer_price` (customer + SKU, versioned) and `catalog.customer_price_tier` (min quantity → whole-VND
  unit price), CHECKs mirroring the aggregate; permission resource `catalog-customer-prices`.
- Price resolution for a customer, SKU and quantity: customer tier → active segment rule → base price.
- Checkout (`OrderServiceImpl.place`) prices account orders through that resolution; the client's expected price is
  still checked (`CHECKOUT_PRICE_CHANGED`), the price is locked into the order line (BR-03).
- Portal: `GET` the logged-in ACTIVE customer's own prices, tiers and MOQ (BR-02, BR-05).
- Back office: list / read / replace / delete a customer's price for a SKU, optimistic version, audited.
- Quotes: back-office lookup of the default tiers for a quote line; a quote line created without tiers starts from
  the customer's list (14 step 2), snapshotted into the quote (BR-03). Sales may type any valid price (user
  override of BR-10 above); the customer accepts.
- P4/P5 in quotes: a line left to the list is refused below the list minimum ("enter the negotiated price"); MOQ
  and break are read at the quote's total of each blank; responses carry `proposedUnitPrice`, `proposedLineTotal`,
  `proposedTotal`, computed on the server.
- Reorder (P1 decision): `POST /api/v1/orders/{orderId}/reorder` turns an order converted from a quote into a new
  DRAFT quote at today's price list (unpriced lines where the list cannot price, R1); an order bought at the price
  list answers `ORDER_NOT_QUOTED`.
- Sales notice: a customer's acceptance, change request or reorder e-mails the Sales account that handled the quote.
- Group prices (P7): `/api/v1/catalog/segment-prices`.
- Household identifier (T1): 12 digits accepted.

## SCRUM-429 follow-up — evidence (commit `aca7f05`)

| Decision | Code | Test |
|---|---|---|
| no tax code needed to create, activate, order | `CreateBusinessCustomerRequest`, `Customer.onboard`, `CustomerOnboardingService.create` | IT `aShopWithoutATaxCodeIsOnboardedActivatesAndOrdersOnItsOwn` (real HTTP order, MST added later) |
| reopen a closed customer only with a reason, audited, history | `Customer.reopen`, `CustomerServiceImpl.changeStatus`, `customer_status_history`, `GET /customers/{id}/status-history` | IT `aManagerReopens…` (no/blank reason 400, Sales 403, history 4 rows, audit), domain test, `CustomerStatusHistoryMigrationUpgradeTest`, QA H1–H4 |
| old sessions stay invalid | sessions revoked on closure, never restored | IT: token from before closure 401 after reopening |
| never-activated customer reopens to PENDING_ACTIVATION | `Customer.reopen(reason, portalActivated)` + `PortalAccountService.awaitingActivation` | IT `aClosedCustomerThatNeverActivatedReopensPending…` (resend, old link dead, activation) |
