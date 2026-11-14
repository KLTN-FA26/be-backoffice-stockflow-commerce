# WF4 demo data (`wf4_demo_data.py`)

Creates the data the WF4 demo starts from (pick -> carton check & labelling -> 3D load plan -> carrier handover),
**only through the public HTTP API** (no SQL), and can be re-run: every object is looked up first (by code, or by a
marker in the quote's terms) and a step that is already done is skipped.

Python 3.9+, standard library only. Nothing is installed.

## Run

```bash
export SF_DEMO_USER=<an admin account>        # signs in for Sales, Accounting, Production Coordinator, warehouse
export SF_DEMO_PASSWORD=<its password>        # also used for the other demo accounts (see below); never printed

# 1. look first: only GETs (plus the sign-in, which is a POST) - prints what a real run would do
python3 tools/demo-data/wf4_demo_data.py --base-url https://<server> --dry-run

# 2. do it
python3 tools/demo-data/wf4_demo_data.py --base-url https://<server>
```

`--base-url` is required and has no default. The output ends with a summary: accounts, order codes, carriers, windows,
instalments. It never prints a password or a token.

| Option | Meaning |
|---|---|
| `--dry-run` | Only GET. Prints `[ok]` for what exists and `[todo]` for what a real run would do. |
| `--warehouse HAN` | Prefix of the demo warehouse (default `HAN`). |
| `--set NAME` | Name of the data set (default `1`). The orders are keyed by it: the same set is never created twice, a **new name makes a fresh set** (use it after a demo consumed the first one). |
| `--d14-window-in-days N` | The D14 order's delivery window starts `N` days after the run date (default `1` = tomorrow). Run the script the day before the demo, or raise `N`. |
| `--only carriers\|vehicles\|staging\|orders` | Run one part only (repeatable). `--only-order D14\|D23-A\|D23-B` narrows `orders`. |
| `--actor ROLE=USERNAME` | Who acts in a role: `sales`, `accountant`, `coordinator`, `warehouse`, `admin` (default: `SF_DEMO_USER`) and `approver` (default `truongkd`). |
| `--customer-user CODE=USERNAME` | The sign-in of a customer when it cannot be looked up (e.g. `caphemoc=kh.caphemoc`). |

Environment: `SF_DEMO_USER`, `SF_DEMO_PASSWORD` (required). `SF_DEMO_PASSWORD_<USERNAME>` (upper case, other characters
as `_`, e.g. `SF_DEMO_PASSWORD_KH_CAPHEMOC`) overrides the password of one account. `SF_DEMO_WEBHOOK_SECRET` (16-512
characters, optional) is the signing secret of the webhook carrier; without it a random one is generated and not shown.

Exit code: `0` ok, `1` the server refused a call, `2` stopped by the script (it does not guess), `3` an order is blocked.

## What it creates

**D13 - carriers and vehicle types**
- `XT-NAMBAC` "Vận tải Nam Bắc": `MANUAL` (the waybill is typed by hand). The standard test seed declares it too; if it
  exists it is left alone.
- `XT-THANGLONG` "Vận tải Thăng Long (kết nối webhook)": `WEBHOOK_LOAD` (its events are signed with a shared secret).
  Storing the secret needs `STOCKFLOW_SECRETS_KEY` on the server; without it the API answers `503 SECRETS_UNAVAILABLE`
  and the script only warns.
- Vehicle types `PICKUP` (small), `TRUCK-1T25`, `TRUCK-2T5`: they are presets of the migrations; the script checks they
  are `ACTIVE` with at least 2 vehicles available (a plan may not use more vehicles of a type than that), and fixes or
  creates them only if not.

**STAGING area** - one `ACTIVE` area of type `STAGING` in the demo warehouse. Created (`CHOXUAT01`, on the first free
12 x 6 spot of the map) or re-activated only when the warehouse has none active.

**Orders** - each one goes through the real flow, so the rows are what the application leaves itself:

| Key | Customer / SKU | Cartons | What it is for |
|---|---|---|---|
| `D14` | `kh.caphemoc` / `LY-GIAY-2L-12OZ-CPM` | 3 | Contract: 30 % `ON_SIGNING` (paid) + 70 % `BEFORE_DELIVERY` tied to batch 1 (**not paid**). Batch 1 window starts **tomorrow**. For R05 (handover blocked until the instalment is paid), R06 (before the window), X03, X04. |
| `D23-A` | `kh.trasuamay` / `TUI-GIAY-QUAI-M-TSM` | 4 | Fully paid, window starts today. |
| `D23-B` | `kh.banhmiviet` / `HOP-BANH-KRAFT-M-BMV` | 4 | Fully paid, window starts today. |

`D23-A` + `D23-B` = 8 cartons (60 x 40 x 50 cm): a load plan on the small vehicle type `PICKUP` (4 to 5 cartons each)
needs **two vehicles**. The script checks it with the advisory trial estimate (`POST /fulfillment/load-estimates/trial`,
which stores nothing) and prints the result. Plan these two together (D23, X05 "vehicle breaks down during loading");
keep `D14` out of that plan, its handover is blocked on purpose (with it the plan would be 11 cartons = 3 vehicles).

The steps per order, each one only if the order is not past it yet:

1. quote (Sales) -> sent -> **accepted by the customer's own login** (the API refuses staff here) -> converted to an order
2. contract drawn up (one delivery batch, payment plan) -> sent -> signed (a one-page PDF is attached as the signed file)
3. payment receipt for the `ON_SIGNING` instalment (Accounting) -> order `CONFIRMED`
4. shipping warehouse chosen (Sales) -> stock held there (none is free, so the cartons are short)
5. production order for the missing cartons (Production Coordinator) -> submitted -> **approved by a second user** (the
   approver is never the drafter) -> goods receipt of the cartons -> putaway into bins -> the hold completes

It stops there: nothing is released, picked, labelled or loaded. The presenter does steps 1-10 live.

The orders end in status `AWAITING_STOCK` with **every carton held** (`GET /orders/{id}/stock-coverage` says
`fullyHeld: true`). That is how the backend leaves an order whose cartons arrived through a production order: it becomes
`READY_TO_FULFILL` when the warehouse manager releases it (`OrderStatus` javadoc, kltn-docs 17 §4.4).
Note: the backoffice FE of 05/11 does not know `AWAITING_STOCK` and shows no Release button for it (finding F1 of
`qa-integration-1112.md`); release through the API, or fix F1 first.

## Accounts

Used by the script (all seeded demo accounts; the standard seed gives them the same password): the account of
`SF_DEMO_USER` for most roles, `truongkd` (Sales Manager) as the approver, and the customers' own accounts
(`kh.caphemoc`, `kh.trasuamay`, `kh.banhmiviet`; looked up through `GET /identity/users/{id}`). The login endpoint is
limited to 5 a minute per IP; the script waits when it hits the limit.

For the live demo (suggestion, from the seeded roles): `quanly.hn` (warehouse manager: release, load plan, handover,
cancel a vehicle), `kho.hn1` / `kho.hn2` (warehouse staff: picking, carton check, loading), `sales1` (Sales: partial
shipment flag, delivery-window change), `ketoan` (Accounting: payment receipts), `kh.caphemoc` (customer).
There is no separate *Dispatch Staff* role in the backend: load planning and handover (`fulfillment-dispatch`,
`fulfillment-load-plans`) belong to the warehouse roles.

## Limits

- No API-booking carrier (Ahamove) is created: it needs a real partner key. The seed declares Ahamove as `MANUAL`.
- The sign-in is the only non-GET call of a dry run.
- The contract needs the seller profile (`stockflow.order.seller.*`) and the production-order slip needs mail; a server
  without them answers an error and the script stops with its message.
- Stock arrives through a production order, so these orders use up cartons of the SKUs above only; the seed's own
  stock is untouched.

## Tested

Against a local backend (`develop` df34c338, standard test seed, security on): first run creates everything (about 25 s),
a second run makes 0 changes, a dry run lists 0 todo, and a run interrupted at the approval resumes from there.
