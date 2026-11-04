package com.stockflow.order.internal.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code ordering.quote_line} with its price breaks ({@code ordering.quote_price_tier}). Not a
 * {@code BaseEntity}: a line has no version or audit columns of its own — it changes only with its quote,
 * whose version and audit cover it. Persisted by cascade from the quote, never on its own.
 */
@Entity
@Table(name = "quote_line", schema = "ordering")
public class QuoteLineJpaEntity {

    @Embeddable
    public static class Tier {

        @Column(name = "min_quantity", nullable = false)
        private int minQuantity;

        @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
        private BigDecimal unitPrice;

        protected Tier() {
        }

        public Tier(int minQuantity, BigDecimal unitPrice) {
            this.minQuantity = minQuantity;
            this.unitPrice = unitPrice;
        }

        public int getMinQuantity() { return minQuantity; }
        public BigDecimal getUnitPrice() { return unitPrice; }
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false, updatable = false)
    private QuoteJpaEntity quote;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "blank_sku", nullable = false, length = 64)
    private String blankSku;

    @Column(name = "design_snapshot_id", nullable = false)
    private UUID designSnapshotId;

    @Column(name = "print_spec", length = 2000)
    private String printSpec;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "accepted_quantity")
    private Integer acceptedQuantity;

    @Column(name = "accepted_unit_price", precision = 19, scale = 4)
    private BigDecimal acceptedUnitPrice;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "quote_price_tier", schema = "ordering",
            joinColumns = @JoinColumn(name = "quote_line_id"))
    @OrderBy("minQuantity")
    private List<Tier> tiers = new ArrayList<>();

    protected QuoteLineJpaEntity() {
    }

    public QuoteLineJpaEntity(UUID id) {
        this.id = id;
    }

    void attachTo(QuoteJpaEntity owner) {
        this.quote = owner;
    }

    public void apply(int lineNo, String blankSku, UUID designSnapshotId, String printSpec, int quantity,
                      List<Tier> tiers, Integer acceptedQuantity, BigDecimal acceptedUnitPrice) {
        this.lineNo = lineNo;
        this.blankSku = blankSku;
        this.designSnapshotId = designSnapshotId;
        this.printSpec = printSpec;
        this.quantity = quantity;
        this.tiers.clear();
        this.tiers.addAll(tiers);
        this.acceptedQuantity = acceptedQuantity;
        this.acceptedUnitPrice = acceptedUnitPrice;
    }

    public UUID getId() { return id; }
    public int getLineNo() { return lineNo; }
    public String getBlankSku() { return blankSku; }
    public UUID getDesignSnapshotId() { return designSnapshotId; }
    public String getPrintSpec() { return printSpec; }
    public int getQuantity() { return quantity; }
    public Integer getAcceptedQuantity() { return acceptedQuantity; }
    public BigDecimal getAcceptedUnitPrice() { return acceptedUnitPrice; }
    public List<Tier> getTiers() { return tiers; }
}
