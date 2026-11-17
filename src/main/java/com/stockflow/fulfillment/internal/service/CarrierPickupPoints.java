package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierPickupPointJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.CarrierPickupPointJpaRepository;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where an API_BOOKING carrier picks up at each warehouse (3-4; kltn-docs 09 BR-29 "điểm lấy hàng là kho xuất", E24):
 * the address as the carrier reads it, who hands the goods over, and the province - which picks the carrier's service
 * code for the load's vehicle type and bounds the drop-offs. Kept by the warehouse manager, for API_BOOKING carriers
 * only. A manager limited to warehouses sees and replaces the points of those warehouses only: the others' are kept as
 * they are, under the carrier's row lock.
 */
@Service
@Transactional
public class CarrierPickupPoints {

    /** A pickup point as shown and as saved. */
    public record PickupPoint(UUID warehouseId, String provinceCode, String address, String contactName,
                              String contactPhone) { }

    private static final Pattern PROVINCE = Pattern.compile("^[!-~]{1,20}$");

    private final CarrierJpaRepository carriers;
    private final CarrierPickupPointJpaRepository points;
    private final WarehouseService warehouses;

    public CarrierPickupPoints(CarrierJpaRepository carriers, CarrierPickupPointJpaRepository points,
                               WarehouseService warehouses) {
        this.carriers = carriers;
        this.points = points;
        this.warehouses = warehouses;
    }

    @Transactional(readOnly = true)
    public List<PickupPoint> list(UUID carrierId) {
        carriers.findById(carrierId).orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND));
        return points.findByCarrierIdOrderByWarehouseIdAsc(carrierId).stream().filter(CarrierPickupPoints::inScope)
                .map(CarrierPickupPoints::view).toList();
    }

    /**
     * The points of the caller's warehouses become {@code wanted}; those of other warehouses stay.
     *
     * @throws BusinessException {@code CARRIER_NOT_FOUND}; 409 {@code CARRIER_NOT_API_BOOKING}; {@code NOT_FOUND} for
     *         an unknown warehouse; {@code OUT_OF_DATA_SCOPE} for a warehouse the caller is not assigned to;
     *         {@code VALIDATION_FAILED} for a malformed field or a warehouse listed twice
     */
    public List<PickupPoint> replace(UUID carrierId, List<PickupPoint> wanted) {
        List<PickupPoint> given = wanted == null ? List.of() : wanted;
        List<FieldError> errors = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        List<PickupPoint> clean = new ArrayList<>();
        for (int i = 0; i < given.size(); i++) {
            PickupPoint p = given.get(i);
            String at = "pickupPoints[" + i + "].";
            String province = trimmed(p == null ? null : p.provinceCode());
            String address = trimmed(p == null ? null : p.address());
            String name = trimmed(p == null ? null : p.contactName());
            String phone = trimmed(p == null ? null : p.contactPhone());
            if (p == null || p.warehouseId() == null) {
                errors.add(FieldError.of(at + "warehouseId", "required"));
            } else if (!seen.add(p.warehouseId())) {
                errors.add(FieldError.of(at + "warehouseId", "one pickup point per warehouse"));
            }
            if (province == null || !PROVINCE.matcher(province).matches()) {
                errors.add(FieldError.of(at + "provinceCode", "1-20 characters, no spaces"));
            }
            if (address == null || address.length() > 500) {
                errors.add(FieldError.of(at + "address", "required, at most 500 characters"));
            }
            if (name != null && name.length() > 120) {
                errors.add(FieldError.of(at + "contactName", "at most 120 characters"));
            }
            if (phone != null && phone.length() > 32) {
                errors.add(FieldError.of(at + "contactPhone", "at most 32 characters"));
            }
            if (errors.isEmpty()) {
                clean.add(new PickupPoint(p.warehouseId(), province, address, name, phone));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid pickup points", errors);
        }
        CarrierJpaEntity carrier = carriers.lockById(carrierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND));
        if (!CarrierConnectionType.API_BOOKING.name().equals(carrier.getConnectionType())) {
            throw new BusinessException(ErrorCode.CARRIER_NOT_API_BOOKING,
                    "Carrier %s does not book through an API".formatted(carrier.getCode()));
        }
        for (PickupPoint p : clean) {
            WarehouseScope.requireWarehouse(p.warehouseId());
            if (warehouses.findWarehouse(p.warehouseId()).isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "No warehouse " + p.warehouseId());
            }
        }
        // Flushed before the new rows: Hibernate inserts before it deletes, and uk_carrier_pickup_point would refuse.
        points.deleteAll(points.findByCarrierIdOrderByWarehouseIdAsc(carrierId).stream()
                .filter(CarrierPickupPoints::inScope).toList());
        points.flush();
        points.saveAllAndFlush(clean.stream().map(p -> new CarrierPickupPointJpaEntity(Identifiers.newId(), carrierId,
                p.warehouseId(), p.provinceCode(), p.address(), p.contactName(), p.contactPhone())).toList());
        return list(carrierId);
    }

    private static boolean inScope(CarrierPickupPointJpaEntity p) {
        return WarehouseScope.restriction().map(limit -> limit.ids().contains(p.getWarehouseId())).orElse(true);
    }

    private static PickupPoint view(CarrierPickupPointJpaEntity p) {
        return new PickupPoint(p.getWarehouseId(), p.getProvinceCode(), p.getAddress(), p.getContactName(),
                p.getContactPhone());
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
