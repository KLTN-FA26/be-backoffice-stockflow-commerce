package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One change of a load's waybill number (kltn-docs 09 §4.4, BR-24).
 *
 * @param newWaybillNo absent when the booking was cancelled and the number went with it
 * @param source       MANUAL, FILE, WEBHOOK or API
 * @param changedBy    absent for a webhook
 */
public record WaybillChangeResponse(String oldWaybillNo, String newWaybillNo, String source, UUID changedBy,
                                    String changedByName, Instant changedAt, String reason) {
}
