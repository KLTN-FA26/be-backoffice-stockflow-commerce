package com.stockflow.fulfillment.internal.controller.dto;

/** A truck service an API_BOOKING carrier sells in a city (3-4), as its API names it. */
public record CarrierServiceOptionResponse(String serviceCode, String name, String cityId) {
}
