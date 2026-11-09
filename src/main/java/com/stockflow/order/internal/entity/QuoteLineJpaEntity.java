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
 * {@code ordering.quote_line} with its price breaks ({@code ordering.quote_price_tier}). A row is a carton line
 * ({@code sku}, {@code uomConversionId}, {@code cartons}, {@code unitsPerCarton}, 4B-2) or a line drafted before on a
 * blank and a design ({@code blankSku}, {@code designSnapshotId}, {@code quantity}); {@code ck_quote_line_shape} lets it
 * be one or the other. Not a
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

    @Column(name = "sku", length = 64)
    private String sku;

    @Column(name = "uom_conversion_id")
    private UUID uomConversionId;

    @Column(name = "cartons")
    private Integer cartons;

    @Column(name = "units_per_carton")
    private Integer unitsPerCarton;

    @Column(name = "accepted_cartons")
    private Integer acceptedCartons;

    @Column(name = "blank_sku", length = 64)
    private String blankSku;

    @Column(name = "design_snapshot_id")
    private UUID designSnapshotId;

    @Column(name = "print_spec", length = 2000)
    private String printSpec;

    @Column(name = "quantity")
    private Integer quantity;

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

    /** A carton line: the columns of a line drafted before are cleared. */
    public void applyCartons(int lineNo, String sku, UUID uomConversionId, int unitsPerCarton, int cartons,
                             List<Tier> tiers, Integer acceptedCartons, BigDecimal acceptedUnitPrice) {
        this.lineNo = lineNo;
        this.sku = sku;
        this.uomConversionId = uomConversionId;
        this.unitsPerCarton = unitsPerCarton;
        this.cartons = cartons;
        this.acceptedCartons = acceptedCartons;
        this.blankSku = null;
        this.designSnapshotId = null;
        this.printSpec = null;
        this.quantity = null;
        this.acceptedQuantity = null;
        replaceTiers(tiers);
        this.acceptedUnitPrice = acceptedUnitPrice;
    }

    /** A line drafted before 4B-2, written back as it was: a blank, a design, units. */
    public void applyLegacy(int lineNo, String blankSku, UUID designSnapshotId, String printSpec, int quantity,
                            List<Tier> tiers, Integer acceptedQuantity, BigDecimal acceptedUnitPrice) {
        this.lineNo = lineNo;
        this.blankSku = blankSku;
        this.designSnapshotId = designSnapshotId;
        this.printSpec = printSpec;
        this.quantity = quantity;
        this.acceptedQuantity = acceptedQuantity;
        this.sku = null;
        this.uomConversionId = null;
        this.unitsPerCarton = null;
        this.cartons = null;
        this.acceptedCartons = null;
        replaceTiers(tiers);
        this.acceptedUnitPrice = acceptedUnitPrice;
    }

    /** Rewrites the tiers only when they changed, so saving the quote for another reason leaves their rows alone. */
    private void replaceTiers(List<Tier> next) {
        boolean same = next.size() == tiers.size();
        for (int i = 0; same && i < next.size(); i++) {
            same = next.get(i).getMinQuantity() == tiers.get(i).getMinQuantity()
                    && next.get(i).getUnitPrice().compareTo(tiers.get(i).getUnitPrice()) == 0;
        }
        if (!same) {
            this.tiers.clear();
            this.tiers.addAll(next);
        }
    }

    public UUID getId() { return id; }
    public int getLineNo() { return lineNo; }
    public String getSku() { return sku; }
    public UUID getUomConversionId() { return uomConversionId; }
    public Integer getCartons() { return cartons; }
    public Integer getUnitsPerCarton() { return unitsPerCarton; }
    public Integer getAcceptedCartons() { return acceptedCartons; }
    public String getBlankSku() { return blankSku; }
    public UUID getDesignSnapshotId() { return designSnapshotId; }
    public String getPrintSpec() { return printSpec; }
    public Integer getQuantity() { return quantity; }
    public Integer getAcceptedQuantity() { return acceptedQuantity; }
    public BigDecimal getAcceptedUnitPrice() { return acceptedUnitPrice; }
    public List<Tier> getTiers() { return tiers; }
}
