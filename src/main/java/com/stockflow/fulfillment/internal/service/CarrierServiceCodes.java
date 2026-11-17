package com.stockflow.fulfillment.internal.service;

import java.util.List;
import java.util.UUID;

/**
 * An API_BOOKING carrier's service code per vehicle type and province (3-1; kltn-docs 09 §3, §4.1 step 2: "mỗi loại xe
 * ứng với mã dịch vụ của hãng theo thành phố"; E24). Booking a load with the carrier (3-4) picks the code of the load's
 * vehicle type in the warehouse's province, so there is at most one per (vehicle type, province).
 */
public interface CarrierServiceCodes {

    /**
     * @param provinceCode    our province code, as an order's shipping address carries it
     * @param carrierCityCode the carrier's id for that city (Ahamove {@code city_id}, e.g. {@code SGN})
     * @param serviceCode     the carrier's service for that truck size there (Ahamove {@code service_id})
     */
    record ServiceCode(UUID vehicleTypeId, String vehicleTypeCode, String provinceCode, String carrierCityCode,
                       String serviceCode) { }

    /** One code to save; {@link #replace} takes the whole list. */
    record SaveServiceCode(UUID vehicleTypeId, String provinceCode, String carrierCityCode, String serviceCode) { }

    /** The carrier's codes, by province then service. */
    List<ServiceCode> list(UUID carrierId);

    /**
     * Replaces the carrier's codes with {@code codes} (an empty list removes them all). Kept only for an API_BOOKING
     * carrier: no other kind books through an API. A carrier that stops being API_BOOKING keeps its codes, unused,
     * for the day it is switched back.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CARRIER_NOT_FOUND}; 409
     *         {@code CARRIER_NOT_API_BOOKING}; {@code VEHICLE_TYPE_NOT_FOUND}; {@code VALIDATION_FAILED} for a blank
     *         or malformed field, more than {@value CarrierServiceCodeService#MAX_CODES} codes, or a vehicle type and
     *         province listed twice
     */
    List<ServiceCode> replace(UUID carrierId, List<SaveServiceCode> codes);
}
