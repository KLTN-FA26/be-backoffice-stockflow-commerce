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

/**
 * <b>The aggregate root of the inventory module:</b> one SKU, at one location, from one lot.
 *
 * <p>The invariants it exists to protect — and the reason this is not a bag of getters and setters:
 *
 * <ul>
 *   <li>{@code onHand} is never negative;
 *   <li>the sum of active reservations never exceeds {@code onHand} — this is the rule that
 *       prevents overselling;
 *   <li>only stock that was actually held can be deducted, so a picker cannot ship goods that
 *       nobody reserved;
 *   <li>a reservation is opened, consumed and released only here, never by touching a {@link
 *       Reservation} directly.
 * </ul>
 *
 * <p><b>No Spring, no JPA, no annotations.</b> This class does not know that a database exists.
 * Mapping it to a table is {@code internal.entity}'s job, which is what lets {@code StockItemTest}
 * exercise every rule above as a plain unit test with no context to boot. The one exception is the
 * three imports from {@code contracts}: the aggregate names the events it causes, which is domain
 * knowledge, and {@code contracts} holds nothing but flat records.
 *
 * <p><b>One carton, or loose stock.</b> A row in a stock package (a carton, V20261020000200) is that carton's
 * contents - one row per carton ({@code uk_stock_item_package}, V20261113000300). A row with no
 * {@link #stockPackageId()} is loose and keyed by (SKU, location, lot, receipt time); since team plan 4C-2 hardly
 * anything writes one. A lookup of one must never land on the other. Every row carries a lot (20 BR-22).</p>
 *
 * <p><b>Known limit — one order line can span several stock items.</b> The aggregate is keyed per
 * location and layer, so 100 units spread over three locations means mutating three aggregates in
 * one transaction. {@link StockAllocator} owns the choice of which ones and in what order (FIFO),
 * and keeps that decision out of the caller. Within a single-database monolith that multi-aggregate
 * transaction is correct and atomic; it is recorded here because it is the assumption that would
 * break first if inventory were ever split back out into its own service. See {@code
 * docs/adr/0005-modular-monolith.md}.
 */
public final class StockItem extends AggregateRoot {

    // final: an aggregate root has no subtypes. Beyond the modelling point, the constructor calls
    // checkInvariants(), and javac's this-escape lint is right to flag that on a non-final class -
    // a subclass could override a method it reaches before its own fields are initialised.

    private final StockItemId id;
    private final Sku sku;
    private LocationId location;
    private final String lotNumber;
    private Instant receivedAt;
    /**
     * The lot these units belong to ({@code inventory.stock_lot}, SCRUM-142): one per goods receipt and lot number,
     * so two receipts reusing a lot number stay two lots. Travels with the units through every move, hold hand-over
     * and putaway; null only for a layer the application creates without one, which the database then resolves
     * ({@code tg_stock_item_lot}).
     */
    private UUID lotId;
    private StockPackageId stockPackageId;

    public Instant receivedAt() {
        return receivedAt;
    }

    public UUID lotId() {
        return lotId;
    }

    /** The stock package this row is the contents of; {@code null} for loose stock. */
    public StockPackageId stockPackageId() {
        return stockPackageId;
    }

    public boolean isLoose() {
        return stockPackageId == null;
    }

    /**
     * Whether this row is the layer of these goods: same lot and receipt time ({@code null}: none). Among the loose
     * rows of one SKU at one bin at most one answers yes ({@code uk_stock_item_sku_location_lot}). No serial number:
     * nothing is serial-tracked (kltn-docs 01 BR-17, team plan 5-5).
     */
    public boolean isLayer(String lot, Instant receipt) {
        return Objects.equals(lotNumber, lot) && Objects.equals(receivedAt, receipt);
    }

    /** A row rehydrated with its stock package ({@code null}: loose). */
    public StockItem(
            StockItemId id,
            Sku sku,
            LocationId location,
            String lotNumber,
            Quantity onHand,
            StockStatus status,
            List<Reservation> reservations,
            long version,
            Instant receivedAt,
            StockPackageId stockPackageId) {
        this(id, sku, location, lotNumber, onHand, status, reservations, version, receivedAt);
        this.stockPackageId = stockPackageId;
    }

    public StockItem(
            StockItemId id,
            Sku sku,
            LocationId location,
            String lotNumber,
            Quantity onHand,
            StockStatus status,
            List<Reservation> reservations,
            long version,
            Instant receivedAt) {
        this(id, sku, location, lotNumber, onHand, status, reservations, version);
        this.receivedAt = receivedAt;
    }

    /** Full constructor, as stored, with the lot the units belong to. */
    public StockItem(StockItemId id, Sku sku, LocationId location, String lotNumber,
                     Quantity onHand, StockStatus status, List<Reservation> reservations, long version,
                     Instant receivedAt, UUID lotId) {
        this(id, sku, location, lotNumber, onHand, status, reservations, version, receivedAt);
        this.lotId = lotId;
    }

    /** Full constructor, as stored: the lot the units belong to and their stock package ({@code null}: loose). */
    public StockItem(StockItemId id, Sku sku, LocationId location, String lotNumber,
                     Quantity onHand, StockStatus status, List<Reservation> reservations, long version,
                     Instant receivedAt, UUID lotId, StockPackageId stockPackageId) {
        this(id, sku, location, lotNumber, onHand, status, reservations, version, receivedAt, lotId);
        this.stockPackageId = stockPackageId;
    }

    /**
     * Receipt integration must pass the real receipt time; never infer it from persistence
     * auditing.
     */
    public static StockItem receive(
            Sku sku,
            LocationId location,
            String lotNumber,
            Quantity quantity,
            Instant receivedAt) {
        Objects.requireNonNull(receivedAt, "receivedAt");
        return new StockItem(
                StockItemId.newId(),
                sku,
                location,
                requireLot(lotNumber),
                quantity,
                StockStatus.QUARANTINE,
                List.of(),
                0L,
                receivedAt);
    }

    /**
     * Every unit carries a lot (kltn-docs 20 BR-22; {@code ck_stock_item_lot}, V20261113000300): a new layer is given
     * one - {@code StockLots.UNTRACED} when its lot is not known. Asked where goods enter stock; the other new layers
     * copy the lot of the units they came from.
     */
    private static String requireLot(String lotNumber) {
        if (lotNumber == null || lotNumber.isBlank()) {
            throw new IllegalArgumentException("Every unit carries a lot; " + StockLots.UNTRACED + " when none is known");
        }
        return lotNumber;
    }

    private Quantity onHand;
    private StockStatus status;
    private final List<Reservation> reservations;
    private final long version;

    public StockItem(
            StockItemId id,
            Sku sku,
            LocationId location,
            String lotNumber,
            Quantity onHand,
            StockStatus status,
            List<Reservation> reservations,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.sku = Objects.requireNonNull(sku, "sku");
        this.location = Objects.requireNonNull(location, "location");
        this.lotNumber = lotNumber;
        this.onHand = Objects.requireNonNull(onHand, "onHand");
        this.status = Objects.requireNonNull(status, "status");
        this.reservations = new ArrayList<>(reservations == null ? List.of() : reservations);
        this.version = version;
        checkInvariants();
    }

    /**
     * Goods have arrived from a supplier.
     *
     * <p>They start in {@link StockStatus#QUARANTINE}: nothing becomes sellable until QC says so
     * (BRD 3.3.4). Making this the only entry point means no code path can create sellable stock
     * that skipped inspection.
     */
    public static StockItem receive(
            Sku sku,
            LocationId location,
            String lotNumber,
            Quantity quantity) {
        return new StockItem(
                StockItemId.newId(),
                sku,
                location,
                requireLot(lotNumber),
                quantity,
                StockStatus.QUARANTINE,
                List.of(),
                0L);
    }

    /**
     * Goods counted in at a receiving location against a goods receipt (docs 03 step 6, SCRUM-435).
     *
     * <p>They start {@link StockStatus#INBOUND}: received, not yet put away, never sellable. One stock
     * layer per receipt: {@code receivedAt} is the confirmation time, which also tells this layer
     * apart from other receipts of the same SKU and lot at the same location.</p>
     */
    public static StockItem receiveInbound(Sku sku, LocationId location, String lotNumber,
                                           Quantity quantity, Instant receivedAt) {
        return receiveInbound(sku, location, lotNumber, quantity, receivedAt, null);
    }

    /** The same, into the lot the receipt opened (SCRUM-142). */
    public static StockItem receiveInbound(Sku sku, LocationId location, String lotNumber,
                                           Quantity quantity, Instant receivedAt, UUID lotId) {
        Objects.requireNonNull(receivedAt, "receivedAt");
        return new StockItem(StockItemId.newId(), sku, location, requireLot(lotNumber), quantity,
                StockStatus.INBOUND, List.of(), 0L, receivedAt, lotId);
    }

    /**
     * Goods counted into a carton at its receiving location: the same INBOUND layer as {@link #receiveInbound}, but
     * the row is the carton's contents ({@code check_stock_package_contents} wants the row at its carton's location).
     */
    public static StockItem receiveInboundInto(StockPackageId carton, Sku sku, LocationId location, String lotNumber,
                                               Quantity quantity, Instant receivedAt, UUID lotId) {
        Objects.requireNonNull(carton, "carton");
        Objects.requireNonNull(receivedAt, "receivedAt");
        return new StockItem(StockItemId.newId(), sku, location, requireLot(lotNumber), quantity,
                StockStatus.INBOUND, List.of(), 0L, receivedAt, lotId, carton);
    }

    // ------------------------------------------------------------------ queries

    /** Units currently held by live reservations. */
    public Quantity reserved() {
        return reservations.stream()
                .filter(r -> r.status().isActive())
                .map(Reservation::quantity)
                .reduce(Quantity.ZERO, Quantity::plus);
    }

    /**
     * Available to promise: {@code onHand − reserved}, or zero if the stock is not sellable at all.
     *
     * <p>The status check is why blocked stock never shows up in catalog's ATP figure even though
     * the units are physically on the shelf.
     */
    public Quantity available() {
        return status.isReservable() ? onHand.minus(reserved()) : Quantity.ZERO;
    }

    public Optional<Reservation> findReservation(ReservationId reservationId) {
        return reservations.stream().filter(r -> r.id().equals(reservationId)).findFirst();
    }

    /**
     * Idempotency: is this exact request currently holding stock here?
     *
     * <p>A double-clicked checkout button, or a client retrying after a timeout, sends the same
     * {@code requestId} twice. Without this the customer holds twice the stock they need.
     *
     * <p><b>Only live holds count.</b> Released and expired reservations keep their row as an audit
     * trail, so matching on the id alone would report a hold that no longer exists and hand the
     * caller a reservation id that releases nothing. It also has to agree with {@link
     * #activeReservationsFor}, which the replay check uses: two idempotency checks that disagree
     * about what "already served" means are worse than one.
     */
    public Optional<Reservation> findByRequestId(UUID requestId) {
        return reservations.stream()
                .filter(r -> r.status().isActive())
                .filter(r -> r.requestId().equals(requestId))
                .findFirst();
    }

    /**
     * Every live hold this stock item is carrying for one caller request.
     *
     * <p>Used by the replay check, which has to run before any lots are chosen — by the time a plan
     * exists, the first attempt's own holds have already changed what looks available.
     */
    public List<Reservation> activeReservationsFor(UUID rootRequestId) {
        return reservations.stream()
                .filter(r -> r.status().isActive())
                .filter(r -> r.rootRequestId().equals(rootRequestId))
                .toList();
    }

    // ------------------------------------------------------------------ status transitions

    /** QC passed: the goods become sellable (BRD 3.3.4). */
    public void releaseFromQuarantine() {
        if (status != StockStatus.QUARANTINE) {
            throw new IllegalStateException(
                    "Only quarantined stock can be released, not " + status);
        }
        this.status = StockStatus.AVAILABLE;
    }

    /**
     * The carton this row is the contents of moved: the row follows it, in place. Id, reservations and carton stay,
     * which is why this is not a {@code move} (that splits a layer and mints a row); a carton moves whole, and the
     * commit-time check wants every row of it at the carton's location.
     */
    public void followPackageTo(LocationId destination) {
        if (stockPackageId == null) {
            throw new IllegalStateException("Stock item %s is loose; only a carton's rows follow it".formatted(id));
        }
        this.location = Objects.requireNonNull(destination, "destination");
    }

    /**
     * A whole carton is put on hold or condemned, or put away as sellable stock: the row changes status in
     * place, keeping its carton, so the carton is never split by a decision about its contents. Units still
     * reserved would silently lose their hold.
     *
     * <p>AVAILABLE is putaway's target: from INBOUND (a receipt's carton), or from QUARANTINE (a carton a warehouse
     * manager released, kltn-docs 05 §4.4, BR-14 - team plan 4D-1). Which of the two a putaway may take is the
     * putaway's to check against its task's source; this only refuses what no putaway makes AVAILABLE.</p>
     */
    public void reclassifyInPackage(StockStatus target) {
        if (stockPackageId == null) {
            throw new IllegalStateException("Stock item %s is loose; reclassify a carton's rows only".formatted(id));
        }
        if (status != StockStatus.INBOUND && status != StockStatus.QUARANTINE) {
            throw new IllegalStateException("Stock item %s is %s, not INBOUND or QUARANTINE".formatted(id, status));
        }
        if (target != StockStatus.QUARANTINE && target != StockStatus.BLOCKED && target != StockStatus.AVAILABLE) {
            throw new IllegalStateException(target + " is not AVAILABLE, QUARANTINE or BLOCKED");
        }
        if (!reserved().isZero()) {
            throw new IllegalStateException(
                    "Cannot reclassify stock item %s: %s units are still reserved".formatted(id, reserved()));
        }
        this.status = target;
    }

    /**
     * Putaway: the carton's row becomes AVAILABLE from the status its task puts away - INBOUND for a receipt's task,
     * QUARANTINE for a quarantine release's (kltn-docs 05 BR-01, BR-14; team plan 4D-1) - and from nothing else, so
     * neither task can put the other's goods on sale.
     */
    public void putAwayInPackage(StockStatus from) {
        if (from != StockStatus.INBOUND && from != StockStatus.QUARANTINE) {
            throw new IllegalArgumentException("A putaway takes INBOUND or QUARANTINE stock, not " + from);
        }
        if (status != from) {
            throw new IllegalStateException("Stock item %s is %s; this putaway takes %s stock".formatted(id, status,
                    from));
        }
        reclassifyInPackage(StockStatus.AVAILABLE);
    }

    /**
     * A warehouse manager quarantines the carton this row is the contents of (kltn-docs 20 §4.8, BR-21; team plan
     * 4D-1): sellable or still-to-be-put-away stock goes on hold, in place, keeping its carton. Only a free carton:
     * units held for an order would lose their hold silently, so the caller reallocates them first; the table says it
     * again ({@code ck_stock_item_quarantine_unheld}). Quarantined stock is not reservable
     * ({@link StockStatus#isReservable}), so it stays out of ATP and allocation until a putaway makes it AVAILABLE.
     */
    public void quarantineInPackage() {
        if (stockPackageId == null) {
            throw new IllegalStateException("Stock item %s is loose; only a carton is quarantined".formatted(id));
        }
        if (status != StockStatus.AVAILABLE && status != StockStatus.INBOUND) {
            throw new IllegalStateException("Stock item %s is %s, not AVAILABLE or INBOUND".formatted(id, status));
        }
        if (!reserved().isZero()) {
            throw new IllegalStateException(
                    "Cannot quarantine stock item %s: %s units are held for an order".formatted(id, reserved()));
        }
        this.status = StockStatus.QUARANTINE;
    }

    /**
     * The carton this row is the contents of came back on a return receipt (team plan 4D-2; kltn-docs 03 §4.2 step
     * 12, BR-04, BR-14): the row, emptied when its carton was picked, gets the carton's units back, in QUARANTINE and
     * in its own lot - out of ATP until a warehouse manager releases the carton or writes it off (20 §4.8). Whatever
     * status the empty row was left in, the goods come back in quarantine: nobody has looked at them yet. Only an
     * empty row holding nothing for anyone: a carton comes back whole, once.
     */
    public void receiveReturnInPackage(Quantity quantity) {
        if (stockPackageId == null) {
            throw new IllegalStateException("Stock item %s is loose; only a carton comes back on a return".formatted(id));
        }
        if (!onHand.isZero() || !reserved().isZero()) {
            throw new IllegalStateException("Stock item %s still holds %s (%s held); a carton comes back into an empty row"
                    .formatted(id, onHand, reserved()));
        }
        this.status = StockStatus.QUARANTINE;
        moveIn(quantity);
    }

    /**
     * The empty row of a LOST carton found again takes the status the carton comes back in (kltn-docs 20 §4.6 step 7):
     * AVAILABLE on the shelf, QUARANTINE in a quarantine area. Only while it holds nothing and no hold names it - the
     * units arrive right after, so no unit ever changes status here.
     */
    public void comeBackAs(StockStatus target) {
        if (target != StockStatus.AVAILABLE && target != StockStatus.QUARANTINE) {
            throw new IllegalArgumentException(target + " is not AVAILABLE or QUARANTINE");
        }
        if (stockPackageId == null || !onHand.isZero() || !reserved().isZero()) {
            throw new IllegalStateException("Stock item %s is not the empty row of a carton".formatted(id));
        }
        this.status = target;
    }

    /**
     * Allocate part of a SKU reservation to this stock item (F-FUL step 02, BR-STK-006): the order was released to
     * this warehouse and the planner chose this bin and lot. A hard hold, pinned, that the picker will walk to; the
     * units stop being the SKU's soft reservation once the caller marks it {@link SkuReservation#markAllocated}.
     * Idempotent on {@code requestId}. Announces {@code StockAllocated}; available-to-promise does not change.
     *
     * @throws InsufficientStockException when fewer than {@code quantity} units here are free
     */
    public Reservation allocate(SkuReservation from, UUID requestId, Quantity quantity, Instant now) {
        if (!from.sku().equals(sku)) {
            throw new IllegalArgumentException("Reservation %s is for %s, not %s".formatted(from.id(), from.sku(), sku));
        }
        return allocate(from.orderId(), from.id().value(), from.id().value(), requestId, quantity, now);
    }

    /**
     * An allocation of {@code orderId}'s units here, made from the SKU reservation {@code allocatedFrom} — at
     * release, or again after a short pick elsewhere (X-SHORT). {@code rootRequestId} is what a replay of the call
     * that made it looks for.
     */
    public Reservation allocate(UUID orderId, UUID allocatedFrom, UUID rootRequestId, UUID requestId,
                                Quantity quantity, Instant now) {
        Optional<Reservation> alreadyServed = findByRequestId(requestId);
        if (alreadyServed.isPresent()) {
            return alreadyServed.get();
        }
        requireHoldable(quantity, now, "order " + orderId);
        Reservation reservation = Reservation.allocate(orderId, allocatedFrom, rootRequestId, requestId, quantity, now);
        reservations.add(reservation);
        checkInvariants();
        requireInCartonWhenStocked();
        registerEvent(new StockEvent.Allocated(new com.stockflow.contracts.StockAllocated(orderId,
                reservation.id().value(), sku.code(), location.code(), lotNumber, quantity.value())));
        return reservation;
    }

    private void requireHoldable(Quantity quantity, Instant now, String holder) {
        if (!status.isReservable()) {
            throw new IllegalStateException(
                    "Stock in status %s cannot be reserved for %s".formatted(status, holder));
        }
        if (available().isLessThan(quantity)) {
            throw new InsufficientStockException(sku.code(), quantity.value(), available().value());
        }
    }

    /**
     * Give up a hold — order cancelled, or released by hand.
     *
     * <p>Releasing an already-closed reservation is a no-op rather than an error, because the
     * cancel path and a release by hand can genuinely race on the same row, and neither should
     * fail because the other got there first.
     */
    public void releaseReservation(ReservationId reservationId, ReleaseReason reason, Instant now) {
        Reservation reservation =
                findReservation(reservationId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Reservation %s does not belong to stock item %s"
                                                        .formatted(reservationId, id)));
        if (reservation.status().isTerminal()) {
            return;
        }
        reservation.release(reason, now);
        checkInvariants();
        registerEvent(
                new StockEvent.Released(
                        new StockReservationReleased(
                                reservation.orderId(),
                                sku.code(),
                                reservation.quantity().value(),
                                reason.toContract())));
    }

    /**
     * The goods have been picked: turn a hold into a real deduction (BRD 3.7 / 3.8).
     *
     * <p>This is the only method that lowers {@code onHand}, and it can only do so through a live
     * reservation. Physical stock therefore never leaves the building without a promise behind it.</p>
     *
     * <p>The hold is checked before the stock: a hold that was released, expired or consumed already is
     * {@code RESERVATION_NOT_ACTIVE} whatever happened to the lot since, so a picker is told the real
     * reason (the order was cancelled) rather than a policy conflict. A HELD hold past a deadline - one of
     * before V20261117000300, which released them all - counts as given up too. Then the
     * stock: blocked or expired stock cannot be shipped ({@code INVENTORY_POLICY_STOCK_CONFLICT}, docs 07
     * BR-02), which is a short pick for the warehouse to resolve.</p>
     */
    public void consumeReservation(ReservationId reservationId, Instant now) {
        Reservation reservation =
                findReservation(reservationId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                                                "Reservation %s does not belong to stock item %s"
                                                        .formatted(reservationId, id)));
        if (reservation.isExpiredAt(now)) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "Reservation %s passed its deadline at %s".formatted(reservationId, reservation.expiresAt()));
        }
        if (reservation.status().isTerminal()) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "Reservation %s is already %s".formatted(reservationId, reservation.status()));
        }
        if (!status.isReservable()) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }
        reservation.consume(now);
        this.onHand = this.onHand.minus(reservation.quantity());
        checkInvariants();
        registerEvent(
                new StockEvent.Deducted(
                        new StockDeducted(
                                reservation.orderId(),
                                sku.code(),
                                location.code(),
                                reservation.quantity().value())));
    }

    /**
     * Goods picked for an order that was cancelled afterwards come back onto this stock item (docs 07
     * BR-05, SCRUM-275): {@code onHand} grows by what the pick took. Only sellable stock takes them —
     * a put-back is not how goods get into a quarantined or blocked lot.
     */
    public void putBack(Quantity quantity) {
        if (status != StockStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                    "Stock item %s is %s; picked goods go back as AVAILABLE stock".formatted(id, status));
        }
        moveIn(quantity);
    }

    /**
     * Correct {@code onHand} after a physical stock count (BRD 3.10).
     *
     * <p>Counted stock can legitimately come out below what is reserved — that is exactly the
     * shrinkage a count is meant to surface. The invariant check would reject it, so the caller
     * gets told which reservations to release first; silently discarding customer holds inside a
     * counting routine would be far worse than a loud failure.
     */
    public void adjustTo(Quantity countedQuantity) {
        if (countedQuantity.isLessThan(reserved())) {
            throw new IllegalStateException(
                    ("Counted %s units for stock item %s but %s are reserved; "
                                    + "release the affected reservations before adjusting")
                            .formatted(countedQuantity, id, reserved()));
        }
        this.onHand = countedQuantity;
        checkInvariants();
        requireInCartonWhenStocked();
    }

    // ------------------------------------------------------------------ moves and adjustments

    /**
     * Units that may leave this stock item without breaking a customer's hold: {@code onHand −
     * reserved}, whatever the status. Unlike {@link #available()} this counts quarantined stock too
     * — moving it from the receiving area to the quarantine area is still a move.
     */
    public Quantity movable() {
        return onHand.minus(reserved());
    }

    /**
     * The source side of a move between locations (SCRUM-424).
     *
     * <p>Only unreserved units move. A reservation names this stock item, and moving its units out
     * from under it would leave a hold on a shelf that no longer has the goods; the caller releases
     * the hold, moves, and reserves again at the destination.
     *
     * @throws InsufficientStockException when fewer than {@code quantity} units are unreserved
     */
    public void moveOut(Quantity quantity) {
        requirePositive(quantity);
        if (movable().isLessThan(quantity)) {
            throw new InsufficientStockException(sku.code(), quantity.value(), movable().value());
        }
        this.onHand = onHand.minus(quantity);
        checkInvariants();
    }

    /** The destination side of a move: the same SKU, lot and status, arriving from elsewhere. */
    public void moveIn(Quantity quantity) {
        requirePositive(quantity);
        this.onHand = onHand.plus(quantity);
        checkInvariants();
        requireInCartonWhenStocked();
    }

    /** Whether units of {@code other} may be merged into this stock item by a move. */
    public boolean canReceiveFrom(StockItem other) {
        return canReceiveFrom(other, other.status);
    }

    /** Whether units of {@code other}, arriving as {@code arrivingStatus}, may be merged into this one. */
    public boolean canReceiveFrom(StockItem other, StockStatus arrivingStatus) {
        return isSameGoodsAs(other) && status == arrivingStatus;
    }

    /**
     * Whether {@code other} is a layer of the same goods, whatever its status: SKU, lot number and receipt time, and
     * never two lots.
     */
    public boolean isSameGoodsAs(StockItem other) {
        return sku.equals(other.sku)
                && Objects.equals(lotNumber, other.lotNumber)
                && Objects.equals(receivedAt, other.receivedAt)
                // Two lots never merge into one layer; a layer the database has not tagged yet takes any.
                && (lotId == null || other.lotId == null || lotId.equals(other.lotId));
    }

    /**
     * Post an approved adjustment (SCRUM-145): {@code onHand} changes by {@code delta}.
     *
     * <p>Refused when the result would fall below what is reserved, for the same reason as {@link
     * #adjustTo}: a write-off must not silently cancel a customer's hold. Release the hold first,
     * then post.
     */
    public void adjustBy(int delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("An adjustment changes the quantity");
        }
        long result = (long) onHand.value() + delta;
        if (result < reserved().value()) {
            throw new InsufficientStockException(sku.code(), -delta, movable().value());
        }
        this.onHand = Quantity.of(Math.toIntExact(result));
        checkInvariants();
        requireInCartonWhenStocked();
    }

    private static void requirePositive(Quantity quantity) {
        if (quantity.isZero()) {
            throw new IllegalArgumentException("A move needs a positive quantity");
        }
    }

    // ------------------------------------------------------------------ invariants

    /**
     * Stock is kept in whole cartons only (kltn-docs 07 BR-03, 20 BR-14; team plan 4C-1, 4C-2): a row in no carton is
     * a layer emptied before that, and holds nothing and nobody's hold. Checked by every mutation that can raise what a
     * row holds or what is held on it - not by the constructor, which learns the row's carton only after the chain of
     * {@code this(...)} calls that runs {@link #checkInvariants}. Stated again in the table by
     * {@code ck_stock_item_whole_cartons} (V20261117000100).
     */
    private void requireInCartonWhenStocked() {
        if (stockPackageId == null && (onHand.value() > 0 || reserved().value() > 0)) {
            throw new IllegalStateException(("Stock item %s is in no carton but would hold %s (%s held): stock is kept "
                    + "in whole cartons").formatted(id, onHand, reserved()));
        }
    }

    /**
     * Checked after every mutation and in the constructor, so an aggregate can never be observed in
     * a broken state — not even one rehydrated from a row a bad migration corrupted.
     */
    private void checkInvariants() {
        Quantity reserved = reserved();
        if (reserved.value() > onHand.value()) {
            throw new IllegalStateException(
                    "Invariant violated on stock item %s: reserved (%s) exceeds onHand (%s)"
                            .formatted(id, reserved, onHand));
        }
    }

    public StockItemId id() {
        return id;
    }

    public Sku sku() {
        return sku;
    }

    public LocationId location() {
        return location;
    }

    public String lotNumber() {
        return lotNumber;
    }

    public Quantity onHand() {
        return onHand;
    }

    public StockStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    /** Unmodifiable: reservations change through this aggregate's methods, never from outside. */
    public List<Reservation> reservations() {
        return Collections.unmodifiableList(reservations);
    }
}
