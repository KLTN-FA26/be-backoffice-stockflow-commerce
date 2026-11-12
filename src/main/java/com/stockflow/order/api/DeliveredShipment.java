package com.stockflow.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a delivered shipment is invoiced for (WF-05 E2; kltn-docs 21 §4.1; NĐ 254 Điều 9 khoản 3: every delivery is
 * invoiced): the goods it carried at the contract's prices, the order's fee lines, and where the shipment stands among
 * the order's deliveries - which the invoice needs to give this delivery its share of the shipping fee and to put the
 * plate fees on the first invoice only. Prices and fees are the order's, fixed when it was placed (14 BR-06); the VAT
 * rate is the one in force on the day of delivery.
 *
 * @param contractNumber        the order's live sales contract; null for an order without one
 * @param deliveryBatchId       the delivery batch it carried; null for an order without a delivery schedule
 * @param deliveredAt           when the carrier reported the delivery: the invoice's date (21 §4.1)
 * @param orderUnits            the pieces the order still means to deliver: its lines less what was closed
 * @param unitsDeliveredBefore  the pieces of the order's shipments delivered before this one; 0 for the order's
 *                              first delivery
 * @param unitsInShipment       the pieces this shipment carried
 */
public record DeliveredShipment(UUID orderId, String orderNumber, UUID customerId, String contractNumber,
                                UUID shipmentId, String shipmentCode, UUID deliveryBatchId, Instant deliveredAt,
                                String currency, List<Line> lines, List<Fee> fees, long orderUnits,
                                long unitsDeliveredBefore, long unitsInShipment) {

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
     * @param taxRate         the VAT rate of the SKU's tax class on the day of delivery, a percentage
     * @param contractTaxRate the rate fixed on the order line; null for a line placed without VAT
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
