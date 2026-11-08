package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Waybill numbers imported from the system's file template (3-3; kltn-docs 09 §4.4 "Nhập hàng loạt từ file", BR-25;
 * E18; QD-17). The dispatcher previews a file - each line checked, nothing written - then imports it: the valid lines
 * are applied, the others kept with their reason, and the file is kept as a waybill file of every load a line applied
 * to.
 */
public interface WaybillImports {

    /**
     * One line and what it comes to.
     *
     * @param outcome     NEW, OVERWRITE (the load has another number - "sẽ ghi đè": confirm it to apply it), SAME
     *                    or ERROR
     * @param problem     why it is not applied (see {@code WaybillImportCheck.Problem}); null unless ERROR
     * @param holderCode  for DUPLICATE_IN_CARRIER, the load holding the number
     */
    record Line(int line, String loadCode, String waybillNo, String vehiclePlate, String driverName,
                String driverPhone, UUID loadId, String outcome, String problem, String holderCode) { }

    /** A file checked and not applied. */
    record Preview(int rowsTotal, int rowsApplicable, int rowsRejected, List<Line> lines) { }

    /** A file imported, with its lines (only those the caller may see on another's import). */
    record ImportView(UUID id, String fileName, UUID uploadedBy, Instant uploadedAt, int rowsTotal, int rowsApplied,
                      int rowsRejected, List<Line> lines) { }

    record ImportRow(UUID id, String fileName, UUID uploadedBy, Instant uploadedAt, int rowsTotal, int rowsApplied,
                     int rowsRejected) { }

    /** The empty template, its headings in the request's language. */
    byte[] template();

    /**
     * Each line checked against the loads it names, as they are now; nothing written.
     *
     * @throws com.stockflow.common.error.BusinessException 400 {@code WAYBILL_IMPORT_INVALID_FILE} for a file that is
     *         not the template's table (not UTF-8, no data line, over 1,000 lines, a quote left open)
     */
    Preview preview(byte[] file, CurrentUser actor);

    /**
     * Applies the file's valid lines, under the loads' locks and against the loads as they are then (a preview may be
     * stale), keeps the file and records the import.
     *
     * @param overwriteLines the lines whose overwrite the preview showed and the dispatcher confirmed (BR-25); a line
     *                       that would overwrite another number and is not among them is refused
     *                       (OVERWRITE_NOT_CONFIRMED) - also one that became an overwrite after the preview
     * @throws com.stockflow.common.error.BusinessException 400 {@code WAYBILL_IMPORT_INVALID_FILE}; 503
     *         {@code STORAGE_ERROR} when the file cannot be kept
     */
    ImportView importFile(byte[] file, String fileName, java.util.Set<Integer> overwriteLines, CurrentUser actor);

    /** The imports the caller made, and those with a line on a load of their warehouses; newest first. */
    PageResponse<ImportRow> list(int page, int size, CurrentUser actor);

    /** @throws com.stockflow.common.error.BusinessException 404 {@code WAYBILL_IMPORT_NOT_FOUND} */
    ImportView get(UUID importId, CurrentUser actor);
}
