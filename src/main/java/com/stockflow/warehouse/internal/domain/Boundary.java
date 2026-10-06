package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Objects;
import java.util.UUID;

/**
 * A wall, or a door in it, on the warehouse map. Walking distance (BR-09) will cross only doors that
 * are passable and {@code OPEN}; a wall is never passable and has no open/closed state - the same
 * rule {@code ck_boundary_kind} states, checked here first so the user gets a reason, not a
 * constraint violation.
 *
 * <p>Inside the map (BR-06) is checked by the application service under the warehouse lock. Not
 * clear of anything (BR-07): a wall alongside a shelf is the normal case.</p>
 *
 * <p>The one thing on the map that is deleted rather than made {@code INACTIVE} (issue #18 D11): it
 * has no status and nothing points at it, and a wall drawn in the wrong place must be removable.</p>
 */
public final class Boundary extends AggregateRoot {

    private final UUID id;
    private final UUID warehouseId;
    private BoundaryType type;
    private Segment segment;
    private boolean passable;
    private DoorStatus operationalStatus;
    private final long version;

    /** Rehydration from storage. New boundaries go through {@link #create}. */
    public Boundary(UUID id, UUID warehouseId, BoundaryType type, Segment segment, boolean passable,
                    DoorStatus operationalStatus, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.type = Objects.requireNonNull(type, "type");
        this.segment = Objects.requireNonNull(segment, "segment");
        this.passable = passable;
        this.operationalStatus = operationalStatus;
        this.version = version;
    }

    /**
     * @param passable          a wall: must be {@code false}
     * @param operationalStatus a wall: must be {@code null}; a door: required
     */
    public static Boundary create(UUID id, UUID warehouseId, BoundaryType type, Segment segment, boolean passable,
                                  DoorStatus operationalStatus) {
        requireShape(type, segment, passable, operationalStatus);
        return new Boundary(id, warehouseId, type, segment, passable, operationalStatus, 0L);
    }

    /**
     * Everything may change, the type included: a wall with a door cut into it is redrawn.
     *
     * @param expectedVersion the version the caller's edit was based on
     */
    public void update(BoundaryType type, Segment segment, boolean passable, DoorStatus operationalStatus,
                       long expectedVersion) {
        requireVersion(expectedVersion);
        requireShape(type, segment, passable, operationalStatus);
        this.type = type;
        this.segment = segment;
        this.passable = passable;
        this.operationalStatus = operationalStatus;
    }

    /**
     * Refuses an action based on an older read. Also asked before a delete: deleting is final, so a
     * stale view must not remove a wall someone has just redrawn as a door.
     */
    public void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Boundary %s changed meanwhile (version %d, action based on %d)".formatted(id, version, expectedVersion));
        }
    }

    private static void requireShape(BoundaryType type, Segment segment, boolean passable,
                                     DoorStatus operationalStatus) {
        if (type == null || segment == null) {
            throw DomainChecks.invalid("A boundary needs a type and a position");
        }
        switch (type) {
            case WALL -> {
                if (passable) {
                    throw DomainChecks.invalid("A wall is never passable");
                }
                if (operationalStatus != null) {
                    throw DomainChecks.invalid("A wall has no open or closed state");
                }
            }
            case DOOR -> {
                if (operationalStatus == null) {
                    throw DomainChecks.invalid("A door needs an operational status, OPEN or CLOSED");
                }
            }
        }
    }

    public UUID id() { return id; }
    public UUID warehouseId() { return warehouseId; }
    public BoundaryType type() { return type; }
    public Segment segment() { return segment; }
    public boolean passable() { return passable; }
    /** {@code null} for a wall. */
    public DoorStatus operationalStatus() { return operationalStatus; }
    public long version() { return version; }
}
