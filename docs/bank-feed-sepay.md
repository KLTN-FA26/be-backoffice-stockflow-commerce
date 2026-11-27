# Bank feed: SePay

Credit plan of 2026-11-26, part C; kltn-docs 15 BR-02 and §4.2 step 6. SePay reads the company's bank account and
calls this system for every incoming transfer, so the Accountant does not type again what the accounting software
already pulled from the bank. This is not the e-invoice SePay integration (`EINVOICE_SEPAY_*`): that is a different
SePay product, with different keys.

## What happens to a transfer

```
SePay ──POST /api/v1/bank-transactions/webhooks/sepay──► kept as payment.bank_transaction (RECEIVED), answered {"success": true}
   │                                                        │
   │   content names exactly one installment, payable,      ├─► receipt recorded by "Bank feed (SePay)" ─► APPLIED
   │   for no more than it still owes, into our account     │     (PaymentReceiptRecorded: the order confirms, the handover gate opens)
   │                                                        └─► anything else ─► NEEDS_REVIEW (with a review reason)
   │                                                                │
nightly catch-up (01:40, GET /v2/transactions) ──► same path        ├─► Accountant assigns it to installments ─► APPLIED
                                                                    └─► Accountant ignores it, with a reason ─► IGNORED
a receipt recorded from a transfer is voided ─► the transfer goes back to NEEDS_REVIEW (RECEIPT_VOIDED)
```

- The content is read as the transfer card writes it: `HD-20261108-000007-1 DOT2`, or `... FEE1` for a fee item.
  Upper or lower case, dashes kept, turned into spaces or dropped, other words around it: all read
  (`TransferReference`). SePay's `code` field is read only when the content names nothing.
- The receipt's statement reference is the bank's own reference (`referenceCode`), so 15 BR-03 refuses the same money
  twice, whether the feed or the Accountant records it first. Without one, `SEPAY-<id>`.
- One transfer is kept once: by SePay's id, and by the bank reference (the webhook's id and the API's id differ).
- Review reasons, for the screen to translate: `NO_INSTALLMENT_NAMED`, `SEVERAL_INSTALLMENTS`, `INSTALLMENT_NOT_FOUND`,
  `NOT_PAYABLE`, `OVERPAYMENT`, `OTHER_ACCOUNT`, `REFUSED`, `RECEIPT_VOIDED`.

The Accountant's endpoints (`payment-bank-transactions`, Accountant and System Admin):

| | |
|---|---|
| `GET /api/v1/bank-transactions?status=NEEDS_REVIEW` | the queue; `status` takes several, comma-separated |
| `GET /api/v1/bank-transactions/{id}` | one transfer |
| `POST /api/v1/bank-transactions/{id}/assignment` | `{customerId, lines: [{installmentId, amount}], note?}`: records the receipt |
| `POST /api/v1/bank-transactions/{id}/ignore` | `{reason}` |

## Settings

All from the environment, the same on a laptop and on the VPS (`application.yml`, `stockflow.payment.bank-feed`).

| Variable | |
|---|---|
| `BANK_FEED_ENABLED` | `true` to take transfers. Off, the webhook answers 503 and the catch-up does nothing |
| `BANK_FEED_WEBHOOK_HMAC_SECRET` | the webhook's HMAC-SHA256 secret key from my.sepay.vn (recommended) |
| `BANK_FEED_WEBHOOK_API_KEY` | only if the webhook uses "API Key" instead; ignored while an HMAC secret is set |
| `BANK_FEED_ACCOUNT_NUMBER` | the account customers pay into; defaults to `COMPANY_BANK_ACCOUNT_NUMBER`. A transfer into another account waits for review |
| `BANK_FEED_API_TOKEN` | API token for the nightly catch-up; blank: the catch-up only retries stranded transfers |
| `BANK_FEED_API_BASE_URL` | `https://userapi.sepay.vn/v2`; a token made in **Test mode** needs `https://userapi-sandbox.sepay.vn/v2` |

Put real values only in the server's `.env` (or the laptop's), never in git.

## Setting it up on my.sepay.vn

1. **Bank account**: connect the company account customers pay into (or, in Test mode, the test account SePay gives).
   Set `COMPANY_BANK_ACCOUNT_NUMBER` (or `BANK_FEED_ACCOUNT_NUMBER`) to that number, so the transfer card's VietQR code
   and the feed name the same account.
2. **Webhook** (SePay's webhook page; menu names may differ slightly):
   - For the bank account above. Money going out is answered success and not kept, so no filter is needed for it.
   - URL: `https://<public host>/api/v1/bank-transactions/webhooks/sepay`.
   - Authentication: **HMAC-SHA256**; copy the secret key into `BANK_FEED_WEBHOOK_HMAC_SECRET`. (Or API Key into
     `BANK_FEED_WEBHOOK_API_KEY`.)
   - Content type: `application/json`.
   - Turn on "Tự động gửi lại khi server trả lỗi", so a 503 is sent again.
3. **API token** (Cấu hình công ty → API Access → Thêm API): copy it into `BANK_FEED_API_TOKEN`.
4. **Payment code** (Cấu hình công ty → Cấu hình chung → Cấu trúc mã thanh toán): optional. The system reads the
   content itself; SePay's `code` is a fallback only.

Restart the application after changing `.env`.

## Demo in Test mode

SePay's Test mode simulates transfers into a test account: nothing touches a real bank. Limits: at most
**499,000,000 VND** per simulated transfer (at least 1,000), content up to 200 characters, 500 simulated transfers a
day. A Test-mode API token works only with the sandbox base URL.

1. Switch SePay to Test mode and do the set-up above there (Test mode keeps its own webhooks, tokens and payment-code
   templates). `BANK_FEED_API_BASE_URL=https://userapi-sandbox.sepay.vn/v2`.
2. A public URL for the webhook:
   - **VPS**: `https://<the server's domain>/api/v1/bank-transactions/webhooks/sepay`, with the variables in the
     server's `.env`.
   - **Laptop**: `ngrok http 8080`, then `https://<id>.ngrok-free.app/api/v1/bank-transactions/webhooks/sepay`. The
     ngrok address changes on each start: update the webhook URL on my.sepay.vn.
3. Make an order, sign its contract, and open the transfer card (portal or chat): note the content, e.g.
   `HD-20261126-000012-1 DOT1`, and the amount. Keep demo amounts at or below 499,000,000 VND, so a whole installment
   can be paid by one simulated transfer.
4. On my.sepay.vn, in Test mode, simulate a transaction (SePay's "Mô phỏng giao dịch"): the test account, type "in", the installment's amount, the content
   from the card. Within seconds the installment is PAID and the order CONFIRMED; the receipt shows "Bank feed (SePay)"
   as its recorder.
5. Show the queue: simulate a transfer with content "thanh toan tien hang" (no installment named) or more than the
   installment owes. It waits in `GET /api/v1/bank-transactions?status=NEEDS_REVIEW`; assign it, or ignore it with a
   reason.

Simulate a transaction rather than use the webhook's test-send button: a test send carries a fixed id, so a second one is taken
for a repeat of the first.

## What is not verified against SePay

Checked against SePay's documentation only, with SePay faked in `BankFeedIntegrationTest`; not yet against a live
SePay account:

- that the webhook's `referenceCode` and the API's `reference_number` are the same for one transfer (the catch-up
  relies on it to recognise a transfer the webhook delivered);
- the API v2 date filter format (`transaction_date_from=yyyy-MM-dd HH:mm:ss`, Vietnam time);
- how each bank rewrites the transfer content (the reader takes the common changes; anything it cannot read waits for
  review, never a wrong receipt).
