package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a quote's prices need the Sales Manager before the customer sees them (kltn-docs 14 BR-10 as changed on
 * 2026-11-26): every price break of every line is measured against its reference - the customer's own price for the SKU
 * where it has one at that break (13 BR-05), else the standard price of the variant the SKU was drawn on (13 BR-04) - and
 * the quote needs approval when one break is more than {@code thresholdPercent} below its reference, or has no reference
 * at all: with nothing to measure against, the Sales Manager looks.
 *
 * <p>Only lower is measured: Sales prices a design printed in more colours above the reference without asking anyone. A
 * break is read at its own start (its {@code minQuantity} cartons): the reference's price there - its break that has
 * started by then - so a quote offering a better price from 50 cartons is measured against the reference at 50 cartons,
 * not at the cartons first proposed. A break below where the reference starts has no reference.</p>
 *
 * <p>Pure: the service reads the references and the threshold; nothing here touches a database.</p>
 */
public final class QuotePriceCheck {

    /** Where a break's reference came from. */
    public enum Source { CUSTOMER_PRICE, STANDARD_PRICE }

    /** A reference price list: from {@code minQuantity} cartons on, {@code unitPrice} VND a piece. */
    public record Reference(Source source, List<Break> breaks) {

        public Reference {
            Objects.requireNonNull(source, "source");
            breaks = breaks.stream().sorted(Comparator.comparingInt(Break::minQuantity)).toList();
        }

        /** The price at this many cartons; empty below the first break. */
        Optional<BigDecimal> priceAt(int cartons) {
            BigDecimal price = null;
            for (Break b : breaks) {
                if (b.minQuantity() > cartons) break;
                price = b.unitPrice();
            }
            return Optional.ofNullable(price);
        }
    }

    public record Break(int minQuantity, BigDecimal unitPrice) {
    }

    /**
     * One break of a line as measured.
     *
     * @param referencePrice  the reference at the break's start; null when there is none
     * @param source          where it came from; null with it
     * @param discountPercent how far below the reference, in percent (2 decimals; negative when above); null without one
     * @param needsApproval   more than the threshold below, or no reference
     */
    public record TierCheck(int minQuantity, BigDecimal unitPrice, BigDecimal referencePrice, Source source,
                            BigDecimal discountPercent, boolean needsApproval) {
    }

    public record LineCheck(int lineNo, String sku, int cartons, List<TierCheck> tiers) {

        public boolean needsApproval() {
            return tiers.stream().anyMatch(TierCheck::needsApproval);
        }
    }

    public record Result(BigDecimal thresholdPercent, List<LineCheck> lines) {

        public boolean needsApproval() {
            return lines.stream().anyMatch(LineCheck::needsApproval);
        }
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private QuotePriceCheck() {
    }

    /**
     * @param customerPrices the customer's own price of each SKU that has one
     * @param standardPrices the standard price behind each SKU - of the variant it was drawn on - that has one
     * @param thresholdPercent in [0, 100)
     */
    public static Result of(List<QuoteLine> lines, Map<String, Reference> customerPrices,
                            Map<String, Reference> standardPrices, BigDecimal thresholdPercent) {
        Objects.requireNonNull(thresholdPercent, "thresholdPercent");
        if (thresholdPercent.signum() < 0 || thresholdPercent.compareTo(HUNDRED) >= 0) {
            throw new IllegalArgumentException("The threshold is a percentage in [0, 100)");
        }
        List<LineCheck> checked = new ArrayList<>();
        for (QuoteLine line : lines) {
            if (line.legacy()) {
                continue;
            }
            String sku = line.sku().code();
            Reference own = customerPrices.get(sku);
            Reference standard = standardPrices.get(sku);
            List<TierCheck> tiers = new ArrayList<>();
            for (PriceTier tier : line.tiers()) {
                BigDecimal price = tier.unitPrice().amount();
                Optional<BigDecimal> fromOwn = own == null ? Optional.empty() : own.priceAt(tier.minQuantity());
                Optional<BigDecimal> fromStandard = fromOwn.isPresent() || standard == null ? Optional.empty()
                        : standard.priceAt(tier.minQuantity());
                BigDecimal reference = fromOwn.or(() -> fromStandard).orElse(null);
                Source source = fromOwn.isPresent() ? Source.CUSTOMER_PRICE
                        : fromStandard.isPresent() ? Source.STANDARD_PRICE : null;
                if (reference == null) {
                    tiers.add(new TierCheck(tier.minQuantity(), price, null, null, null, true));
                    continue;
                }
                BigDecimal below = reference.subtract(price).multiply(HUNDRED);
                // Exactly: (reference - price) x 100 > threshold x reference. The 2-decimal figure is only shown, so a
                // break 10.004% below is not let through as "10.00%".
                boolean over = below.compareTo(thresholdPercent.multiply(reference)) > 0;
                tiers.add(new TierCheck(tier.minQuantity(), price, reference, source,
                        below.divide(reference, 2, RoundingMode.HALF_UP), over));
            }
            checked.add(new LineCheck(line.lineNo(), sku, line.quantity(), List.copyOf(tiers)));
        }
        return new Result(thresholdPercent, List.copyOf(checked));
    }
}
