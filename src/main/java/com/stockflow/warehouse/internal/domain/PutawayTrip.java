package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A clerk's cart of cartons to put away in one go (kltn-docs 05 §4.5, BR-15, BR-16).
 *
 * <p>A putaway task stays one carton (05 BR-08); the trip only says which tasks are on the cart, which bin each was
 * assigned when the clerk set off, in what order the bins are visited and whether the space at each is still held.
 * Putting a carton away is the task's business ({@link PutawayTask}); the trip is told when it happened.</p>
 *
 * <p>Invariants it protects:</p>
 * <ul>
 *   <li>cartons come onto and off the cart only while {@code LOADING}; bins are assigned only on {@link #start}, for
 *       exactly the cartons on the cart then (BR-15);</li>
 *   <li>a trip sets off with at least one carton;</li>
 *   <li>space is held only for a carton on the cart with a bin assigned: placing it, moving it to another bin, taking
 *       it off the cart or ending the trip gives the space back (BR-16);</li>
 *   <li>a trip is {@code COMPLETED} exactly when its last carton on the cart was put away, and it never reopens.</li>
 * </ul>
 *
 * <p>Whether a second trip of the same clerk, or a second cart for the same task, may exist cannot be decided by one
 * aggregate: the service asks the repository first and {@code uk_putaway_trip_open_operator} /
 * {@code uk_putaway_trip_item_on_cart} decide for good.</p>
 */
public final class PutawayTrip extends AggregateRoot {

    private final UUID id;
    private final UUID warehouseId;
    private final UUID operatorId;
    private PutawayTripStatus status;
    private UUID startLocationId;
    private Instant startedAt;
    private Instant lastActivityAt;
    private Instant closedAt;
    private UUID closedBy;
    private final List<Item> items;

    /** Rehydration from storage. New trips go through {@link #open}. */
    public PutawayTrip(UUID id, UUID warehouseId, UUID operatorId, PutawayTripStatus status, UUID startLocationId,
                       Instant startedAt, Instant lastActivityAt, Instant closedAt, UUID closedBy, List<Item> items) {
        this.id = Objects.requireNonNull(id, "id");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
        this.status = Objects.requireNonNull(status, "status");
        this.startLocationId = startLocationId;
        this.startedAt = startedAt;
        this.lastActivityAt = Objects.requireNonNull(lastActivityAt, "lastActivityAt");
        this.closedAt = closedAt;
        this.closedBy = closedBy;
        this.items = new ArrayList<>(items);
    }

    public static PutawayTrip open(UUID id, UUID warehouseId, UUID operatorId, Instant now) {
        return new PutawayTrip(id, warehouseId, operatorId, PutawayTripStatus.LOADING, null, null, now, null, null,
                List.of());
    }

    /**
     * Scans a carton onto the cart. Scanning one already on it changes nothing; one taken off earlier comes back as
     * the same row.
     *
     * @param itemId the id of the row, used only when the carton was never on this cart
     */
    public void load(UUID itemId, UUID taskId, Instant now) {
        requireStatus(PutawayTripStatus.LOADING, "take a carton onto the cart");
        Optional<Item> known = itemOf(taskId);
        if (known.isPresent()) {
            known.get().backOnCart(now);
        } else {
            items.add(new Item(Objects.requireNonNull(itemId, "itemId"), Objects.requireNonNull(taskId, "taskId"),
                    PutawayTripItemStatus.ON_CART, null, null, null, now, null));
        }
        lastActivityAt = now;
    }

    /** Takes a carton off the cart before the clerk sets off (05 §4.5 step 12). */
    public void unload(UUID taskId, Instant now) {
        requireStatus(PutawayTripStatus.LOADING, "take a carton off the cart");
        onCart(taskId).returned(now);
        lastActivityAt = now;
    }

    /**
     * The clerk sets off (05 §4.5 step 13): every carton on the cart gets the bin (or OVERFLOW area) chosen for it and
     * its stop on the route, and the space is held from now.
     *
     * @param assignedLocationByTask the location of every carton on the cart; a carton nothing was found for is absent
     *                               and holds nothing
     * @param stopByTask             the stop number of each assigned carton, from 1
     * @param startLocationId        the source area the route starts from
     */
    public void start(Map<UUID, UUID> assignedLocationByTask, Map<UUID, Integer> stopByTask, UUID startLocationId,
                      Instant now) {
        requireStatus(PutawayTripStatus.LOADING, "set off");
        List<Item> cart = cart();
        if (cart.isEmpty()) {
            throw new BusinessException(ErrorCode.PUTAWAY_TRIP_EMPTY,
                    "Putaway trip " + id + " has no carton on the cart");
        }
        cart.forEach(item -> item.assign(assignedLocationByTask.get(item.taskId), stopByTask.get(item.taskId)));
        this.startLocationId = startLocationId;
        this.status = PutawayTripStatus.IN_PROGRESS;
        this.startedAt = now;
        this.lastActivityAt = now;
    }

    /**
     * A carton on its way gets another bin (05 §4.5 step 15: the assigned one was refused when scanned). The space at
     * the old bin is given back, the new one is held.
     */
    public void reassign(UUID taskId, UUID locationId, Instant now) {
        requireStatus(PutawayTripStatus.IN_PROGRESS, "assign another bin");
        onCart(taskId).assign(locationId, null);
        lastActivityAt = now;
    }

    /** The stops in visiting order, renumbered after a carton was reassigned or put away somewhere else. */
    public void renumberStops(Map<UUID, Integer> stopByTask) {
        cart().forEach(item -> item.stopSequence = item.assignedLocationId == null ? null
                : stopByTask.get(item.taskId));
    }

    /**
     * A carton of the cart was put away - into the bin assigned or any other that took it. Its hold ends, and the
     * trip is {@code COMPLETED} when nothing is left on the cart (05 §4.5 step 17).
     */
    public void recordPlaced(UUID taskId, Instant now) {
        requireStatus(PutawayTripStatus.IN_PROGRESS, "put a carton away");
        onCart(taskId).placed(now);
        lastActivityAt = now;
        if (cart().isEmpty()) {
            status = PutawayTripStatus.COMPLETED;
            closedAt = now;
        }
    }

    /**
     * Ends the trip with cartons still on the cart (05 §4.5 step 17): each goes back to its source area, its task
     * PENDING for anybody to take, and what was held is given back.
     *
     * @param by the clerk, or the warehouse manager closing for them
     */
    public void close(UUID by, Instant now) {
        if (!status.open()) {
            throw invalidState("be closed");
        }
        cart().forEach(item -> item.returned(now));
        status = PutawayTripStatus.CLOSED;
        closedAt = now;
        closedBy = Objects.requireNonNull(by, "by");
        lastActivityAt = now;
    }

    /**
     * Gives back every space still held, the trip staying as it is (05 BR-16: no scan for the hold window). The
     * cartons keep the bin they were assigned; the bin is checked when it is scanned, as always.
     *
     * @return whether anything was held
     */
    public boolean releaseHolds(Instant now) {
        boolean held = false;
        for (Item item : cart()) {
            if (item.holding()) {
                item.holdReleasedAt = now;
                held = true;
            }
        }
        return held;
    }

    /** Whether {@code taskId} is on the cart now. */
    public boolean carries(UUID taskId) {
        return itemOf(taskId).filter(item -> item.status == PutawayTripItemStatus.ON_CART).isPresent();
    }

    private List<Item> cart() {
        return items.stream().filter(item -> item.status == PutawayTripItemStatus.ON_CART).toList();
    }

    private Optional<Item> itemOf(UUID taskId) {
        return items.stream().filter(item -> item.taskId.equals(taskId)).findFirst();
    }

    private Item onCart(UUID taskId) {
        return itemOf(taskId).filter(item -> item.status == PutawayTripItemStatus.ON_CART)
                .orElseThrow(() -> new BusinessException(ErrorCode.PUTAWAY_CARTON_NOT_ON_TRIP,
                        "That carton is not on the cart of putaway trip " + id));
    }

    private void requireStatus(PutawayTripStatus expected, String action) {
        if (status != expected) {
            throw invalidState(action);
        }
    }

    private BusinessException invalidState(String action) {
        return new BusinessException(ErrorCode.PUTAWAY_TRIP_INVALID_STATE,
                "Putaway trip %s is %s and cannot %s".formatted(id, status, action));
    }

    public UUID id() { return id; }
    public UUID warehouseId() { return warehouseId; }
    public UUID operatorId() { return operatorId; }
    public PutawayTripStatus status() { return status; }
    public UUID startLocationId() { return startLocationId; }
    public Instant startedAt() { return startedAt; }
    public Instant lastActivityAt() { return lastActivityAt; }
    public Instant closedAt() { return closedAt; }
    public UUID closedBy() { return closedBy; }
    public List<Item> items() { return Collections.unmodifiableList(items); }

    /** A carton that is, or was, on the cart. Changed only through its trip. */
    public static final class Item {

        private final UUID id;
        private final UUID taskId;
        private PutawayTripItemStatus status;
        private UUID assignedLocationId;
        private Integer stopSequence;
        private Instant holdReleasedAt;
        private Instant loadedAt;
        private Instant placedAt;

        /** Rehydration from storage. */
        public Item(UUID id, UUID taskId, PutawayTripItemStatus status, UUID assignedLocationId, Integer stopSequence,
                    Instant holdReleasedAt, Instant loadedAt, Instant placedAt) {
            this.id = Objects.requireNonNull(id, "id");
            this.taskId = Objects.requireNonNull(taskId, "taskId");
            this.status = Objects.requireNonNull(status, "status");
            this.assignedLocationId = assignedLocationId;
            this.stopSequence = stopSequence;
            this.holdReleasedAt = holdReleasedAt;
            this.loadedAt = Objects.requireNonNull(loadedAt, "loadedAt");
            this.placedAt = placedAt;
        }

        /** Whether space is held for the carton at the bin assigned to it. */
        public boolean holding() {
            return status == PutawayTripItemStatus.ON_CART && assignedLocationId != null && holdReleasedAt == null;
        }

        private void backOnCart(Instant now) {
            if (status == PutawayTripItemStatus.RETURNED) {
                status = PutawayTripItemStatus.ON_CART;
                loadedAt = now;
            }
        }

        /** A new bin is a new hold; no bin holds nothing. */
        private void assign(UUID locationId, Integer stop) {
            assignedLocationId = locationId;
            stopSequence = locationId == null ? null : stop;
            holdReleasedAt = null;
        }

        private void placed(Instant now) {
            status = PutawayTripItemStatus.PLACED;
            placedAt = now;
            endHold(now);
        }

        private void returned(Instant now) {
            status = PutawayTripItemStatus.RETURNED;
            endHold(now);
        }

        /** ck_putaway_trip_item_hold: nothing is held for a carton no longer on the cart. */
        private void endHold(Instant now) {
            if (assignedLocationId != null && holdReleasedAt == null) {
                holdReleasedAt = now;
            }
        }

        public UUID id() { return id; }
        public UUID taskId() { return taskId; }
        public PutawayTripItemStatus status() { return status; }
        public UUID assignedLocationId() { return assignedLocationId; }
        public Integer stopSequence() { return stopSequence; }
        public Instant holdReleasedAt() { return holdReleasedAt; }
        public Instant loadedAt() { return loadedAt; }
        public Instant placedAt() { return placedAt; }
    }
}
