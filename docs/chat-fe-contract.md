# Sales chat (F-13) - FE contract

kltn-docs 16. Packages 2 (foundation) and 3 (cards and operations). Every REST answer is the envelope
`{success, data, errorCode, message}`; times are ISO-8601 UTC instants, dates `yyyy-MM-dd`, money whole VND numbers.

## 1. REST - customer (portal)

Base `/api/v1/chat/me/conversations`. The customer is the signed-in account's customer; another customer's
conversation, message or card answers **404**.

| Method | Path | Body / params | Answer |
|---|---|---|---|
| POST | `` | `{subject?, contextKind?, contextId?, text?, clientMessageId?}` | conversation (opened, or the open one about the same document reused). 404 `CHAT_CONTEXT_NOT_FOUND` for a document not theirs |
| GET | `` | `contextKind?, contextId?, page, size` | page of conversations, closed ones included |
| GET | `/{id}` | | conversation (detail) |
| GET | `/{id}/messages` | `before?` (seq, newest first) or `since?` (seq, oldest first, catch-up), `limit?` | `{items, nextBefore, nextSince, lastSeq}`; never an internal note |
| POST | `/{id}/messages` | `{text, clientMessageId?}` | message. 409 `CHAT_CONVERSATION_ENDED`, `CHAT_CLIENT_MESSAGE_ID_REUSED`. Rate limit 60/min |
| POST | `/{id}/attachments` | multipart `file` (JPEG, PNG, WebP, PDF, <= 20 MB), `caption?`, `clientMessageId?` | message |
| GET | `/{id}/attachments/{attachmentId}/link` | | `{url, expiresAt}` |
| POST | `/{id}/read` | `{seq?}` | read marker |
| POST | `/{id}/reopen` | | the conversation (REOPENED in the window, else a new one with `continuedFromId`) |
| **POST** | `/{id}/cards/{messageId}/actions` | see §4 | the card message (updated). Rate limit 30/min |
| **POST** | `/{id}/csat` | `{score: 1-5, comment?}` | conversation. 409 `CHAT_CSAT_NOT_OPEN` (not resolved), `CHAT_CSAT_ALREADY_GIVEN` |
| **PATCH** | `/{id}/messages/{messageId}` | `{text}` | message (`editedAt` set). Own text only, within 5 min |
| **DELETE** | `/{id}/messages/{messageId}` | | message (`deleted: true`, body and files hidden). Own text/file only, within 5 min |
| **GET** | `/unread-count` | | `{conversations, messages}` - the badge |

## 2. REST - staff (back office)

Base `/api/v1/chat`. A portal account is refused (**403**) on all of these.

| Method | Path | Body / params | Answer |
|---|---|---|---|
| GET | `/conversations` | `status*, mine, unassigned, overdue, customerId, contextKind, contextId, page, size` | queue page, the overdue first. `overdue=true`: only those whose first answer **or resolution** is late (BR-09) |
| POST | `/conversations` | `{customerId, subject?, contextKind?, contextId?, text?, clientMessageId?}` | conversation, assigned to the caller |
| GET | `/conversations/{id}` | | detail with `nextStatuses` |
| GET | `/conversations/{id}/messages` | as the customer's | internal notes included |
| POST | `/conversations/{id}/messages` | `{text?, clientMessageId?, contextId?, cannedResponseId?}` | message. With `cannedResponseId` the saved reply is sent, its variables filled by the server (needs `chat-canned-responses:READ`). 400 when the text spells the company's account number (send the transfer card instead, BR-06). 409 `CHAT_NOT_ASSIGNEE` |
| POST | `/conversations/{id}/notes` | `{text, mentions?: [userId], clientMessageId?, contextId?}` | internal note (STAFF only) |
| POST | `/conversations/{id}/attachments` | multipart | message |
| GET | `/conversations/{id}/attachments/{aid}/link` | | link |
| POST | `/conversations/{id}/read` | `{seq?}` | marker |
| POST | `/conversations/{id}/claim` | | detail. 409 `CHAT_ALREADY_ASSIGNED` |
| POST | `/conversations/{id}/assignment` | `{agentId, note?}` | detail. 409 `CHAT_AGENT_NOT_AVAILABLE` |
| POST | `/conversations/{id}/status` | `{status, reason?}` - WAITING_ON_CUSTOMER, WAITING_ON_INTERNAL, ESCALATED (reason), RESOLVED, ACTIVE | detail. RESOLVED posts the CSAT survey card |
| POST | `/conversations/{id}/contexts` | `{kind, id}` | detail |
| GET | `/conversations/{id}/events` | | the BR-01 log |
| GET | `/agents` | | `[{id, name, online}]` |
| GET | `/customers/{customerId}/overview` | | customer panel |
| **POST** | `/conversations/{id}/cards/{messageId}/actions` | `{action: "CREATE_QUOTE"}` | the card (ACTED, `payload.quoteId`). 409 `CHAT_DESIGN_NOT_APPROVED`, `CHAT_CARD_ACTION_NOT_ALLOWED` (any customer action) |
| **POST** | `/conversations/{id}/cards/transfer-details` | `{installmentId, reminder?: bool}` | PAYMENT_INFO (or PAYMENT_REMINDER) card. 404 `CHAT_CONTEXT_NOT_FOUND` for another customer's installment, 409 for one paid/cancelled |
| **PATCH** | `/conversations/{id}/messages/{messageId}` | `{text}` | own text or note, within 5 min |
| **DELETE** | `/conversations/{id}/messages/{messageId}` | | own text, note or file, within 5 min |
| **GET** | `/search` | `q` (2-200 chars), `customerId?, conversationId?, page, size` | page of `{message, conversationId, customerId, customerName, subject, conversationStatus}`; accents ignored, notes included. Rate limit 30/min |
| **GET** | `/unread-count` | | `{conversations, messages, queued}` - unread in the caller's conversations, and the unassigned queue |
| **GET** | `/conversations/{id}/canned-responses/{cannedId}/preview` | | `{cannedResponseId, conversationId, text}` |
| **GET** | `/canned-responses` | `q?, category?` | own + shared, shared first |
| **POST** | `/canned-responses` | `{title, body, shortcut?, category?, shared?}` | saved reply. `shared: true` is a chat supervisor's (403 otherwise) |
| **PUT** | `/canned-responses/{id}` | same + `version?` | saved reply. 409 `OPTIMISTIC_LOCK` on a stale version |
| **DELETE** | `/canned-responses/{id}` | | archived |
| **GET** | `/reports/agents` | `from, to` (dates, <= 366 days), `agentId?` | `{from, to, total, agents: [...]}` - see §6. Needs `chat-supervision:READ` |

Saved-reply variables: `{customerName}`, `{agentName}`, `{quoteCode}`, `{orderCode}`, `{contractCode}`,
`{productionOrderCode}`, `{invoiceCode}`, `{designName}` (the latest document of that kind the conversation is about).
A known variable with no value becomes empty; an unknown `{name}` is left as written. Shortcut: `[a-z0-9][a-z0-9_-]{0,31}`.

## 3. Views

**Conversation** (`ChatConversationView`): `id, customerId, customerName, subject, status, nextStatuses (staff),
assignedAgentId, assignedAgentName, agentOnline, escalationReason (staff), firstResponseDueAt, firstRespondedAt,
firstResponseOverdue, resolutionDueAt (staff), resolutionOverdue (staff), reopenUntil, continuedFromId, createdAt,
lastMessageAt, lastSeq, unread, lastMessage, contexts, readMarkers (detail), withinBusinessHours, expectedResponseAt,
resolvedAt, canRate, csatScore, csatComment (staff), csatSubmittedAt`.
Show "Sale sẽ phản hồi lúc …" with `expectedResponseAt` when `withinBusinessHours` is false or no first answer came.
`resolutionDueAt` is when the conversation is to be RESOLVED (`resolution-target` working days after the customer asked,
counted again from a reopen); `resolutionOverdue` is true past it while the conversation goes on - mark the queue row
as for `firstResponseOverdue`. Both are null / false in the customer's view.

**Message** (`ChatMessageView`): `id, conversationId, seq, kind (TEXT|ATTACHMENT|CARD|SYSTEM|INTERNAL_NOTE),
visibility (CUSTOMER|STAFF), senderType (CUSTOMER|AGENT|SYSTEM), senderId, senderName, body, clientMessageId, contextId,
card, attachments, mentions, sentAt, editedAt, deleted`.
`card = {kind, state, payload, updatedAt, customerActions, staffActions}` - **draw exactly the buttons in
`customerActions` / `staffActions`**; both are empty once the card is not OPEN.

Text is masked before it is stored (BR-08): card numbers (13-19 digits, Luhn) keep their last 4 digits
(`**** **** **** 1111`), a CVV becomes `***`, whatever follows "password / mật khẩu / mk / pass / OTP" + `:`/`=`/"là"
becomes `********`.

## 4. Cards

`state`: `OPEN` (waiting for its action) · `ACTED` · `SUPERSEDED` (a newer card of the same document) · `EXPIRED` ·
`CANCELLED` · `CLOSED`. Cards are drawn by the system from the owning module's document; the chat changes no document
(BR-03) - an action calls that module, whose own error codes come back unchanged (`QUOTE_EXPIRED`,
`INVALID_QUOTE_TRANSITION`, `OPTIMISTIC_LOCK`, `SAMPLE_ROUND_ANSWERED`, `PRODUCTION_ORDER_STATUS_INVALID`...). A card
also updates when its document moves elsewhere (`message.updated` over STOMP).

Action body: `{action, version?, cartons?: {quoteLineId: n}, note?, score?, comment?}`.

| kind | posted when | payload (main fields) | actions |
|---|---|---|---|
| `QUOTE_REQUEST` | customer asks a quote on a design / with a submission / on a project (system) | `scope` DESIGN\|PROJECT, `requestId`; DESIGN: `designId, designName, designStatus, sku, customerSku, returnReason, cartons, desiredDeliveryDate, requestStatus`; PROJECT: `projectId, projectName, desiredDeliveryDate, lines[{designId, designName, designStatus, customerSku, cartons}]`; after the quote: `quoteId, quoteNumber` | staff `CREATE_QUOTE` (only when every design is APPROVED with its SKU) |
| `QUOTE` | Sales send a quote revision | `quoteId, number, status, revision, validUntil, requestedDeliveryDate, lines[{id, lineNo, sku, cartons, unitsPerCarton, units, unitPrice, lineTotal, acceptedCartons}], proposedTotal, acceptedTotal, convertedOrderId, convertedOrderNumber, sentAt, version, currency` | customer `ACCEPT` (`version`, `cartons`), `REQUEST_CHANGES` (`note` = feedback) while `status` SENT |
| `CONTRACT` | Sales send the contract | `contractId, number, orderId, orderNumber, status, totalAmount, currency, freeSampleRounds, paymentPlan[{seq, name, milestone, amount, percent, dueDays, deliveryBatchSeq}], sentAt, signedOn, documentPath` (GET it for a short link) | none. SIGNED -> ACTED, CANCELLED |
| `PAYMENT_INFO` / `PAYMENT_REMINDER` | an installment falls due (system) / Sales send transfer details (reminder for an overdue one) | `installmentId, contractId, contractNumber, orderId, orderNumber, seq, name, amount` (still owed), `installmentAmount, currency, dueDate, overdue, status, transferContent, reminder, bank{bankName, bankBin, accountNumber, accountName}` (null when not configured), `vietQr` (EMVCo/NAPAS string; draw it as a QR; null without a BIN) | none. Paid in full -> ACTED |
| `SAMPLE_APPROVAL` | a print sample round waits for the customer (system) | `roundId, roundNo, productionOrderId, productionOrderCode, productionOrderStatus, status` (WAITING\|APPROVED\|REPRINT_REQUESTED), `skus, media[{id, fileName, contentType, video}], physicalSampleSentOn, openedAt, answeredAt, earlier[{roundId, roundNo, result, answeredAt}], mediaPath` (replace `{mediaId}`) | customer `APPROVE`, `REQUEST_REPRINT` (`note` required) while WAITING. Never Sales (BR-10) |
| `INVOICE` | an e-invoice is issued (system) | `id, code, status, orderId, orderNumber, templateCode, symbol, invoiceNumber, issueDate, currency, subtotal, taxTotal, grandTotal, files{XML, PDF}` (GET for a short link) | none |
| `ORDER_STATUS` | a shipment is handed over, delivered, not delivered (system) | `orderId, orderNumber, shipmentId, shipmentCode, status, failureReasonCode, carrierName, packageCount, occurredAt` | none |
| `CSAT_SURVEY` | the conversation is resolved | `conversationId, scale` (5), `score` once rated | customer `RATE` (`score` 1-5, `comment?`) |

Where a card lands: the customer's open conversation about its document, else about a related one (an invoice's order,
an installment's contract), else their most recent open conversation, else a new conversation the system opens.

## 5. STOMP

WebSocket `/ws`, `Authorization: Bearer <jwt>` in CONNECT, heartbeats 10 s.

| Destination | Who | Carries |
|---|---|---|
| `/topic/chat.conversations.{id}` | the customer, staff | customer-visible messages, read markers, typing, conversation changes |
| `/topic/chat.conversations.{id}.staff` | staff | internal notes (and their edits) |
| `/topic/chat.inbox` | staff | queue rows changed |
| `/topic/chat.presence` | staff | agents online/away |
| `/user/queue/chat` | each user | their messages and assignments |
| send `/app/chat.conversations.{id}.typing` | both | `{typing}` (at most 1 per 2 s) |
| send `/app/chat.presence.heartbeat` | staff | keep-alive |

Envelope `{type, conversationId, data, at}`; `type`: `message.created`, `message.updated` (edit, delete, card state),
`message.read`, `typing`, `conversation.updated`, `presence`. Catch up after a lost socket with
`GET .../messages?since=<lastSeq>`.

A customer with no socket open is told of Sales' replies in the bell (category `CHAT`, link
`/chat/conversations/{id}`) once per conversation per hour, and by e-mail where customer mail is on. Supervisors get
`CHAT_SLA` (WARN) when a first answer is late, once per conversation, and when a conversation is past its resolution
target, once per due time (a reopened conversation's new due time may alert again). The log (`/events`) gets
`FIRST_RESPONSE_OVERDUE` / `RESOLUTION_OVERDUE` with `detail.dueAt`.

## 6. Report

`total` and each of `agents[]`: `agentId, agentName, conversations, answered, averageFirstResponseMinutes` (business
minutes), `firstResponsesOverdue, resolved, averageResolutionHours` (wall clock), `csatCount, csatAverage,
csatDistribution {"1".."5"}`.

## 7. Errors (chat)

404 `CHAT_CONVERSATION_NOT_FOUND`, `CHAT_CONTEXT_NOT_FOUND`, `CHAT_MESSAGE_NOT_FOUND`, `CHAT_CANNED_RESPONSE_NOT_FOUND` ·
409 `CHAT_TRANSITION_NOT_ALLOWED`, `CHAT_CONVERSATION_ENDED`, `CHAT_NOT_ASSIGNEE`, `CHAT_ALREADY_ASSIGNED`,
`CHAT_AGENT_NOT_AVAILABLE`, `CHAT_CLIENT_MESSAGE_ID_REUSED`, `CHAT_MESSAGE_NOT_EDITABLE`, `CHAT_EDIT_WINDOW_PASSED`,
`CHAT_CSAT_NOT_OPEN`, `CHAT_CSAT_ALREADY_GIVEN`, `CHAT_CARD_ACTION_NOT_ALLOWED`, `CHAT_DESIGN_NOT_APPROVED` ·
400 `VALIDATION_FAILED` · 403 `FORBIDDEN` · 429 rate limit.

## 8. Settings (`stockflow.chat.*`, env in brackets)

| Setting | Default |
|---|---|
| business hours | `Asia/Ho_Chi_Minh`, MONDAY-SATURDAY, 08:00-17:30 (`CHAT_BUSINESS_*`) |
| first-response target (business time) | `PT15M` |
| resolution target, working days of the business hours (`resolution-target`, `CHAT_RESOLUTION_TARGET`) | `P2D` (asked Saturday 17:00 -> due Tuesday 17:00) |
| auto-close after customer silence in WAITING_ON_CUSTOMER | `P7D` |
| reopen window after RESOLVED / AUTO_CLOSED, then CLOSED | `P14D` |
| edit/delete window | `PT5M` |
| offline customer notice period | `PT1H` |
| validity of a quote drawn up from a card | `P14D` |
| SLA/auto-close/close job interval | `PT1M` |
| attachments | JPEG, PNG, WebP, PDF, 20 MB |
| company bank account for transfer cards | `stockflow.payment.company-bank.*` (`COMPANY_BANK_NAME`, `COMPANY_BANK_ACCOUNT_NUMBER`, `COMPANY_BANK_ACCOUNT_NAME`, `COMPANY_BANK_BIN`) |
