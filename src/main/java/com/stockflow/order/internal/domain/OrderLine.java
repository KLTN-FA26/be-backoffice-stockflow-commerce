package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One line of an {@link Order}. An entity inside the aggregate, with no repository of its own.
 *
 * <p><b>{@code unitPrice} is copied, not looked up.</b> The price the customer agreed to is a fact
 * about this order, and it must not change when marketing edits the price list next week. This is
 * the difference between a reference and a snapshot, and getting it wrong is how invoices stop
 * matching orders.</p>
 *
 * <p>{@code reservationIds} is the one field that ties a line to another module, and it is a
 * <b>list</b> rather than a single id. A line for ten units is often drawn from two lots, and
 * inventory returns one hold per lot; keeping only the first would strand the rest, so cancelling
 * the order would release six units and leave four held until they expired. They are plain
 * {@code UUID}s: order stores what inventory handed back and passes them to
 * {@code InventoryService.release(...)} later, without ever naming an inventory type.</p>
 */
public final class OrderLine {

    private final UUID id;
    private final Sku sku;
    private final int quantity;
    private final Money unitPrice;
    private final UUID designSnapshotId;
    private String designChecksum;

    public String designChecksum() { return designChecksum; }
    public void recordDesignChecksum(String checksum) {
        if (designSnapshotId == null || checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A confirmed design requires a SHA-256 checksum");
        }
        if (designChecksum != null && !designChecksum.equals(checksum)) {
            throw new IllegalStateException("An order's confirmed design checksum cannot change");
        }
        designChecksum = checksum;
    }

    private final List<UUID> reservationIds;

    /**
     * Units closed out of the line - the rest of a partly fulfilled order (17 edge "short pick"), or a production
     * shortfall the customer agreed to: they will not be delivered.
     */
    private int cancelledQuantity;

    /**
     * Review 2026-10-25 of S6: what the customer agreed with Sales to go without on a print line production cannot
     * finish - at most {@code quantity} units, why (the call, the message), who recorded it and when. Production
     * completing a job short closes at most that many ({@link #shortfallClosedQuantity}): the warehouse manager does not
     * lower what an order is worth on their own.
     */
    public record ShortfallAgreement(int quantity, String note, UUID agreedBy, Instant agreedAt) {
    }

    private ShortfallAgreement shortfallAgreement;
    /** Of {@link #cancelledQuantity}, the units closed because production completed the line short. */
    private int shortfallClosedQuantity;

    public OrderLine(UUID id, Sku sku, int quantity, Money unitPrice,
                     UUID designSnapshotId, List<UUID> reservationIds, int cancelledQuantity) {
        this(id, sku, quantity, unitPrice, designSnapshotId, reservationIds);
        if (cancelledQuantity < 0 || cancelledQuantity > quantity) {
            throw new IllegalArgumentException("Closed units are between 0 and the line's quantity");
        }
        this.cancelledQuantity = cancelledQuantity;
    }

    public OrderLine(UUID id, Sku sku, int quantity, Money unitPrice,
                     UUID designSnapshotId, List<UUID> reservationIds) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.sku = java.util.Objects.requireNonNull(sku, "sku");
        this.unitPrice = java.util.Objects.requireNonNull(unitPrice, "unitPrice");
        if (quantity < 1) {
            throw new IllegalArgumentException("An order line needs at least one unit");
        }
        this.quantity = quantity;
        this.designSnapshotId = designSnapshotId;
        this.reservationIds = new ArrayList<>(reservationIds == null ? List.of() : reservationIds);
    }

    static OrderLine of(UUID id, Sku sku, int quantity, Money unitPrice, UUID designSnapshotId) {
        return new OrderLine(id, sku, quantity, unitPrice, designSnapshotId, List.of());
    }

    /**
     * Record the holds inventory granted. Package-private: only {@link Order} may set them.
     *
     * <p>Refuses a second attach rather than appending, because a line that already holds stock
     * being reserved again means a code path ran twice — and appending would silently double the
     * stock held for one line.</p>
     */
    /** Holds added after checkout, each once (see {@code Order#addProductionHolds}). */
    boolean addHolds(List<UUID> more) {
        boolean added = false;
        for (UUID id : more) {
            if (!reservationIds.contains(java.util.Objects.requireNonNull(id, "reservationId"))) {
                reservationIds.add(id);
                added = true;
            }
        }
        return added;
    }

    void attachReservations(List<UUID> granted) {
        if (!this.reservationIds.isEmpty()) {
            throw new IllegalStateException(
                    "Line %s already holds reservation(s) %s".formatted(id, this.reservationIds));
        }
        if (granted == null || granted.isEmpty()) {
            throw new IllegalArgumentException("A line cannot be reserved with zero holds");
        }
        this.reservationIds.addAll(granted);
    }

    void clearReservations() {
        this.reservationIds.clear();
    }

    /** One hold gives way to the holds that replace it, in its place on the line; each kept once. */
    void replaceHold(UUID replaced, List<UUID> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            throw new IllegalArgumentException("A hold is replaced by at least one hold");
        }
        int at = reservationIds.indexOf(replaced);
        if (at < 0) {
            return;
        }
        reservationIds.remove(at);
        List<UUID> fresh = replacements.stream().filter(id -> !reservationIds.contains(id)).distinct().toList();
        reservationIds.addAll(at, fresh);
    }

    public boolean isReserved() {
        return !reservationIds.isEmpty();
    }

    public Money lineTotal() {
        return unitPrice.times(quantity);
    }

    public UUID id() { return id; }
    public Sku sku() { return sku; }
    public int quantity() { return quantity; }
    public Money unitPrice() { return unitPrice; }
    public UUID designSnapshotId() { return designSnapshotId; }

    /** Unmodifiable: holds are attached and cleared through this class's own methods. */
    public List<UUID> reservationIds() {
        return java.util.Collections.unmodifiableList(reservationIds);
    }

    /** Package-private: only {@link Order#closeRemainder} and {@link #closeShortfall} close units. */
    void closeUnits(int units) {
        if (units <= 0 || cancelledQuantity + units > quantity) {
            throw new IllegalArgumentException("Cannot close %d more of line %s".formatted(units, id));
        }
        cancelledQuantity += units;
    }

    /** Package-private: {@link Order#closeProductionShortfall}, within the agreement. */
    void closeShortfall(int units) {
        if (shortfallAgreement == null || shortfallClosedQuantity + units > shortfallAgreement.quantity()) {
            throw new IllegalStateException("Line %s has no agreement for %d more unit(s)".formatted(id, units));
        }
        closeUnits(units);
        shortfallClosedQuantity += units;
    }

    /** Package-private: {@link Order#agreeProductionShortfall}, which checks it first. */
    void agreeShortfall(ShortfallAgreement agreement) {
        this.shortfallAgreement = java.util.Objects.requireNonNull(agreement, "agreement");
    }

    /** Package-private: {@link Order#agreeProductionShortfall} with 0 units, nothing closed under it. */
    void withdrawShortfall() {
        if (shortfallClosedQuantity > 0) {
            throw new IllegalStateException("Line %s has units closed under its agreement".formatted(id));
        }
        this.shortfallAgreement = null;
    }

    /** Rehydration of the agreement and what production closed under it. */
    public OrderLine withShortfall(ShortfallAgreement agreement, int closed) {
        if (closed < 0 || closed > cancelledQuantity || closed > (agreement == null ? 0 : agreement.quantity())) {
            throw new IllegalArgumentException("Units closed for a shortfall are within the agreement and the closed units");
        }
        this.shortfallAgreement = agreement;
        this.shortfallClosedQuantity = closed;
        return this;
    }

    public int cancelledQuantity() {
        return cancelledQuantity;
    }

    /** Null until Sales records the customer's agreement. */
    public ShortfallAgreement shortfallAgreement() {
        return shortfallAgreement;
    }

    public int shortfallClosedQuantity() {
        return shortfallClosedQuantity;
    }

    public boolean isMadeToOrder() {
        return designSnapshotId != null;
    }
}
