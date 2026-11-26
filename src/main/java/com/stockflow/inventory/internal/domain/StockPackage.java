package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.StockDeducted;
import com.stockflow.contracts.StockReservationReleased;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * <b>The aggregate root of the inventory module:</b> one physical carton with its own LPN (GitHub #125, SCRUM-473) -
 * and the stock itself (ADR-0011, V20261230000600).
 *
 * <p><b>The carton is the stock.</b> There is no quantity anywhere: a carton in stock stands for {@link #nominalQty}
 * units of its SKU (its packing unit's factor when it was made, the one place that says how many), in
 * {@link #stockStatus}, of lot {@link #lotId}, received at {@link #receivedAt}; out of stock it stands for nothing.
 * Until V20261230000600 a second row ({@code stock_item}) repeated all of that in units and had to be kept equal to
 * the carton at commit. Whole cartons only (team plan 4C-2; kltn-docs 07 BR-03, 20 §4.6): a carton is received,
 * allocated, picked, counted, written off and restocked whole, so nothing here can take part of one.</p>
 *
 * <p>The invariants it exists to protect:</p>
 * <ul>
 *   <li>a carton has a stock status exactly while it is {@code IN_STOCK}, and a lot once it has been in stock
 *       ({@code ck_stock_package_stock_status}, {@code ck_stock_package_lot});</li>
 *   <li><b>at most one live allocation</b> - the rule that prevents giving one carton to two orders; "is this carton
 *       taken" is that allocation, not a counter ({@code uk_stock_reservation_live_carton});</li>
 *   <li>only a free AVAILABLE carton of the hold's count is allocated ({@code tg_stock_reservation_carton});</li>
 *   <li>a held carton stays AVAILABLE: it is not quarantined, reclassified, written off or lost from under its hold
 *       ({@code tg_stock_package_held_available}) - the hold is released, reallocated or given up first;</li>
 *   <li>stock leaves only through a live allocation ({@link #consumeReservation}) or whole, by a decision
 *       ({@link #consume}, {@link #lose});</li>
 *   <li>the lot and the FIFO date are written when the carton goes into stock and never after: a carton that comes
 *       back ({@link #restock}, {@link #found}) keeps them, so FIFO never loses the age of stock
 *       ({@code tg_stock_package_transition});</li>
 *   <li>an allocation is opened, consumed and released only here, never by touching a {@link Reservation}.</li>
 * </ul>
 *
 * <p><b>A carton that comes back is given its stock status by what brings it back</b> (owner decision, ADR-0011): a
 * carton out of stock has none to fall back on. A put-back of a picked carton is AVAILABLE, a carton removed as
 * damaged or returned by a customer QUARANTINE, a LOST carton found AVAILABLE unless it stands in a quarantine area.</p>
 *
 * <p><b>No Spring, no JPA, no annotations.</b> Mapping it to its table is {@code internal.entity}'s job, which is what
 * lets {@code StockPackageTest} exercise every rule above with no context to boot. The imports from
 * {@code contracts} are the events it causes, which is domain knowledge.</p>
 *
 * <p><b>Known limit - one order line spans several cartons.</b> {@link StockAllocator} owns the choice of which and in
 * what order (FIFO); mutating them in one transaction is correct in a single-database monolith
 * ({@code docs/adr/0005-modular-monolith.md}).</p>
 */
public final class StockPackage extends AggregateRoot {

    // final: an aggregate root has no subtypes, and the constructor calls checkInvariants().

    private static final Pattern LPN = Pattern.compile("[A-Z0-9][A-Z0-9-]{3,29}");

    private final StockPackageId id;
    private final String lpn;
    private final Sku sku;
    private final UUID inventoryItemId;
    private final UUID uomConversionId;
    private final int nominalQty;
    private final UUID goodsReceiptLineId;
    private final long version;
    private final List<Reservation> reservations;
    private Instant countedAt;
    private LocationId location;
    private StockPackageStatus status;
    private StockStatus stockStatus;
    private UUID lotId;
    private String lotNumber;
    private Instant receivedAt;
    private Instant closedAt;

    /** As stored. {@code sku} is the item's and {@code lotNumber} the lot's: read with the carton, never written by it. */
    @SuppressWarnings("java:S107")
    public StockPackage(StockPackageId id, String lpn, Sku sku, UUID inventoryItemId, UUID uomConversionId,
                        int nominalQty, Instant countedAt, LocationId location, StockPackageStatus status,
                        StockStatus stockStatus, UUID lotId, String lotNumber, Instant receivedAt,
                        UUID goodsReceiptLineId, Instant closedAt, List<Reservation> reservations, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.lpn = Objects.requireNonNull(lpn, "lpn");
        this.sku = Objects.requireNonNull(sku, "sku");
        this.inventoryItemId = Objects.requireNonNull(inventoryItemId, "inventoryItemId");
        this.uomConversionId = Objects.requireNonNull(uomConversionId, "uomConversionId");
        this.nominalQty = nominalQty;
        this.countedAt = countedAt;
        this.location = Objects.requireNonNull(location, "location");
        this.status = Objects.requireNonNull(status, "status");
        this.stockStatus = stockStatus;
        this.lotId = lotId;
        this.lotNumber = lotNumber;
        this.receivedAt = receivedAt;
        this.goodsReceiptLineId = goodsReceiptLineId;
        this.closedAt = closedAt;
        this.reservations = new ArrayList<>(reservations == null ? List.of() : reservations);
        this.version = version;
        checkInvariants();
    }

    /** A carton labelled on a receipt line, still to be counted ({@code tg_stock_package_created}). */
    public static StockPackage receiving(StockPackageId id, String lpn, Sku sku, UUID inventoryItemId,
                                         UUID uomConversionId, int factor, LocationId receivingArea,
                                         UUID goodsReceiptLineId) {
        return new StockPackage(id, lpn, sku, inventoryItemId, uomConversionId, factor, null, receivingArea,
                StockPackageStatus.RECEIVING, null, null, null, null,
                Objects.requireNonNull(goodsReceiptLineId, "goodsReceiptLineId"), null, List.of(), 0L);
    }

    // ------------------------------------------------------------------ receiving

    /**
     * Counted (again) while still being received: seen on the line, at {@code now}. Nobody types a number - it
     * stands for its standard count, {@link #nominalQty} (kltn-docs 03 BR-13: a carton of another count is not listed
     * at all) - so the count is only the moment; a recount of the draft moves it to the later one. Fixed once in stock
     * ({@code tg_stock_package_transition}).
     */
    public void count(Instant now) {
        requireStatus(StockPackageStatus.RECEIVING, "be recounted");
        countedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * RECEIVING → IN_STOCK: its receipt was posted (docs 03 step 6, SCRUM-435). It must have been counted in. It
     * starts {@link StockStatus#INBOUND} - received, not yet put away, never sellable - in the lot the receipt opened,
     * with the receipt's time as its FIFO date. This is the only place a carton gets its lot and its date
     * (kltn-docs 20 BR-22: every unit carries a lot; {@code StockLots.UNTRACED} when none is known).
     */
    public void putInStock(UUID lot, String lotNumberOfLot, Instant receipt) {
        requireStatus(StockPackageStatus.RECEIVING, "go into stock");
        if (countedAt == null) {
            throw transition("%s has not been counted".formatted(lpn));
        }
        if (lotNumberOfLot == null || lotNumberOfLot.isBlank()) {
            throw new IllegalArgumentException("Every unit carries a lot; " + StockLots.UNTRACED + " when none is known");
        }
        Objects.requireNonNull(lot, "lot");
        Objects.requireNonNull(receipt, "receipt");
        lotId = lot;
        lotNumber = lotNumberOfLot;
        receivedAt = receipt;
        status = StockPackageStatus.IN_STOCK;
        stockStatus = StockStatus.INBOUND;
        checkInvariants();
    }

    /** RECEIVING → VOID: its receipt was cancelled before posting. */
    public void cancel(Instant now) {
        requireStatus(StockPackageStatus.RECEIVING, "be voided");
        status = StockPackageStatus.VOID;
        closedAt = Objects.requireNonNull(now, "now");
    }

    // ------------------------------------------------------------------ where it stands, and as what

    /**
     * Moved whole. Its allocation, if it has one, goes with it: that is how a pick stages a carton (team plan 4C-5).
     * Whether a held carton may be moved at all is the caller's rule (a person's move may not, kltn-docs 11 BR-05).
     */
    public void moveTo(LocationId destination) {
        if (status.isClosed()) {
            throw transition("%s is %s; a closed carton is not moved".formatted(lpn, status));
        }
        location = Objects.requireNonNull(destination, "destination");
    }

    /**
     * A whole carton is put on hold or condemned, or put away as sellable stock: its stock status changes in place.
     * A hold on it would silently lose its stock.
     *
     * <p>AVAILABLE is putaway's target: from INBOUND (a receipt's carton), or from QUARANTINE (a carton a warehouse
     * manager released, kltn-docs 05 §4.4, BR-14 - team plan 4D-1). Which of the two a putaway may take is
     * {@link #putAway}'s to check against its task's source; this only refuses what no putaway makes AVAILABLE.</p>
     */
    public void reclassify(StockStatus target) {
        requireInStock("be reclassified");
        if (stockStatus != StockStatus.INBOUND && stockStatus != StockStatus.QUARANTINE) {
            throw new IllegalStateException("Stock package %s is %s, not INBOUND or QUARANTINE".formatted(lpn,
                    stockStatus));
        }
        if (target != StockStatus.QUARANTINE && target != StockStatus.BLOCKED && target != StockStatus.AVAILABLE) {
            throw new IllegalStateException(target + " is not AVAILABLE, QUARANTINE or BLOCKED");
        }
        requireUnheld("reclassify");
        stockStatus = target;
    }

    /**
     * Putaway: the carton becomes AVAILABLE from the status its task puts away - INBOUND for a receipt's task,
     * QUARANTINE for a quarantine release's (kltn-docs 05 BR-01, BR-14; team plan 4D-1) - and from nothing else, so
     * neither task can put the other's goods on sale.
     */
    public void putAway(StockStatus from) {
        if (from != StockStatus.INBOUND && from != StockStatus.QUARANTINE) {
            throw new IllegalArgumentException("A putaway takes INBOUND or QUARANTINE stock, not " + from);
        }
        if (stockStatus != from) {
            throw new IllegalStateException("Stock package %s is %s; this putaway takes %s stock".formatted(lpn,
                    stockStatus == null ? status : stockStatus, from));
        }
        reclassify(StockStatus.AVAILABLE);
    }

    /**
     * A warehouse manager quarantines the carton (kltn-docs 20 §4.8, BR-21; team plan 4D-1): sellable or
     * still-to-be-put-away stock goes on hold, in place. Only a free carton: a carton held for an order would lose its
     * hold silently, so the caller reallocates it first; the table says it again
     * ({@code tg_stock_package_held_available}). Quarantined stock is not reservable
     * ({@link StockStatus#isReservable}), so it stays out of ATP and allocation until a putaway makes it AVAILABLE.
     */
    public void quarantine() {
        requireInStock("be quarantined");
        if (stockStatus != StockStatus.AVAILABLE && stockStatus != StockStatus.INBOUND) {
            throw new IllegalStateException("Stock package %s is %s, not AVAILABLE or INBOUND".formatted(lpn,
                    stockStatus));
        }
        requireUnheld("quarantine");
        stockStatus = StockStatus.QUARANTINE;
    }

    // ------------------------------------------------------------------ out of stock, and back

    /**
     * IN_STOCK → CONSUMED: it left stock whole by a decision - written off, or a returned carton handed back to its
     * customer. A pick is not this: it leaves through its allocation ({@link #consumeReservation}). A carton held for
     * an order is refused; a write-off that may take it gives the hold up first
     * ({@link #giveUpHoldsOfWrittenOffCarton}).
     */
    public void consume(Instant now) {
        requireStatus(StockPackageStatus.IN_STOCK, "be emptied");
        requireUnheld("write off");
        leaveStock(StockPackageStatus.CONSUMED, now);
    }

    /** IN_STOCK → LOST: a count did not find it, or it was written off as lost (kltn-docs 20 §4.6 step 7). */
    public void lose(Instant now) {
        requireStatus(StockPackageStatus.IN_STOCK, "be marked lost");
        requireUnheld("lose");
        leaveStock(StockPackageStatus.LOST, now);
    }

    /**
     * CONSUMED → IN_STOCK at {@code location}, as {@code as}: the carton came back whole (kltn-docs 20 §4.6 steps
     * 5-6) - put back after its order was cancelled (AVAILABLE), removed at the check station as damaged or received
     * again on a return (QUARANTINE). It keeps its LPN, its lot and its FIFO date: the same goods, as old as they were.
     * The caller names the status; a carton out of stock has none of its own.
     *
     * @throws BusinessException {@code INVALID_STOCK_PACKAGE_TRANSITION} unless CONSUMED
     */
    public void restock(LocationId location, StockStatus as) {
        requireStatus(StockPackageStatus.CONSUMED, "come back into stock");
        comeBack(as);
        this.location = Objects.requireNonNull(location, "location");
    }

    /**
     * LOST → IN_STOCK where it stands, as {@code as}: found again, whole (kltn-docs 20 §4.6 step 7, BR-18: the very
     * carton, its standard count, its lot). An approved restock does it: AVAILABLE on the shelf, QUARANTINE in a
     * quarantine area.
     *
     * @throws BusinessException {@code INVALID_STOCK_PACKAGE_TRANSITION} unless LOST
     */
    public void found(StockStatus as) {
        requireStatus(StockPackageStatus.LOST, "be found");
        comeBack(as);
    }

    private void comeBack(StockStatus as) {
        if (as != StockStatus.AVAILABLE && as != StockStatus.QUARANTINE) {
            throw new IllegalArgumentException(as + " is not AVAILABLE or QUARANTINE");
        }
        status = StockPackageStatus.IN_STOCK;
        stockStatus = as;
        closedAt = null;
        checkInvariants();
    }

    private void leaveStock(StockPackageStatus as, Instant now) {
        status = as;
        stockStatus = null;
        closedAt = Objects.requireNonNull(now, "now");
        checkInvariants();
    }

    // ------------------------------------------------------------------ allocations

    /**
     * Give this carton to a SKU reservation (F-FUL step 02, BR-STK-006): the order was released to this warehouse and
     * the planner chose this carton. A hard hold that the picker will walk to; the carton stops being counted in the
     * SKU's soft reservation once the caller marks that {@link SkuReservation#markAllocated}.
     * Idempotent on {@code requestId}. Announces {@code StockAllocated}; available-to-promise does not change.
     *
     * @throws InsufficientStockException when the carton is no longer free, or is not of the hold's count
     */
    public Reservation allocate(SkuReservation from, UUID requestId, Instant now) {
        if (!from.sku().equals(sku)) {
            throw new IllegalArgumentException("Reservation %s is for %s, not %s".formatted(from.id(), from.sku(), sku));
        }
        return allocate(from.orderId(), from.id().value(), from.id().value(), requestId, from.unitsPerCarton(), now);
    }

    /**
     * An allocation of this carton to {@code orderId}, made from the SKU reservation {@code allocatedFrom} — at
     * release, or again after a short pick elsewhere (X-SHORT). {@code rootRequestId} is what a replay of the call
     * that made it looks for.
     *
     * <p><b>The whole carton, of the count asked for, and only while it is free (ADR-0011).</b> An allocation is the
     * carton: it must be AVAILABLE, of exactly {@code unitsPerCarton}, with no live allocation. A carton of another
     * count is not a candidate; the planner never offers one, and this is the same rule read again under the carton's
     * lock ({@code tg_stock_reservation_carton} and {@code uk_stock_reservation_live_carton} state it a third time).
     * That it does not stand in a staging area is the planner's to know ({@code PickableStock}) and the trigger's.</p>
     *
     * @throws InsufficientStockException when the carton is not a free carton of {@code unitsPerCarton}
     */
    public Reservation allocate(UUID orderId, UUID allocatedFrom, UUID rootRequestId, UUID requestId,
                                int unitsPerCarton, Instant now) {
        Optional<Reservation> alreadyServed = findByRequestId(requestId);
        if (alreadyServed.isPresent()) {
            return alreadyServed.get();
        }
        if (stockStatus == null || !stockStatus.isReservable()) {
            throw new IllegalStateException("Stock package %s is %s and cannot be allocated to order %s".formatted(lpn,
                    stockStatus == null ? status : stockStatus, orderId));
        }
        if (isHeld() || nominalQty != unitsPerCarton) {
            throw new InsufficientStockException(sku.code(), unitsPerCarton, 0);
        }
        Reservation reservation = Reservation.allocate(orderId, allocatedFrom, rootRequestId, requestId,
                unitsPerCarton, now);
        reservations.add(reservation);
        checkInvariants();
        registerEvent(new StockEvent.Allocated(new com.stockflow.contracts.StockAllocated(orderId,
                reservation.id().value(), sku.code(), location.code(), lotNumber, unitsPerCarton)));
        return reservation;
    }

    /**
     * Give up a hold — order cancelled, or released by hand. The carton is free again.
     *
     * <p>Releasing an already-closed reservation is a no-op rather than an error, because the
     * cancel path and a release by hand can genuinely race on the same carton, and neither should
     * fail because the other got there first.
     */
    public void releaseReservation(ReservationId reservationId, ReleaseReason reason, Instant now) {
        Reservation reservation = findReservation(reservationId).orElseThrow(() -> new IllegalArgumentException(
                "Reservation %s does not belong to stock package %s".formatted(reservationId, lpn)));
        if (reservation.status().isTerminal()) {
            return;
        }
        reservation.release(reason, now);
        checkInvariants();
        registerEvent(new StockEvent.Released(new StockReservationReleased(reservation.orderId(), sku.code(),
                reservation.quantity().value(), reason.toContract())));
    }

    /**
     * The carton is written off by an approved count's shortage (20 §4.2 step 8), or quarantined after its picker
     * reported it damaged: it is not there for its order, so its live hold is a shortage. The hold is given up as a
     * short pick ({@link ReleaseReason#SHORT_PICKED}, what a reallocation does to the same hold) and announced as
     * {@code StockHeldCartonWrittenOff}, which fulfillment turns into a SHORT line and a held order after the commit.
     * {@code decidedBy} is who decided. Call before {@link #lose}, {@link #consume} or {@link #quarantine}, which
     * refuse a held carton. Nothing happens on a carton with no live hold.
     */
    public void giveUpHoldsOfWrittenOffCarton(UUID decidedBy, Instant now) {
        for (Reservation hold : reservations.stream().filter(r -> r.status().isActive()).toList()) {
            releaseReservation(hold.id(), ReleaseReason.SHORT_PICKED, now);
            registerEvent(new StockEvent.HeldCartonWrittenOff(new com.stockflow.contracts.StockHeldCartonWrittenOff(
                    hold.orderId(), hold.id().value(), sku.code(), location.code(), hold.quantity().value(), decidedBy)));
        }
    }

    /**
     * WF4-COUNT-EDGE (a): this carton was written off by an approved count's shortage (20 §4.2 step 8). Announced as
     * {@code StockWrittenOffByCount}, in units as the event always was, after the save, so the order module can
     * recompute what orders hold of the SKU at the warehouse by SKU - a carton fewer may leave a hold uncovered.
     */
    public void announceCountWriteOff(UUID decidedBy) {
        registerEvent(new StockEvent.WrittenOffByCount(new com.stockflow.contracts.StockWrittenOffByCount(sku.code(),
                location.warehouseCode(), nominalQty, decidedBy)));
    }

    /**
     * The carton has been picked: its hold becomes a real deduction and the carton leaves stock, IN_STOCK → CONSUMED,
     * in one step (BRD 3.7 / 3.8).
     *
     * <p>This is the only way a carton leaves stock for an order, and it can only do so through a live allocation.
     * Physical stock therefore never leaves the building without a promise behind it.</p>
     *
     * <p>The hold is checked before the stock: a hold that was released or consumed already is
     * {@code RESERVATION_NOT_ACTIVE} whatever happened to the carton since, so a picker is told the real
     * reason (the order was cancelled) rather than a policy conflict. Then the stock: only sellable stock in stock
     * ships ({@code INVENTORY_POLICY_STOCK_CONFLICT}, docs 07 BR-02) - a state no live hold should ever meet (a held
     * carton stays AVAILABLE), kept as the refusal a corrupted row gets.</p>
     */
    public void consumeReservation(ReservationId reservationId, Instant now) {
        Reservation reservation = findReservation(reservationId).orElseThrow(() -> new BusinessException(
                ErrorCode.RESERVATION_NOT_FOUND,
                "Reservation %s does not belong to stock package %s".formatted(reservationId, lpn)));
        if (reservation.status().isTerminal()) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "Reservation %s is already %s".formatted(reservationId, reservation.status()));
        }
        if (status != StockPackageStatus.IN_STOCK || !stockStatus.isReservable()) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }
        reservation.consume(now);
        LocationId from = location;
        leaveStock(StockPackageStatus.CONSUMED, now);
        registerEvent(new StockEvent.Deducted(new StockDeducted(reservation.orderId(), sku.code(), from.code(),
                reservation.quantity().value())));
    }

    // ------------------------------------------------------------------ invariants

    /**
     * Checked in the constructor and after every mutation that touches what they are about, so an aggregate can never
     * be observed in a broken state — not even one rehydrated from a row a bad migration corrupted.
     */
    private void checkInvariants() {
        if (!LPN.matcher(lpn).matches()) {
            throw new IllegalStateException("LPN %s is not 4-30 upper-case letters, digits or '-'".formatted(lpn));
        }
        if (nominalQty <= 0) {
            throw new IllegalStateException("Stock package %s: nominal quantity %d".formatted(lpn, nominalQty));
        }
        if (countedAt == null && status != StockPackageStatus.RECEIVING && status != StockPackageStatus.VOID) {
            throw new IllegalStateException("Stock package %s is %s but was never counted".formatted(lpn, status));
        }
        if (status.isClosed() != (closedAt != null)) {
            throw new IllegalStateException("Stock package %s is %s with closed_at %s".formatted(lpn, status, closedAt));
        }
        if ((status == StockPackageStatus.IN_STOCK) != (stockStatus != null)) {
            throw new IllegalStateException(("Stock package %s is %s with stock status %s: a carton has one exactly "
                    + "while it is in stock").formatted(lpn, status, stockStatus));
        }
        if (lotId == null && status != StockPackageStatus.RECEIVING && status != StockPackageStatus.VOID) {
            throw new IllegalStateException("Stock package %s is %s but has no lot".formatted(lpn, status));
        }
        long live = reservations.stream().filter(r -> r.status().isActive()).count();
        if (live > 1 || (live == 1 && status != StockPackageStatus.IN_STOCK)) {
            throw new IllegalStateException("Invariant violated on stock package %s (%s): %d live allocation(s)"
                    .formatted(lpn, status, live));
        }
    }

    private void requireStatus(StockPackageStatus expected, String action) {
        if (status != expected) {
            throw transition("%s is %s and cannot %s".formatted(lpn, status, action));
        }
    }

    private void requireInStock(String action) {
        if (status != StockPackageStatus.IN_STOCK) {
            throw new IllegalStateException("Stock package %s is %s and cannot %s".formatted(lpn, status, action));
        }
    }

    private void requireUnheld(String action) {
        liveAllocation().ifPresent(hold -> {
            throw new IllegalStateException("Cannot %s stock package %s: it is held for order %s".formatted(action,
                    lpn, hold.orderId()));
        });
    }

    private static BusinessException transition(String message) {
        return new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION, message);
    }

    // ------------------------------------------------------------------ queries

    public StockPackageId id() { return id; }
    public String lpn() { return lpn; }
    /** The SKU of the carton's item; it never changes. */
    public Sku sku() { return sku; }
    public UUID inventoryItemId() { return inventoryItemId; }
    public UUID uomConversionId() { return uomConversionId; }
    public int nominalQty() { return nominalQty; }
    /** When it was counted in at receiving; null while it is only labelled. */
    public Instant countedAt() { return countedAt; }
    /** Whether it has been counted in: it then stands for {@link #nominalQty} units. */
    public boolean counted() { return countedAt != null; }
    public LocationId location() { return location; }
    public StockPackageStatus status() { return status; }
    /** INBOUND, AVAILABLE, QUARANTINE, ...; null exactly when the carton is not in stock. */
    public StockStatus stockStatus() { return stockStatus; }
    public boolean inStock() { return status == StockPackageStatus.IN_STOCK; }
    /** The carton's lot; null until it goes into stock. */
    public UUID lotId() { return lotId; }
    /** The lot's number, as {@code inventory.stock_lot} has it; null until the carton goes into stock. */
    public String lotNumber() { return lotNumber; }
    /** The FIFO date; null when the carton has not been in stock, or its receipt time is not known (it sorts last). */
    public Instant receivedAt() { return receivedAt; }
    public UUID goodsReceiptLineId() { return goodsReceiptLineId; }
    public Instant closedAt() { return closedAt; }
    public long version() { return version; }

    /** The live allocation: the order this carton is taken for, if any. At most one. */
    public Optional<Reservation> liveAllocation() {
        return reservations.stream().filter(r -> r.status().isActive()).findFirst();
    }

    /** Whether an order holds this carton. */
    public boolean isHeld() {
        return liveAllocation().isPresent();
    }

    /** Free: sellable stock no order holds - what a release may allocate and what is promised. */
    public boolean isFree() {
        return stockStatus == StockStatus.AVAILABLE && !isHeld();
    }

    /** Units, for whoever still speaks them (other modules, the events, the HTTP contract): its count while in stock. */
    public int unitsInStock() {
        return inStock() ? nominalQty : 0;
    }

    /** Units held for an order: the whole carton, or nothing. */
    public int unitsHeld() {
        return isHeld() ? nominalQty : 0;
    }

    /** Units available to promise from this carton: its count while it is free. */
    public int unitsAvailable() {
        return isFree() ? nominalQty : 0;
    }

    public Optional<Reservation> findReservation(ReservationId reservationId) {
        return reservations.stream().filter(r -> r.id().equals(reservationId)).findFirst();
    }

    /**
     * Idempotency: is this exact request currently holding this carton?
     *
     * <p><b>Only live holds count.</b> Released reservations keep their row as an audit trail, so matching on the id
     * alone would report a hold that no longer exists and hand the caller a reservation id that releases nothing. It
     * also has to agree with {@link #activeReservationsFor}, which the replay check uses.</p>
     */
    public Optional<Reservation> findByRequestId(UUID requestId) {
        return reservations.stream()
                .filter(r -> r.status().isActive())
                .filter(r -> r.requestId().equals(requestId))
                .findFirst();
    }

    /** The live hold this carton carries for one caller request: what a replay of that request finds. */
    public List<Reservation> activeReservationsFor(UUID rootRequestId) {
        return reservations.stream()
                .filter(r -> r.status().isActive())
                .filter(r -> r.rootRequestId().equals(rootRequestId))
                .toList();
    }

    /** Unmodifiable: reservations change through this aggregate's methods, never from outside. */
    public List<Reservation> reservations() {
        return Collections.unmodifiableList(reservations);
    }
}
