package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.DeliveryFailureReason;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * What carriers report through their webhooks - and what a file of delivery outcomes says, line by line - and the
 * warehouse manager's error list (3-5; kltn-docs 09 §4.5, BR-22, BR-26, BR-27).
 */
public interface CarrierEvents {

    /**
     * What a webhook call came to.
     *
     * @param eventId       the event kept; null for a call naming no carrier the system knows, which is not kept
     * @param authenticated whether its credential held (an unknown carrier's call did not)
     * @param retry         it could not be handled now: the carrier is answered 503 so that it sends it again
     */
    record Receipt(UUID eventId, String result, boolean authenticated, boolean retry) { }

    /**
     * One call as listed: what it reported, what it was matched to, what came of it, and who resolved it.
     *
     * @param source WEBHOOK (the carrier called) or FILE (a line of an imported file of delivery outcomes)
     */
    record EventRow(UUID eventId, UUID carrierId, String carrierCode, String source, Instant receivedAt,
                    boolean signatureValid,
                    String kind, String carrierReference, Instant carrierReportedAt, UUID warehouseId, UUID loadId,
                    String loadCode, UUID shipmentId, String shipmentCode, String result, String resultNote,
                    Instant decidedAt, Instant resolvedAt, UUID resolvedBy, String resolvedByName,
                    String resolutionNote) { }

    /** One call with the body it carried (a credential it echoed masked). */
    record EventDetail(EventRow event, String payload) { }

    /**
     * One accepted line of an imported file of delivery outcomes (WF-05 fix plan item 5), its load already found.
     *
     * @param shipmentCode the shipment it names; null: every shipment that left on the load (BR-26)
     * @param kind         DELIVERED or DELIVERY_FAILED
     * @param reason       the internal reason of a failed delivery when the line names one outright; null when it
     *                     gives the carrier's own words ({@code wording}), which the carrier's table maps
     * @param recipient    who received the goods, as the file says; kept with the event
     * @param note         the line's note; with {@code reason}, the failure's note
     */
    record FileLine(UUID importId, int line, UUID carrierId, UUID loadId, String waybillNo, String shipmentCode,
                    CarrierEventKind kind, Instant reportedAt, DeliveryFailureReason reason, String wording,
                    String recipient, String note) { }

    /** What came of a line of a file: its event, the result decided and the note that says why. */
    record FileOutcome(UUID eventId, String result, String note) { }

    /**
     * Checks the call's credential (API_BOOKING: the configured header in constant time; WEBHOOK_LOAD: the HMAC
     * signature of the raw body) and keeps it - in full when the credential held, as a summary when it did not, not at
     * all for an unknown carrier - then matches it to a load and applies it. Never throws for what the call said: every
     * outcome is a result on the event.
     *
     * <p>An API_BOOKING carrier's header is not a signature, so its call is defended further (WF-05 fix plan item 6):
     * it may be limited to a list of addresses and may carry either of two credentials while one replaces the other;
     * its report must fall in a window of time; and a final outcome is applied only once the carrier's own API gives
     * the same - otherwise the event is UNVERIFIED.</p>
     *
     * @param header        the call's headers by name, case-insensitive; null for one it lacks
     * @param remoteAddress the address the call came from
     * @param body          the raw body, at most 64 KiB
     */
    Receipt receive(String carrierCode, UnaryOperator<String> header, String remoteAddress, byte[] body);

    /**
     * A line of an imported file, through the webhooks' own pipeline: kept as an event of source FILE, then decided
     * by the same rules - a repeat is DUPLICATE, a report older than the last applied is STALE, one the shipment's
     * state refuses is NEEDS_REVIEW. Never throws for what the line said.
     */
    FileOutcome recordFromFile(FileLine line);

    /** What came of each line of an import that became an event, by line number. */
    Map<Integer, FileOutcome> outcomesOfImport(UUID importId);

    /**
     * Calls, newest first. Unattributed ones (no load matched) are everybody's with the permission; the others are their
     * load's warehouse's.
     *
     * @param result one result, or {@code ERRORS} for the error list's (RECEIVED, UNMATCHED, INVALID_SIGNATURE,
     *               NEEDS_REVIEW, UNVERIFIED)
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
