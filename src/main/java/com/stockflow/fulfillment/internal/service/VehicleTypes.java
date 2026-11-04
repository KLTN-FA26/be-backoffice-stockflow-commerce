package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The vehicle-type catalogue of full-truckload load planning (SCRUM-287; kltn-docs 09 §3, §4.2). Read by dispatch
 * staff and coordinators; kept by the warehouse manager. The ISO container presets (20DC, 40DC, 40HC, 45HC) are seeded.
 */
public interface VehicleTypes {

    record VehicleTypeView(UUID id, String code, String name, String kind, int innerLengthMm, int innerWidthMm,
                           int innerHeightMm, int gussetMm, Integer doorWidthMm, Integer doorHeightMm,
                           BigDecimal maxPayloadKg, int availableCount, BigDecimal rentalPrice, boolean preset, String status, long version) { }

    /**
     * @param code    create: the code; update: optional, and if given it must be the current one (a code never changes)
     * @param gussetMm ceiling-corner gusset cube edge in mm (see the migration); null = 0, also on update (full replace)
     * @param doorWidthMm  optional clear door opening width in mm, at most the inner width; null = no limit, also on update (full replace)
     * @param doorHeightMm optional clear door opening height in mm, at most the inner height; null = no limit, also on update (full replace)
     * @param status  ACTIVE / INACTIVE; null keeps the current one (ACTIVE on create)
     * @param version required to change an existing type: the version read (optimistic lock); ignored on create
     */
    record SaveVehicleType(String code, String name, String kind, Integer innerLengthMm, Integer innerWidthMm,
                           Integer innerHeightMm, Integer gussetMm, Integer doorWidthMm, Integer doorHeightMm,
                           BigDecimal maxPayloadKg, Integer availableCount,
                           BigDecimal rentalPrice, String status, Long version) { }

    /** Paged, by name; {@code status} ACTIVE / INACTIVE or null for both; {@code search} in code or name. */
    PageResponse<VehicleTypeView> list(String status, String search, int page, int size);

    /** @throws com.stockflow.common.error.BusinessException {@code VEHICLE_TYPE_NOT_FOUND} */
    VehicleTypeView get(UUID id);

    /**
     * Declares a truck, pickup or container type that is not a preset.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VEHICLE_TYPE_CODE_EXISTS};
     *         {@code VALIDATION_FAILED}
     */
    VehicleTypeView create(SaveVehicleType type);

    /**
     * A full replace of everything but the code (and, for a preset, the kind): an optional field left out is cleared,
     * only a missing status keeps the current one. INACTIVE takes the type out of new plans; plans that already use it keep it.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VEHICLE_TYPE_NOT_FOUND};
     *         {@code VALIDATION_FAILED} (a different code); {@code OPTIMISTIC_LOCK} when {@code version} is not the
     *         current one
     */
    VehicleTypeView update(UUID id, SaveVehicleType type);
}
