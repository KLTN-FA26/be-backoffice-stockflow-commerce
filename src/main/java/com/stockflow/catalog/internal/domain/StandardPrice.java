package com.stockflow.catalog.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The standard price of a variant of the catalogue - the plain cup, "quy cách" - by carton break (kltn-docs 13 BR-04 as
 * changed on 2026-11-26): what a quote line starts from when the customer has no price of its own for the SKU, and what
 * the quote's discount is measured against before it is sent (14 BR-10). The Sales Manager keeps it, for a usual print:
 * a design printed in more colours is priced up by Sales, which needs nobody's approval.
 *
 * <p>The breaks follow {@link PriceTiers}, as a customer's own price: from {@code minQuantity} cartons on, each piece is
 * {@code unitPrice} whole VND, until the next break starts; below the first there is no standard price. Only a
 * catalogue variant has one ({@code fk_standard_price_variant}; a customer's own SKU is no variant since
 * V20270105000100): a customer's own SKU is priced by the customer's own price. The same invariants are CHECKs and a unique key on {@code catalog.standard_price_tier}.</p>
 */
public final class StandardPrice {

    public record Tier(int minQuantity, BigDecimal unitPrice) {
        public Tier {
            if (minQuantity < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A price tier starts at 1 carton or more");
            }
            unitPrice = new SellingPrice(unitPrice, "VND").amount();
        }
    }

    private final UUID id;
    private final String sku;
    private List<Tier> tiers;
    private String note;
    private final long version;

    public StandardPrice(UUID id, String sku, List<Tier> tiers, String note, long version) {
        this.id = Objects.requireNonNull(id, "id");
        if (sku == null || sku.isBlank()) throw new IllegalArgumentException("sku is required");
        this.sku = sku;
        this.tiers = PriceTiers.sorted(tiers, Tier::minQuantity, "A standard price");
        this.note = PriceTiers.note(note);
        this.version = version;
    }

    /**
     * Replaces the breaks. {@code expectedVersion} is the version the editor read: a stale editor would otherwise
     * overwrite a colleague's change unseen.
     *
     * @throws BusinessException {@code OPTIMISTIC_LOCK}, {@code VALIDATION_FAILED}
     */
    public void replace(List<Tier> next, String nextNote, long expectedVersion) {
        if (expectedVersion != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "The standard price of %s changed since it was read".formatted(sku));
        }
        this.tiers = PriceTiers.sorted(next, Tier::minQuantity, "A standard price");
        this.note = PriceTiers.note(nextNote);
    }

    /** Where the first break starts, in cartons. */
    public int moq() {
        return tiers.getFirst().minQuantity();
    }

    /** The break price at this many cartons; empty below the first break. */
    public Optional<BigDecimal> priceFor(int cartons) {
        BigDecimal price = null;
        for (Tier tier : tiers) {
            if (tier.minQuantity() > cartons) break;
            price = tier.unitPrice();
        }
        return Optional.ofNullable(price);
    }

    public UUID id() { return id; }
    public String sku() { return sku; }
    public List<Tier> tiers() { return tiers; }
    public String note() { return note; }
    public long version() { return version; }
}
