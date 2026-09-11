package com.stockflow.reporting.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA mapping of a daily sales summary (table {@code reporting.sales_daily_summary}). STARTER ENTITY.
 *
 * <p>A read model projected from order/payment events — not a source of truth.</p>
 */
@Entity
@Table(name = "sales_daily_summary", schema = "reporting",
        uniqueConstraints = @UniqueConstraint(name = "uk_sales_daily_summary_day", columnNames = "day"))
public class SalesDailySummaryJpaEntity extends BaseEntity {

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "orders_count", nullable = false)
    private int ordersCount;

    @Column(name = "revenue", nullable = false, precision = 18, scale = 2)
    private BigDecimal revenue;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    protected SalesDailySummaryJpaEntity() {
    }

    public SalesDailySummaryJpaEntity(UUID id, LocalDate day, int ordersCount,
                                      BigDecimal revenue, String currency) {
        super(id);
        this.day = day;
        this.ordersCount = ordersCount;
        this.revenue = revenue;
        this.currency = currency;
    }

    public LocalDate getDay() { return day; }
    public int getOrdersCount() { return ordersCount; }
    public BigDecimal getRevenue() { return revenue; }
    public String getCurrency() { return currency; }
}
