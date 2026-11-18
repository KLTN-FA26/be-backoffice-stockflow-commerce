package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *
 * <p>{@code utilizationFactor} is the share of its bins' computed volume the warehouse can really fill once working
 * space and the gaps between cartons are taken out (kltn-docs 06 §4.6 step 2): one factor for the whole warehouse, 1
 * (100%) until its manager sets it, always in (0, 1] (C43). Only the capacity board reads it; putaway checks a bin
 * against its full volume (05 BR-03). The database states the bound again, {@code ck_warehouse_utilization_factor}.</p>
 */
public final class Warehouse extends AggregateRoot {

    static final int NAME_MAX_LENGTH = 200;
    static final int ADDRESS_MAX_LENGTH = 500;
    /** {@code numeric(5,4)}: 0.8500 is 85%. */
    static final int UTILIZATION_FACTOR_SCALE = 4;

    private final UUID id;
    private final String prefix;
    private String name;
    private String address;
    private String returnAddress;
    private final MapUnit mapUnit;
    private BigDecimal mapWidth;
    private BigDecimal mapHeight;
    private WarehouseStatus status;
    private BigDecimal utilizationFactor;
    private final long version;
    private final Instant createdAt;

    /**
     * Rehydration from storage; trusts what the database already checked. New warehouses go
     * through {@link #register}.
     */
    public Warehouse(UUID id, String prefix, String name, String address, String returnAddress,
                     MapUnit mapUnit, BigDecimal mapWidth, BigDecimal mapHeight,
                     WarehouseStatus status, BigDecimal utilizationFactor, long version, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.prefix = prefix;
        this.name = name;
        this.address = address;
        this.returnAddress = returnAddress;
        this.mapUnit = mapUnit;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.status = Objects.requireNonNull(status, "status");
        this.utilizationFactor = Objects.requireNonNull(utilizationFactor, "utilizationFactor");
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
                WarehouseStatus.ACTIVE, BigDecimal.ONE, 0L, null);
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

    /** BR-06: a shelf or area must lie inside the map frame; flush with its edge is inside. */
    public void requireOnMap(Footprint footprint) {
        if (!footprint.fitsWithin(mapWidth, mapHeight)) {
            throw new BusinessException(ErrorCode.LAYOUT_OUT_OF_BOUNDS,
                    "This would stick out of the %s x %s map of %s".formatted(
                            mapWidth.toPlainString(), mapHeight.toPlainString(), prefix));
        }
    }

    /** BR-06 for a wall or door: both ends inside the map frame; on its edge is inside. */
    public void requireOnMap(Segment segment) {
        if (!segment.fitsWithin(mapWidth, mapHeight)) {
            throw new BusinessException(ErrorCode.LAYOUT_OUT_OF_BOUNDS,
                    "This boundary would run off the %s x %s map of %s".formatted(
                            mapWidth.toPlainString(), mapHeight.toPlainString(), prefix));
        }
    }

    /**
     * C43: one factor for the whole warehouse, in (0, 1] - a warehouse that fills none of its bins has no capacity to
     * plan with, and one cannot hold more than its bins. At most four decimals, the column's ({@code numeric(5,4)}).
     */
    public void changeUtilizationFactor(BigDecimal factor) {
        if (factor == null) {
            throw DomainChecks.invalid("utilizationFactor is required");
        }
        if (factor.signum() <= 0 || factor.compareTo(BigDecimal.ONE) > 0) {
            throw DomainChecks.invalid("utilizationFactor must be above 0 and at most 1 (100%%): %s"
                    .formatted(factor.toPlainString()));
        }
        if (factor.stripTrailingZeros().scale() > UTILIZATION_FACTOR_SCALE) {
            throw DomainChecks.invalid("utilizationFactor has more than %d decimals: %s"
                    .formatted(UTILIZATION_FACTOR_SCALE, factor.toPlainString()));
        }
        this.utilizationFactor = factor.setScale(UTILIZATION_FACTOR_SCALE, RoundingMode.UNNECESSARY);
    }

    public void activate() {
        this.status = WarehouseStatus.ACTIVE;
    }

    /**
     * No cascade (issue #18 D2). That no carton stands in the warehouse any more (kltn-docs 06 BR-04) is the
     * application service's check: what stands where is inventory's knowledge.
     */
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
    public BigDecimal utilizationFactor() { return utilizationFactor; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
