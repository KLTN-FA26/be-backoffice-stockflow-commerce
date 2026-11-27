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
 * <p>What other modules deliberately cannot reach: {@code StockPackage}, {@code StockPackageRepository},
 * the JPA entities, the controller. They all live under {@code inventory.internal}, and
 * {@code ModularityTest} fails the build if anyone imports them.</p>
 *
 * <p>The parameters and return types are records declared in this same package, never domain
 * objects. Handing another module a {@code StockPackage} would let it call {@code allocate()} on the
 * aggregate directly, outside a transaction and outside the invariants.</p>
 */
public interface InventoryService {

    /**
     * Available to promise for one SKU (docs 01): sellable units on hand — AVAILABLE — minus
     * every hold on them (allocations) minus the live SKU reservations (the holds Sales takes at a
     * shipping warehouse), which sit on no carton. Never negative.
     */
    int availableToPromise(Sku sku);

    /** Batch availability; missing keys mean zero. A number to show, never a hold: holding is {@link #holdAtWarehouse}. */
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

    /** Per-location breakdown, oldest receipt first (FIFO, kltn-docs 01 BR-17). Used by fulfilment when allocating. */
    List<StockAvailability> availabilityOf(Sku sku);

    /**
     * Inventory Level per warehouse for one SKU, ordered by warehouse code.
     *
     * <p>Bounded by the number of warehouses, so it is returned as a plain list rather than a page.</p>
     */
    List<StockLevel> levelsOf(Sku sku);

    /** Take every SKU's advisory lock, in SKU order, before holding any line: two orders holding two shared SKUs then
     * wait for each other instead of deadlocking. Must join the same transaction as the holds. */
    void prepareReservation(java.util.Set<Sku> skus);

    /**
     * 4B-7 (17 §4.2 step 3, BR-21; 20 §5.6, BR-17): hold, for an order of a contract, as many whole cartons of the SKU
     * as its shipping warehouse has available to promise, up to {@code cartons} - a SKU reservation at that warehouse,
     * on no bin, with no deadline. Cartons of the command's {@code unitsPerCarton} only (ADR-0011): the hold counts
     * cartons of that count, and a free carton of another count is neither promised to it nor taken by it. What is
     * not available is held later, when stock arrives; nothing is refused for a shortage. Joins the caller's transaction, under the SKU's advisory lock (take {@link #prepareReservation} first
     * for several SKUs). Idempotent on the command's request id.
     */
    HoldStockResult holdAtWarehouse(HoldStockCommand command);

    /**
     * 4B-7 (17 §4.2 step 2, 20 BR-01): what each warehouse has available to promise of the SKU, by warehouse code -
     * unheld sellable stock less the SKU's reservations held there, never more than is left system-wide. Every
     * warehouse holding the SKU is listed, whatever the caller's warehouse scope: the order's choice is the business's,
     * not a view of a warehouse.
     */
    Map<String, Integer> availableToPromiseByWarehouse(Sku sku);

    /**
     * ADR-0011: what each warehouse can still promise a line quoted in cartons of {@code unitsPerCarton}, in such
     * cartons, by warehouse code - the free cartons of exactly that count a release there could allocate, less the
     * holds of that count at it, never more than is left of that count system-wide. The figure
     * {@link #holdAtWarehouse} holds against, so a warehouse shown as covering a line can hold it. Unlike
     * {@link #availableToPromiseByWarehouse}, cartons of another count are not in it: they never serve the line, and
     * their units divided by the line's count would promise cartons that do not exist. A warehouse with none is
     * absent; every warehouse is listed whatever the caller's scope, as there.
     *
     * @throws IllegalArgumentException for a count below 1
     */
    Map<String, Integer> cartonsToPromiseByWarehouse(Sku sku, int unitsPerCarton);

    /**
     * ADR-0011: for each SKU, the cartons of the count given for it still to promise over every warehouse - the free
     * cartons of exactly that count a release could allocate, less the live holds of that count. Every SKU asked for
     * is a key, {@code 0} for none, in the order asked; one read for the whole page. For a figure shown in cartons of
     * the count a SKU is sold in, where {@link #availableToPromise(Collection)} divided by that count would count the
     * units of cartons of other counts too.
     *
     * @param unitsPerCarton the count each SKU is sold in, at least 1
     * @throws IllegalArgumentException for a count missing or below 1
     */
    Map<Sku, Integer> cartonsToPromise(Map<Sku, Integer> unitsPerCarton);

    /**
     * WF4-COUNT-EDGE (a): the live SKU reservations of {@code sku} held at {@code warehouseCode} and the whole free
     * cartons there that cover them ({@link SkuHoldCover}). Read in the caller's transaction; under the SKU's advisory
     * lock ({@link #prepareReservation}) it is the figure the next hold or release sees.
     */
    SkuHoldCover skuHoldCover(Sku sku, String warehouseCode);

    /**
     * WF4-COUNT-EDGE (a): a hold at a warehouse that stock no longer covers keeps only {@code keepUnits}. The live SKU
     * reservation is released ({@code SHORT_PICKED}: a count found its stock missing, 20 §4.2 step 8) and, when
     * {@code keepUnits} is above zero, a new one of {@code keepUnits} is held at the same warehouse for the same order -
     * not checked against what is available, as it holds less than the one it replaces. Joins the caller's transaction,
     * under the SKU's advisory lock. Idempotent: called again for the same hold and size it answers the same.
     * Units, because the caller counts in them; the hold counts cartons of one count (ADR-0011), so {@code keepUnits}
     * must be a whole number of them.
     *
     * @return the new hold's id; empty when {@code keepUnits} is zero
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND} (not a SKU reservation at a
     *         warehouse), {@code RESERVATION_NOT_ACTIVE} (already closed, and not by this call),
     *         {@code VALIDATION_FAILED} ({@code keepUnits} negative, not below the hold, or not a whole number of
     *         the hold's cartons)
     */
    Optional<UUID> shrinkHold(UUID reservationId, int keepUnits);

    /**
     * WF4-COUNT-EDGE (b): the holds among {@code reservationIds} that an approved count gave up with their carton (20 §4.2
     * step 8): allocations released as a short pick whose carton is empty now - the shortage of a released order that the
     * reallocation of a short line replaces ({@link #reallocate}). Read-only; any other id is left out.
     */
    java.util.Set<UUID> holdsGivenUpWithTheirCarton(Collection<UUID> reservationIds);

    /**
     * HELD → ALLOCATED (docs 03 §6, 04c F-FUL step 02): the order was released to a warehouse; each live SKU
     * reservation of the order becomes allocations of whole LPN cartons on pickable bins of that warehouse, one
     * allocation per carton, in the SKU's removal order - the earliest receipt first (FIFO, kltn-docs 07 BR-03) - and
     * never on loose units, on a carton another order holds part of, or on blocked or non-pickable stock. A
     * reservation takes cartons of its own count only (ADR-0011): one held in cartons of 1000 is never given a carton
     * of another count, older or not. All or nothing: when the warehouse has too few whole
     * cartons of that count for every line, nothing is allocated (k &lt; N waits for {@code AWAITING_STOCK}, team plan 4A-6). Idempotent: an order with no SKU reservation left
     * gets an empty result. Joins the caller's transaction; locks the SKUs, then the reservations, then the stock
     * rows in id order.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INSUFFICIENT_STOCK} naming the SKU and warehouse;
     *         {@code RESERVATION_NOT_ACTIVE}
     */
    AllocateStockResult allocate(AllocateStockCommand command);

    /**
     * X-SHORT reallocation: the allocation came up short - its carton was not found whole. It is released, and the
     * same quantity is allocated again in the same warehouse, as whole cartons in the SKU's removal order, from any
     * pickable carton but that one, its bin's other cartons included (07 BR-03, BR-09). An allocation already given up
     * because an approved count variance wrote its carton off (20 §4.2 step 8) is taken as released: only the new
     * allocations are made. Joins the caller's transaction. Idempotent on the command's request id.
     *
     * @return the new allocations, in pick order
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND} (unknown, or another
     *         order's), {@code RESERVATION_NOT_ACTIVE}, {@code INSUFFICIENT_STOCK} (nothing changes)
     */
    List<StockReservation> reallocate(ReallocateStockCommand command);

    /**
     * A carton removed at the check station (kltn-docs 08 §4.2 step 4: damaged, it goes to quarantine) is a shortage of
     * its order line, handled as a short pick (07 §4.4 step 10): the same quantity is allocated again in the same
     * warehouse, as whole cartons in the SKU's removal order, from any pickable carton but that one. Unlike
     * {@link #reallocate}, the hold - CONSUMED, its carton picked and deducted - is left as it is: the carton goes back
     * into stock, in a quarantine area, through it. Joins the caller's transaction. Idempotent on the command's request
     * id, whatever became of what it allocated (picked since, or released).
     *
     * @return the new allocations, in pick order; on a replay, what the request made
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND} (unknown, or another
     *         order's), {@code RESERVATION_NOT_ACTIVE} unless the hold is CONSUMED, {@code INSUFFICIENT_STOCK}
     *         (nothing changes)
     */
    List<StockReservation> reallocateRemoved(ReallocateStockCommand command);

    /** Release a reservation — the order cancelled or closed, or a hold released by hand. */
    void release(UUID reservationId, String reason);

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
     * Count received goods in at a receiving location as INBOUND stock (SCRUM-435, docs 03 step 6). Joins the
     * caller's transaction: the receipt and the stock it creates commit or roll back together. Always a new stock
     * layer, never merged into an older receipt's.
     *
     * <p>Every unit arrives in one of the line's labelled cartons ({@link #countReceivingPackages}; kltn-docs 03
     * BR-12): one INBOUND row per carton, holding the SKU's standard count, the carton IN_STOCK and sealed, one
     * RECEIPT ledger line per carton naming it. Nothing goes loose (team plan 4C-2): a line whose cartons do not hold
     * exactly {@code quantity} is refused. The move returned is the first carton's; a retried receipt returns it again
     * and writes nothing.</p>
     *
     * @throws com.stockflow.common.error.BusinessException {@code LOCATION_NOT_FOUND}, {@code LOCATION_NOT_USABLE},
     *         {@code RECEIPT_PACKAGES_EXCEED_LINE} (the cartons hold more than {@code quantity}),
     *         {@code RECEIPT_LINE_NOT_IN_CARTONS} (they hold less: units outside a carton are not received),
     *         {@code STOCK_PACKAGE_WRONG_LOCATION} (a carton was counted at another receiving area)
     */
    StockMove receive(ReceiveStockCommand command);

    /**
     * Label, recount and void the cartons of a goods-receipt line while its receipt is a draft (SCRUM-473): the
     * whole list each time ({@link CountReceivingPackagesCommand}). Joins the caller's transaction, under the SKU's
     * lock and then the line's cartons'. A new carton gets the next LPN of the day, {@code LPN-yyyyMMdd-nnnnnn}. Every
     * carton holds the packing unit's factor, the SKU's standard count: the system assigns it, nobody types another
     * (kltn-docs 03 BR-13 - a carton of another count is not received at all).
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
     * Move labelled cartons whole (SCRUM-473; kltn-docs 11 BR-04): each carton and its rows change location together,
     * keeping their ids, holds and sealed state; one MOVE ledger line per row names the carton. Only an
     * IN_STOCK carton moves, and all or none of them do (11 BR-07). A move a person asks for ({@link
     * MoveReference#MOVE_TASK}) never takes a carton held for an order (11 BR-05: the picker would find its bin empty)
     * nor one a cycle count holds, and is answered again, not repeated, for the same reference id. Locks, once for the
     * whole list: the SKUs in code order, then the cartons, then their rows, each in id order.
     *
     * @return the cartons after the move, by LPN
     * @throws com.stockflow.common.error.BusinessException {@code STOCK_PACKAGE_NOT_FOUND},
     *         {@code VALIDATION_FAILED} (already there), {@code MOVE_ACROSS_WAREHOUSES},
     *         {@code LOCATION_NOT_FOUND}, {@code LOCATION_NOT_USABLE}, {@code INVALID_STOCK_PACKAGE_TRANSITION}
     *         (a carton is not IN_STOCK), {@code STOCK_PACKAGE_HELD}, {@code STOCK_SLOT_UNDER_COUNT}
     */
    List<StockPackageView> moveStockPackages(MoveStockPackagesCommand command);

    /**
     * A decision about whole cartons: {@link #moveStockPackages} and the rows' status change in one step, so
     * no carton is split or opened. Same errors, plus {@code STOCK_STATUS_MISMATCH} (a row is not INBOUND or
     * QUARANTINE).
     */
    List<StockPackageView> reclassifyStockPackages(ReclassifyStockPackagesCommand command);

    /**
     * Opens the inventory item of each customer SKU an order names, in the caller's transaction (owner decision Q5 of
     * 2026-11-26): a customer's SKU has an item - and so can be received, held, counted and shipped - only once Sales
     * converts a quote naming it into an order, and a catalogue variant never has one. The item records the catalogue
     * variant whose cartons it is packed in, for good. An item that exists already is kept as it is, so a reorder, a
     * retried conversion and two orders converted at once all end with the one item.
     *
     * <p>{@code tg_inventory_items_customer_sku} refuses an item whose SKU is not a customer's SKU drawn on that
     * variant; the caller (order placement) passes what {@code CustomerSkus} says.</p>
     *
     * @return the item id of every SKU of the command
     * @throws com.stockflow.common.error.BusinessException {@code CONFLICT} when a SKU's existing item records another
     *         variant, or one SKU is named with two variants
     */
    Map<String, UUID> openItems(OpenInventoryItemsCommand command);

    /** The inventory item with this id, empty when no such item exists. */
    Optional<InventoryItemPolicy> itemPolicy(UUID inventoryItemId);

    /** The same, by SKU: the inventory item a customer's SKU has, empty when no order has named it yet. */
    Optional<InventoryItemPolicy> itemPolicy(Sku sku);

    /** Each inventory item that exists, in one query (SCRUM-464). */
    java.util.Map<UUID, InventoryItemPolicy> itemPolicies(java.util.Collection<UUID> inventoryItemIds);

    /** The SKU of each inventory item that exists, in one query. */
    java.util.Map<UUID, String> skusOf(java.util.Collection<UUID> inventoryItemIds);

    /**
     * Deletes the inventory item of each SKU, for a product that is deleted before anything happened to
     * it (SCRUM-461). Since V20270105000100 only a customer's SKU that an order named has an item, so a product
     * nothing happened to has none and this deletes nothing; kept for the product deletion's last check. A SKU with
     * no item is skipped.
     *
     * <p>The caller asks {@link #skusInUse} first; this is the last line of defence: cartons, movements,
     * adjustments, counts, purchase order and receipt lines, pick lines and putaway or move tasks
     * all point at an item through foreign keys, and any of them refuses the delete.</p>
     *
     * @throws org.springframework.dao.DataIntegrityViolationException when something still refers to an item;
     *         the caller's transaction is then rolled back as a whole
     */
    void deleteUnusedItems(java.util.Collection<String> skus);

    /**
     * The SKUs among {@code skus} whose inventory item a document uses — stock, a movement, an adjustment, a count,
     * a putaway, move or pick task, a selling price, a supplier item or a procurement line — in SKU
     * order; empty when none is. Such an item cannot be deleted (kltn-docs 01 BR-01, BR-02); since V20270105000100 an
     * item's SKU never changes at all ({@code tg_inventory_items_immutable}), so this no longer guards a rename. A SKU
     * without an item - a catalogue variant, or a customer's SKU never ordered - is never in use.
     */
    java.util.List<String> skusInUse(java.util.Collection<String> skus);

    /**
     * Ask for a stock correction with a reason (SCRUM-145). The stock does not change until a
     * different person approves it.
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
