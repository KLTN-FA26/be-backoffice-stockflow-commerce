package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.StockDeducted;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.contracts.StockReserved;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
 * <p><b>Loose stock or the contents of one stock package.</b> A row with no {@link #stockPackageId()} is loose
 * and keyed by (SKU, location, lot, serial, receipt time); a row in a stock package (a carton, V20261020000200)
 * is keyed by the package instead, so a bin may hold a loose layer and several cartons of the very same receipt.
 * A lookup of one must never land on the other.</p>
 *
 * <p><b>Known limit — one order line can span several stock items.</b> The aggregate is keyed per
 * location and layer, so 100 units spread over three locations means mutating three aggregates in
 * one transaction. {@link StockAllocator} owns the choice of which ones and in what order (FEFO),
 * and keeps that decision out of the caller. Within a single-database monolith that multi-aggregate
 * transaction is correct and atomic; it is recorded here because it is the assumption that would
 * break first if inventory were ever split back out into its own service. See {@code
 * docs/adr/0005-modular-monolith.md}.
 */
public final class StockItem extends AggregateRoot {

    // final: an aggregate root has no subtypes. Beyond the modelling point, the constructor calls
    // checkInvariants(), and javac's this-escape lint is right to flag that on a non-final class -
    // a subclass could override a method it reaches before its own fields are initialised.

    /**
     * How long a checkout hold survives while its order waits for payment (kltn-docs 14 BR-07), when
     * no other value is configured ({@code stockflow.inventory.reservation-ttl}). Once the order is
     * paid or released the hold is pinned and no longer expires.
     */
    public static final Duration DEFAULT_RESERVATION_TTL = Duration.ofMinutes(30);

    private final StockItemId id;
    private final Sku sku;
    private final LocationId location;
    private final String lotNumber;
    private final LocalDate expiryDate;
    private Instant receivedAt;
    private String serialNumber;
    private StockPackageId stockPackageId;

    public Instant receivedAt() {
        return receivedAt;
    }

    public String serialNumber() {
        return serialNumber;
    }

    /** The stock package this row is the contents of; {@code null} for loose stock. */
    public StockPackageId stockPackageId() {
        return stockPackageId;
    }

    public boolean isLoose() {
        return stockPackageId == null;
    }

    /**
     * Whether this row is the layer of these goods: same lot, serial and receipt time ({@code null}: none). Among
     * the loose rows of one SKU at one bin at most one answers yes ({@code uk_stock_item_sku_location_lot}).
     */
    public boolean isLayer(String lot, String serial, Instant receipt) {
        return Objects.equals(lotNumber, lot) && Objects.equals(serialNumber, serial)
                && Objects.equals(receivedAt, receipt);
    }

    /** A row rehydrated with its stock package ({@code null}: loose). */
    public StockItem(
            StockItemId id,
            Sku sku,
            LocationId location,
            String lotNumber,
            LocalDate expiryDate,
            Quantity onHand,
            StockStatus status,
            List<Reservation> reservations,
            long version,
            Instant receivedAt,
            String serialNumber,
            StockPackageId stockPackageId) {
        this(id, sku, location, lotNumber, expiryDate, onHand, status, reservations, version, receivedAt, serialNumber);
        this.stockPackageId = stockPackageId;
    }

    public StockItem(
            StockItemId id,
            Sku sku,
            LocationId location,
            String lotNumber,
            LocalDate expiryDate,
            Quantity onHand,
            StockStatus status,
            List<Reservation> reservations,
            long version,
            Instant receivedAt,
            String serialNumber) {
        this(id, sku, location, lotNumber, expiryDate, onHand, status, reservations, version);
        this.receivedAt = receivedAt;
        this.serialNumber = serialNumber;
        if (serialNumber != null && (serialNumber.isBlank() || onHand.value() > 1))
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_INVALID);
    }

    /**
     * Receipt integration must pass the real receipt time; never infer it from persistence
     * auditing.
     */
    public static StockItem receive(
            Sku sku,
            LocationId location,
            String lotNumber,
            LocalDate expiryDate,
            Quantity quantity,
            Instant receivedAt,
            String serialNumber) {
        Objects.requireNonNull(receivedAt, "receivedAt");
        return new StockItem(
                StockItemId.newId(),
                sku,
                location,
                lotNumber,
                expiryDate,
                quantity,
                StockStatus.QUARANTINE,
                List.of(),
                0L,
                receivedAt,
                serialNumber);
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
            LocalDate expiryDate,
            Quantity onHand,
            StockStatus status,
            List<Reservation> reservations,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.sku = Objects.requireNonNull(sku, "sku");
        this.location = Objects.requireNonNull(location, "location");
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
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
            LocalDate expiryDate,
            Quantity quantity) {
        return new StockItem(
                StockItemId.newId(),
                sku,
                location,
                lotNumber,
                expiryDate,
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
                                           LocalDate expiryDate, Quantity quantity, Instant receivedAt) {
        Objects.requireNonNull(receivedAt, "receivedAt");
        return new StockItem(StockItemId.newId(), sku, location, lotNumber, expiryDate, quantity,
                StockStatus.INBOUND, List.of(), 0L, receivedAt, null);
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
     * QC failed, damage found, or the lot expired.
     *
     * <p>Blocking does not silently drop live holds — that would let an order believe it still has
     * stock. The caller must release them first, and this refuses until they have.
     */
    public void block(StockStatus blockedStatus) {
        if (blockedStatus.isReservable()) {
            throw new IllegalArgumentException(blockedStatus + " is not a blocking status");
        }
        if (!reserved().isZero()) {
            throw new IllegalStateException(
                    "Cannot block stock item %s: %s units are still reserved"
                            .formatted(id, reserved()));
        }
        this.status = blockedStatus;
    }

    // ------------------------------------------------------------------ reservation lifecycle

    /**
     * Pin every active hold of {@code orderId} on this stock row: its order is no longer waiting for
     * payment, so the holds must not expire (kltn-docs 14 BR-07, SCRUM-465). Nothing else changes —
     * the quantities held stay exactly as they are.
     *
     * @return how many holds this call pinned (0 when they were all pinned already)
     */
    /** Gives the order's pinned holds here a deadline again; see {@link Reservation#rearm}. */
    public int rearmReservationsOf(UUID orderId, Instant deadline) {
        if (status == StockStatus.IN_PRODUCTION) {
            return 0;   // goods on the press do not go back to waiting for payment
        }
        int rearmed = 0;
        for (Reservation reservation : reservations) {
            if (orderId.equals(reservation.orderId()) && reservation.rearm(deadline)) {
                rearmed++;
            }
        }
        return rearmed;
    }

    public int pinReservationsOf(UUID orderId) {
        int pinned = 0;
        for (Reservation reservation : reservations) {
            if (orderId.equals(reservation.orderId()) && reservation.pin()) {
                pinned++;
            }
        }
        return pinned;
    }

    /**
     * Hold stock for an order line at checkout (BRD 3.14.5).
     *
     * <p>Nothing is deducted — {@code onHand} is unchanged and the goods are still physically
     * there. Only the promise is made, and it expires by itself if the order never pays.
     *
     * @throws IllegalStateException if the stock is not in a sellable status
     * @throws InsufficientStockException if available-to-promise is below {@code quantity}
     */
    public Reservation reserve(
            UUID orderId, UUID rootRequestId, UUID requestId, Quantity quantity, Instant now) {
        return reserve(orderId, rootRequestId, requestId, quantity, now, DEFAULT_RESERVATION_TTL);
    }

    public Reservation reserve(
            UUID orderId,
            UUID rootRequestId,
            UUID requestId,
            Quantity quantity,
            Instant now,
            Duration ttl) {
        Optional<Reservation> alreadyServed = findByRequestId(requestId);
        if (alreadyServed.isPresent()) {
            return alreadyServed.get();
        }
        requireHoldable(quantity, now, "order " + orderId);
        Reservation reservation =
                Reservation.open(orderId, rootRequestId, requestId, quantity, now, ttl);
        reservations.add(reservation);
        checkInvariants();
        registerEvent(
                new StockEvent.Reserved(
                        new StockReserved(
                                orderId,
                                sku.code(),
                                location.code(),
                                quantity.value(),
                                reservation.expiresAt())));
        return reservation;
    }

    /**
     * Hold blanks for a production order rather than an order (a sample round from its submission,
     * decision Q1 2026-10-12; a reprint round). The same checks as {@link #reserve}; the hold is pinned
     * from the start and announces nothing to {@code order}, which has no stake in it.
     */
    public Reservation reserveForProduction(UUID productionOrderId, UUID rootRequestId, UUID requestId,
                                            Quantity quantity, Instant now) {
        Optional<Reservation> alreadyServed = findByRequestId(requestId);
        if (alreadyServed.isPresent()) {
            return alreadyServed.get();
        }
        requireHoldable(quantity, now, "production order " + productionOrderId);
        Reservation reservation =
                Reservation.openForProduction(productionOrderId, rootRequestId, requestId, quantity, now);
        reservations.add(reservation);
        checkInvariants();
        return reservation;
    }

    /**
     * Allocate stock to a transfer order at its source (SCRUM-287 PR T; kltn-docs 10 step 4, BR-02/03): the same checks
     * as {@link #reserve}; pinned from the start, announced to nobody - no order has a stake in it. Consumed when the
     * transfer is dispatched, released when its picking is cancelled.
     */
    public Reservation reserveForTransfer(UUID transferOrderId, UUID rootRequestId, UUID requestId, Quantity quantity,
                                          Instant now) {
        Optional<Reservation> alreadyServed = findByRequestId(requestId);
        if (alreadyServed.isPresent()) {
            return alreadyServed.get();
        }
        requireHoldable(quantity, now, "transfer order " + transferOrderId);
        Reservation reservation = Reservation.openForTransfer(transferOrderId, rootRequestId, requestId, quantity, now);
        reservations.add(reservation);
        checkInvariants();
        return reservation;
    }

    private void requireHoldable(Quantity quantity, Instant now, String holder) {
        if (expiryDate != null && expiryDate.isBefore(BusinessCalendar.date(now))) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }
        if (!status.isReservable()) {
            throw new IllegalStateException(
                    "Stock in status %s cannot be reserved for %s".formatted(status, holder));
        }
        if (available().isLessThan(quantity)) {
            throw new InsufficientStockException(sku.code(), quantity.value(), available().value());
        }
    }

    /**
     * Give up a hold — order cancelled, payment failed, or the sweeper found it expired.
     *
     * <p>Releasing an already-closed reservation is a no-op rather than an error, because the
     * cancel path and the expiry sweeper can genuinely race on the same row, and neither should
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
        if (reservation.orderId() == null) {
            return;     // a production hold: order has no stake in it
        }
        registerEvent(
                new StockEvent.Released(
                        new StockReservationReleased(
                                reservation.orderId(),
                                sku.code(),
                                reservation.quantity().value(),
                                reason.toContract())));
    }

    /**
     * Release every live hold that has outlived its {@code expiresAt}. Called by the sweeper. Never in the print
     * shop: see {@link StockStatus#IN_PRODUCTION}.
     */
    public int releaseExpired(Instant now) {
        if (status == StockStatus.IN_PRODUCTION) {
            return 0;   // holds in the print shop are closed only by production (they are pinned anyway)
        }
        List<ReservationId> expired =
                reservations.stream().filter(r -> r.isExpiredAt(now)).map(Reservation::id).toList();
        expired.forEach(rid -> releaseReservation(rid, ReleaseReason.RESERVATION_EXPIRED, now));
        return expired.size();
    }

    /**
     * The goods have been picked: turn a hold into a real deduction (BRD 3.7 / 3.8).
     *
     * <p>This is the only method that lowers {@code onHand}, and it can only do so through a live
     * reservation. Physical stock therefore never leaves the building without a promise behind it.</p>
     *
     * <p>The hold is checked before the stock: a hold that was released, expired or consumed already is
     * {@code RESERVATION_NOT_ACTIVE} whatever happened to the lot since, so a picker is told the real
     * reason (the order was cancelled) rather than a policy conflict. A HELD hold past its payment
     * deadline counts as given up too, even before the sweeper closes it (kltn-docs 14 BR-07). Then the
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
        if (!status.isReservable()
                || expiryDate != null && expiryDate.isBefore(BusinessCalendar.date(now))) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }
        reservation.consume(now);
        this.onHand = this.onHand.minus(reservation.quantity());
        checkInvariants();
        if (reservation.orderId() == null) {
            return;
        }
        registerEvent(
                new StockEvent.Deducted(
                        new StockDeducted(
                                reservation.orderId(),
                                sku.code(),
                                location.code(),
                                reservation.quantity().value())));
    }

    /**
     * Decision G4-Q2 (2026-10-21), kltn-docs 19 BR-10: printed goods consumed on this layer when their production
     * order completed belong to an order that has since been cancelled (or whose unshipped rest was closed). They
     * cannot go back on sale, so they come back onto the books here only to be written off as SCRAP: {@code onHand}
     * grows by the units and a new hold of exactly them is opened for {@code orderId}, pinned, so nothing can take
     * them before the write-off is decided.
     *
     * <p>The new hold carries the consumed hold's id as its {@code rootRequestId}, so the units already brought back
     * from that hold are known: no more than it consumed ever come back, however many closures ask. Idempotent on
     * {@code requestId}.</p>
     *
     * @return the new hold, or empty when everything the consumed hold took has been brought back already
     */
    public Optional<Reservation> bookBackForScrap(ReservationId consumed, UUID orderId, UUID requestId,
                                                  Quantity wanted, Instant now) {
        Objects.requireNonNull(orderId, "orderId");
        Optional<Reservation> alreadyServed = reservations.stream().filter(r -> r.requestId().equals(requestId))
                .findFirst();
        if (alreadyServed.isPresent()) {
            return alreadyServed;
        }
        Reservation source = findReservation(consumed).orElseThrow(() -> new IllegalArgumentException(
                "Reservation %s does not belong to stock item %s".formatted(consumed, id)));
        if (source.status() != ReservationStatus.CONSUMED || !orderId.equals(source.orderId())) {
            throw new IllegalArgumentException("Reservation %s is not order %s's consumed goods".formatted(consumed,
                    orderId));
        }
        if (status != StockStatus.AVAILABLE || stockPackageId != null) {
            // Finished goods reach packing as loose stock (emptyLayerOf); a carton never takes printed goods back.
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                    "Stock item %s is %s%s; printed goods come back onto their loose AVAILABLE layer".formatted(id,
                            status, stockPackageId == null ? "" : " in a carton"));
        }
        int back = reservations.stream().filter(r -> r != source && consumed.value().equals(r.rootRequestId()))
                .mapToInt(r -> r.quantity().value()).sum();
        int take = Math.min(wanted.value(), source.quantity().value() - back);
        if (take <= 0) {
            return Optional.empty();
        }
        Quantity quantity = Quantity.of(take);
        this.onHand = onHand.plus(quantity);
        Reservation hold = new Reservation(ReservationId.newId(), orderId, null, consumed.value(), requestId, quantity,
                now, null, ReservationStatus.HELD, null, null);
        reservations.add(hold);
        checkInvariants();
        return Optional.of(hold);
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
    }

    // ------------------------------------------------------------------ moves and adjustments

    /**
     * Units that may leave this stock item without breaking a customer's hold: {@code onHand −
     * reserved}, whatever the status. Unlike {@link #available()} this counts quarantined stock too
     * — moving it from the receiving area to the QC area is still a move.
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
    }

    /**
     * The source side of issuing held goods (SCRUM-397): one live hold leaves this stock item
     * <b>together with</b> the units it holds. Unlike {@link #moveOut}, which refuses held units, the
     * promise travels with the goods, so there is never a moment in which they are unheld — no
     * release-then-move window in which another order's checkout could take them. The hold keeps its id,
     * so whoever stored it (an order line, a production order) still finds it.
     *
     * @throws IllegalArgumentException when the hold is not on this stock item
     * @throws IllegalStateException when the hold is closed
     */
    public Reservation handOverReservation(ReservationId reservationId) {
        Reservation reservation = findReservation(reservationId).orElseThrow(() -> new IllegalArgumentException(
                "Reservation %s does not belong to stock item %s".formatted(reservationId, id)));
        if (reservation.status().isTerminal()) {
            throw new IllegalStateException("Reservation %s is %s; only a live hold is handed over"
                    .formatted(reservationId, reservation.status()));
        }
        reservations.remove(reservation);
        this.onHand = onHand.minus(reservation.quantity());
        checkInvariants();
        return reservation;
    }

    /**
     * The destination side of {@link #handOverReservation}: the hold and its units arrive here, in this
     * layer's status (issued blanks become {@link StockStatus#IN_PRODUCTION}, finished goods
     * {@link StockStatus#AVAILABLE} again).
     */
    public void takeOverReservation(StockItem from, Reservation reservation) {
        if (!sku.equals(from.sku) || !Objects.equals(lotNumber, from.lotNumber)
                || !Objects.equals(serialNumber, from.serialNumber) || !Objects.equals(expiryDate, from.expiryDate)) {
            throw new IllegalArgumentException(
                    "Stock item %s cannot take a hold of stock item %s: other goods".formatted(id, from.id));
        }
        if (findReservation(reservation.id()).isPresent()) {
            throw new IllegalStateException("Stock item %s already carries reservation %s"
                    .formatted(id, reservation.id()));
        }
        reservations.add(reservation);
        this.onHand = onHand.plus(reservation.quantity());
        checkInvariants();
    }

    /**
     * Units leave a hold in the print shop without leaving the layer (SCRUM-398..401): spoilt units waiting
     * for their write-off, unprinted blanks about to go back to storage. The hold keeps its id and covers
     * {@code quantity} fewer units, or closes with {@code reason} when that is all of it. {@code onHand} does not
     * change, and the units stay {@link StockStatus#IN_PRODUCTION}: freed from the hold, they are still not
     * sellable — whatever comes next is an explicit step (an approved adjustment, a return).
     *
     * @throws IllegalStateException when the stock is not in the print shop or the hold is closed
     * @throws IllegalArgumentException when the hold covers fewer units, or is not on this stock item
     */
    public void takeFromHold(ReservationId reservationId, Quantity quantity, ReleaseReason reason, Instant now) {
        if (status != StockStatus.IN_PRODUCTION) {
            throw new IllegalStateException("Stock item %s is %s, not in the print shop".formatted(id, status));
        }
        Reservation reservation = findReservation(reservationId).orElseThrow(() -> new IllegalArgumentException(
                "Reservation %s does not belong to stock item %s".formatted(reservationId, id)));
        if (reservation.status().isTerminal()) {
            throw new IllegalStateException("Reservation %s is %s".formatted(reservationId, reservation.status()));
        }
        if (quantity.isZero() || reservation.quantity().isLessThan(quantity)) {
            throw new IllegalArgumentException("Reservation %s holds %s, not %s".formatted(reservationId,
                    reservation.quantity(), quantity));
        }
        if (quantity.equals(reservation.quantity())) {
            reservation.release(reason, now);
        } else {
            reservation.reduceBy(quantity);
        }
        checkInvariants();
    }

    /**
     * A hold in storage covers {@code quantity} fewer units, which become free again (SCRUM-441: the part of an
     * order a subcontractor makes with its own blanks). The hold keeps its id, or closes with {@code reason}
     * when that is all of it. Not in the print shop: there, units leave a hold only through
     * {@link #takeFromHold}, which keeps them out of sale.
     */
    public void reduceHold(ReservationId reservationId, Quantity quantity, ReleaseReason reason, Instant now) {
        if (status == StockStatus.IN_PRODUCTION) {
            throw new IllegalStateException("Stock item %s is in the print shop".formatted(id));
        }
        Reservation reservation = findReservation(reservationId).orElseThrow(() -> new IllegalArgumentException(
                "Reservation %s does not belong to stock item %s".formatted(reservationId, id)));
        if (reservation.status().isTerminal() || quantity.isZero() || reservation.quantity().isLessThan(quantity)) {
            throw new IllegalArgumentException("Reservation %s (%s, %s) cannot give up %s".formatted(reservationId,
                    reservation.status(), reservation.quantity(), quantity));
        }
        if (quantity.equals(reservation.quantity())) {
            reservation.release(reason, now);
        } else {
            reservation.reduceBy(quantity);
        }
        checkInvariants();
    }

    /**
     * Goods just received for a production order (a subcontractor's finished goods, SCRUM-441, decision Q8) are
     * held for it at once, on their INBOUND layer: they never wait unheld for QC or putaway. Pinned, idempotent on
     * {@code requestId}; refused for any other status, or for more than the layer has unheld.
     */
    public Reservation holdArrival(UUID productionOrderId, UUID rootRequestId, UUID requestId, Quantity quantity,
                                   Instant now) {
        Optional<Reservation> alreadyServed = findByRequestId(requestId);
        if (alreadyServed.isPresent()) {
            return alreadyServed.get();
        }
        if (status != StockStatus.INBOUND) {
            throw new IllegalStateException("Stock item %s is %s, not just received".formatted(id, status));
        }
        if (movable().isLessThan(quantity)) {
            throw new InsufficientStockException(sku.code(), quantity.value(), movable().value());
        }
        Reservation reservation = Reservation.openForProduction(productionOrderId, rootRequestId, requestId, quantity, now);
        reservations.add(reservation);
        checkInvariants();
        return reservation;
    }

    /**
     * Held blanks a subcontractor turned into the goods received back (SCRUM-441, decision Q9) leave stock: the
     * hold covers {@code quantity} fewer units (consumed when that is all of it) and {@code onHand} falls by as
     * many. Only in the print shop or at a subcontractor (IN_PRODUCTION).
     */
    public void consumeFromHold(ReservationId reservationId, Quantity quantity, Instant now) {
        if (status != StockStatus.IN_PRODUCTION) {
            throw new IllegalStateException("Stock item %s is %s, not issued to production".formatted(id, status));
        }
        Reservation reservation = findReservation(reservationId).orElseThrow(() -> new IllegalArgumentException(
                "Reservation %s does not belong to stock item %s".formatted(reservationId, id)));
        if (reservation.status().isTerminal() || quantity.isZero() || reservation.quantity().isLessThan(quantity)) {
            throw new IllegalArgumentException("Reservation %s (%s, %s) cannot give %s".formatted(reservationId,
                    reservation.status(), reservation.quantity(), quantity));
        }
        if (quantity.equals(reservation.quantity())) {
            reservation.consume(now);
        } else {
            reservation.reduceBy(quantity);
        }
        this.onHand = onHand.minus(quantity);
        checkInvariants();
    }

    /** See {@link Reservation#handToOrder}: the hold must be the production order's own. */
    public void handHoldToOrder(ReservationId reservationId, UUID productionOrderId, UUID orderId) {
        Reservation reservation = findReservation(reservationId).orElseThrow(() -> new IllegalArgumentException(
                "Reservation %s does not belong to stock item %s".formatted(reservationId, id)));
        if (!Objects.equals(productionOrderId, reservation.productionOrderId())) {
            throw new IllegalArgumentException("Reservation %s is not production order %s's".formatted(reservationId,
                    productionOrderId));
        }
        reservation.handToOrder(orderId);
        checkInvariants();
    }

    /**
     * An empty layer of {@code source}'s goods at {@code destination} in {@code status} — the same SKU, lot,
     * expiry and receipt time — for {@link #takeOverReservation} to fill. Kept apart from {@link #arrivedAs},
     * which arrives with unheld units.
     */
    public static StockItem emptyLayerOf(StockItem source, LocationId destination, StockStatus status) {
        if (source.location.equals(destination)) {
            throw new IllegalArgumentException("A move needs two different locations");
        }
        return new StockItem(StockItemId.newId(), source.sku, destination, source.lotNumber, source.expiryDate,
                Quantity.ZERO, Objects.requireNonNull(status, "status"), List.of(), 0L, source.receivedAt,
                source.serialNumber);
    }

    /**
     * A new stock item at {@code destination} for units moved out of {@code source}: same SKU, lot,
     * expiry and status. Status travels with the goods — moving quarantined stock to a bin does not
     * make it sellable, only QC does (see {@link #receive}).
     */
    public static StockItem arrivedFrom(
            StockItem source, LocationId destination, Quantity quantity) {
        if (source.location.equals(destination)) {
            throw new IllegalArgumentException("A move needs two different locations");
        }
        StockItem arrived =
                new StockItem(
                        StockItemId.newId(),
                        source.sku,
                        destination,
                        source.lotNumber,
                        source.expiryDate,
                        Quantity.ZERO,
                        source.status,
                        List.of(),
                        0L,
                        source.receivedAt,
                        source.serialNumber);
        arrived.moveIn(quantity);
        return arrived;
    }

    /**
     * Units moved out of {@code source} that change status on the way (SCRUM-435): a QC decision
     * sends part of a receipt to the quarantine area as QUARANTINE or BLOCKED, a putaway turns
     * INBOUND goods into AVAILABLE ones. The layer (receipt time, serial) travels with the goods.
     */
    public static StockItem arrivedAs(StockItem source, LocationId destination, Quantity quantity,
                                      StockStatus status) {
        if (source.location.equals(destination)) {
            throw new IllegalArgumentException("A move needs two different locations");
        }
        StockItem arrived = new StockItem(StockItemId.newId(), source.sku, destination, source.lotNumber,
                source.expiryDate, Quantity.ZERO, Objects.requireNonNull(status, "status"), List.of(), 0L,
                source.receivedAt, source.serialNumber);
        arrived.moveIn(quantity);
        return arrived;
    }

    /** Whether units of {@code other} may be merged into this stock item by a move. */
    public boolean canReceiveFrom(StockItem other) {
        return canReceiveFrom(other, other.status);
    }

    /** Whether units of {@code other}, arriving as {@code arrivingStatus}, may be merged into this one. */
    public boolean canReceiveFrom(StockItem other, StockStatus arrivingStatus) {
        return sku.equals(other.sku)
                && Objects.equals(lotNumber, other.lotNumber)
                && Objects.equals(receivedAt, other.receivedAt)
                && Objects.equals(serialNumber, other.serialNumber)
                && Objects.equals(expiryDate, other.expiryDate)
                && status == arrivingStatus;
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
    }

    private static void requirePositive(Quantity quantity) {
        if (quantity.isZero()) {
            throw new IllegalArgumentException("A move needs a positive quantity");
        }
    }

    // ------------------------------------------------------------------ invariants

    /**
     * Checked after every mutation and in the constructor, so an aggregate can never be observed in
     * a broken state — not even one rehydrated from a row a bad migration corrupted.
     */
    private void checkInvariants() {
        if (serialNumber != null && (serialNumber.isBlank() || onHand.value() > 1))
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_INVALID);
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

    public LocalDate expiryDate() {
        return expiryDate;
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
