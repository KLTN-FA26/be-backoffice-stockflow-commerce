package com.stockflow.inventory.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One carton as {@code inventory.stock_package_balance} shows it (SCRUM-473): its label, unit, place and status, and
 * what its stock rows hold. A read model of the carton lookup, as {@link LotTrace} is of the lot trace - the search
 * port fills it and the web layer maps it, so the lookup has one set of types, not one per layer. The carton that
 * changes is {@link StockPackage}.
 *
 * @param nominalQty  what it was made to hold: its packing unit's factor then
 * @param receivedQty what was counted into it at receipt; null until counted
 * @param lotNumber   of its rows holding stock - a carton holds one lot at most - and null when it holds nothing
 * @param expiryDate  of those rows likewise
 * @param onHand      what is left in it, the sum of its rows; 0 for a carton with none yet (RECEIVING)
 * @param reserved    of that, what orders hold
 * @param closedAt    when it was emptied, lost or voided
 */
public record StockPackageBalance(UUID id, String lpn, String sku, String unitOfMeasure, int nominalQty,
                                  Integer receivedQty, String locationCode, StockPackageStatus status, boolean unopened,
                                  String lotNumber, LocalDate expiryDate, int onHand, int reserved,
                                  UUID goodsReceiptLineId, Instant closedAt) {

    /** A carton with its stock rows holding something, earliest expiry first. */
    public record Detail(StockPackageBalance carton, List<Content> contents) {

        public Detail {
            contents = List.copyOf(contents);
        }
    }

    /** One stock row inside a carton. */
    public record Content(String lotNumber, LocalDate expiryDate, Instant receivedAt, StockStatus status, int onHand,
                          int reserved) {
    }

    /**
     * What one location holds of one SKU, lot and stock status: unopened cartons and their units, opened cartons
     * still holding something and what is left in them, units in no carton.
     *
     * @param totalUnits the three unit columns added; any other value is refused
     */
    public record LocationLine(String sku, String lotNumber, LocalDate expiryDate, StockStatus status,
                               int wholeCartons, int wholeCartonUnits, int openedCartons, int openedCartonUnits,
                               int looseUnits, int totalUnits) {

        public LocationLine {
            if (totalUnits != wholeCartonUnits + openedCartonUnits + looseUnits) {
                throw new IllegalArgumentException("totalUnits " + totalUnits + " is not " + wholeCartonUnits + " + "
                        + openedCartonUnits + " + " + looseUnits);
            }
        }

        public LocationLine(String sku, String lotNumber, LocalDate expiryDate, StockStatus status, int wholeCartons,
                            int wholeCartonUnits, int openedCartons, int openedCartonUnits, int looseUnits) {
            this(sku, lotNumber, expiryDate, status, wholeCartons, wholeCartonUnits, openedCartons, openedCartonUnits,
                    looseUnits, wholeCartonUnits + openedCartonUnits + looseUnits);
        }
    }
}
