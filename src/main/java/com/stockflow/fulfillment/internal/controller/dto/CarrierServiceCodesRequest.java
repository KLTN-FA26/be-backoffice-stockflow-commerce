package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * An API_BOOKING carrier's service codes, replaced whole (kltn-docs 09 §3, E24): one per vehicle type and province.
 *
 * @param codes the full list; empty removes them all
 */
public record CarrierServiceCodesRequest(@NotNull @Size(max = 500) List<@Valid @NotNull Code> codes) {

    /**
     * @param provinceCode    our province code, as an order's shipping address carries it
     * @param carrierCityCode the carrier's id for that city (Ahamove {@code city_id}, e.g. SGN)
     * @param serviceCode     the carrier's service for this truck size there (Ahamove {@code service_id})
     */
    public record Code(@NotNull UUID vehicleTypeId, @NotNull @Size(max = 20) String provinceCode,
                       @NotNull @Size(max = 32) String carrierCityCode, @NotNull @Size(max = 64) String serviceCode) {
    }
}
