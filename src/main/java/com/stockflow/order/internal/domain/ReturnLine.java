package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One order line of a {@link ReturnRequest}: an entity inside the aggregate, written only through it.
 *
 * <p>The order line's SKU, unit price and whether it was printed to the customer's design are copied when the return
 * is opened, as an order line copies its price: what is credited is what the customer paid, whatever the price list
 * says by then, and whether the goods may go back on sale is a fact of the sale.</p>
 *
 * <p>The units narrow as the return goes on: asked for ({@code requestedQuantity}) ≥ approved ≥ accepted. What arrived
 * may be more or fewer than approved, or of another SKU; every unit that arrived is accepted (the claim holds) or
 * rejected, and goes back on sale, to scrap or back to the customer. Every accepted unit is then replaced or credited.
 * {@code ck_return_request_line_*} state each sum again.</p>
 */
public final class ReturnLine {

    private final UUID id;
    private final UUID orderLineId;
    private final String sku;
    private final boolean customPrinted;
    private final BigDecimal unitPrice;
    private final int requestedQuantity;

    private Integer approvedQuantity;
    private String receivedSku;
    private Integer receivedQuantity;
    private String lotNumber;
    private Integer acceptedQuantity;
    private Integer rejectedQuantity;
    private ReturnItemCondition condition;
    private String inspectionNote;
    private int restockQuantity;
    private int scrapQuantity;
    private int customerReturnQuantity;
    private String restockLocation;
    private UUID scrapAdjustmentId;
    private UUID customerReturnAdjustmentId;
    private int replacementQuantity;
    private int creditQuantity;
    private UUID replacementOrderLineId;

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
    public ReturnLine restore(Integer approved, String receivedSku, Integer received, String lot, Integer accepted,
                              Integer rejected, ReturnItemCondition condition, String inspectionNote, int restock,
                              int scrap, int customerReturn, String restockLocation, UUID scrapAdjustment,
                              UUID customerReturnAdjustment, int replacement, int credit, UUID replacementOrderLine) {
        this.approvedQuantity = approved;
        this.receivedSku = receivedSku;
        this.receivedQuantity = received;
        this.lotNumber = lot;
        this.acceptedQuantity = accepted;
        this.rejectedQuantity = rejected;
        this.condition = condition;
        this.inspectionNote = inspectionNote;
        this.restockQuantity = restock;
        this.scrapQuantity = scrap;
        this.customerReturnQuantity = customerReturn;
        this.restockLocation = restockLocation;
        this.scrapAdjustmentId = scrapAdjustment;
        this.customerReturnAdjustmentId = customerReturnAdjustment;
        this.replacementQuantity = replacement;
        this.creditQuantity = credit;
        this.replacementOrderLineId = replacementOrderLine;
        return this;
    }

    // ---- written through the aggregate only ----------------------------------------------------------------------------

    void approve(int units) {
        this.approvedQuantity = units;
    }

    void receive(String sku, int units, String lot) {
        this.receivedSku = sku;
        this.receivedQuantity = units;
        this.lotNumber = lot;
    }

    void inspect(int accepted, int rejected, ReturnItemCondition condition, String note, int restock, int scrap,
                 int customerReturn, String restockLocation) {
        this.acceptedQuantity = accepted;
        this.rejectedQuantity = rejected;
        this.condition = condition;
        this.inspectionNote = note;
        this.restockQuantity = restock;
        this.scrapQuantity = scrap;
        this.customerReturnQuantity = customerReturn;
        this.restockLocation = restockLocation;
    }

    void remedy(int replacement, int credit) {
        this.replacementQuantity = replacement;
        this.creditQuantity = credit;
    }

    void replacedBy(UUID orderLineId) {
        this.replacementOrderLineId = orderLineId;
    }

    void stockAdjustments(UUID scrap, UUID customerReturn) {
        this.scrapAdjustmentId = scrap;
        this.customerReturnAdjustmentId = customerReturn;
    }

    // ---- reading -------------------------------------------------------------------------------------------------------

    /** What the credited units are worth: the price the customer paid, times the units credited. */
    public BigDecimal creditAmount() {
        return unitPrice.multiply(BigDecimal.valueOf(creditQuantity));
    }

    /** The units this line claims of its order line now: asked for, approved, or accepted, as far as it got. */
    public int claimedUnits(ReturnStatus status) {
        return switch (status) {
            case REQUESTED -> requestedQuantity;
            case AWAITING_RETURN, RECEIVED -> approvedQuantity == null ? 0 : approvedQuantity;
            case INSPECTED, SETTLEMENT_PENDING, RESOLVED -> acceptedQuantity == null ? 0 : acceptedQuantity;
            case REJECTED, CANCELLED, EXPIRED -> 0;
        };
    }

    /**
     * Whether the goods that came back for this line were printed to the customer's design: the line's own SKU on a
     * printed line. Another SKU that arrived instead (a wrong item) is ordinary stock.
     */
    public boolean receivedPrintedGoods() {
        return customPrinted && (receivedSku == null || receivedSku.equals(sku));
    }

    /** The SKU that arrived; the line's own when nothing else did. */
    public String receivedSkuOrOwn() {
        return receivedSku == null ? sku : receivedSku;
    }

    public UUID id() { return id; }
    public UUID orderLineId() { return orderLineId; }
    public String sku() { return sku; }
    public boolean customPrinted() { return customPrinted; }
    public BigDecimal unitPrice() { return unitPrice; }
    public int requestedQuantity() { return requestedQuantity; }
    public Integer approvedQuantity() { return approvedQuantity; }
    public String receivedSku() { return receivedSku; }
    public Integer receivedQuantity() { return receivedQuantity; }
    public String lotNumber() { return lotNumber; }
    public Integer acceptedQuantity() { return acceptedQuantity; }
    public Integer rejectedQuantity() { return rejectedQuantity; }
    public ReturnItemCondition condition() { return condition; }
    public String inspectionNote() { return inspectionNote; }
    public int restockQuantity() { return restockQuantity; }
    public int scrapQuantity() { return scrapQuantity; }
    public int customerReturnQuantity() { return customerReturnQuantity; }
    public String restockLocation() { return restockLocation; }
    public UUID scrapAdjustmentId() { return scrapAdjustmentId; }
    public UUID customerReturnAdjustmentId() { return customerReturnAdjustmentId; }
    public int replacementQuantity() { return replacementQuantity; }
    public int creditQuantity() { return creditQuantity; }
    public UUID replacementOrderLineId() { return replacementOrderLineId; }
}
