# Open Questions

Decisions the team still has to make. Each has a recommendation, because an open question with no
default becomes an ad-hoc choice made by whoever writes the code first.

| Id | Question | Why it matters | Recommendation |
|---|---|---|---|
| **OQ-01** | Is `condition` modelled as one enum with quantity buckets, or as the five "status categories" of WBS 3.6.1.3? | Determines whether *quarantined and reserved* is representable at all. | One `condition` enum plus quantities. Present the five categories as a computed view so the BRD wording still holds. |
| **OQ-02** | Do we distinguish reserved from allocated, or use a single hold? | A single hold either oversells at checkout or blocks the picker. | Keep both. It is one extra table and it removes a whole class of bug. |
| **OQ-03** | How long does a checkout reservation live? WBS 3.14.5.1 says only "time-limited". | Too short and honest customers lose their basket; too long and a campaign sells nothing. | 15 minutes for card and e-wallet, 24 hours for bank transfer, 30 minutes for COD. Configurable per payment method. |
| **OQ-04** | Is an order allowed to be split across warehouses? | Changes the release step, the pick lists and the shipping cost model. | Not in v1. One order fulfils from one warehouse; the coordinator splits manually into two orders when needed. |
| **OQ-05** | Does `IN_PRODUCTION` cover printing only, or any make-to-order step? | Affects who owns the state and whether a separate production service is needed. | Printing only in v1, owned by `fulfillment-service`. Revisit if assembly is added. |
| **OQ-06** | Are partial shipments allowed? | Affects the order machine, the shipment machine and refunds. | Not in v1. A short pick puts the order ON_HOLD rather than shipping what is available. |
| **OQ-07** | What is the return window? | BRD does not state it. | 7 days from delivery for stock items; custom-printed items non-returnable unless defective (BR-RMA-002). |
| **OQ-08** | Are approval limits per role or per user? | BRD says "within limits" without saying whose. | Per user, defaulted from the role. A manager on leave must be able to delegate a limit without changing the role. |
| **OQ-09** | Multi-currency? | Touches every money field in the system. | VND only in v1. Keep the `currency` column so it is a data change later, not a schema migration. |
| **OQ-10** | Does the customer see live ATP, or a coarse indicator? | Live ATP leaks stock levels to competitors and hammers `inventory-service`. | Show a band ("in stock" / "low" / "out of stock") on the PLP; show the exact figure only in the cart. |
| **OQ-11** | Who owns the print job queue — `fulfillment-service` or `design-service`? | Determines where `IN_PRODUCTION` transitions come from. | `fulfillment-service`. `design-service` owns the artifact; fulfilment owns the work. |
| **OQ-12** | Is stock returned from RTO sellable immediately? | Affects how quickly stock recovers after failed deliveries. | No. It re-enters as QUARANTINE and needs a QC pass, same as any other return. |

## How to close one

Add a row to the ADR log with the decision and the reason, update the state machine or rule it
affects, and delete the row here. An open question that stays open for three weeks is a decision
being made by accident.
