package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

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

    public boolean isMadeToOrder() {
        return designSnapshotId != null;
    }
}
