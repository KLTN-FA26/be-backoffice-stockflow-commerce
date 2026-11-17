package com.stockflow.fulfillment.internal.domain;

/**
 * How a carrier talks to the system (kltn-docs 09 §4.1 step 2, BR-21).
 *
 * <ul>
 *   <li>{@link #MANUAL} - no webhook: dispatch staff type or import the waybill number (§4.4) and the warehouse manager
 *       records each delivery outcome (§4.3).</li>
 *   <li>{@link #WEBHOOK_LOAD} - the carrier pushes events naming the internal load code, each signed with a secret
 *       both sides hold (§4.5; QD-11: HMAC-SHA256 of the body).</li>
 *   <li>{@link #API_BOOKING} - the system creates and cancels the carrier's order itself (§4.2 step 6, BR-29) and the
 *       carrier pushes the order's and each stop's status; it signs nothing, so its webhooks carry a credential in a
 *       header instead (Ahamove: {@code apikey}, Bearer or Basic).</li>
 * </ul>
 */
public enum CarrierConnectionType {
    MANUAL, WEBHOOK_LOAD, API_BOOKING;

    /** Whether the carrier sends webhooks at all. */
    public boolean sendsWebhooks() {
        return this != MANUAL;
    }
}
