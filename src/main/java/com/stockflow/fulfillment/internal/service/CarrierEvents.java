package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;

import java.time.Instant;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * What carriers report through their webhooks, and the warehouse manager's error list (3-5; kltn-docs 09 §4.5,
 * BR-22, BR-26, BR-27).
 */
public interface CarrierEvents {

    /**
     * What a webhook call came to.
     *
     * @param authenticated whether its credential held (an unknown carrier's call did not)
     */
    record Receipt(UUID eventId, String result, boolean authenticated) { }

    /** One call as listed: what it reported, what it was matched to, what came of it, and who resolved it. */
    record EventRow(UUID eventId, UUID carrierId, String carrierCode, Instant receivedAt, boolean signatureValid,
                    String kind, String carrierReference, Instant carrierReportedAt, UUID warehouseId, UUID loadId,
                    String loadCode, UUID shipmentId, String shipmentCode, String result, String resultNote,
                    Instant decidedAt, Instant resolvedAt, UUID resolvedBy, String resolvedByName,
                    String resolutionNote) { }

    /** One call with the body it carried (a credential it echoed masked). */
    record EventDetail(EventRow event, String payload) { }

    /**
     * Keeps the call before anything else, checks its credential (API_BOOKING: the configured header in constant time;
     * WEBHOOK_LOAD: the HMAC signature of the raw body), then matches it to a load and applies it. Never throws for
     * what the call said: every outcome is a result on the event.
     *
     * @param header the call's headers by name, case-insensitive; null for one it lacks
     * @param body   the raw body, at most 64 KiB
     */
    Receipt receive(String carrierCode, UnaryOperator<String> header, byte[] body);

    /**
     * Calls, newest first. Unattributed ones (no load matched) are everybody's with the permission; the others are their
     * load's warehouse's.
     *
     * @param result one result, or {@code ERRORS} for the error list's (RECEIVED, UNMATCHED, INVALID_SIGNATURE,
     *               NEEDS_REVIEW)
     * @param open   true: not resolved yet; false: resolved; null: both
     * @throws com.stockflow.common.error.BusinessException {@code UNSUPPORTED_PARAMETER} for an unknown result
     */
    PageResponse<EventRow> list(String result, Boolean open, UUID carrierId, UUID loadId, int page, int size);

    /** @throws com.stockflow.common.error.BusinessException {@code CARRIER_EVENT_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE} */
    EventDetail get(UUID eventId);

    /**
     * An error-list call dealt with by hand (09 §4.5 step 8: recorded or corrected by hand, a waybill number typed) and
     * marked so, with a note. It is never applied again.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CARRIER_EVENT_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         409 {@code CARRIER_EVENT_NOT_RESOLVABLE} for a call that is not on the error list, is resolved already, or
     *         is still being handled; {@code VALIDATION_FAILED} without a note
     */
    EventDetail resolve(UUID eventId, String note, CurrentUser actor);
}
