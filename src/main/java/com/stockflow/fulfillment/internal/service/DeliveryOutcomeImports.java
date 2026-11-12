package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Delivery outcomes a carrier sent as a list, applied from the system's file template (WF-05 fix plan item 5; kltn-docs
 * 09 §4.3, BR-26, BR-27) - for a carrier with no webhook, or when its webhook failed. The warehouse manager previews a
 * file - each line checked, nothing written - then applies it: every accepted line becomes a carrier event of source
 * FILE and is decided by the webhooks' own rules, so a line that repeats what a webhook (or the same file) already
 * reported changes nothing.
 */
public interface DeliveryOutcomeImports {

    /**
     * One line and what it comes to.
     *
     * @param verdict  the check's: ACCEPTED or ERROR
     * @param changes  for an accepted line just checked, whether a shipment it is about does not have the outcome yet
     *                 (false: nothing is left to change); null for a refused line and for an import read back
     * @param problem  why it is refused (see {@code DeliveryOutcomeCheck.Problem}); null unless ERROR
     * @param loadCode the vehicle its waybill number names, when one was found
     * @param eventId  the carrier event made of it; null on a preview and for a refused line
     * @param result   what came of the event (APPLIED, DUPLICATE, STALE, IGNORED, NEEDS_REVIEW, ...); null on a preview,
     *                 for a refused line, and for an accepted line whose event was never made (apply the file again)
     * @param resultNote the event's note
     */
    record Line(int line, String waybillNo, String outcome, String failureReason, String reportedAt, String recipient,
                String note, String shipmentCode, String carrierCode, UUID loadId, String loadCode, String verdict, Boolean changes,
                String problem, UUID eventId, String result, String resultNote) { }

    /** A file checked and not applied. */
    record Preview(int rowsTotal, int rowsAccepted, int rowsRejected, List<Line> lines) { }

    /**
     * A file applied, with its lines.
     *
     * @param rowsApplied the accepted lines whose event changed a shipment
     */
    record ImportView(UUID id, String fileName, UUID uploadedBy, Instant uploadedAt, int rowsTotal, int rowsAccepted,
                      int rowsRejected, int rowsApplied, List<Line> lines) { }

    record ImportRow(UUID id, String fileName, UUID uploadedBy, Instant uploadedAt, int rowsTotal, int rowsAccepted,
                     int rowsRejected) { }

    /** The empty template, its headings in the request's language. */
    byte[] template();

    /**
     * Each line checked against the vehicle its waybill number names, as things are now; nothing written.
     *
     * @throws com.stockflow.common.error.BusinessException 400 {@code DELIVERY_OUTCOME_IMPORT_INVALID_FILE} for a file
     *         that is not the template's table (not UTF-8, no data line, over 1,000 lines, a quote left open)
     */
    Preview preview(byte[] file, CurrentUser actor);

    /**
     * Records the file and its lines, then sends each accepted line through the carrier-event pipeline, one event per
     * line. The lines are checked again as things are then (a preview may be stale). Applying the same file again
     * changes nothing: its lines are repeats.
     *
     * @throws com.stockflow.common.error.BusinessException 400 {@code DELIVERY_OUTCOME_IMPORT_INVALID_FILE}
     */
    ImportView importFile(byte[] file, String fileName, CurrentUser actor);

    /** The imports the caller made - or everyone's, for a caller not limited to warehouses; newest first. */
    PageResponse<ImportRow> list(int page, int size, CurrentUser actor);

    /**
     * @throws com.stockflow.common.error.BusinessException 404 {@code DELIVERY_OUTCOME_IMPORT_NOT_FOUND}, also for
     *         another's import asked for by a caller limited to warehouses
     */
    ImportView get(UUID importId, CurrentUser actor);
}
