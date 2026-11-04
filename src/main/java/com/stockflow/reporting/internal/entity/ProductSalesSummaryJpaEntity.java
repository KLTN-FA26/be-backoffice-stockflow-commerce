package com.stockflow.reporting.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a per-SKU sales summary (table {@code reporting.product_sales_summary}).
 *
 * <p>{@code period} is the day, an ISO date like {@code 2026-10-24}: one row per SKU per Asia/Ho_Chi_Minh day. A read
 * model projected from order events, written with SQL upserts by {@code SalesRollupLedgerAdapter}.</p>
 */
@Entity
@Table(name = "product_sales_summary", schema = "reporting",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_sales_summary_sku_period",
                columnNames = {"sku", "period"}))
public class ProductSalesSummaryJpaEntity extends BaseEntity {

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "period", nullable = false, length = 10)
    private String period;

    @Column(name = "quantity_sold", nullable = false)
    private int quantitySold;

    @Column(name = "revenue", nullable = false, precision = 18, scale = 2)
    private BigDecimal revenue;

    protected ProductSalesSummaryJpaEntity() {
    }

    public ProductSalesSummaryJpaEntity(UUID id, String sku, String period,
                                        int quantitySold, BigDecimal revenue) {
        super(id);
        this.sku = sku;
        this.period = period;
        this.quantitySold = quantitySold;
        this.revenue = revenue;
    }

    public String getSku() { return sku; }
    public String getPeriod() { return period; }
    public int getQuantitySold() { return quantitySold; }
    public BigDecimal getRevenue() { return revenue; }
}
