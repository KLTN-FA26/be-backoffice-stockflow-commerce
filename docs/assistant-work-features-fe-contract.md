# Assistant work features - FE contract

The assistant's features that sit **inside the screens people already work in**: a button on the order, the chat, the
bank transaction, the quote, the carton; a work queue on the dashboard; a draft to edit and send. Why each exists, for
whom, and what was dropped is in `exports/danh-gia-lai-tro-ly-AI-2026-11-27.md` (owner's local copy) and
`docs/assistant-design.md`.

Every REST answer is the envelope `{success, data, errorCode, message}`; times are ISO-8601 UTC instants, dates
`yyyy-MM-dd`, money whole VND numbers. Base path `/api/v1/assistant`.

## 0. Rules every feature keeps

1. **Read-only.** Nothing here writes business data. A draft is returned to the user, who edits it and sends it through
   the screen's own action (ADR-0015). No endpoint posts a chat message, records a receipt or approves anything.
2. **The screen's permission, the screen's scope.** Each endpoint is guarded by the permission of the screen the button
   sits on (column "Permission"), with the same data scope (warehouse, own). A user who cannot open the screen gets 403;
   a document outside their scope is 404, as on the screen.
3. **Deterministic where it can be.** Lists, scores, timelines and reasons are worked out by code from the modules'
   `api`s. The language model is used only where language is the product (a draft, a summary), and its output goes
   through the same grounding check as a chat answer: `unverifiedCodes` lists codes and numbers the text states that
   no source carries; the UI shows them flagged.
4. **Every item links to its screen.** `href` is a back-office path (`/admin/orders/{id}`, ...), so the user can check
   the source in one click.
5. **Model unavailable is not an error of the feature.** A model-backed endpoint whose model is off or failing answers
   **503 `ASSISTANT_UNAVAILABLE`**; deterministic endpoints never call the model.
6. **Rate limits.** Model-backed endpoints share the assistant's per-user limit (429 `RATE_LIMITED`); deterministic ones
   have their own, generous one.
7. **Usage is counted** per feature key, role and day (section 5), never the content.

Common shapes:

```
Source   { kind: "ORDER"|"CONTRACT"|"INSTALLMENT"|"BANK_TRANSACTION"|"QUOTE"|"CARTON"|"PRODUCTION_ORDER"|"CHAT"|"RULE",
           label: string, href: string|null }
Draft    { text: string, sources: Source[], unverifiedCodes: string[], model: string, generatedAt: instant }
```

Every model-backed endpoint (1.2, 1.3, 2.2's reminder, 3.3's message) writes its `Draft` the same way: one model turn
told the business day (dates are written `dd/MM/yyyy`), handed to the next model when one fails before replying, asked
once more on the next model when one replies with nothing usable; `unverifiedCodes` from the facts and typed text the
model was given; the customer the model saw as `KH#1` and its contact people as `LH#1`, `LH#2`... named again in
`text`.

## 1. Sales and chat

### 1.1 Why is this order not moving - `GET /orders/{orderNumber}/next-steps`

Button "Vì sao đơn chưa đi?" on the order detail, and a chip in the chat's order card.
Permission `sales-orders:READ`, scope as the order list (a warehouse-limited caller finds only the orders of their
warehouses; anything else is 404, as on the list). A portal account is 403. Deterministic. Rate limit: 60 a minute per
user, its own bucket.

```
{ orderNumber, status, statusLabel,
  blockers: [{ code, waitingOn: "CUSTOMER"|"SALES"|"ACCOUNTANT"|"WAREHOUSE"|"PRODUCTION"|"CARRIER"|"SALES_MANAGER",
               title, detail, nextStep, batchSeq|null, amountDue|null, href|null }],
  notChecked: ["HANDOVER"|"PRODUCTION_ORDERS"|"STOCK_HELD"|"INSTALLMENTS"],
  movingNormally: boolean,
  blockersTruncated: boolean }
```

`title`, `detail` and `nextStep` are sentences from the message bundle in the request's language (`Accept-Language`,
Vietnamese by default), never model text. `notChecked` lists the checks the user's permissions do not reach, or that
failed: show "không kiểm được phần này", never "không có gì chặn".

- `code`: `CONTRACT_NOT_SIGNED` (DRAFT: waits on SALES to send it; PENDING_CONTRACT: on the CUSTOMER to sign),
  `SIGNING_INSTALLMENT_UNPAID` (CUSTOMER), `ON_HOLD` (by hold reason: CREDIT → ACCOUNTANT, href
  `/admin/orders/credit-holds`; FRAUD → SALES_MANAGER; INVENTORY → WAREHOUSE; CUSTOMER → CUSTOMER; the rest → SALES),
  `INSTALLMENT_DUE` (CUSTOMER; `amountDue` the batch's figure from the handover check), `DELIVERY_WINDOW_NOT_OPEN`
  (SALES: only Sales may move a window, with the customer's consent), `HANDOVER_REFUSED` (WAREHOUSE),
  `NO_SHIPPING_WAREHOUSE` (SALES), `STOCK_NOT_HELD` and `PRODUCTION_NOT_RECEIVED` (PRODUCTION), `AWAITING_RELEASE`
  (WAREHOUSE: every carton of the batch the release would take next - the whole order without a schedule - is held,
  and only the warehouse manager's release moves it on; for a CONFIRMED or AWAITING_STOCK order, or a
  PARTIALLY_FULFILLED one whose next batch may go because the batch before it was handed over; `batchSeq` that batch),
  `AWAITING_PICK` (WAREHOUSE: a READY_TO_FULFILL order waits for its pick task to be opened and picked; for a caller who
  may open pick lists, `detail` says the task's status when it has one). The same reasons, from the same code, as the
  chat's `findOrder`.
- `ON_HOLD` for `DELIVERY_FAILED`: nothing is delivered again on the order; the next step is Sales cancelling what was
  not delivered (the delivery failure case), and goods the customer still wants go on a new order.
- The handover check gives **every** reason a batch may not leave (`HandoverBlock.reasonCodes`): a batch both unpaid
  and before its window gets two blockers, because paying alone would not release it.
- `amountDue` is null for a caller without `payment-installments:READ`; then `INSTALLMENTS` is in `notChecked` (the
  reason is shown, its amount is not). The amount due on signing is not given (`SIGNING_INSTALLMENT_UNPAID` has no
  `amountDue`): no `api` gives that figure alone, and the standing's total would overstate it.
- `movingNormally` is true only for a live order with no blocker and nothing in `notChecked`. An order no longer live
  (handed over whole, delivered, cancelled, closed) has no blockers and `movingNormally: false`: show its
  `statusLabel`.
- `href` is the order's page (`/admin/orders/{orderId}`), or the credit holds page for a CREDIT hold.

### 1.2 Reply draft for the customer - `POST /chat/conversations/{conversationId}/reply-draft`

Button "Soạn trả lời" in the staff chat. Permission `chat-conversations:READ` (the conversation must be one the user
may open: any back-office holder, as the staff chat screen; a portal account is 403; unknown is 404
`CHAT_CONVERSATION_NOT_FOUND`). Model-backed (shares the assistant's 10 a minute). Body `{ instruction?: string(≤300) }`
("nhắc khách hợp đồng chưa ký", ...); the body may be omitted.

Reads the last 20 messages of the conversation **as the customer saw them** (no internal note, no staff-only line) and
the orders, quotes and contracts it refers to (its contexts - a card's document is one - the order it was opened from,
and order numbers typed in it), each checked to be this conversation's customer's; at most 5 of each. It drafts a reply
**to the customer** in Vietnamese. What the model is given of an order: number, status, total, the delivery batches as
the customer's portal shows them (window and state; only for a caller with `sales-order-delivery-batches:READ`), the
shipments with carrier and tracking code (only for a caller with `sales-orders:READ`), and the open instalments with
amount still owed and due date (as the chat's customer panel shows them). A draft order is left out (the customer does
not see it yet); a quote the customer's portal does not show (DRAFT, or CANCELLED - possibly never sent) gives its
number and status, never its prices. It is never given hold notes or reasons, cancellation reasons, cost or margin, or
any staff name. The customer is sent to the model as `KH#1` wherever one of its names appears in the messages - its
registered name, its legal profile's company name, or either without its legal form ("bên Minh Phát"), however cased
or accented (a one-word short name only when capitalised) - and its contact people (legal representative, bank account
holder, the owner of its portal account) by full name as `LH#1`, `LH#2`...; the names are put back in `text`. A given
name alone ("chị Lan") is not recognised.

```
{ draft: Draft, orderNumbers: string[], language: "vi" }
```

`draft.sources`: one `ORDER` (`/admin/orders/{id}`), `INSTALLMENT` (the order's page), `QUOTE`
(`/admin/quotes/{id}`), `CONTRACT` (href null) per document given, and the `CHAT` itself
(`/admin/chat/{conversationId}`). Nothing is posted: the user edits `text` and sends it through the chat.

### 1.3 Conversation summary - `POST /chat/conversations/{conversationId}/summary`

Button "Tóm tắt" for a conversation handed over or escalated. Permission `chat-conversations:READ`, same access as
1.2. Model-backed. Reads the last 50 messages **including internal notes** (the summary is for staff), and the
numbers and statuses of the documents it is about.

```
{ summary: Draft, customerAsks: string[], promisesMade: string[], openQuestions: string[], orderNumbers: string[] }
```

Each list has at most 8 items. A model reply that is not the expected JSON is kept whole as `summary.text` with empty
lists. `summary.sources` starts with the `CHAT`. The conversation's customer and its contacts are pseudonymised as in
1.2. **Not covered:** another customer a staff note names reaches the model by name - nothing tells a name from other
words in free text without a guess, so keep other customers' names out of internal notes.

### 1.4 Price review before approving a quote - `GET /quotes/{quoteId}/price-review`

Panel on the quote price approval screen. Permission `sales-quote-price-approvals:APPROVE`: that resource has only
APPROVE (a `READ` would be refused at start-up as an action the resource does not list), and it is exactly who sees the
approval screen (Sales Manager, System Admin). A portal account is 403; an unknown quote 404. Deterministic.

```
{ quoteId, quoteNumber, quoteStatus, customer: { id, name, code },
  lines: [{ lineNo, sku, cartons, unitsPerCarton, quotedUnitPrice|null, customerPrice|null, standardPrice|null,
            lastSoldUnitPrice|null, lastSoldOn|null, lastSoldOrderNumber|null,
            deltaVsReference: number|null (percent),
            reference: "CUSTOMER_PRICE"|"STANDARD_PRICE"|"LAST_SOLD"|null,
            flags: ["BELOW_REFERENCE"|"NO_REFERENCE"|"BELOW_LAST_SOLD"|"NOT_PRICED"|"LAST_SOLD_OTHER_PACK"] }],
  credit: { limit|null, outstanding, overdue, available|null, oldestOverdueDueDate|null, currency } | null,
  flags: ["LINES_BELOW_REFERENCE"|"LINES_WITHOUT_REFERENCE"|"LINES_BELOW_LAST_SOLD"|"CREDIT_OVERDUE"] }
```

- Every price is per piece, read **at the line's proposed cartons** (a price list kept in pieces is read at its
  pieces). `reference` is the customer's price there, else the standard price of the SKU's variant, else the last price
  sold. `deltaVsReference = (quoted - reference) / reference × 100`, two decimals; **negative is below**.
- `lastSoldUnitPrice` is the customer's latest order line of that SKU whose contract was signed (order not cancelled);
  `lastSoldOn` is the signing day. `LAST_SOLD_OTHER_PACK`: that line had another carton count.
- The quote's own price check, shown on the quote, measures each price break at its start and is what decides whether
  approval is needed; this panel does not replace it. What it adds is the last price sold and the credit.
- `credit` is null when the user may not read the customer's credit (`customer-credit-profiles:READ`, the credit
  position screen's). **In the seeded role matrix the Sales Manager does not hold it**, so the approver sees null until
  the owner grants it (open question).

## 2. Money

### 2.1 Match a bank transfer - `GET /bank-transactions/{id}/match-suggestions`

Panel on a bank transaction `NEEDS_REVIEW`. Permission `payment-bank-transactions:READ` (back office only).
Deterministic.

```
{ transaction: { id, status, amount, transactionAt, content|null, reviewReason|null },
  suggestions: [{ installmentId, contractNumber, orderNumber, customerName|null, label, transferReference, amountDue,
                  dueDate|null, score: 0..100, reasons: ["CONTRACT_NUMBER_IN_CONTENT"|"ORDER_NUMBER_IN_CONTENT"|
                  "INSTALLMENT_IN_CONTENT"|"TAX_CODE_IN_CONTENT"|"CUSTOMER_NAME_IN_CONTENT"|"AMOUNT_EXACT"|
                  "AMOUNT_WITHIN_DUE"|"AMOUNT_ABOVE_DUE"|"OVERDUE"|"DUE_SOON"],
                  href }],
  ambiguous }
```

At most 3, best first; empty when nothing scores at least 30, and always empty when `status` is not `NEEDS_REVIEW`
(applied or ignored already). The accountant still records the receipt on the transfer screen; this only says where
to look. 404 `BANK_TRANSACTION_NOT_FOUND`.

- `transactionAt` is when the bank booked the transfer (the money came in), not when the feed received it.
- Candidates are the installments a receipt may still pay (not paid, not cancelled), `amountDue` what each still owes:
  the payment module's own figures, the ones the bank feed compares a transfer with.
- Weights, added and capped at 100: the content names this installment as the bank feed reads it 60, its contract
  whole 45, else its order number 40 (dashes, spaces, case and a bank's digits glued after `SO...` tolerated);
  `INSTALLMENT_IN_CONTENT` (`DOT2`, `đợt 2`, `FEE1` beside an order or contract number) 15; tax code 35; customer
  name without its legal form ("Công ty TNHH ...", accents aside) 25; exact amount 30, less than it owes 10, more than
  it owes -10 (`AMOUNT_ABOVE_DUE`: a receipt refuses more than the installment owes, 15 BR-05 - the feed's OVERPAYMENT
  is exactly this, so the right installment can be the lead and still not take the amount as it is); overdue or due
  within 7 days 5; never below 0.
- `ambiguous`: the best two score the same (e.g. several customers' installments owing exactly the amount, nothing else
  in the content). "Best first" is then no preference: show it, e.g. "khớp ngang nhau - kiểm tra khách hàng".
- `label` is the installment's name in the plan; `transferReference` is what the content should have said.
- `href` `/admin/payment/installments/{installmentId}`.

### 2.2 Collections - `GET /collections` and `POST /collections/{installmentId}/reminder-draft`

Accountant's and Sales' list of installments overdue or due within 7 days, the ones to chase first on top.
Permission `payment-installments:READ`, back office only (a portal customer holds it for its own installments: 403).
The list is deterministic; the reminder draft is model-backed.

```
GET  -> { items: [{ installmentId, contractNumber, orderNumber, customer: {id, name|null},
                    salesperson: {id, name|null}|null, label, amountDue, dueDate, daysOverdue,
                    priority: "HIGH"|"MEDIUM"|"LOW", why: string[], href }],
          total, truncated }
POST -> { draft: Draft }     body { tone?: "GENTLE"|"FIRM" }   (absent or empty body: GENTLE)
```

- Overdue is the installment's flag (past its due day and still owing), never a status. An installment before
  delivery has no due day and is never listed.
- Priority points: days overdue 1-7 → 20, 8-30 → 30, 31-60 → 40, more → 50 (due soon: 0); still owes ≥ 200,000,000 →
  20, ≥ 50,000,000 → 10; the customer owes other overdue money → 15; the customer's exposure after delivery is over
  its credit limit → 15. HIGH from 50, MEDIUM from 25. `why` says each of those facts in the caller's language
  (Vietnamese sentences from the message bundle, e.g. "Quá hạn 12 ngày (hạn 15/11/2026)").
- `salesperson` is who sent the contract, else who recorded it signed; null when neither is known.
- At most 50 items, ordered by points, then days overdue, then amount; `total` counts every installment overdue or due
  within 7 days, `truncated` says items shows fewer.
- `href` `/admin/payment/installments/{installmentId}`.
- The reminder names the contract, the installment, what it still owes, its due date (how late, when overdue) and the
  transfer content to use (`HD-... DOT2`, so the payment matches itself), nothing else: no hold note, no credit
  figure, no other order, no staff name. The customer is `KH#1` to the model and named in the returned text. Sources:
  CONTRACT (`href` null: no contract page), ORDER (`/admin/orders/{orderId}`), INSTALLMENT
  (`/admin/payment/installments/{id}`). 404
  `INSTALLMENT_NOT_FOUND`; 409 `INSTALLMENT_NOT_PAYABLE` (paid or cancelled); 409 `INSTALLMENT_NOT_DUE_SOON` for one
  the list would not offer - no due date yet (before delivery, or after a delivery not made) or due more than 7 days
  ahead: a letter would ask for money not owed yet; 400 `VALIDATION_FAILED` for another tone; 503
  `ASSISTANT_UNAVAILABLE`; 429 `RATE_LIMITED` (the assistant's question bucket).
- The two deterministic endpoints share a bucket of their own, 60 calls a minute per user.

### 2.3 Customer price with the standard price

The chat tool `getCustomerPrice` answers with the variant's standard price when the customer has no own price for the
SKU (kltn-docs 13 BR-04 as of 2026-11-26), saying which one it is: `priceKind` `CUSTOMER_PRICE` or `STANDARD_PRICE`,
and `standardPriceOf` the variant whose standard price it is. The order is the quote's: the customer's own price, else
the standard price of the variant the SKU was drawn on, else none. Another customer's SKU gets no price at all. Every
price counts whole cartons (`measure` CARTON).

## 3. Warehouse and production

### 3.1 Work queue - `GET /work-queue?warehouse={prefix}`

Dashboard widget "Việc cần xử lý" for every role, and the warehouse manager's shift brief (`warehouse` given).
Permission `assistant-conversations:READ` (own); each section appears only when the user holds the permission of the
screen it counts, and is read under that screen's data scope (a warehouse-bound user's sections are their warehouses').
Deterministic, no model. `/today` stays for the assistant panel. Own rate limit (30 a minute per user).

```
{ generatedAt, warehouse|null,
  sections: [{ key, title, count|null, severity: "INFO"|"WARN"|"CRITICAL", href, failed,
               items: [{ label, detail|null, at|null, href|null }]   (at most 5) }] }
```

| key | screen permission | what is counted (the screen's own list) | warehouse-bound |
|---|---|---|---|
| `heldOrders` | `sales-orders:READ` | orders ON_HOLD, oldest first; detail = hold reason, customer | yes |
| `awaitingPayment` | `sales-orders:READ` | orders PENDING_PAYMENT | yes |
| `awaitingStock` | `sales-orders:READ` | orders AWAITING_STOCK | yes |
| `batchesDueToday` | `sales-order-delivery-batches:READ` **and** `sales-order-releases:UPDATE` | delivery batches whose window includes today, of an order still owing deliveries, with no shipment handed over for the batch (a batch partly shipped is not counted) | yes |
| `receiptsToPutAway` | `procurement-goods-receipts:READ` **and** `warehouse-putaway-tasks:UPDATE` | goods receipts IN_PUTAWAY | yes |
| `pendingAdjustments` | `inventory-stock-adjustments:READ` **and** `:APPROVE` | adjustments PENDING_APPROVAL (blind count as on the screen) | yes |
| `bankTransactionsToReview` | `payment-bank-transactions:READ` | transfers NEEDS_REVIEW | no |
| `overdueInstallments` | `payment-installments:READ` | installments overdue (past due day, still owed) | no |
| `creditReviewsPending` | `sales-order-credit-holds:READ` **and** `:APPROVE` | contracts waiting for accounting's credit decision | no |
| `quotesAwaitingPriceApproval` | `sales-quotes:READ` **and** `sales-quote-price-approvals:APPROVE` | draft quotes whose prices wait for the Sales Manager | no |
| `productionOrdersLate` | `production-orders:READ` **and** `:UPDATE` | production orders with the producer and LATE, PRODUCER_OVERDUE or AT_RISK (3.3's rules) | yes |
| `einvoicesToIssue` | `einvoices:READ` | e-invoices DRAFT, SUBMITTING or REJECTED (item href null: the page is by an id the list does not give) | no |

- `warehouse` (a code prefix, any case) keeps the warehouse-bound sections, narrowed to it, and **leaves the others
  out** (they are company-wide and would read as that warehouse's). An unknown prefix, or a warehouse the user is not
  assigned to, is **404 `WAREHOUSE_NOT_FOUND`**.
- `severity`: INFO when the count is 0 or the queue is informational (awaiting payment/stock, receipts to put away,
  e-invoices); WARN when someone has to act; CRITICAL when a batch due ends today, a production order is past its
  need-by day, or an e-invoice is past its deadline to be issued.
- `failed: true` (count null, no items): that section could not be read and the error is logged; the others answer.
- The two approval queues need the acting permission besides the list's: a WARN "to approve" for someone who cannot
  approve (Sales Staff read both lists) is a call to act they cannot answer.
- `batchesDueToday` has no screen of its own: its `href` is the order list, each item opens its order. Only the
  order's live contract counts: a contract cancelled before signing keeps its batches, which nobody owes.
- `productionOrdersLate` has `href` null: no screen lists that set (the production schedule's delay alerts are a
  different one); each item opens its production order.
- `href`s are the FE's routes (`/admin/orders?status=...`, `/admin/receipts?status=IN_PUTAWAY`, ...), listed in
  `WorkLinks`; null where no screen lists the section's set. Amounts in `detail` and `label` are whole VND, grouped
  ("50.000.000 VND").

A morning digest of the same sections is posted to each user's inbox at `stockflow.assistant.digest.cron` (default
07:30 Mon-Sat), off unless `stockflow.assistant.digest.enabled=true`: one message per user per day (dedupe key
`assistant:work-queue:{date}`), section titles and counts only, nothing when nothing waits; read as the user
(`RunAs`), so a warehouse manager hears about their warehouses only. Link: `/admin`.

### 3.2 Missing carton investigation - `GET /cartons/{lpn}/investigation`

Button "Tìm thùng này" on a short pick line, a count line found missing, or the carton lookup. Permission
`inventory-stock-packages:READ`, warehouse scope (a carton of another warehouse is **403 `OUT_OF_DATA_SCOPE`**, as the
carton lookup answers; an unknown LPN **404 `STOCK_PACKAGE_NOT_FOUND`**). Deterministic. Also the chat tool
`investigateCarton`.

```
{ lpn, sku, status, stockStatus|null, locationCode|null, warehouseCode, lotNumber|null, receivedAt|null,
  timeline: [{ at, movementType, from|null, to|null, reference|null, by|null }]   (newest first, at most 30),
  timelineTotal|null, timelineWithheld: null|"BLIND_COUNT"|"NO_PERMISSION",
  openWork: [{ kind: "PICK_LINE"|"CARTON_CHECK"|"PUTAWAY_TASK"|"COUNT_LINE"|"ADJUSTMENT"|"RETURN_RECEIPT",
               reference, status|null, href }],
  whereToLook: [{ locationCode, why: "LAST_RECORDED"|"PUTAWAY_TARGET"|"STAGING_AREA"|"QUARANTINE"|"NEIGHBOUR_BIN"|
                  "RECEIVING_AREA", detail }] }
```

- `timeline` is the stock ledger's: shown only with `inventory-stock-movements:READ` (else empty,
  `timelineWithheld: NO_PERMISSION`). For a counter who may not see the books of a carton a count holds it is empty
  with `BLIND_COUNT`, and the carton's pending adjustment is not listed (kltn-docs 20 BR-07). Whenever
  `timelineWithheld` is set, `timelineTotal` is null (never 0, which would read as "never moved"). The blind count
  covers the ledger and the adjustment only: status and location are shown, as the carton lookup shows them. `reference` is what the
  line was written for (PICK_LINE, GOODS_RECEIPT...), `by` the person's display name.
- `openWork`: ADJUSTMENT only with `inventory-stock-adjustments:READ`, RETURN_RECEIPT only with
  `procurement-goods-receipts:READ` (the other kinds are what the carton lookup already shows). `reference` is a
  document number where there is one (count, adjustment, return receipt), else the task id. `CARTON_CHECK` was added:
  a carton in an open carton check is open work too.
- `whereToLook` is ranked by fixed rules, each place once: the books' place; the bin a putaway trip assigned it; the
  staging area of its pick task (when that task sends cartons to staging and the user may read the task); a quarantine
  area (its return receipt's, or the warehouse's for stock in QUARANTINE/DAMAGED not standing in one); the existing bins
  beside the last bin (next bin on the level, same bin a level up and down, by the map's codes); the receiving areas
  when it is still being received or nothing moved it since its receipt. `detail` is in the user's language.

### 3.3 Production follow-ups - `GET /production-follow-ups` and `POST /production-follow-ups/{id}/message-draft`

The Production Coordinator's list of production orders to chase, the most urgent first, with the delivery batch and
customer each holds up. Permission `production-orders:READ`, under the production orders screen's warehouse rule.

```
GET  -> { today, total, truncated,
          items: [{ productionOrderId, code, producer, status, reason, expectedCompletion|null, neededBy|null,
                    daysLate|null, cartons, receivedCartons,
                    affects: [{ orderNumber|null, batchSeq|null, customerName|null, windowStart|null, windowEnd|null }],
                    priority: "HIGH"|"MEDIUM"|"LOW", href }] }      (at most 50)
POST -> { draft: Draft }
```

- On the list: production orders with the producer (SENT, ACCEPTED, SAMPLE_PENDING, IN_PRODUCTION,
  PARTIALLY_RECEIVED) that are `LATE` (need-by day passed; `daysLate` from it), `PRODUCER_OVERDUE` (the producer's
  expected day passed; `daysLate` from it), `AT_RISK` (expected day after the need-by day) or `DUE_SOON` (expected day,
  or need-by day without one, within 3 days). Priority HIGH, HIGH, MEDIUM, LOW respectively. The delay alerts' rules,
  read on today's dates rather than on alerts recomputed overnight.
- `affects` is the one batch it was drawn up for (one production order per batch). `orderNumber` and `customerName`
  only for a user with `sales-orders:READ`, as the order list names them.
- The draft needs `production-orders:UPDATE` (the production order's send, acceptance and progress actions: the
  Production Coordinator's); Sales and warehouse managers who read the list get 403 - show the button only with it.
- The draft is to the **producer**: built from the production order's code, status, SKUs with cartons ordered and
  received, dates (`dd/MM/yyyy`) and the latest print sample round - never a customer, an order number or a price. 404
  `PRODUCTION_ORDER_NOT_FOUND` (also another warehouse's), 409 `PRODUCTION_ORDER_STATUS_INVALID` for one not with its
  producer, 503 `ASSISTANT_UNAVAILABLE`, 429 sharing the questions' limit. `sources` holds the production order.

## 4. Help when the system says no

### 4.1 Why was I refused - `GET /error-explanations/{errorCode}`

Link "Vì sao?" next to a business error message. Permission `assistant-conversations:READ`. Deterministic.

```
{ errorCode, message, rules: [{ ruleId, chapter, title, text, href|null }], nextSteps: string[] }
```

- `message` is the code's message in the caller's language (`Accept-Language`), as the refusal showed it; `rules` and
  `nextSteps` are Vietnamese.
- `rules` come from the kltn-docs snapshot the assistant indexes (`ruleId` "15 BR-08", `chapter` the snapshot name
  "15-payment", `title` the rule's id, name and chapter, `text` the whole rule, plain). `href` is always null: no
  back-office screen shows the analysis.
- The rules and next steps come from a curated map (`src/main/resources/assistant/error-rules.yml`, 56 refusals
  people meet in daily work: holds and handover, contracts and credit, installments and receipts, cartons and counts,
  quotes and prices, production orders, permission and warehouse scope). A code it does not hold answers its
  `message` with `rules: []` and `nextSteps: []`: show the message alone, never "no rule applies". A value that is no
  error code is 404 `NOT_FOUND`.
- The explanation is about the code, not the record refused (the refusal's own `message` and `details` say which
  record and why); it reveals no data, hence the panel's permission.
- Shares a rate limit of 60 a minute per user with the other deterministic assistant reads (bucket `assistant-reads`).

## 5. Making the assistant better over time

### 5.1 Answers marked wrong - `GET /feedback?verdict=DOWN&from&to&page&size`

For the administrator: answers users marked 👎, with the question, the answer, the tools and the comment, to review and
turn into golden-set cases. Permission `assistant-feedback:READ` (System Admin only). Deterministic.

`verdict` `DOWN` (default) or `UP`; `from`, `to` business days `yyyy-MM-dd` (Vietnam time), both included, by when the
verdict was last given, default the last 30 days; `size` at most 50. A page (`PageResponse`) of:

```
{ feedbackId, messageId, verdict, comment|null, givenAt,
  raterRoles: string[], raterWarehouses: string[],
  question|null, answer, answeredAt, model,
  tools: [{ step, tool, arguments: object, outcome }],
  goldenCase: object }
```

- Text is as stored: customers appear as the aliases the model saw (`KH-1`), except a customer the conversation had
  not met when the question named it, and anything in the comment, which are as typed: read them before committing a
  case. Who rated is shown by role and
  warehouses, never by name; both lists are empty for a verdict given before roles were recorded (27/11).
- `tools[].step` is the model turn that asked for the call: calls with one step were asked for together.
- `goldenCase` is the item as a case of `src/test/resources/assistant/golden-set.json`, ready to paste: persona from the
  rater's first role and warehouses (`TODO` when unknown), `expectTools` the tools that answered OK (`expectNoTools`
  when none was called), `oracle` the calls by step (`together` for calls of one step); `useCase` is `TODO` and `facts`
  is empty, for the reviewer to fill with what a right answer holds.
- A verdict leaves with its conversation when the retention job deletes it (90 days idle).

### 5.2 Usage - `GET /usage?from&to`

Counts per feature key, role and day. Permission `assistant-usage:READ` (System Admin, Sales Manager). Deterministic.

```
{ from, to, featureKeys: string[], rows: [{ day, featureKey, role, uses }] }
```

- `from`, `to` business days, both included; default the last 30 days; at most 92 days (400 `VALIDATION_FAILED`).
- A row is a day, a feature and a role with at least one use; `featureKeys` lists every key, so a key with no row was
  not used. `role` is every role the user holds, `+`-joined in a fixed order (`SALES_STAFF+SALES_MANAGER`), `OTHER` for
  a role created at run time: one use is counted once.
- Counts reach the endpoint up to a minute after the use (kept in memory, written about once a minute). No user, no
  content is stored. Kept 400 days.
- Feature keys: `chat` (a question answered in the panel), `today` (the "today" card), `next-steps`, `reply-draft`,
  `chat-summary`, `price-review`, `match-suggestions`, `collections`, `reminder-draft`, `work-queue`,
  `carton-investigation`, `production-follow-ups`, `producer-message-draft`, `error-explanation`. Each endpoint counts
  its own key after it answered (`AssistantTelemetry.featureUsed`), so a refused or failed call is not a use.
