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
     * Available to promise for one SKU (docs 01): sellable units on hand — AVAILABLE, not past expiry — minus
     * every hold on them (allocations, production holds, checkout holds made before SCRUM-152) minus the live
     * SKU reservations, which sit on no stock item. Never negative.
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
     * The id of the inventory item of each SKU that has one, in one query: the inverse of {@link #skusOf}. A page of
     * variants needs it to link each row to its item screen; a SKU without an item is simply absent. An empty
     * input costs no query.
     */
    Map<String, UUID> itemIdsOf(Collection<String> skus);

    /**
     * ATP for one SKU inside one warehouse (SCRUM-157: the storefront may promise per warehouse
     * or system-wide, open question C3). Zero for a warehouse holding none of it. A SKU reservation names no
     * warehouse, so this is the warehouse's unheld sellable stock capped at the system-wide figure: never more
     * than {@link #availableToPromise(Sku)}.
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
     * Reserve stock for an order line: a SKU reservation (docs 01 "Reserved"), on no location and no lot, for the
     * checkout deadline. Bins and lots are chosen when the order is released ({@link #allocate}).
     *
     * <p>Called synchronously from {@code order} during checkout, <b>inside the caller's
     * transaction</b>. That is the whole point of the monolith: if the order row fails to persist,
     * this reservation rolls back with it. In the microservices version this same step needed a
     * saga plus a compensating action plus a reservation timeout.</p>
     *
     * @throws com.stockflow.common.error.BusinessException when ATP is below the requested quantity
     */
    ReserveStockResult reserve(ReserveStockCommand command);
    /** Take every basket SKU's advisory lock, in SKU order, before reserving any line: two checkouts sharing two
     * SKUs then wait for each other instead of deadlocking. Must join the same transaction as the reserve calls. */
    void prepareReservation(java.util.Set<Sku> skus);

    /**
     * HELD → ALLOCATED (docs 03 §6, 04c F-FUL step 02): the order was released to a warehouse; each live SKU
     * reservation of the order becomes allocations on pickable bins of that warehouse, chosen by BR-STK-006 —
     * earliest expiry first, and where there is none the earliest real receipt time (FIFO) — and never on expired,
     * blocked or non-pickable stock. Allocations are pinned (X-RESV: they do not expire). All or nothing: when the
     * warehouse cannot cover every line, nothing is allocated. Idempotent: an order with no SKU reservation left
     * gets an empty result. Joins the caller's transaction; locks the SKUs, then the reservations, then the stock
     * rows in id order.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INSUFFICIENT_STOCK} naming the SKU and warehouse;
     *         {@code RESERVATION_NOT_ACTIVE}
     */
    AllocateStockResult allocate(AllocateStockCommand command);

    /**
     * X-SHORT reallocation: the allocation came up short at its bin. It is released, and the same quantity is
     * allocated again in the same warehouse, FEFO, from any pickable bin but that one. Joins the caller's
     * transaction. Idempotent on the command's request id.
     *
     * @return the new allocations, in pick order
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND} (unknown, or another
     *         order's), {@code RESERVATION_NOT_ACTIVE}, {@code INSUFFICIENT_STOCK} (nothing changes)
     */
    List<StockReservation> reallocate(ReallocateStockCommand command);

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
     * The same holds as they stand, oldest first, for a screen to show (SCRUM-410): read-only and locking nothing,
     * unlike {@link #productionHolds}, which locks the stock rows for the caller's write.
     */
    List<HoldView> productionHoldViews(UUID productionOrderId);

    /**
     * Move one live hold together with its goods (see {@link IssueReservedStockCommand}) - AVAILABLE from storage,
     * or INBOUND when a subcontractor's goods were held on receipt (SCRUM-441) - writing one MOVE
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
     * The hand-over of finished goods (SCRUM-400): a hold and its goods from the print shop (IN_PRODUCTION) to a
     * PACKING area as AVAILABLE, still held for its owner. Same locks, ledger and refusals as
     * {@link #issueReserved}.
     */
    IssueReservedStockResult finishHeld(IssueReservedStockCommand command);

    /**
     * Unprinted goods of a stopped print job, still held for their order, back to storage with the hold
     * (SCRUM-401): IN_PRODUCTION → AVAILABLE, same locks and ledger as {@link #issueReserved}.
     */
    IssueReservedStockResult unissueHeld(IssueReservedStockCommand command);

    /**
     * BR-PRD-10 (decision 2026-10-16): the custom-printed goods of a cancelled order are scrapped, never sold. The
     * order's cancellation calls this for each hold of its print lines, in its own transaction, before it releases
     * them. When the hold is finished goods of {@code orderId} waiting in a PACKING area, the hold stays - its units
     * out of available-to-promise and out of every other order's reach - and a SCRAP write-off of exactly them is
     * asked for in {@code requestedBy}'s name, for a second person to approve (SCRUM-145). Approved, the hold closes
     * and stock falls once; rejected, the goods stay held and never go back on sale. A release never frees them.
     * Called again, the write-off already standing is answered and nothing is asked twice.
     *
     * <p>Anything else - blanks not issued yet, goods still in the print shop (production's to close), a hold closed
     * or another order's - is not this method's: empty, and the caller releases as for any line.</p>
     *
     * @param requestedBy who cancelled; null (the system) keeps the goods held and asks for nothing
     * @return the write-off asked for, or standing
     */
    java.util.Optional<StockAdjustmentSummary> scrapCancelledPrint(UUID reservationId, UUID orderId, String note,
                                                                   UUID requestedBy);

    /**
     * kltn-docs 19 BR-10 for goods consumed when their production order completed (decision G4-Q2, 2026-10-21):
     * the printed goods of one print line that will not ship - their order cancelled, or its unshipped rest
     * closed - are written off as SCRAP, never sold. They left stock as {@code CONSUMPTION} when production
     * completed, so they first come back onto their packing-area layer ({@code PUT_BACK}, reference {@code ORDER})
     * under a new pinned hold of the order - out of available-to-promise, out of every other order's reach - and a
     * SCRAP write-off of exactly that hold is asked for in {@code requestedBy}'s name, for a second person to approve
     * (SCRUM-145). Approved, the hold closes and stock falls again: the units leave the books once, as scrap.
     * Rejected, they stay held and are asked again ({@code askScrapAgain}).
     *
     * <p>A hold still live as finished goods in packing (an order handed over before G4-Q2 and cancelled before the
     * in-flight data was converted) is scrapped whole as {@link #scrapCancelledPrint(UUID, UUID, String, UUID)}
     * does. No more units come back than the line's holds consumed; a hold never printed, released, or picked is
     * skipped. Joins the caller's transaction. Idempotent on {@code requestId}.</p>
     *
     * @return the write-offs asked for, or standing
     */
    java.util.List<StockAdjustmentSummary> scrapCancelledPrint(ScrapCancelledPrintCommand command);

    /**
     * Decision G4-Q2 (2026-10-21): printed goods leave stock when their production order COMPLETES, not when they are
     * packed. A hold of finished goods production has just handed to a PACKING area for its order ({@link #finishHeld})
     * is consumed whole: {@code on_hand} falls by it, the hold becomes CONSUMED, one {@code CONSUMPTION} ledger line
     * (reference {@code PRODUCTION_ORDER}, "Hold &lt;id&gt;") is written and {@code StockDeducted} is published.
     * Packing then only puts the goods into parcels, as it does with picked goods; it deducts nothing. Joins the
     * caller's transaction.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND};
     *         {@code RESERVATION_NOT_ISSUABLE} for a hold not {@code orderId}'s; {@code RESERVATION_NOT_ACTIVE} for a
     *         closed one; {@code STOCK_STATUS_MISMATCH} for goods production did not hand to a packing area
     */
    StockMove consumeFinishedGoods(UUID reservationId, UUID orderId, UUID productionOrderId, UUID actorId);

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

    /** See {@link HoldReceivedStockCommand}. Joins the caller's transaction. */
    StockReservation holdReceived(HoldReceivedStockCommand command);

    /** See {@link ConsumeHeldStockCommand}. Joins the caller's transaction. */
    StockMove consumeHeld(ConsumeHeldStockCommand command);

    /**
     * The holds among {@code reservationIds} that are still live, with what they hold now and where. Holds on
     * stock items only: a SKU reservation holds no particular goods yet.
     */
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
     * Where an order's stock is held right now (SCRUM-466): one row per live (HELD) hold, by SKU and
     * location, in SKU order. Read-only; empty once the holds were consumed or released.
     */
    List<OrderHold> holdsOf(UUID orderId);

    /**
     * The holds with these ids, in any status, with the SKU, location and lot each sits on (SCRUM-267):
     * fulfillment builds a pick list from them. Read-only; an unknown id is simply absent.
     */
    List<HoldView> holds(java.util.Collection<UUID> reservationIds);

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
     * Count received goods in at a receiving location as INBOUND stock (SCRUM-435, docs 03 step 6). Joins the
     * caller's transaction: the receipt and the stock it creates commit or roll back together. Always a new stock
     * layer, never merged into an older receipt's.
     *
     * <p>SCRUM-473: the goods go into the line's labelled cartons first ({@link #countReceivingPackages}) - one
     * INBOUND row per carton, holding what was counted into it, the carton IN_STOCK and still unopened, one RECEIPT
     * ledger line per carton naming it. Only the remainder goes loose, as one more row and ledger line, and none
     * when the cartons hold everything. The move returned is the loose line, or the first carton's when nothing is
     * loose; a retried receipt returns it again and writes nothing.</p>
     *
     * @throws com.stockflow.common.error.BusinessException {@code LOCATION_NOT_FOUND}, {@code LOCATION_NOT_USABLE},
     *         {@code RECEIPT_PACKAGES_EXCEED_LINE} (the cartons hold more than {@code quantity}),
     *         {@code STOCK_PACKAGE_WRONG_LOCATION} (a carton was counted at another receiving area)
     */
    StockMove receive(ReceiveStockCommand command);

    /**
     * Label, recount and void the cartons of a goods-receipt line while its receipt is a draft (SCRUM-473): the
     * whole list each time ({@link CountReceivingPackagesCommand}). Joins the caller's transaction, under the SKU's
     * lock and then the line's cartons'. A new carton gets the next LPN of the day, {@code LPN-yyyyMMdd-nnnnnn}.
     *
     * @return the line's live cartons after the call, by LPN
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} (a carton of no unit or under 1
     *         unit, an LPN listed twice, the base unit), {@code UOM_CONVERSION_NOT_FOUND},
     *         {@code STOCK_PACKAGE_NOT_FOUND} (an LPN that is not this line's), {@code INVALID_STOCK_PACKAGE_TRANSITION}
     *         (a voided carton named again), {@code RECEIPT_LINE_HAS_PACKAGES} (a kept carton of another unit)
     */
    List<StockPackageView> countReceivingPackages(CountReceivingPackagesCommand command);

    /**
     * The cartons labelled on these goods-receipt lines, voided ones left out, each line's by LPN; a line without
     * one is absent.
     */
    Map<UUID, List<StockPackageView>> receivingPackagesOf(Collection<UUID> receiptLineIds);

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
     * Deletes the inventory item of each SKU, for a product that is deleted before anything happened to
     * it (SCRUM-461): the item exists only because its variant was created (docs 01 BR-07), and the
     * variant cannot go while it stands. A SKU with no item is skipped.
     *
     * <p>The caller asks {@link #skusInUse} first; this is the last line of defence: stock rows, movements,
     * adjustments, counts, transfer lines, purchase order and receipt lines, pick lines and putaway or move tasks
     * all point at an item through foreign keys, and any of them refuses the delete. The item's stock alerts are
     * not a use: they are deleted with it.</p>
     *
     * @throws org.springframework.dao.DataIntegrityViolationException when something still refers to an item;
     *         the caller's transaction is then rolled back as a whole
     */
    void deleteUnusedItems(java.util.Collection<String> skus);

    /**
     * The SKUs among {@code skus} whose inventory item a document uses — stock, a movement, an adjustment, a count,
     * a transfer line, a putaway, move or pick task, a selling price, a supplier item or a procurement line — in SKU
     * order; empty when none is. Such an item can be neither deleted nor renamed (kltn-docs 01 BR-01, BR-02); the
     * SKU rename guard asks the same question in the database ({@code inventory.item_in_use}).
     */
    java.util.List<String> skusInUse(java.util.Collection<String> skus);

    /**
     * SKUs that are low on stock, in SKU order, the {@code limit} after {@code afterSku} ({@code null} or empty for
     * the first page): pass the last SKU returned to get the next page; fewer than {@code limit} back means the end.
     *
     * <p>The one definition of "low stock" (plan A8): usable on-hand at or below the reorder point, or below the
     * safety stock; a threshold that is not set never makes a SKU low. The stock alerts and the dashboard read it
     * from here, so they cannot disagree about which SKUs are low.</p>
     */
    java.util.List<LowStockItem> lowStockBatch(String afterSku, int limit);

    /**
     * Ask for a stock correction with a reason (SCRUM-145). The stock does not change until a
     * different person approves it; production records scrapped blanks through this.
     */
    StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command);

    /**
     * The goods receipt lines among {@code receiptLineIds} that cartons (stock packages) were labelled on
     * ({@code inventory.stock_package.goods_receipt_line_id}, V20261020000100). Such a line is not deleted: the
     * carton points at it with a restricting foreign key (SCRUM-473).
     */
    java.util.Set<UUID> receiptLinesWithStockPackages(java.util.Collection<UUID> receiptLineIds);

    /**
     * A receipt cancelled before it was posted: the cartons labelled on these lines and still being received are
     * voided (RECEIVING → VOID), in the caller's transaction. They held no stock; their labels are never valid.
     */
    void voidStockPackagesOfReceiptLines(java.util.Collection<UUID> receiptLineIds);
}
