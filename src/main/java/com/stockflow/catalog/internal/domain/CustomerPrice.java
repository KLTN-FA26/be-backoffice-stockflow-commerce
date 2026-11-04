package com.stockflow.catalog.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One customer's own price for one SKU, by quantity tier (SCRUM-428; kltn-docs 13 §3, D14).
 *
 * <p>A tier applies from its {@code minQuantity} on, until the next tier starts. The first tier is
 * this customer's minimum order quantity for the SKU (BR-05): below it there is no price, rather than
 * a silent fall back to another price - the same rule a quote's tiers follow. The documents set no
 * order between tier prices (a larger quantity is not required to be cheaper), so none is enforced.
 * Prices are positive whole VND, the rule of every selling price in the catalog. The same invariants
 * are CHECKs and a unique key on {@code catalog.customer_price_tier}.</p>
 */
public final class CustomerPrice {

    /** Generous for a price list, small enough that a mistyped request cannot store thousands of rows. */
    public static final int MAX_TIERS = 20;

    public record Tier(int minQuantity, BigDecimal unitPrice) {
        public Tier {
            if (minQuantity < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A price tier starts at 1 unit or more");
            }
            unitPrice = new SellingPrice(unitPrice, "VND").amount();
        }
    }

    private final UUID id;
    private final UUID customerId;
    private final String sku;
    private List<Tier> tiers;
    private String note;
    private final long version;

    public CustomerPrice(UUID id, UUID customerId, String sku, List<Tier> tiers, String note, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        if (sku == null || sku.isBlank()) throw new IllegalArgumentException("sku is required");
        this.sku = sku;
        this.tiers = validated(tiers);
        this.note = noteOf(note);
        this.version = version;
    }

    /**
     * Replaces the tiers. {@code expectedVersion} is the version the editor read: a stale editor would
     * otherwise overwrite a colleague's change unseen.
     *
     * @throws BusinessException {@code OPTIMISTIC_LOCK}, {@code VALIDATION_FAILED}
     */
    public void replace(List<Tier> next, String nextNote, long expectedVersion) {
        if (expectedVersion != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "The price of %s for this customer changed since it was read".formatted(sku));
        }
        this.tiers = validated(next);
        this.note = noteOf(nextNote);
    }

    public int moq() {
        return tiers.getFirst().minQuantity();
    }

    /** The tier price for this quantity; empty below the first tier. */
    public Optional<BigDecimal> priceFor(int quantity) {
        BigDecimal price = null;
        for (Tier tier : tiers) {
            if (tier.minQuantity() > quantity) break;
            price = tier.unitPrice();
        }
        return Optional.ofNullable(price);
    }

    private static List<Tier> validated(List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A customer price needs at least one tier");
        }
        if (tiers.size() > MAX_TIERS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "At most " + MAX_TIERS + " price tiers");
        }
        var starts = new HashSet<Integer>();
        for (Tier tier : tiers) {
            if (!starts.add(Objects.requireNonNull(tier, "tier").minQuantity())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Two price tiers start at " + tier.minQuantity());
            }
        }
        var sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingInt(Tier::minQuantity));
        return List.copyOf(sorted);
    }

    private static String noteOf(String note) {
        if (note == null || note.isBlank()) return null;
        if (note.length() > 500) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "note is too long");
        return note.strip();
    }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public String sku() { return sku; }
    public List<Tier> tiers() { return tiers; }
    public String note() { return note; }
    public long version() { return version; }
}
