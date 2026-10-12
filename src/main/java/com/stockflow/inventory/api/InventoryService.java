package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the inventory module. Every other module talks to inventory through this
 * interface and nothing else.
 *
 * <p>What other modules deliberately cannot reach: {@code StockItem}, {@code StockItemRepository},
 * the JPA entities, the controller. They all live under {@code inventory.internal}, and
 * {@code ModularityTest} fails the build if anyone imports them.</p>
 *
 * <p>The parameters and return types are records declared in this same package, never domain
 * objects. Handing another module a {@code StockItem} would let it call {@code reserve()} on the
 * aggregate directly, outside a transaction and outside the invariants.</p>
 */
public interface InventoryService {

    /**
     * Available to promise for one SKU, summed over every location holding sellable stock:
     * {@code ATP = available − reserved}. Allocated units are not subtracted a second time,
     * because stock leaves the {@code available} bucket the moment it is allocated.
     */
    int availableToPromise(Sku sku);

    /** Batch availability; missing keys mean zero. Never authorizes a checkout without reserve(). */
    default java.util.Map<String, Long> availableQuantities(java.util.Set<String> skus) {
        return skus.stream().collect(java.util.stream.Collectors.toMap(
                code -> code, code -> (long) availableToPromise(new Sku(code))));
    }

    /**
     * {@link #availableToPromise(Sku)} for several SKUs in one query: every SKU asked for is a key,
     * {@code 0} when nothing sellable is held. For a page that shows many variants at once — one
     * call per SKU would be one query per SKU.
     *
     * <p>Read from the denormalised {@code reserved} column, as the allocation planner does, not
     * from the reservation rows; the two are kept equal on every save.</p>
     */
    Map<Sku, Integer> availableToPromise(Collection<Sku> skus);

    /**
     * ATP for one SKU inside one warehouse (SCRUM-157: the storefront may promise per warehouse
     * or system-wide, open question C3). Zero for a warehouse holding none of it.
     */
    int availableToPromise(Sku sku, String warehouseCode);

    /** Per-location breakdown, earliest expiry first (FEFO). Used by fulfilment when allocating. */
    List<StockAvailability> availabilityOf(Sku sku);

    /**
     * Inventory Level per warehouse for one SKU, ordered by warehouse code.
     *
     * <p>Bounded by the number of warehouses, so it is returned as a plain list rather than a page.</p>
     */
    List<StockLevel> levelsOf(Sku sku);

    /**
     * Reserve stock for an order line.
     *
     * <p>Called synchronously from {@code order} during checkout, <b>inside the caller's
     * transaction</b>. That is the whole point of the monolith: if the order row fails to persist,
     * this reservation rolls back with it. In the microservices version this same step needed a
     * saga plus a compensating action plus a reservation timeout.</p>
     *
     * @throws com.stockflow.common.error.BusinessException when ATP is below the requested quantity
     */
    ReserveStockResult reserve(ReserveStockCommand command);
    /** Acquire policy locks and all stock rows in global order before reserving any basket line.
     * Must join the same transaction as every subsequent reserve call. */
    void prepareReservation(java.util.Set<Sku> skus);

    /** Release a reservation — order cancelled, payment failed, or the hold expired. */
    void release(UUID reservationId, String reason);

    /**
     * Hold blanks for a production order (see {@link ReserveProductionStockCommand}). Joins the caller's
     * transaction, like {@link #reserve}: a sample submission that fails afterwards leaves no hold behind.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INSUFFICIENT_STOCK} when the warehouse
     *         holds fewer free units than asked
     */
    ReserveStockResult reserveForProduction(ReserveProductionStockCommand command);

    /**
     * Release every live hold of a production order whose goods are still in storage (cancelled before they
     * were issued; issued ones go back through {@link #returnHeld}). Joins the caller's transaction.
     * Idempotent: closed holds are left as they are.
     *
     * @return the units released
     */
    int releaseProductionHolds(UUID productionOrderId);

    /** The live holds of a production order, wherever their goods are now; in stock item id order. */
    List<StockReservation> productionHolds(UUID productionOrderId);

    /**
     * Move one live hold together with its goods (see {@link IssueReservedStockCommand}), writing one MOVE
     * ledger line with reference {@code PRODUCTION_ORDER}. The units are held for their owner the whole
     * time: available-to-promise does not change, and no other order can take them on the way. Joins the
     * caller's transaction; locks both stock rows in id order.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND},
     *         {@code RESERVATION_NOT_ISSUABLE} (closed, another owner's, or still waiting for payment),
     *         {@code LOCATION_NOT_FOUND}, {@code LOCATION_NOT_USABLE}, {@code MOVE_ACROSS_WAREHOUSES},
     *         {@code STOCK_STATUS_MISMATCH}
     */
    IssueReservedStockResult issueReserved(IssueReservedStockCommand command);

    /**
     * The hand-over of finished goods (SCRUM-400): a hold and its goods from the print shop to a PACKING area,
     * IN_PRODUCTION → AVAILABLE, still held for its owner. Same locks, ledger and refusals as
     * {@link #issueReserved}.
     */
    IssueReservedStockResult finishHeld(IssueReservedStockCommand command);

    /**
     * Unprinted goods of a stopped print job, still held for their order, back to storage with the hold
     * (SCRUM-401): IN_PRODUCTION → AVAILABLE, same locks and ledger as {@link #issueReserved}.
     */
    IssueReservedStockResult unissueHeld(IssueReservedStockCommand command);

    /** See {@link WriteOffHeldStockCommand}. Joins the caller's transaction. @return the write-off asked for */
    StockAdjustmentSummary writeOffHeld(WriteOffHeldStockCommand command);

    /** See {@link ReturnHeldStockCommand}. Joins the caller's transaction. */
    StockMove returnHeld(ReturnHeldStockCommand command);

    /**
     * A hold in storage gives up {@code quantity} units, free again (SCRUM-441: part of a production order goes to
     * a subcontractor who sources the blanks). The hold must belong to {@code orderId} or {@code productionOrderId}.
     * Joins the caller's transaction.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND},
     *         {@code RESERVATION_NOT_ISSUABLE} (another owner's, closed, in the print shop, or fewer units)
     */
    void reduceHold(UUID reservationId, UUID orderId, UUID productionOrderId, int quantity);

    /**
     * A production order's live hold becomes its sales order's, same id, still pinned (SCRUM-400: finished goods
     * of a reprint round or a subcontracted child are the order's to ship). Joins the caller's transaction;
     * idempotent — a hold already the order's is left as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND},
     *         {@code RESERVATION_NOT_ISSUABLE} (another production order's, another order's, or closed)
     */
    void handHoldToOrder(UUID reservationId, UUID productionOrderId, UUID orderId);

    /** The holds among {@code reservationIds} that are still live, with what they hold now and where. */
    List<StockReservation> liveHolds(Collection<UUID> reservationIds);

    /**
     * Stop the holds of an order from expiring: the order no longer waits for payment (paid, or
     * released on its deposit), so its stock stays held until it is consumed or the order releases
     * it (kltn-docs 14 BR-07, SCRUM-465). Joins the caller's transaction, like {@link #reserve}, so
     * the order's new status and the pinned holds commit together and the expiry sweep cannot slip
     * in between. Idempotent.
     *
     * @return how many holds were pinned by this call
     */
    int pinReservations(UUID orderId);

    /**
     * The order waits for payment again (SCRUM-427: credit refused, switched to prepaid or deposit):
     * its pinned holds get the checkout deadline from now (kltn-docs 14 BR-07). Joins the caller's
     * transaction. Idempotent.
     *
     * @return how many holds got a deadline
     */
    int rearmReservations(UUID orderId);

    /**
     * Move unreserved stock between two locations, writing one ledger line (SCRUM-424). Joins the
     * caller's transaction, like {@link #reserve}. Locks the source and destination rows in id
     * order, as {@code reserve} does, so two moves crossing the same pair cannot deadlock.
     *
     * @throws com.stockflow.common.error.BusinessException {@code STOCK_ITEM_NOT_FOUND},
     *         {@code LOCATION_NOT_FOUND}, {@code INSUFFICIENT_STOCK} (fewer unreserved units than
     *         asked), {@code STOCK_STATUS_MISMATCH} (the destination holds the lot in another status)
     */
    StockMove move(MoveStockCommand command);

    /**
     * Count received goods in at a receiving location as INBOUND stock, writing a RECEIPT ledger line
     * (SCRUM-435, docs 03 step 6). Joins the caller's transaction: the receipt and the stock it
     * creates commit or roll back together. Merges into INBOUND stock of the same SKU and lot already
     * at that location.
     *
     * @throws com.stockflow.common.error.BusinessException {@code LOCATION_NOT_FOUND},
     *         {@code STOCK_STATUS_MISMATCH} (the location holds the lot in a status other than INBOUND)
     */
    StockMove receive(ReceiveStockCommand command);

    /**
     * Move units and change their status in one step (QC decision, putaway); see
     * {@link ReclassifyStockCommand}. Same locking and errors as {@link #move}.
     */
    StockMove reclassify(ReclassifyStockCommand command);

    /** The receiving policy of an inventory item, empty when no such item exists. */
    Optional<InventoryItemPolicy> itemPolicy(UUID inventoryItemId);

    /** The same, by SKU: the inventory item a variant has, empty when it has none yet. */
    Optional<InventoryItemPolicy> itemPolicy(Sku sku);

    /** The receiving policy of each inventory item that exists, in one query (SCRUM-464). */
    java.util.Map<UUID, InventoryItemPolicy> itemPolicies(java.util.Collection<UUID> inventoryItemIds);

    /** The SKU of each inventory item that exists, for a list of purchase order lines in one query. */
    java.util.Map<UUID, String> skusOf(java.util.Collection<UUID> inventoryItemIds);

    /**
     * Ask for a stock correction with a reason (SCRUM-145). The stock does not change until a
     * different person approves it; production records scrapped blanks through this.
     */
    StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command);
}
