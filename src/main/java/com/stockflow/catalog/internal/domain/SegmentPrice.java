package com.stockflow.catalog.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer group's price for one SKU (user decision P7; kltn-docs 13 §3: a customer without its own
 * price list pays its group's price). One price from the first unit: the documents give groups no
 * quantity tiers, and {@code catalog.pricing_rule} holds one price per rule. Positive whole VND, the rule
 * of every selling price; {@code ck_pricing_rule_segment_vnd} and {@code uk_pricing_rule_segment_sku} state
 * the same in the database.
 */
public final class SegmentPrice {

    private final UUID id;
    private final UUID segmentId;
    private final String sku;
    private BigDecimal price;
    private final long version;

    public SegmentPrice(UUID id, UUID segmentId, String sku, BigDecimal price, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.segmentId = Objects.requireNonNull(segmentId, "segmentId");
        if (sku == null || sku.isBlank()) throw new IllegalArgumentException("sku is required");
        this.sku = sku;
        this.price = new SellingPrice(price, "VND").amount();
        this.version = version;
    }

    /**
     * @param expectedVersion the version the editor read: a stale editor would otherwise overwrite a
     *                        colleague's change unseen
     * @throws BusinessException {@code OPTIMISTIC_LOCK}, {@code VALIDATION_FAILED}
     */
    public void replace(BigDecimal next, long expectedVersion) {
        if (expectedVersion != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "The group price of %s changed since it was read".formatted(sku));
        }
        this.price = new SellingPrice(next, "VND").amount();
    }

    public UUID id() { return id; }
    public UUID segmentId() { return segmentId; }
    public String sku() { return sku; }
    public BigDecimal price() { return price; }
    public long version() { return version; }
}
