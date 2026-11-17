---
name: business-docs
description: Use when a task depends on a business rule, a flow, a status, a role or a decision of StockFlowCommerce - a Jira ticket citing "06 BR-04", "17 §4.12", "C40" or a team-plan id, a question of what the business wants, or before designing a feature, a migration or a test. Says where the business analysis (kltn-docs) lives, which code module each of its sections belongs to, how to read its markers, and where a new decision is recorded.
---

# The business analysis: where it is and how to read it

The business analysis is the **kltn-docs** repository (GitHub `KLTN-FA26/docs`, Vietnamese), usually cloned next to
this one (on the owner's machine: `D:\FPTU\KLTN\docs`). It is the **source of truth** for behaviour: it wins over
`docs/business-design/**` in this repo (pre-rescope, marked superseded) and over a code comment that disagrees.
Read it from `origin/develop` (`git -C <docs> show origin/develop:docs/...`) - a local checkout may sit on an old branch.

**Read the section, never a summary of it.** This skill is a map, not a copy: copies go stale, the docs change
weekly.

## Section → code module

| kltn-docs (`docs/…`) | About | Code |
|---|---|---|
| `00-system-overview` | actors, roles, the overall flow, notifications (§6) | `identity` (roles), `notification` |
| `warehouse/01-product-creation` | SKU master data, one carton spec per SKU | `product`, `inventory` (carton unit) |
| `warehouse/03-receipt` | receiving against production orders, return receipts | `procurement` (name kept, purchasing is gone) |
| `warehouse/05-putaway` | putaway tasks and trips, bin ranking, OVERFLOW | `warehouse` (tasks, trips), `inventory` (the move) |
| `warehouse/06-warehouse-map-slotting` | map, bins, areas, statuses, labels, capacity board | `warehouse` |
| `warehouse/07-picking` | pick by LPN, staging | `fulfillment`, `inventory :: consumption` |
| `warehouse/08-packing` | carton check and labelling | `fulfillment` (`CartonCheckService`) |
| `warehouse/09-shipping` | load plans, carriers, delivery outcome | `fulfillment` |
| `warehouse/11-intra-warehouse-transfer` | moving cartons inside one warehouse | `inventory` (`StockOperationsServiceImpl` moves) |
| `warehouse/20-inventory-control` | counts, adjustments, quarantine, lot trace | `inventory` |
| `ecommerce/12-product-design` | customer designs, 2D/3D, print PDF/X, projects | `design` |
| `ecommerce/13-catalog` | customer portal, own SKUs, quote requests | `catalog` |
| `ecommerce/14-cart-and-order` | quotes, contracts, orders | `order` |
| `ecommerce/15-payment` | installments, receipts, credit, settlements | `payment` |
| `ecommerce/16-sales-chat` | sales chat | `chat` |
| `ecommerce/17-order-management` | order lifecycle, production orders, holds, returns | `order` |
| `ecommerce/18-customer-information` | wholesale customers | `customer` |
| `ecommerce/21-e-invoice` | VAT e-invoices per delivery | `invoicing` |
| `glossary`, `open-questions`, `diagrams` | terms (and terms dropped, §8), open/decided questions `Cxx`, flow and state diagrams | - |

Numbers 02, 04, 10 and 19 are deliberately empty (purchasing, supplier invoices, inter-warehouse transfer and in-house
production were removed, 2026-11-04/05).

## How to read a section

- Every section follows one template: purpose · actors · input/output · step-by-step flow (§4) · statuses (§5) ·
  business rules and edge cases (§6) · links to other sections. "06 BR-04" is business rule 4 of section 06;
  "17 §4.12 b47" is step 47 of §4.12 of section 17.
- **`✅ Đã chốt <date>`** marks a decision; a later "chốt lại" replaces an earlier one. **`⚠️ Giả định`** is the
  writer's own assumption, collected in `open-questions` - not a decision, so check its `Cxx` row before relying on it.
- Team-plan ids in code comments (4A-2, 4C-5, 5-4, G4-05, …) are from `docs/superpowers/plans/2026-11-07-rescope/` in
  this repo; Jira stories cite the docs section and the BRs they implement.

## Where a decision goes

- **A business decision** (what the system should do: a rule, a status, what counts) → kltn-docs: the section's BR /
  flow text with `✅ Chốt lại <date>`, the glossary if a term changes, and the `open-questions` row it settles. Open a
  PR on the docs repo; say in the Jira ticket what changed.
- **An architecture decision** (how the code is built: module boundaries, persistence, cross-module calls) → a new ADR
  in `docs/adr/`, marking what it supersedes in the older ADR.
- **Neither goes into `CLAUDE.md`**, which only points at them and records what the code does that is not obvious.
