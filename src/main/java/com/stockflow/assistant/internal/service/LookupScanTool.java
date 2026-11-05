package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.ScannedCodeQueries;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * What a label scanned at the shelf stands for (SCRUM-473's carton lookup, design §4.2): a carton by its LPN, what a
 * bin holds, or the SKU of a product barcode. Floor staff work by labels; they had no way to ask the assistant about
 * one, and a code read aloud is where a voice question goes wrong (a digit misheard names another real carton), so the
 * label is scanned and this tool reads it.
 *
 * <p><b>The screen it mirrors</b> is the carton lookup's location summary ({@code inventory-stock-packages:READ}),
 * whose carton read shares the guard, and both keep that screen's warehouse checks: a bin of a warehouse the user is
 * not assigned to is refused whether it exists or not; a carton is found first and then refused, as
 * {@code GET /stock-packages/{lpn}} answers 404 or 403, so whether another warehouse's LPN exists shows exactly as much
 * as that screen shows. A code whose prefix is no warehouse's is no bin ({@code ScannedCodeQueries}), so a SKU is never
 * refused as another warehouse's. A barcode is the item list's to read ({@code inventory-inventory-items:READ}):
 * without that permission the tool says so instead of searching.</p>
 *
 * <p><b>A carton's status is not its stock's.</b> IN_STOCK says the carton is in the warehouse; the goods in it may be
 * INBOUND waiting for QC, QUARANTINE, DAMAGED or EXPIRED. The carton's stock rows go with it, each with its status, so
 * a carton waiting for QC is never read as one to pick.</p>
 *
 * <p><b>Counts, not a conversion.</b> Whole and opened cartons here are the cartons as they stand, unlike the
 * {@code ...Packed} figures of the stock tools, which divide a quantity by a packing's size. A receiving or QC area
 * can hold hundreds of lines: {@value #MAX_LOCATION_ROWS} at most, with the total.</p>
 */
@Component
class LookupScanTool implements AssistantTool {

    /** Location lines one answer carries; a busy area's are cut, with the total. */
    static final int MAX_LOCATION_ROWS = 50;

    private static final ToolSpec SPEC = new ToolSpec("lookupScan",
            "What a scanned or typed warehouse label stands for. A carton's LPN (LPN-...): its SKU, lot, expiry, "
                    + "units left and held, where it is, whether it is still sealed, and its contents: each stock row "
                    + "in it with its stock status. The carton's own status (IN_STOCK...) says only where the carton "
                    + "is: the goods may be used only where a content row is AVAILABLE; INBOUND waits for QC or "
                    + "putaway; QUARANTINE, BLOCKED, DAMAGED, EXPIRED and IN_PRODUCTION may not be picked. A location code (a bin or an area): "
                    + "per SKU, lot and stock status, the whole cartons and their units, the opened cartons and what "
                    + "is left in them, the loose units and the total; an empty list means nothing is stored there; at "
                    + "most " + MAX_LOCATION_ROWS + " lines, locationTotal counting them all. Any other code is tried "
                    + "as a carton label, then as a product barcode giving its SKUs. These are cartons as they stand, "
                    + "unlike the ...Packed conversions of the stock tools. A code that is none of these comes back "
                    + "NOT_FOUND: say it was not found and should be scanned again, or, when it may be a SKU, look it "
                    + "up with getStockLevels.",
            ToolSchema.object()
                    .string("code", "The label as scanned or typed, whole")
                    .build());

    private final ScannedCodeQueries scans;
    private final CurrentUserProvider currentUsers;

    LookupScanTool(ScannedCodeQueries scans, CurrentUserProvider currentUsers) {
        this.scans = scans;
        this.currentUsers = currentUsers;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/inventory/stock-packages/location-summary";
    }

    @Override
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    /**
     * An {@code LPN-} label is a carton, and its errors are the answer. Any other code is tried as a location when it
     * has a location's shape (a warehouse prefix, a dash, the rest: docs 06 BR-13), then as a carton, since cartons
     * labelled before the LPN sequence carry other codes, then as a barcode. A location of another warehouse is
     * remembered rather than reported at once: a barcode with a dash would otherwise be refused as a bin, and the
     * refusal is the answer only when nothing else matches.
     */
    @Override
    @RequiresPermission(resource = ToolPermissions.STOCK_PACKAGES, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        String code = arguments.text("code", 64).strip();
        String upper = code.toUpperCase(Locale.ROOT);
        if (upper.startsWith("LPN-")) {
            return Answer.carton(code, Carton.of(scans.carton(code)));
        }
        BusinessException outside = null;
        if (upper.indexOf('-') > 0) {
            try {
                List<ScannedCodeQueries.LocationLine> lines = scans.atLocation(code);
                return Answer.location(code, lines.stream().limit(MAX_LOCATION_ROWS).map(LocationRow::of).toList(),
                        lines.size());
            } catch (BusinessException notOurs) {
                if (notOurs.errorCode() == ErrorCode.OUT_OF_DATA_SCOPE) {
                    outside = notOurs;
                } else if (notOurs.errorCode() != ErrorCode.LOCATION_NOT_FOUND) {
                    throw notOurs;
                }
            }
        }
        try {
            return Answer.carton(code, Carton.of(scans.carton(code)));
        } catch (BusinessException noCarton) {
            if (noCarton.errorCode() != ErrorCode.STOCK_PACKAGE_NOT_FOUND) {
                throw noCarton;
            }
        }
        boolean mayReadItems = currentUsers.current()
                .map(user -> user.can(ToolPermissions.INVENTORY_ITEMS, Action.READ)).orElse(false);
        List<String> skus = mayReadItems ? scans.skusWithBarcode(code) : List.of();
        if (!skus.isEmpty()) {
            return Answer.barcode(code, skus);
        }
        if (outside != null) {
            throw outside;
        }
        throw new NotFoundException("Code", mayReadItems ? code : code + " (no carton or location has it; reading "
                + "it as a product barcode needs inventory-inventory-items:READ)");
    }

    /**
     * @param kind              CARTON, LOCATION or BARCODE
     * @param carton            for a carton's LPN
     * @param location          for a location: one row per SKU, lot and stock status; empty when nothing is stored
     *                          there; at most {@value #MAX_LOCATION_ROWS}
     * @param locationTotal     every line the location has; above the rows listed, some were left out
     * @param locationTruncated {@code locationTotal} is above the rows listed
     * @param skus              for a product barcode
     */
    record Answer(String code, String kind, Carton carton, List<LocationRow> location, Integer locationTotal,
                  Boolean locationTruncated, List<String> skus) {

        static Answer carton(String code, Carton carton) {
            return new Answer(code, "CARTON", carton, null, null, null, null);
        }

        static Answer location(String code, List<LocationRow> rows, int total) {
            return new Answer(code, "LOCATION", null, rows, total, total > rows.size(), null);
        }

        static Answer barcode(String code, List<String> skus) {
            return new Answer(code, "BARCODE", null, null, null, null, skus);
        }
    }

    /**
     * @param status   the carton's: RECEIVING (being counted in), IN_STOCK (in the warehouse), CONSUMED (emptied), LOST
     *                 or VOID; not whether its goods may be used, which {@code contents} says
     * @param unopened no unit taken out of it yet
     * @param onHand   units left in it; {@code held} of them for orders
     * @param contents its stock rows, each with the stock's own status
     */
    record Carton(String lpn, String sku, String unit, int unitsWhenFull, String locationCode, String status,
                  boolean unopened, String lotNumber, LocalDate expiryDate, int onHand, int held,
                  List<Content> contents) {

        static Carton of(ScannedCodeQueries.Carton carton) {
            return new Carton(carton.lpn(), carton.sku(), carton.unitOfMeasure(), carton.nominalQty(),
                    carton.locationCode(), carton.status(), carton.unopened(), carton.lotNumber(), carton.expiryDate(),
                    carton.onHand(), carton.reserved(), carton.contents().stream()
                    .map(row -> new Content(row.lotNumber(), row.expiryDate(), row.status(), row.onHand(),
                            row.reserved())).toList());
        }
    }

    /** One stock row in a carton; {@code stockStatus} AVAILABLE may be used, the others (INBOUND...) may not. */
    record Content(String lotNumber, LocalDate expiryDate, String stockStatus, int onHand, int held) {
    }

    /** @param status the stock's: AVAILABLE, QUARANTINE, DAMAGED, EXPIRED... */
    record LocationRow(String sku, String lotNumber, LocalDate expiryDate, String status, int wholeCartons,
                       int wholeCartonUnits, int openedCartons, int openedCartonUnits, int looseUnits,
                       int totalUnits) {

        static LocationRow of(ScannedCodeQueries.LocationLine line) {
            return new LocationRow(line.sku(), line.lotNumber(), line.expiryDate(), line.status(),
                    line.wholeCartons(), line.wholeCartonUnits(), line.openedCartons(), line.openedCartonUnits(),
                    line.looseUnits(), line.totalUnits());
        }
    }
}
