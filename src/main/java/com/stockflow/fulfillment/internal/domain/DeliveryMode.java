package com.stockflow.fulfillment.internal.domain;

/**
 * How a shipment travels (kltn-docs 09 §1): parcels through a carrier, or a full truckload planned in 3D (09 §4.2).
 * Packing copies the order's mode ({@code order.api.DeliveryMode}, the same names; LP-1) onto every shipment it makes.
 */
public enum DeliveryMode { PARCEL, FULL_TRUCKLOAD }
