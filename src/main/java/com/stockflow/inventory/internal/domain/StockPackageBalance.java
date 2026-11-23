package com.stockflow.inventory.internal.domain;

import java.time.Instant;
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
 * @param onHand      what is left in it, the sum of its rows; 0 for a carton with none yet (RECEIVING)
 * @param reserved    of that, what orders hold
 * @param closedAt    when it was emptied, lost or voided
 * @param returnOpenedAt when a return receipt found it opened or empty (WF-05 TH-4, Q-18): never sold again - since
 *                    Q-18 it stays CONSUMED, before that it sat in quarantine to be written off; null when it never came
 *                    back so
 * @param returnOwnership whose goods it is after it last came back on a return request (WF-05 Q-4): PENDING while the
 *                    refund is undecided - no release, no write-off (audit round 3, RAD-4: the screen must know before
 *                    it offers them) - COMPANY, or CUSTOMER_*; null for a carton that never came back on one
 * @param returnRequestId the return request it last came back on; null with {@code returnOwnership}
 * @param returnRmaNumber that return's RMA number, filled by the lookup; null when not known
 */
public record StockPackageBalance(UUID id, String lpn, String sku, String unitOfMeasure, int nominalQty,
                                  Integer receivedQty, String locationCode, StockPackageStatus status,
                                  String lotNumber, int onHand, int reserved,
                                  UUID goodsReceiptLineId, Instant closedAt, Instant returnOpenedAt,
                                  String returnOwnership, UUID returnRequestId, String returnRmaNumber) {

    /** As before the return's ownership was shown. */
    public StockPackageBalance(UUID id, String lpn, String sku, String unitOfMeasure, int nominalQty,
                               Integer receivedQty, String locationCode, StockPackageStatus status, String lotNumber,
                               int onHand, int reserved, UUID goodsReceiptLineId, Instant closedAt,
                               Instant returnOpenedAt) {
        this(id, lpn, sku, unitOfMeasure, nominalQty, receivedQty, locationCode, status, lotNumber, onHand, reserved,
                goodsReceiptLineId, closedAt, returnOpenedAt, null, null, null);
    }

    /** The same carton naming its return by its RMA number. */
    public StockPackageBalance withReturnRmaNumber(String rmaNumber) {
        return new StockPackageBalance(id, lpn, sku, unitOfMeasure, nominalQty, receivedQty, locationCode, status,
                lotNumber, onHand, reserved, goodsReceiptLineId, closedAt, returnOpenedAt, returnOwnership,
                returnRequestId, rmaNumber);
    }

    /** A carton that never came back opened on a return. */
    public StockPackageBalance(UUID id, String lpn, String sku, String unitOfMeasure, int nominalQty,
                               Integer receivedQty, String locationCode, StockPackageStatus status, String lotNumber,
                               int onHand, int reserved, UUID goodsReceiptLineId, Instant closedAt) {
        this(id, lpn, sku, unitOfMeasure, nominalQty, receivedQty, locationCode, status, lotNumber, onHand, reserved,
                goodsReceiptLineId, closedAt, null);
    }

    /** Whether a return receipt found it opened or empty (WF-05 BE-24). */
    public boolean openedOnReturn() {
        return returnOpenedAt != null;
    }

    /** A carton with its stock rows holding something, oldest receipt first. */
    public record Detail(StockPackageBalance carton, List<Content> contents) {

        public Detail {
            contents = List.copyOf(contents);
        }
    }

    /** One stock row inside a carton. */
    public record Content(String lotNumber, Instant receivedAt, StockStatus status, int onHand,
                          int reserved) {
    }

    /**
     * What one location holds of one SKU, lot and stock status: cartons and their units - every carton in stock is
     * whole (team plan 4C-3), so there is no opened column - and units in no carton, which only the paths not yet in
     * cartons write (a put-back, a customer return, a damage report).
     *
     * @param totalUnits the two unit columns added; any other value is refused
     */
    public record LocationLine(String sku, String lotNumber, StockStatus status,
                               int wholeCartons, int wholeCartonUnits, int looseUnits, int totalUnits) {

        public LocationLine {
            if (totalUnits != wholeCartonUnits + looseUnits) {
                throw new IllegalArgumentException("totalUnits " + totalUnits + " is not " + wholeCartonUnits + " + "
                        + looseUnits);
            }
        }

        public LocationLine(String sku, String lotNumber, StockStatus status, int wholeCartons,
                            int wholeCartonUnits, int looseUnits) {
            this(sku, lotNumber, status, wholeCartons, wholeCartonUnits, looseUnits, wholeCartonUnits + looseUnits);
        }
    }
}
