package com.stockflow.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>procurement</b> module when a return receipt is confirmed (team plan 4D-2; kltn-docs 03 §4.2
 * step 12, §3 Output; 17 BR-18): goods that came back are in the warehouse, in quarantine at {@code locationCode}. The
 * receipt is CLOSED in the same transaction. Exactly one of {@code shipmentId} (a shipment the carrier brought back,
 * RTO) and {@code returnRequestId} (an approved return request, RMA) is set: what the receipt was against.
 *
 * <p>What it is the fact for: the <b>order</b> module takes it as the goods being back (the order {@code Returned},
 * the return request received), and <b>payment</b> as the ground for refunding them (kltn-docs 15) - those listeners
 * are team plan 4D-3 and 4B-9's. The cartons are the ones actually received, which may be fewer than the shipment or
 * return request carried (03 edge cases: the receipt is confirmed with the cartons that came); Sales follow up the
 * rest outside the system.</p>
 *
 * <p>WF-05 TH-4: each carton says what was counted inside it ({@link Carton#countedQuantity}, fewer than its label when
 * it came back opened), and {@code looseLines} are units that came back with no carton label - recorded on the receipt,
 * never put into stock, counted as received for the return. Null in publications stored before: the cartons whole, no
 * loose units.</p>
 *
 * <p>WF-05 decisions Q-18, Q-11 (BE-20, BE-24): a carton that came back opened or empty is not put into stock at all -
 * it stays consumed, its count on the receipt only ({@link Carton#stockEntered} false). {@code missingCartons} are the
 * LPNs of a shipment brought back by the carrier (RTO) that left on it and were not on this receipt; empty for a
 * return request's receipt, and in publications stored before. A shipment brought back may come in several receipts
 * (BE-13): each publishes its own event, and the order closes the shipment's units at the first.</p>
 *
 * <p>Keyed by {@code receiptId}: a receipt is confirmed once, and a consumer that sees it twice must not count the
 * goods twice. Consumers receive it in-process through {@code @ApplicationModuleListener}; see
 * {@code package-info.java} for the rules on changing this contract.</p>
 */
public record ReturnedGoodsReceived(
        UUID receiptId,
        String receiptNumber,
        UUID warehouseId,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        UUID shipmentId,
        UUID returnRequestId,
        String locationCode,
        List<Carton> cartons,
        UUID receivedBy,
        Instant receivedAt,
        List<LooseLine> looseLines,
        List<String> missingCartons
) {

    public ReturnedGoodsReceived {
        cartons = List.copyOf(cartons);
        looseLines = looseLines == null ? List.of() : List.copyOf(looseLines);
        missingCartons = missingCartons == null ? List.of() : List.copyOf(missingCartons);
    }

    /** As published before the cartons missing from a carrier's return were named (WF-05 BE-24). */
    public ReturnedGoodsReceived(UUID receiptId, String receiptNumber, UUID warehouseId, UUID orderId,
                                 String orderNumber, UUID customerId, UUID shipmentId, UUID returnRequestId,
                                 String locationCode, List<Carton> cartons, UUID receivedBy, Instant receivedAt,
                                 List<LooseLine> looseLines) {
        this(receiptId, receiptNumber, warehouseId, orderId, orderNumber, customerId, shipmentId, returnRequestId,
                locationCode, cartons, receivedBy, receivedAt, looseLines, null);
    }

    /** As published before loose units were recorded (WF-05 TH-4): cartons only. */
    public ReturnedGoodsReceived(UUID receiptId, String receiptNumber, UUID warehouseId, UUID orderId,
                                 String orderNumber, UUID customerId, UUID shipmentId, UUID returnRequestId,
                                 String locationCode, List<Carton> cartons, UUID receivedBy, Instant receivedAt) {
        this(receiptId, receiptNumber, warehouseId, orderId, orderNumber, customerId, shipmentId, returnRequestId,
                locationCode, cartons, receivedBy, receivedAt, null, null);
    }

    /**
     * One carton received back, as it was shipped: its LPN, SKU, units and original lot (03 BR-03). {@code condition}
     * is what the warehouse noted of its outside (dented, wet, opened), null when nothing was. {@code countedQuantity}
     * is what was found inside it (WF-05 TH-4): null when it came back sealed - every unit of {@code quantity} - or
     * fewer, when it was opened. {@code stockEntered} says whether it went into stock (QUARANTINE) at all: false for a
     * carton that came back opened or empty (WF-05 Q-18), which stays consumed and is only counted on the receipt; null
     * in publications stored before, which put every carton into stock.
     */
    public record Carton(String lpn, String sku, String lotNumber, int quantity, String condition,
                         Integer countedQuantity, Boolean stockEntered) {

        /** As published before cartons were counted: whole. */
        public Carton(String lpn, String sku, String lotNumber, int quantity, String condition) {
            this(lpn, sku, lotNumber, quantity, condition, null, null);
        }

        /** As published before an opened carton was kept out of stock (WF-05 Q-18): every carton went in. */
        public Carton(String lpn, String sku, String lotNumber, int quantity, String condition,
                      Integer countedQuantity) {
            this(lpn, sku, lotNumber, quantity, condition, countedQuantity, null);
        }

        /** Whether it went into stock: unless said otherwise, as every carton did before WF-05 Q-18. */
        public boolean enteredStock() {
            return stockEntered == null || stockEntered;
        }

        /** The units that came back in it: those counted, else the whole carton. */
        public int unitsInside() {
            return countedQuantity == null ? quantity : countedQuantity;
        }

        /** Whether it came back with fewer units than its label: it is written off, never sold again. */
        public boolean opened() {
            return unitsInside() < quantity;
        }
    }

    /** Units of {@code sku} that came back with no carton label, in {@code lotNumber} when it is known (WF-05 TH-4). */
    public record LooseLine(String sku, String lotNumber, int quantity) {
    }
}
