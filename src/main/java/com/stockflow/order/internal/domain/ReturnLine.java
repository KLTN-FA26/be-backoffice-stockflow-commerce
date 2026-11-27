package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One order line of a {@link ReturnRequest}: an entity inside the aggregate, written only through it.
 *
 * <p>The order line's SKU, unit price and whether it was printed to the customer's design are copied when the return
 * is opened, as an order line copies its price: whether the goods may come back is a fact of the sale.</p>
 *
 * <p>The units go asked for ({@code requestedQuantity}) ≥ approved; then what came back on the return receipt - the
 * units of the line's SKU in the cartons received ({@code receivedQuantity}), which may be more or fewer than approved;
 * then the units refunded ({@code refundQuantity}), at most what was approved and what came back, worth
 * {@code refundAmount} with their VAT. {@code ck_return_request_line_*} state each bound again.</p>
 */
public final class ReturnLine {

    private final UUID id;
    private final UUID orderLineId;
    private final String sku;
    private final boolean customPrinted;
    private final BigDecimal unitPrice;
    private final int requestedQuantity;

    private Integer approvedQuantity;
    private Integer receivedQuantity;
    private int refundQuantity;
    private BigDecimal refundAmount = BigDecimal.ZERO;

    public ReturnLine(UUID id, UUID orderLineId, String sku, boolean customPrinted, BigDecimal unitPrice,
                      int requestedQuantity) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderLineId = Objects.requireNonNull(orderLineId, "orderLineId");
        this.sku = Objects.requireNonNull(sku, "sku");
        this.customPrinted = customPrinted;
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("A unit price is not negative");
        }
        if (requestedQuantity < 1) {
            throw new IllegalArgumentException("A return line asks for at least one unit");
        }
        this.requestedQuantity = requestedQuantity;
    }

    /** Rehydration of everything decided since the line was opened. */
    public ReturnLine restore(Integer approved, Integer received, int refund, BigDecimal refundAmount) {
        this.approvedQuantity = approved;
        this.receivedQuantity = received;
        this.refundQuantity = refund;
        this.refundAmount = refundAmount == null ? BigDecimal.ZERO : refundAmount;
        return this;
    }

    // ---- written through the aggregate only ----------------------------------------------------------------------------

    void approve(int units) {
        this.approvedQuantity = units;
    }

    /**
     * The units of the line's SKU in the cartons received, every parcel so far counted together (WF-05 TH-3): none or
     * more, never fewer ({@code ck_return_request_line_received}).
     */
    void receive(int units) {
        if (units < 0) {
            throw new IllegalArgumentException("A return line receives no fewer than zero units");
        }
        this.receivedQuantity = units;
    }

    void refund(int units, BigDecimal amount) {
        this.refundQuantity = units;
        this.refundAmount = Objects.requireNonNull(amount, "amount");
    }

    // ---- reading -------------------------------------------------------------------------------------------------------

    /**
     * The units this line claims of its order line now, so that no other return claims them (WF-05 BE-21): asked for,
     * approved or refunded, as far as it got - and never fewer than came back. Units that are back in the warehouse
     * are this return's for good, whatever it refunds for them: read off the money stage alone, a return that refunded
     * 6 of the 10 units back (or refused all of them, Q-4) left 4 claimable by a new return, whose loose line could
     * then refund them a second time. {@code ordering.return_line_claimed} says the same in the database.
     */
    public int claimedUnits(ReturnStatus status) {
        int back = receivedQuantity == null ? 0 : receivedQuantity;
        return switch (status) {
            case REQUESTED -> Math.max(requestedQuantity, back);
            case AWAITING_RETURN, RECEIVED, SETTLEMENT_PENDING ->
                    Math.max(approvedQuantity == null ? 0 : approvedQuantity, back);
            case RESOLVED -> Math.max(refundQuantity, back);
            case INSPECTED, REJECTED, CANCELLED, EXPIRED -> back;
        };
    }

    /**
     * As {@link #claimedUnits(ReturnStatus)}, for a return with a parcel on a draft receipt now ({@code draftOpen}): a
     * withdrawn-after-approval or expired return whose parcel is being counted claims what it approved again - the
     * parcel takes it back to RECEIVED (WF-05 Q-11) - so no new return takes those units meanwhile (audit round 2, M2:
     * the parcel's goods were otherwise refused by the line check for ever, and nobody was refunded).
     * {@code ordering.return_line_claimed} with six arguments says the same.
     */
    public int claimedUnits(ReturnStatus status, boolean draftOpen) {
        if (draftOpen && (status == ReturnStatus.CANCELLED || status == ReturnStatus.EXPIRED)) {
            return Math.max(approvedQuantity == null ? 0 : approvedQuantity,
                    receivedQuantity == null ? 0 : receivedQuantity);
        }
        return claimedUnits(status);
    }

    /** The most units that may be refunded: approved, and back in the warehouse. */
    public int refundableUnits() {
        int approved = approvedQuantity == null ? 0 : approvedQuantity;
        int received = receivedQuantity == null ? 0 : receivedQuantity;
        return Math.min(approved, received);
    }

    public UUID id() { return id; }
    public UUID orderLineId() { return orderLineId; }
    public String sku() { return sku; }
    public boolean customPrinted() { return customPrinted; }
    public BigDecimal unitPrice() { return unitPrice; }
    public int requestedQuantity() { return requestedQuantity; }
    public Integer approvedQuantity() { return approvedQuantity; }
    public Integer receivedQuantity() { return receivedQuantity; }
    public int refundQuantity() { return refundQuantity; }
    /** What the refunded units are worth with their VAT: what payment takes off the installments or refunds. */
    public BigDecimal refundAmount() { return refundAmount; }
    /** What the refunded units are worth before VAT, at the price paid: what the sales rollups take off revenue. */
    public BigDecimal refundNetAmount() { return unitPrice.multiply(BigDecimal.valueOf(refundQuantity)); }
}
