package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierServiceCodeJpaEntity;
import com.stockflow.fulfillment.internal.entity.VehicleTypeJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.CarrierServiceCodeJpaRepository;
import com.stockflow.fulfillment.internal.repository.VehicleTypeJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * See {@link CarrierServiceCodes}. The replacement runs under the carrier's row lock, so two managers saving at once
 * end with one list, not a mix; the lock is {@code FOR NO KEY UPDATE}, which a load being booked with the carrier
 * (a foreign-key share lock) does not wait for.
 */
@Service
@Transactional
class CarrierServiceCodeService implements CarrierServiceCodes {

    /** One code per truck size per city: a carrier with more than this is a paste gone wrong. */
    static final int MAX_CODES = 500;
    private static final Pattern PROVINCE = Pattern.compile("^[!-~]{1,20}$");
    private static final Pattern CITY = Pattern.compile("^[!-~]{1,32}$");
    private static final Pattern SERVICE = Pattern.compile("^[!-~]{1,64}$");

    private final CarrierJpaRepository carriers;
    private final CarrierServiceCodeJpaRepository codes;
    private final VehicleTypeJpaRepository vehicleTypes;

    CarrierServiceCodeService(CarrierJpaRepository carriers, CarrierServiceCodeJpaRepository codes,
                              VehicleTypeJpaRepository vehicleTypes) {
        this.carriers = carriers;
        this.codes = codes;
        this.vehicleTypes = vehicleTypes;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceCode> list(UUID carrierId) {
        carriers.findById(carrierId).orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND));
        return views(codes.findByCarrierIdOrderByProvinceCodeAscServiceCodeAsc(carrierId));
    }

    @Override
    public List<ServiceCode> replace(UUID carrierId, List<SaveServiceCode> wanted) {
        List<SaveServiceCode> given = wanted == null ? List.of() : wanted;
        List<FieldError> errors = new ArrayList<>();
        if (given.size() > MAX_CODES) {
            errors.add(FieldError.of("codes", "at most " + MAX_CODES));
        }
        Set<String> seen = new HashSet<>();
        List<SaveServiceCode> clean = new ArrayList<>();
        for (int i = 0; i < given.size() && errors.isEmpty(); i++) {
            SaveServiceCode code = given.get(i);
            String at = "codes[" + i + "].";
            String province = trimmed(code == null ? null : code.provinceCode());
            String city = trimmed(code == null ? null : code.carrierCityCode());
            String service = trimmed(code == null ? null : code.serviceCode());
            if (code == null || code.vehicleTypeId() == null) {
                errors.add(FieldError.of(at + "vehicleTypeId", "required"));
            }
            if (province == null || !PROVINCE.matcher(province).matches()) {
                errors.add(FieldError.of(at + "provinceCode", "1-20 characters, no spaces"));
            }
            if (city == null || !CITY.matcher(city).matches()) {
                errors.add(FieldError.of(at + "carrierCityCode", "1-32 characters, no spaces"));
            }
            if (service == null || !SERVICE.matcher(service).matches()) {
                errors.add(FieldError.of(at + "serviceCode", "1-64 characters, no spaces"));
            }
            if (errors.isEmpty() && !seen.add(code.vehicleTypeId() + "|" + province)) {
                errors.add(FieldError.of(at + "provinceCode", "this vehicle type already has a code in " + province));
            }
            if (errors.isEmpty()) {
                clean.add(new SaveServiceCode(code.vehicleTypeId(), province, city, service));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid carrier service codes", errors);
        }

        CarrierJpaEntity carrier = carriers.lockById(carrierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND));
        if (!CarrierConnectionType.API_BOOKING.name().equals(carrier.getConnectionType())) {
            throw new BusinessException(ErrorCode.CARRIER_NOT_API_BOOKING,
                    "Carrier %s does not book through an API".formatted(carrier.getCode()));
        }
        Set<UUID> typeIds = clean.stream().map(SaveServiceCode::vehicleTypeId).collect(Collectors.toSet());
        Map<UUID, VehicleTypeJpaEntity> types = vehicleTypes.findAllById(typeIds).stream()
                .collect(Collectors.toMap(VehicleTypeJpaEntity::getId, Function.identity()));
        if (types.size() != typeIds.size()) {
            throw new BusinessException(ErrorCode.VEHICLE_TYPE_NOT_FOUND);
        }

        codes.deleteByCarrier(carrierId);
        codes.saveAllAndFlush(clean.stream().map(code -> new CarrierServiceCodeJpaEntity(Identifiers.newId(),
                carrierId, code.vehicleTypeId(), code.provinceCode(), code.carrierCityCode(), code.serviceCode()))
                .toList());
        return views(codes.findByCarrierIdOrderByProvinceCodeAscServiceCodeAsc(carrierId));
    }

    private List<ServiceCode> views(List<CarrierServiceCodeJpaEntity> rows) {
        Map<UUID, String> typeCodes = vehicleTypes.findAllById(rows.stream()
                        .map(CarrierServiceCodeJpaEntity::getVehicleTypeId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(VehicleTypeJpaEntity::getId, VehicleTypeJpaEntity::getCode));
        return rows.stream().map(row -> new ServiceCode(row.getVehicleTypeId(), typeCodes.get(row.getVehicleTypeId()),
                row.getProvinceCode(), row.getCarrierCityCode(), row.getServiceCode())).toList();
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
