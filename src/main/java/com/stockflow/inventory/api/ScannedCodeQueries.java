package com.stockflow.inventory.api;

import java.time.LocalDate;
import java.util.List;

/**
 * What a label scanned in the warehouse stands for (SCRUM-473's carton lookup, read by the assistant's
 * {@code lookupScan}): a carton by its LPN, what one location holds in cartons and loose units, and the SKUs a product
 * barcode belongs to.
 *
 * <p>The carton lookup screen's own reads, with its warehouse checks: a location in a warehouse the caller is not
 * assigned to is {@code OUT_OF_DATA_SCOPE} whether it exists or not, since its code names the warehouse; a code whose
 * prefix is no warehouse's is no location ({@code LOCATION_NOT_FOUND}). A carton is found first and then refused when
 * it stands in such a warehouse, as {@code GET /stock-packages/{lpn}} answers 404 and 403. The barcode read has no
 * scope of its own, as the item list has none; the caller decides whether its user may search items.</p>
 *
 * <p>Call it inside the screen's guard, {@code @RequiresPermission(resource = "inventory-stock-packages",
 * action = READ)}; outside a guard no scope is in force and every warehouse is seen, as by the system.</p>
 */
public interface ScannedCodeQueries {

    /**
     * One carton by the LPN on its label, in either case, with what is left in it.
     *
     * @throws com.stockflow.common.error.BusinessException {@code STOCK_PACKAGE_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE}
     *         for a carton in a warehouse the caller is not assigned to
     */
    Carton carton(String lpn);

    /**
     * What one exact location holds, a bin or an area, never a prefix: a line per SKU, lot and stock status.
     *
     * @throws com.stockflow.common.error.BusinessException {@code LOCATION_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code VALIDATION_FAILED} for a blank code
     */
    List<LocationLine> atLocation(String locationCode);

    /** The SKUs whose inventory item carries exactly this barcode, in either case; empty for none. */
    List<String> skusWithBarcode(String barcode);

    /**
     * @param nominalQty what it was made to hold
     * @param status     RECEIVING, IN_STOCK, CONSUMED, LOST or VOID
     * @param unopened   no unit has been taken out of it yet
     * @param onHand     what is left in it, the sum of its stock rows
     * @param reserved   of that, what orders hold
     */
    record Carton(String lpn, String sku, String unitOfMeasure, int nominalQty, String locationCode, String status,
                  boolean unopened, String lotNumber, LocalDate expiryDate, int onHand, int reserved,
                  List<CartonContent> contents) {

        public Carton {
            contents = contents == null ? List.of() : List.copyOf(contents);
        }
    }

    /** One stock row inside a carton; {@code status} is the stock's: AVAILABLE, QUARANTINE, DAMAGED... */
    record CartonContent(String lotNumber, LocalDate expiryDate, String status, int onHand, int reserved) {
    }

    /**
     * What one location holds of one SKU, lot and stock status.
     *
     * @param wholeCartons      unopened cartons, and {@code wholeCartonUnits} the units in them
     * @param openedCartons     opened cartons still holding something, and {@code openedCartonUnits} what is left
     * @param looseUnits        units in no carton
     * @param totalUnits        the three added
     */
    record LocationLine(String sku, String lotNumber, LocalDate expiryDate, String status, int wholeCartons,
                        int wholeCartonUnits, int openedCartons, int openedCartonUnits, int looseUnits,
                        int totalUnits) {
    }
}
