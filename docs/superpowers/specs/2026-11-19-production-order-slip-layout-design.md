# Production order slip as a production paper — design

Date: 2026-11-19 · Size: small change · Status: approved by the owner (2026-11-19), built

## Intent

The production order slip (4A-3, kltn-docs 17 §4.3 steps 6-7) was a column of text lines. It should read as a real
production paper: the document a print shop (a partner or the company's own workshop) receives and makes the goods
from, and the warehouse receives them against. Content stays what 17 §4.3 asks for, less what the owner ruled out below;
the change is presentation.

The owner, after seeing the drafts (2026-11-19): the slip is **only what is needed to produce the goods and to receive
them** - a paper that can be handed to anyone at the print shop, which may be the company's own workshop - so **no
money, no signatures and nothing unrelated to making them**. Vetting producers, contracts and prices belong to other
departments, outside the system (kltn-docs 17 §3, B45). The need-by date stays, prominently. The reviewed draft is
`2026-11-19-production-order-slip-sample.pdf` next to this file.

## Goals

- Header with **our company's name** (`stockflow.seller.name`), vertically centred on the QR of the code at the right; the title
  "PHIẾU ĐẶT HÀNG SẢN XUẤT", the slip's number and date, and the barcode of the code (the lot goods are received under,
  03 BR-03).
- A **key-facts row** of three boxes right under the barcode: **"Cần hàng trước ngày"** (the need-by date, large),
  **"Giao về"** (receiving warehouse name **and its full address**, wrapped inside the box, which is the widest of the
  three; a warehouse with no address recorded shows its name only), **"Duyệt mẫu in trước"** (CÓ / KHÔNG).
- One box **"Đơn vị sản xuất"** (neutral: it may be the company's own workshop): producer name, code, contact (person,
  phone, e-mail as recorded).
- **Goods as a table**: No. · SKU · name / spec · cartons · units per carton · total units. Under the name, in smaller
  grey text: carton size (or "not recorded"), packing requirements when entered, and the print file (design snapshot id
  and the **full** SHA-256, wrapped). **No totals row**: units of different products do not add up (cups plus lids is
  no quantity anyone makes), and receiving counts line by line in the system (owner, 2026-11-19).
- **Requirements** block: deliver to (receiving warehouse, address, need-by date), print sample yes/no, print only
  the files whose checksum is given, every carton holds exactly the units per carton, note when entered, "write this
  number on the delivery note".
- **No signature boxes.** Instead one contact line - who drew it up (the coordinator: name and e-mail as recorded)
  for the print shop's questions - and a grey line "drawn up and approved in StockFlow at <time>; no signature
  needed": the approval (BR-23) and the producer's acceptance are recorded in the system, not on paper.
- Footer "Phiếu <code>" and "Trang x / y" on every page; the table splits across pages and repeats its header.

## Non-goals

- **No money**: no unit price, amount, total or amount in words, even when a processing price was entered (it stays on
  the production order in the system).
- **Nothing unrelated to making the goods**: no sales order number, customer, delivery batch window, our tax code or
  address, approver's name.
- **No signatures and no legal content** (terms, partner vetting): other departments' business, outside the system.
- **Not a receiving record**: no "units received" or "received by" fields; receiving is counted in the system against
  the production order (03 §4.1); the slip only carries the code the receiving scan reads.
- No change to the quote, contract, delivery note or sandbox invoice: they keep today's look.
- No change to what a production order holds, to when the slip is drawn (approval) or to how it is sent.

## Anti-goals

- A shortened checksum: the print shop must be able to compare the whole SHA-256 (the draft cut it only for space).
- A barcode the receiving scanner no longer reads.

## Constraints

- Vietnamese text, Noto Sans (`LabelFonts`), A4, as every document of the system.
- Slips already stored keep their bytes (their digest is checked on download); only slips drawn after the change look
  new.
- `stockflow.seller.name` may be blank outside `prod`; the header line is then left out (the slip is not refused,
  unlike a contract: a production order must still go out).

## Approaches

1. **Add shared layout blocks next to `PrintedDocument`** — a masthead, a key-facts row, a box, a table with column
   widths, wrapping and page breaks, a bullet list, a note, page numbers — and redraw the slip with them.
   The other four documents keep calling the line API, so they do not change; they can adopt the blocks later.
   **Picked**: one layout code, the barcode band and fonts stay shared.
2. A standalone PDFBox renderer for the slip only — quickest, but copies margins, fonts and the code band, and the next
   document wanting a table copies it again.
3. HTML template rendered to PDF (e.g. openhtmltopdf) — most freedom of look, but a new dependency, Vietnamese font
   embedding to redo, and two ways of drawing documents in one codebase.

## Decided

- **No "demo" wording on the slip** (owner, 2026-11-19: it is a capstone, the slip must read as real). The slip
  prints what the data holds, so the word reaches it only through data: the `stockflow.seller.name` default of the
  `local`, `dev` and `test` profiles ("DEMO STOCKFLOW CO LTD") becomes "CÔNG TY TNHH STOCKFLOW", and a new demo
  migration renames the two demo producers (`DEMO-INDN` "… (demo)", `DEMO-INLX` "… (demo)") to plain names and codes.
  The other "(demo)" names in the demo data (about 550) are out of this change.
- The layout of the v7 draft the owner reviewed on 2026-11-19 (v4 plus the full delivery address in "Giao về", the
  company name centred on the QR, no demo wording, no totals row): company name + QR, title + number + date + barcode,
  key-facts row (need-by date first), producer box, goods table, requirements, contact line, footer with page numbers.
- No money, no signatures, nothing unrelated to production (see Non-goals).
- **The producer's e-mail follows the slip** (owner, 2026-11-19, after the build): it names no processing price either,
  and it carries the slip PDF as an attachment - the stored bytes, checked against the digest recorded at approval
  (`ProductionOrderDirectory.slipPdf`). A slip that cannot be read or no longer matches does not hold the e-mail back:
  it goes with its links, and its intro does not claim an attachment. The e-mail and the slip-mismatch error now call
  the paper "Phiếu đặt hàng sản xuất".
- Approach 1.

## Builder's calls

- Approach 1 is built as a sibling class, `common/label/FormDocument`, in the same package as `PrintedDocument` (same
  fonts, same barcode drawing) rather than inside it: the block layout is about as long as the line layout, and two
  focused classes read better than one. The four other documents keep calling `PrintedDocument` untouched.
- Accounts carry no phone number (`StaffUser`): the contact line prints the coordinator's name and e-mail.
- The delivery-batch lookup stays in `ProductionOrderDispatch` as a guard (approval is refused when the batch is gone),
  though the slip no longer prints the batch.

- `ProductionOrderSlipDocument.Facts` loses what the slip no longer prints (sales order, customer, batch window, price,
  currency, approver); it gains the coordinator's contact (name, phone, e-mail as identity records them; any missing
  part is left out). `ProductionOrderDispatch` stops looking up what the slip no longer prints. Whatever else uses
  those values keeps them.
- Open for the team, not for this change: if processing prices are handled by another department, whether the
  production order still needs its unit price field (kltn-docs 17 asks the coordinator to enter it).
- New labels go into the `production-order.pdf.*` keys of `messages_vi` (and `messages` for the en bundle, which
  `MessageBundleTest` keeps in step); keys no longer used are removed from both.
- Tests: `ProductionOrderSlipDocumentTest` reads the text back (header, key facts with the full address, table cells,
  requirements, contact line), checks that no price, customer, sales order, totals row or signature caption appears even
  when the production order has the data for them, the full
  checksum is printed, a multi-page table repeats its header and numbers its pages, and the barcode and QR still decode
  to the code.
