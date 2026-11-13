package com.stockflow.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a delivered shipment is invoiced for (WF-05 E2; kltn-docs 21 §4.1; NĐ 254 Điều 9 khoản 3: every delivery is
 * invoiced): the goods it carried at the contract's prices, the order's fee lines whole, and how many pieces the order
 * still means to deliver. Prices and fees are the order's, fixed when it was placed (14 BR-06); the VAT rate is the one
 * in force on the day of delivery.
 *
 * <p>Which delivery is the order's "first", and how much of a fee earlier invoices carry, is not said here: the order
 * knows when the carrier says each shipment arrived, not in which order the deliveries were recorded and invoiced, and
 * a carrier's report of an earlier time can be recorded later. The invoicing module decides that from the invoices it
 * has drawn up.</p>
 *
 * @param contractNumber        the order's live sales contract; null for an order without one
 * @param deliveryBatchId       the delivery batch it carried; null for an order without a delivery schedule
 * @param deliveredAt           when the carrier reported the delivery: the invoice's date (21 §4.1)
 * @param orderUnits            the pieces the order means to deliver as it stands now: its lines less what was
 *                              closed. It shrinks when part of the order is closed
 * @param unitsInShipment       the pieces this shipment carried
 */
public record DeliveredShipment(UUID orderId, String orderNumber, UUID customerId, String contractNumber,
                                UUID shipmentId, String shipmentCode, UUID deliveryBatchId, Instant deliveredAt,
                                String currency, List<Line> lines, List<Fee> fees, long orderUnits,
                                long unitsInShipment) {

    public DeliveredShipment {
        lines = List.copyOf(lines);
        fees = List.copyOf(fees);
    }

    /**
     * The goods of one order line in the shipment.
     *
     * @param name            the SKU's product name
     * @param cartons         cartons delivered; null for a line not ordered in cartons, which is counted in pieces
     * @param unitsPerCarton  pieces in a carton; null with {@code cartons}
     * @param units           pieces delivered
     * @param unitPrice       the contract's price of one piece
     * @param taxRate         the VAT rate of the SKU's tax class on the day of delivery, a percentage; for a SKU whose
     *                        product has no tax class, the rate the order fixed, and where the order fixed none
     *                        either, the day's STANDARD rate - a guess the invoice must flag, never 0%
     * @param contractTaxRate the rate fixed on the order line; null for a line placed without VAT, which the invoice
     *                        flags: the customer never agreed to a rate
     */
    public record Line(UUID orderLineId, String sku, String name, Integer cartons, Integer unitsPerCarton, int units,
                       BigDecimal unitPrice, BigDecimal taxRate, BigDecimal contractTaxRate) {
    }

    /**
     * A fee line of the order, whole: the invoice decides how much of it this delivery carries.
     *
     * @param sku     the SKU a plate fee is for; null on the shipping fee
     * @param taxRate the VAT rate on the day of delivery; {@code contractTaxRate} the one fixed on the order
     */
    public record Fee(FeeKind kind, String sku, BigDecimal amount, BigDecimal taxRate, BigDecimal contractTaxRate) {
    }
}
