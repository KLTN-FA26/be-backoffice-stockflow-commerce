# Sprint 2 lane B — customer pricing (SCRUM-428) + quote integration + SCRUM-429 follow-up

**Checkpoint 2026-10-12:** `8ae1c00` (this plan) → `aca7f05` (SCRUM-429 follow-up) → `2c190ff` (SCRUM-428),
on `SCRUM-428-customer-pricing` from `4a389a6`. Migrations `V20261014000500` (status history) and `…000510`
(customer prices). Details, API and test evidence: `docs/handoffs/SCRUM-428.md`. Still open: P1, P3–P7 and T1
below.

**User decision 2026-10-12 — overrides kltn-docs 14 BR-10 and an earlier same-day P2 answer (5 % limit + admin
approval), which is withdrawn:** there is **no discount limit and no internal discount approval**. Sales negotiates,
decides any valid selling price and sends the quote; no ECOMMERCE_ADMIN / SYSTEM_ADMIN approval, no new role. The
**customer still has to accept** the quote. Kept: server-side price validation and totals, audit of Sales' quote
edits, the price snapshot on quote and order, existing permissions. No floor (e.g. cost) and no other ceiling is
invented. The approval code drafted for the withdrawn answer (migration `…000520`, `QuoteDiscountApproval*`) was
never committed and never ran outside throwaway Testcontainers databases; it was deleted from the working tree.
P1 is unaffected: this does not let Sales edit a customer's price list.

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

## Questions without an answer in the documents

| # | Question | Why it matters | Default used until answered |
|---|---|---|---|
| P1 | **Who edits a customer's price list?** 13/14 say E-commerce Admin; 18's input table names "Sale + người duyệt hạn mức". Sales alone, Sales with an approver, or admin only? | write permission | E-commerce Admin (and System Admin) write; Sales Staff and Order Coordinator read |
| P3 | Does a customer price list have **validity dates** (from/until), or is the current list simply the price? | effective-dated history | no dates: the current list applies; every change is versioned and audited |
| P4 | **Below the lowest tier** of a customer's list: refuse (the list's first tier is that customer's MOQ, BR-05) or fall back to the group/base price? | small orders | refuse with `CUSTOMER_PRICE_BELOW_MOQ` — the same rule quotes use |
| P5 | Tier when one order has **several lines of the same SKU**: by line or by the SKU's total? | tier choice | by the SKU's total in the order |
| P6 | **Sales units** (thùng = 1,000 cái, 13/14 BR-11, marked ⚠️ assumption): needed now? | price per unit | prices are per SKU unit (no sales-unit model exists anywhere yet) |
| P7 | **Group (segment) prices**: who manages them? There is no screen/API for segment rules. | fallback source | resolution reads an active segment rule if one exists; no management API in this lane |
| T1 | A household business that later supplies a **12-digit personal ID** as its tax identifier: accept it? The company's tax-code rule is unchanged (10 or 10-3). | onboarding follow-up | identifier stays optional; when given, the existing 10 / 10-3 rule applies (a 12-digit value is refused) |

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

## SCRUM-429 follow-up — evidence (commit `aca7f05`)

| Decision | Code | Test |
|---|---|---|
| no tax code needed to create, activate, order | `CreateBusinessCustomerRequest`, `Customer.onboard`, `CustomerOnboardingService.create` | IT `aShopWithoutATaxCodeIsOnboardedActivatesAndOrdersOnItsOwn` (real HTTP order, MST added later) |
| reopen a closed customer only with a reason, audited, history | `Customer.reopen`, `CustomerServiceImpl.changeStatus`, `customer_status_history`, `GET /customers/{id}/status-history` | IT `aManagerReopens…` (no/blank reason 400, Sales 403, history 4 rows, audit), domain test, `CustomerStatusHistoryMigrationUpgradeTest`, QA H1–H4 |
| old sessions stay invalid | sessions revoked on closure, never restored | IT: token from before closure 401 after reopening |
| never-activated customer reopens to PENDING_ACTIVATION | `Customer.reopen(reason, portalActivated)` + `PortalAccountService.awaitingActivation` | IT `aClosedCustomerThatNeverActivatedReopensPending…` (resend, old link dead, activation) |
