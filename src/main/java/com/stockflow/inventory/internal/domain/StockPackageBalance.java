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
 * @param countedAt   when it was counted in at receiving; null until counted. Its units are {@code nominalQty}
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
 * @param stockStatuses   the distinct statuses of its stock rows holding something (#454): a quarantined or damaged
 *                        carton is told apart in a list without opening it; empty for a carton holding nothing
 * @param receivedAt      the oldest receipt of its rows holding something, for FIFO (kltn-docs 20 §4.1); null when
 *                        it holds nothing
 * @param source          where it came from; null when not read (the location summary)
 * @param heldFor         the orders holding its units ({@code reserved}), one entry per order; empty when none
 * @param openTask        the open work it is in (20 BR-10: a busy carton); null when none
 * @param warehouseId     the warehouse its location is in, filled by the lookup
 * @param warehouseCode   that warehouse's code (its location prefix, 06 BR-13)
 * @param productName     the product its SKU is a variant of, filled by the lookup; null when not known
 * @param variantName     the variant its SKU is, as quotes and orders name it; null when not known
 * @param goodsReceiptId  the goods receipt of {@code goodsReceiptLineId}, filled by the lookup; null with it
 * @param goodsReceiptNumber that receipt's number
 */
public record StockPackageBalance(UUID id, String lpn, String sku, String unitOfMeasure, int nominalQty,
                                  Instant countedAt, String locationCode, StockPackageStatus status,
                                  String lotNumber, int onHand, int reserved,
                                  UUID goodsReceiptLineId, Instant closedAt, Instant returnOpenedAt,
                                  String returnOwnership, UUID returnRequestId, String returnRmaNumber,
                                  List<StockStatus> stockStatuses, Instant receivedAt, Source source,
                                  List<Hold> heldFor, OpenTask openTask, UUID warehouseId, String warehouseCode,
                                  String productName, String variantName, UUID goodsReceiptId,
                                  String goodsReceiptNumber) {

    public StockPackageBalance {
        stockStatuses = stockStatuses == null ? List.of() : List.copyOf(stockStatuses);
        heldFor = heldFor == null ? List.of() : List.copyOf(heldFor);
    }

    /** What the carton's own rows say, before the lookup names what other modules know of it. */
    public StockPackageBalance(UUID id, String lpn, String sku, String unitOfMeasure, int nominalQty,
                               Instant countedAt, String locationCode, StockPackageStatus status, String lotNumber,
                               int onHand, int reserved, UUID goodsReceiptLineId, Instant closedAt,
                               Instant returnOpenedAt, String returnOwnership, UUID returnRequestId,
                               List<StockStatus> stockStatuses, Instant receivedAt, Source source, OpenTask openTask) {
        this(id, lpn, sku, unitOfMeasure, nominalQty, countedAt, locationCode, status, lotNumber, onHand, reserved,
                goodsReceiptLineId, closedAt, returnOpenedAt, returnOwnership, returnRequestId, null, stockStatuses,
                receivedAt, source, List.of(), openTask, null, null, null, null, null, null);
    }

    /** As before the return's ownership was shown. */
    public StockPackageBalance(UUID id, String lpn, String sku, String unitOfMeasure, int nominalQty,
                               Instant countedAt, String locationCode, StockPackageStatus status, String lotNumber,
                               int onHand, int reserved, UUID goodsReceiptLineId, Instant closedAt,
                               Instant returnOpenedAt) {
        this(id, lpn, sku, unitOfMeasure, nominalQty, countedAt, locationCode, status, lotNumber, onHand, reserved,
                goodsReceiptLineId, closedAt, returnOpenedAt, null, null, List.of(), null, null, null);
    }

    /** The same carton naming its return by its RMA number. */
    public StockPackageBalance withReturnRmaNumber(String rmaNumber) {
        return new StockPackageBalance(id, lpn, sku, unitOfMeasure, nominalQty, countedAt, locationCode, status,
                lotNumber, onHand, reserved, goodsReceiptLineId, closedAt, returnOpenedAt, returnOwnership,
                returnRequestId, rmaNumber, stockStatuses, receivedAt, source, heldFor, openTask, warehouseId,
                warehouseCode, productName, variantName, goodsReceiptId, goodsReceiptNumber);
    }

    /** The same carton with the orders holding its units. */
    public StockPackageBalance withHeldFor(List<Hold> holds) {
        return new StockPackageBalance(id, lpn, sku, unitOfMeasure, nominalQty, countedAt, locationCode, status,
                lotNumber, onHand, reserved, goodsReceiptLineId, closedAt, returnOpenedAt, returnOwnership,
                returnRequestId, returnRmaNumber, stockStatuses, receivedAt, source, holds, openTask, warehouseId,
                warehouseCode, productName, variantName, goodsReceiptId, goodsReceiptNumber);
    }

    /**
     * The same carton named as other modules know it: its warehouse, its SKU's product and variant, its receipt, and
     * the open work it is in when its own rows named none. Each argument null keeps what it has.
     */
    public StockPackageBalance named(UUID warehouse, String warehouseCodeOf, String product, String variant,
                                     UUID receipt, String receiptNumber, OpenTask work) {
        return new StockPackageBalance(id, lpn, sku, unitOfMeasure, nominalQty, countedAt, locationCode, status,
                lotNumber, onHand, reserved, goodsReceiptLineId, closedAt, returnOpenedAt, returnOwnership,
                returnRequestId, returnRmaNumber, stockStatuses, receivedAt, source, heldFor,
                openTask != null ? openTask : work,
                warehouse != null ? warehouse : warehouseId, warehouseCodeOf != null ? warehouseCodeOf : warehouseCode,
                product != null ? product : productName, variant != null ? variant : variantName,
                receipt != null ? receipt : goodsReceiptId,
                receiptNumber != null ? receiptNumber : goodsReceiptNumber);
    }

    /**
     * Where a carton came from (#454). {@code GOODS_RECEIPT}: labelled on a goods receipt line - goods from a producer
     * against a production order, the only receipts there are (kltn-docs 03). {@code RETURN}: it last came back on a
     * return receipt (4D-2). {@code PRODUCTION}: made by the in-house production that was removed (V20261108000100);
     * history only. {@code IN_WAREHOUSE}: boxed in the warehouse, on no receipt (V20261111000110).
     */
    public enum Source { GOODS_RECEIPT, PRODUCTION, RETURN, IN_WAREHOUSE }

    /** An order holding units of the carton: what {@code reserved} is made of. */
    public record Hold(UUID orderId, String orderNumber, int quantity) {
    }

    /**
     * The open work a carton is in (kltn-docs 20 BR-10): it is busy until that work ends. {@code number} is the
     * document number where the work has one (a cycle count's); a putaway task, a pick task and a carton check have
     * none, and {@code id} is the task to open.
     */
    public record OpenTask(TaskType type, UUID id, String number) {
    }

    /** What kind of open work holds a carton. */
    public enum TaskType { PUTAWAY, PICK, CARTON_CHECK, CYCLE_COUNT }

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
