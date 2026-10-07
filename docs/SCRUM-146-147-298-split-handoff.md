# SCRUM-146 / 147 / 298 — split coordination handoff

## Recoverable source, not a merge-ready feature branch

The original implementation is preserved locally on
`feature/SCRUM-146-147-298-split-handoff`, commit `b7d6cda`.
This is a **full source snapshot**, including 70/71 and its stacked PR36 ancestry.
Do not merge this snapshot wholesale or open it as an independent PR: that would reintroduce
the duplicate schema and the scope split this handoff removes.

On the scoped 70/71 branch the files below have been removed from the working tree. They remain
recoverable using `git show b7d6cda:<path>`. Shared notification classes were restored to PR36
`9fbb90f`, preserving supplier email/cancellation fixes. Shared checkout types no longer require
quoteId. Existing standard checkout server-price checks are deliberately retained.

Nothing here has been sent to another team member or pushed to a remote branch yet.

## Owners and acceptance gates

| Work | Coordinate with | Must resolve before porting |
| --- | --- | --- |
| 146 cycle counts | Phương; Tú for schema; Võ for adjustment integration | Use existing cycle_count/line + stock_adjustment; document_sequence; blind count/data scope; clear wrong-warehouse denial; four-eyes; reservation shortage; count staleness; idempotent posting + stock_movement atomically |
| 147 inventory alerts | Tú | Consume policy/stock contracts; immediate after-commit evaluation and scheduled reconciliation; null != zero; alert episode dedup/resolution; explicit authorized recipients; missing config visible; fair backoff/retry |
| 298 design quotes / checkout | Tú and checkout owner | Map agreed quote schema; immutable accepted offer; customer/snapshot/SKU/quantity/currency binding; expiry/revision; server price; atomic one-time consumption; retry and concurrent consumption; FE contract |
| Receipt/FIFO/serial | Receipt/stock owner + Tú | Real receipt-layer age, no fake created_at mapping; move preserves age; serial uniqueness; locks consistent with policy/count/reserve |

Keep approval actor/time and ledger evidence. Do not port legacy count tables or generate replacement
migrations independently. Separate owners must adopt the source as reference and map it to the
develop schema, then open narrowly scoped PRs.

## Sequencing

1. Tú approves the [schema request](business-design/db-design/SCRUM-70-71-schema-change-request.md).
2. Land canonical product/inventory policy adapters and their tests in 70/71.
3. Start clean feature branches from the agreed develop baseline; selectively port each owner's
   files from this source snapshot. Resolve dependencies, do not cherry-pick the original monolithic commit.
4. 146 posts adjustments and stock_movement; 147 reuses common notification infrastructure;
   298 supplies the quote integration before enabling customer design checkout.
5. Test each owner PR and the integrated workflows. Re-run migration fresh/upgrade and security tests.

## Removed dedicated files

- `src/main/java/com/stockflow/inventory/internal/controller/CycleCountController.java`
- `src/main/java/com/stockflow/inventory/internal/controller/CycleCountWebMapper.java`
- `src/main/java/com/stockflow/inventory/internal/controller/StockAlertController.java`
- `src/main/java/com/stockflow/inventory/internal/controller/StockAlertWebMapper.java`
- `src/main/java/com/stockflow/inventory/internal/controller/dto/AlertDeliveryResponse.java`
- `src/main/java/com/stockflow/inventory/internal/controller/dto/CycleCountRequests.java`
- `src/main/java/com/stockflow/inventory/internal/controller/dto/CycleCountResponse.java`
- `src/main/java/com/stockflow/inventory/internal/controller/dto/StockAlertResponse.java`
- `src/main/java/com/stockflow/inventory/internal/domain/CycleCount.java`
- `src/main/java/com/stockflow/inventory/internal/domain/CycleCountLine.java`
- `src/main/java/com/stockflow/inventory/internal/domain/CycleCountStatus.java`
- `src/main/java/com/stockflow/inventory/internal/domain/StockAlert.java`
- `src/main/java/com/stockflow/inventory/internal/entity/CycleCountJpaEntity.java`
- `src/main/java/com/stockflow/inventory/internal/repository/CycleCountJpaRepository.java`
- `src/main/java/com/stockflow/inventory/internal/repository/CycleCountRepository.java`
- `src/main/java/com/stockflow/inventory/internal/repository/StockAlertRepository.java`
- `src/main/java/com/stockflow/inventory/internal/service/CycleCountService.java`
- `src/main/java/com/stockflow/inventory/internal/service/InventoryAlertJob.java`
- `src/main/java/com/stockflow/inventory/internal/service/InventoryAlertService.java`
- `src/main/java/com/stockflow/notification/api/AlertDeliverySummary.java`
- `src/main/java/com/stockflow/notification/api/InventoryAlertMessage.java`
- `src/main/java/com/stockflow/notification/internal/repository/InventoryAlertDeliveryRepository.java`
- `src/main/java/com/stockflow/notification/internal/service/InventoryAlertDeliveryJob.java`
- `src/main/java/com/stockflow/notification/internal/service/InventoryAlertDeliveryTransactions.java`
- `src/main/java/com/stockflow/order/internal/controller/DesignQuoteController.java`
- `src/main/java/com/stockflow/order/internal/controller/DesignQuoteWebMapper.java`
- `src/main/java/com/stockflow/order/internal/controller/dto/DesignQuoteRequests.java`
- `src/main/java/com/stockflow/order/internal/controller/dto/DesignQuoteResponse.java`
- `src/main/java/com/stockflow/order/internal/domain/CheckoutFingerprint.java`
- `src/main/java/com/stockflow/order/internal/domain/DesignQuote.java`
- `src/main/java/com/stockflow/order/internal/domain/DesignQuoteStatus.java`
- `src/main/java/com/stockflow/order/internal/domain/QuoteOffer.java`
- `src/main/java/com/stockflow/order/internal/entity/DesignQuoteJpaEntity.java`
- `src/main/java/com/stockflow/order/internal/repository/CheckoutRequestRepository.java`
- `src/main/java/com/stockflow/order/internal/repository/DesignQuoteJpaRepository.java`
- `src/main/java/com/stockflow/order/internal/repository/QuoteOfferRepository.java`
- `src/main/java/com/stockflow/order/internal/service/DesignQuoteService.java`
- `src/main/resources/db/migration/V20260930001200__inventory_cycle_counts.sql`
- `src/main/resources/db/migration/V20260930001300__approved_count_posting.sql`
- `src/main/resources/db/migration/V20260930001400__inventory_alerts.sql`
- `src/main/resources/db/migration/V20260930001500__inventory_operations_permissions.sql`
- `src/main/resources/db/migration/V20260930001600__checkout_quotes.sql`
- `src/test/java/com/stockflow/DesignQuoteCheckoutIntegrationTest.java`
- `src/test/java/com/stockflow/InventoryAlertDeliveryIntegrationTest.java`
- `src/test/java/com/stockflow/InventoryOperationsIntegrationTest.java`
- `src/test/java/com/stockflow/inventory/internal/domain/CycleCountTest.java`
- `src/test/java/com/stockflow/notification/internal/service/InventoryAlertDeliveryJobTest.java`
- `src/test/java/com/stockflow/order/internal/domain/DesignQuoteTest.java`

## Shared files also split

- NotificationService, NotificationServiceImpl, NotificationSender and NotificationSenderTest:
  removed inventory alert contracts/hooks while retaining supplier communication fixes.
- InventoryResources/package dependencies: removed count/alert endpoint resources and their
  identity/notification dependencies; original reference inventory APIs remain.
- StockPolicy: removed count-specific validation; InventoryPolicyRepository reads develop
  count status/sku for the policy-change guard.
- OrderServiceImpl, command/request/mapper and unit tests: removed quote/fingerprint repository
  dependencies; ordinary server-price validation remains as an explicit minimal dependency.
- InventoryCatalogSecurityIntegrationTest: moved count/alert/quote endpoint cases with their
  implementation; kept publication/inventory-control security tests.
- Error codes/messages/configuration: removed extension-only codes and recipient configuration.
- StockLevelChanged: removed unused extension contract; SkuInventoryControlChanged remains for 147.

## Recovery / verification limits

Removal is from source, not a DROP against any running database. An existing DB which already ran
the removed migrations needs an owner-managed upgrade; never delete Flyway rows to hide the mismatch.
The snapshot is a recovery point, not proof that the separated features pass against the new schema.
