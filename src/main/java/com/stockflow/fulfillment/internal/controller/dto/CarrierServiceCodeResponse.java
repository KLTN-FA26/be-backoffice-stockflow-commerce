package com.stockflow.fulfillment.internal.controller.dto;

import java.util.UUID;

/** One of an API_BOOKING carrier's service codes (kltn-docs 09 §3). */
public record CarrierServiceCodeResponse(UUID vehicleTypeId, String vehicleTypeCode, String provinceCode,
                                         String carrierCityCode, String serviceCode) {
}
