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
 * @param orderGoodsValue       what the goods the order means to deliver as it stands now are worth before tax - its
 *                              lines less what was closed, at their prices: what the shipping fee is shared out by
 *                              (WF-05 Q-19: by value, as payment waives it). Null from a caller that does not say
 * @param shippingFeeWaiver     the part of the shipping fee waived for shipments that ended undelivered (Q-19)
 */
public record DeliveredShipment(UUID orderId, String orderNumber, UUID customerId, String contractNumber,
                                UUID shipmentId, String shipmentCode, UUID deliveryBatchId, Instant deliveredAt,
                                String currency, List<Line> lines, List<Fee> fees, long orderUnits,
                                long unitsInShipment, BigDecimal orderGoodsValue, ShippingFeeWaiver shippingFeeWaiver) {

    public DeliveredShipment {
        lines = List.copyOf(lines);
        fees = List.copyOf(fees);
        shippingFeeWaiver = shippingFeeWaiver == null ? ShippingFeeWaiver.NONE : shippingFeeWaiver;
    }

    /** As before the fee was shared by value: no goods value said, nothing waived. */
    public DeliveredShipment(UUID orderId, String orderNumber, UUID customerId, String contractNumber,
                             UUID shipmentId, String shipmentCode, UUID deliveryBatchId, Instant deliveredAt,
                             String currency, List<Line> lines, List<Fee> fees, long orderUnits,
                             long unitsInShipment) {
        this(orderId, orderNumber, customerId, contractNumber, shipmentId, shipmentCode, deliveryBatchId, deliveredAt,
                currency, lines, fees, orderUnits, unitsInShipment, null, ShippingFeeWaiver.NONE);
    }

    /** What this shipment's goods are worth before tax: each line's pieces at the order's price of one. */
    public BigDecimal goodsValue() {
        return lines.stream().map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.units())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
     *                        product has no tax class, the highest rate, 10% (Luật Thuế GTGT 48/2024/QH15 Điều 9
     *                        khoản 4; WF-05 Q-21), flagged by {@code taxClassUnknown}; for a class with no rate that
     *                        day, the rate the order fixed, and where the order fixed none either, the day's STANDARD
     *                        rate - a guess the invoice must flag, never 0%
     * @param contractTaxRate the rate fixed on the order line; null for a line placed without VAT, which the invoice
     *                        flags: the customer never agreed to a rate
     */
    public record Line(UUID orderLineId, String sku, String name, Integer cartons, Integer unitsPerCarton, int units,
                       BigDecimal unitPrice, BigDecimal taxRate, BigDecimal contractTaxRate,
                       boolean taxClassUnknown) {

        /** A line whose product's tax class is known. */
        public Line(UUID orderLineId, String sku, String name, Integer cartons, Integer unitsPerCarton, int units,
                    BigDecimal unitPrice, BigDecimal taxRate, BigDecimal contractTaxRate) {
            this(orderLineId, sku, name, cartons, unitsPerCarton, units, unitPrice, taxRate, contractTaxRate, false);
        }
    }

    /**
     * A fee line of the order, whole: the invoice decides how much of it this delivery carries.
     *
     * @param sku     the SKU a plate fee is for; null on the shipping fee
     * @param taxRate the VAT rate on the day of delivery; {@code contractTaxRate} the one fixed on the order
     */
    public record Fee(FeeKind kind, String sku, BigDecimal amount, BigDecimal taxRate, BigDecimal contractTaxRate,
                      boolean taxClassUnknown) {

        /** A fee whose tax class is known (the shipping fee's is always STANDARD). */
        public Fee(FeeKind kind, String sku, BigDecimal amount, BigDecimal taxRate, BigDecimal contractTaxRate) {
            this(kind, sku, amount, taxRate, contractTaxRate, false);
        }
    }
}
