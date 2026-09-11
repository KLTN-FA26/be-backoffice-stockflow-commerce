package com.stockflow.inventory.internal.domain;

import com.stockflow.contracts.StockDeducted;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.contracts.StockReserved;
import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Sku;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>The aggregate root of the inventory module:</b> one SKU, at one location, from one lot.
 *
 * <p>The invariants it exists to protect — and the reason this is not a bag of getters and
 * setters:</p>
 * <ul>
 *   <li>{@code onHand} is never negative;</li>
 *   <li>the sum of active reservations never exceeds {@code onHand} — this is the rule that
 *       prevents overselling;</li>
 *   <li>only stock that was actually held can be deducted, so a picker cannot ship goods that
 *       nobody reserved;</li>
 *   <li>a reservation is opened, consumed and released only here, never by touching a
 *       {@link Reservation} directly.</li>
 * </ul>
 *
 * <p><b>No Spring, no JPA, no annotations.</b> This class does not know that a database exists.
 * Mapping it to a table is {@code internal.entity}'s job, which is what lets
 * {@code StockItemTest} exercise every rule above as a plain unit test with no context to boot.
 * The one exception is the three imports from {@code contracts}: the aggregate names the events
 * it causes, which is domain knowledge, and {@code contracts} holds nothing but flat records.</p>
 *
 * <p><b>Known limit — one order line can span several stock items.</b> The aggregate is keyed by
 * (SKU, location, lot), so 100 units spread over three locations means mutating three aggregates
 * in one transaction. {@link StockAllocator} owns the choice of which ones and in what order
 * (FEFO), and keeps that decision out of the caller. Within a single-database monolith that
 * multi-aggregate transaction is correct and atomic; it is recorded here because it is the
 * assumption that would break first if inventory were ever split back out into its own service.
 * See {@code docs/adr/0005-modular-monolith.md}.</p>
 */
public final class StockItem extends AggregateRoot {

    // final: an aggregate root has no subtypes. Beyond the modelling point, the constructor calls
    // checkInvariants(), and javac's this-escape lint is right to flag that on a non-final class -
    // a subclass could override a method it reaches before its own fields are initialised.

    /** How long a checkout hold survives without payment (BR-014). */
    public static final Duration DEFAULT_RESERVATION_TTL = Duration.ofMinutes(30);

    private final StockItemId id;
    private final Sku sku;
    private final LocationId location;
    private final String lotNumber;
    private final LocalDate expiryDate;

    private Quantity onHand;
    private StockStatus status;
    private final List<Reservation> reservations;
    private final long version;

    public StockItem(StockItemId id, Sku sku, LocationId location, String lotNumber,
                     LocalDate expiryDate, Quantity onHand, StockStatus status,
                     List<Reservation> reservations, long version) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.sku = java.util.Objects.requireNonNull(sku, "sku");
        this.location = java.util.Objects.requireNonNull(location, "location");
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
        this.onHand = java.util.Objects.requireNonNull(onHand, "onHand");
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.reservations = new ArrayList<>(reservations == null ? List.of() : reservations);
        this.version = version;
        checkInvariants();
    }

    /**
     * Goods have arrived from a supplier.
     *
     * <p>They start in {@link StockStatus#QUARANTINE}: nothing becomes sellable until QC says so
     * (BRD 3.3.4). Making this the only entry point means no code path can create sellable stock
     * that skipped inspection.</p>
     */
    public static StockItem receive(Sku sku, LocationId location, String lotNumber,
                                    LocalDate expiryDate, Quantity quantity) {
        return new StockItem(StockItemId.newId(), sku, location, lotNumber, expiryDate,
                quantity, StockStatus.QUARANTINE, List.of(), 0L);
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
     * the units are physically on the shelf.</p>
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
     * {@code requestId} twice. Without this the customer holds twice the stock they need.</p>
     *
     * <p><b>Only live holds count.</b> Released and expired reservations keep their row as an audit
     * trail, so matching on the id alone would report a hold that no longer exists and hand the
     * caller a reservation id that releases nothing. It also has to agree with
     * {@link #activeReservationsFor}, which the replay check uses: two idempotency checks that
     * disagree about what "already served" means are worse than one.</p>
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
     * <p>Used by the replay check, which has to run before any lots are chosen — by the time a
     * plan exists, the first attempt's own holds have already changed what looks available.</p>
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
            throw new IllegalStateException("Only quarantined stock can be released, not " + status);
        }
        this.status = StockStatus.AVAILABLE;
    }

    /**
     * QC failed, damage found, or the lot expired.
     *
     * <p>Blocking does not silently drop live holds — that would let an order believe it still has
     * stock. The caller must release them first, and this refuses until they have.</p>
     */
    public void block(StockStatus blockedStatus) {
        if (blockedStatus.isReservable()) {
            throw new IllegalArgumentException(blockedStatus + " is not a blocking status");
        }
        if (!reserved().isZero()) {
            throw new IllegalStateException(
                    "Cannot block stock item %s: %s units are still reserved".formatted(id, reserved()));
        }
        this.status = blockedStatus;
    }

    // ------------------------------------------------------------------ reservation lifecycle

    /**
     * Hold stock for an order line at checkout (BRD 3.14.5).
     *
     * <p>Nothing is deducted — {@code onHand} is unchanged and the goods are still physically
     * there. Only the promise is made, and it expires by itself if the order never pays.</p>
     *
     * @throws IllegalStateException        if the stock is not in a sellable status
     * @throws InsufficientStockException   if available-to-promise is below {@code quantity}
     */
    public Reservation reserve(UUID orderId, UUID rootRequestId, UUID requestId,
                               Quantity quantity, Instant now) {
        return reserve(orderId, rootRequestId, requestId, quantity, now, DEFAULT_RESERVATION_TTL);
    }

    public Reservation reserve(UUID orderId, UUID rootRequestId, UUID requestId,
                               Quantity quantity, Instant now, Duration ttl) {
        Optional<Reservation> alreadyServed = findByRequestId(requestId);
        if (alreadyServed.isPresent()) {
            return alreadyServed.get();
        }
        if (!status.isReservable()) {
            throw new IllegalStateException(
                    "Stock in status %s cannot be reserved for order %s".formatted(status, orderId));
        }
        if (available().isLessThan(quantity)) {
            throw new InsufficientStockException(sku.code(), quantity.value(), available().value());
        }

        Reservation reservation =
                Reservation.open(orderId, rootRequestId, requestId, quantity, now, ttl);
        reservations.add(reservation);
        checkInvariants();
        registerEvent(new com.stockflow.inventory.internal.domain.StockEvent.Reserved(
                new StockReserved(orderId, sku.code(), location.code(),
                        quantity.value(), reservation.expiresAt())));
        return reservation;
    }

    /**
     * Give up a hold — order cancelled, payment failed, or the sweeper found it expired.
     *
     * <p>Releasing an already-closed reservation is a no-op rather than an error, because the
     * cancel path and the expiry sweeper can genuinely race on the same row, and neither should
     * fail because the other got there first.</p>
     */
    public void releaseReservation(ReservationId reservationId, ReleaseReason reason, Instant now) {
        Reservation reservation = findReservation(reservationId).orElseThrow(() ->
                new IllegalArgumentException(
                        "Reservation %s does not belong to stock item %s".formatted(reservationId, id)));
        if (reservation.status().isTerminal()) {
            return;
        }
        reservation.release(reason, now);
        checkInvariants();
        registerEvent(new com.stockflow.inventory.internal.domain.StockEvent.Released(
                new StockReservationReleased(reservation.orderId(), sku.code(),
                        reservation.quantity().value(), reason.toContract())));
    }

    /** Release every live hold that has outlived its {@code expiresAt}. Called by the sweeper. */
    public int releaseExpired(Instant now) {
        List<ReservationId> expired = reservations.stream()
                .filter(r -> r.isExpiredAt(now))
                .map(Reservation::id)
                .toList();
        expired.forEach(rid -> releaseReservation(rid, ReleaseReason.RESERVATION_EXPIRED, now));
        return expired.size();
    }

    /**
     * The goods have been picked: turn a hold into a real deduction (BRD 3.7 / 3.8).
     *
     * <p>This is the only method that lowers {@code onHand}, and it can only do so through a live
     * reservation. Physical stock therefore never leaves the building without a promise behind
     * it.</p>
     */
    public void consumeReservation(ReservationId reservationId, Instant now) {
        Reservation reservation = findReservation(reservationId).orElseThrow(() ->
                new IllegalArgumentException(
                        "Reservation %s does not belong to stock item %s".formatted(reservationId, id)));
        reservation.consume(now);
        this.onHand = this.onHand.minus(reservation.quantity());
        checkInvariants();
        registerEvent(new com.stockflow.inventory.internal.domain.StockEvent.Deducted(
                new StockDeducted(reservation.orderId(), sku.code(), location.code(),
                        reservation.quantity().value())));
    }

    /**
     * Correct {@code onHand} after a physical stock count (BRD 3.10).
     *
     * <p>Counted stock can legitimately come out below what is reserved — that is exactly the
     * shrinkage a count is meant to surface. The invariant check would reject it, so the caller
     * gets told which reservations to release first; silently discarding customer holds inside a
     * counting routine would be far worse than a loud failure.</p>
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

    // ------------------------------------------------------------------ invariants

    /**
     * Checked after every mutation and in the constructor, so an aggregate can never be observed
     * in a broken state — not even one rehydrated from a row a bad migration corrupted.
     */
    private void checkInvariants() {
        Quantity reserved = reserved();
        if (reserved.value() > onHand.value()) {
            throw new IllegalStateException(
                    "Invariant violated on stock item %s: reserved (%s) exceeds onHand (%s)"
                            .formatted(id, reserved, onHand));
        }
    }

    public StockItemId id() { return id; }
    public Sku sku() { return sku; }
    public LocationId location() { return location; }
    public String lotNumber() { return lotNumber; }
    public LocalDate expiryDate() { return expiryDate; }
    public Quantity onHand() { return onHand; }
    public StockStatus status() { return status; }
    public long version() { return version; }

    /** Unmodifiable: reservations change through this aggregate's methods, never from outside. */
    public List<Reservation> reservations() {
        return java.util.Collections.unmodifiableList(reservations);
    }
}
