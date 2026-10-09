package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A warehouse and the frame of its map.
 *
 * <p>{@code prefix} starts every location code and document number raised here, and {@code mapUnit}
 * gives every coordinate on the map its meaning, so neither changes once registered (BR-13; the
 * database refuses too, {@code tg_warehouse_immutable}). There is deliberately no method that could
 * change them.</p>
 *
 * <p>The map can grow freely but shrink only down to what is on it (BR-06). What is on it lives in
 * other aggregates - shelves, areas, boundaries - so the caller passes their {@link MapExtent},
 * read under the warehouse row lock that serialises every layout change (issue #18 D4).</p>
 *
 * <p>Deactivating does not touch the shelves, bins and areas of the warehouse: their usability is
 * derived from the warehouse's status when it is read (issue #18 D2), so reactivating brings every
 * location back exactly as it was.</p>
 */
public final class Warehouse extends AggregateRoot {

    static final int NAME_MAX_LENGTH = 200;
    static final int ADDRESS_MAX_LENGTH = 500;

    private final UUID id;
    private final String prefix;
    private String name;
    private String address;
    private String returnAddress;
    private final MapUnit mapUnit;
    private BigDecimal mapWidth;
    private BigDecimal mapHeight;
    private WarehouseStatus status;
    private final long version;
    private final Instant createdAt;

    /**
     * Rehydration from storage; trusts what the database already checked. New warehouses go
     * through {@link #register}.
     */
    public Warehouse(UUID id, String prefix, String name, String address, String returnAddress,
                     MapUnit mapUnit, BigDecimal mapWidth, BigDecimal mapHeight,
                     WarehouseStatus status, long version, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.prefix = prefix;
        this.name = name;
        this.address = address;
        this.returnAddress = returnAddress;
        this.mapUnit = mapUnit;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
        this.createdAt = createdAt;
    }

    public static Warehouse register(UUID id, String prefix, String name, String address,
                                     String returnAddress, MapUnit mapUnit,
                                     BigDecimal mapWidth, BigDecimal mapHeight) {
        if (mapUnit == null) {
            throw DomainChecks.invalid("mapUnit is required");
        }
        return new Warehouse(id,
                CodePart.of(prefix, CodePart.PREFIX_MAX_LENGTH).value(),
                DomainChecks.requiredText("name", name, NAME_MAX_LENGTH),
                DomainChecks.requiredText("address", address, ADDRESS_MAX_LENGTH),
                DomainChecks.optionalText("returnAddress", returnAddress, ADDRESS_MAX_LENGTH),
                mapUnit,
                DomainChecks.positiveMeasure("mapWidth", mapWidth),
                DomainChecks.positiveMeasure("mapHeight", mapHeight),
                WarehouseStatus.ACTIVE, 0L, null);
    }

    /** @param expectedVersion the version the caller's edit was based on */
    public void updateDetails(String name, String address, String returnAddress, long expectedVersion) {
        if (expectedVersion != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Warehouse %s changed meanwhile (version %d, edit based on %d)"
                            .formatted(prefix, version, expectedVersion));
        }
        this.name = DomainChecks.requiredText("name", name, NAME_MAX_LENGTH);
        this.address = DomainChecks.requiredText("address", address, ADDRESS_MAX_LENGTH);
        this.returnAddress = DomainChecks.optionalText("returnAddress", returnAddress, ADDRESS_MAX_LENGTH);
    }

    /**
     * BR-06: the new frame must still hold everything on the map. Edges may coincide - a shelf
     * flush with the far wall is inside.
     *
     * @param occupied how far the shelves, areas and boundaries of this warehouse reach, read under
     *                 the warehouse lock
     */
    public void resizeMap(BigDecimal width, BigDecimal height, MapExtent occupied) {
        BigDecimal newWidth = DomainChecks.positiveMeasure("mapWidth", width);
        BigDecimal newHeight = DomainChecks.positiveMeasure("mapHeight", height);
        if (occupied.right().compareTo(newWidth) > 0 || occupied.bottom().compareTo(newHeight) > 0) {
            throw new BusinessException(ErrorCode.LAYOUT_OUT_OF_BOUNDS,
                    "The map of %s cannot shrink to %s x %s: the layout reaches %s x %s".formatted(
                            prefix, newWidth.toPlainString(), newHeight.toPlainString(),
                            occupied.right().toPlainString(), occupied.bottom().toPlainString()));
        }
        this.mapWidth = newWidth;
        this.mapHeight = newHeight;
    }

    public void activate() {
        this.status = WarehouseStatus.ACTIVE;
    }

    /** No cascade (issue #18 D2), and no stock check yet (BR-04, issue #18 Q5). */
    public void deactivate() {
        this.status = WarehouseStatus.INACTIVE;
    }

    public UUID id() { return id; }
    public String prefix() { return prefix; }
    public String name() { return name; }
    public String address() { return address; }
    public String returnAddress() { return returnAddress; }
    public MapUnit mapUnit() { return mapUnit; }
    public BigDecimal mapWidth() { return mapWidth; }
    public BigDecimal mapHeight() { return mapHeight; }
    public WarehouseStatus status() { return status; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
