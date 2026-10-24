package com.stockflow.assistant.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How much to reorder (design §8). Arithmetic, in Java, never by the model: a language model asked to multiply is a
 * source of wrong purchase orders.
 *
 * <pre>
 * position  = availableToPromise + onOrder + incoming
 * target    = averageDailySales × (leadTimeDays + coverDays) + safetyStock     at most maxQty, when one is set
 * need      = target − position
 * suggested = 0 when need ≤ 0; otherwise at least the MOQ, then rounded up to the pack size
 * </pre>
 *
 * <p>The order-up-to rule of a periodic review. Two inputs matter more than they look:</p>
 * <ul>
 *   <li><b>The position counts what is already coming.</b> Without the open purchase orders and the goods in
 *   receiving or on a transfer, the same shortage is proposed again every day until the first lot is put away
 *   (InventoryPolicy: "maxQty minus what is on hand and on order").</li>
 *   <li><b>The lead time is part of the horizon.</b> Goods ordered today arrive after the lead time; the shelf is
 *   drawn down meanwhile, so a target of {@code coverDays} of sales alone runs out before the order lands.</li>
 * </ul>
 * <p>{@code maxQty} is the SKU's order-up-to level (InventoryPolicy): the target never exceeds it, although pack and
 * MOQ rounding may. The formula is the starting proposal; the inventory planner owns it (design §13 AQ-05). Every
 * input is returned with the result, so a person sees why a number was suggested before putting it on an order.</p>
 */
public final class ReorderPolicy {

    private ReorderPolicy() {
    }

    /**
     * @param soldInPeriod       units sold over {@code periodDays}
     * @param leadTimeDays       days from ordering to receipt; 0 when unknown
     * @param safetyStock        null when not configured, which counts as zero
     * @param maxQty             the order-up-to level; null when not configured
     * @param availableToPromise units on hand that are not already promised, across every warehouse
     * @param onOrder            units on approved purchase orders not yet received
     * @param incoming           units received but not yet available, and units on a transfer between warehouses
     * @param moq                the supplier's minimum order quantity; null when unknown
     * @param packSize           the supplier's pack size; null when unknown
     */
    public record Inputs(long soldInPeriod, int periodDays, int coverDays, int leadTimeDays, Integer safetyStock,
                         Integer maxQty, long availableToPromise, long onOrder, long incoming, Integer moq,
                         Integer packSize) {

        public Inputs {
            if (periodDays < 1 || coverDays < 1) {
                throw new IllegalArgumentException("periodDays and coverDays must be at least 1");
            }
            if (soldInPeriod < 0 || leadTimeDays < 0 || onOrder < 0 || incoming < 0) {
                throw new IllegalArgumentException("soldInPeriod, leadTimeDays, onOrder and incoming must not be "
                        + "negative");
            }
        }
    }

    public static Suggestion suggest(Inputs in) {
        BigDecimal averageDailySales = BigDecimal.valueOf(in.soldInPeriod())
                .divide(BigDecimal.valueOf(in.periodDays()), 2, RoundingMode.HALF_UP);
        long position = in.availableToPromise() + in.onOrder() + in.incoming();
        BigDecimal target = averageDailySales.multiply(BigDecimal.valueOf((long) in.leadTimeDays() + in.coverDays()))
                .add(BigDecimal.valueOf(in.safetyStock() == null ? 0 : in.safetyStock()))
                .setScale(0, RoundingMode.CEILING);
        boolean cappedAtMax = in.maxQty() != null && target.compareTo(BigDecimal.valueOf(in.maxQty())) > 0;
        if (cappedAtMax) {
            target = BigDecimal.valueOf(in.maxQty());
        }
        long need = Math.max(0, target.longValueExact() - position);
        long quantity = need;
        String rounding = null;
        if (quantity > 0 && in.moq() != null && quantity < in.moq()) {
            quantity = in.moq();
            rounding = "MOQ";
        }
        // After the MOQ: a legacy MOQ that is not a multiple of the pack would give a line the PO form refuses
        // (PO_LINE_NOT_PACK_MULTIPLE).
        if (quantity > 0 && in.packSize() != null && in.packSize() > 1 && quantity % in.packSize() != 0) {
            quantity = (quantity / in.packSize() + 1) * in.packSize();
            rounding = rounding == null ? "PACK_SIZE" : "MOQ_AND_PACK_SIZE";
        }
        return new Suggestion(quantity, averageDailySales, target.longValueExact(), position, need, rounding,
                cappedAtMax);
    }

    /**
     * @param quantity          what to order; zero when the position covers the target
     * @param averageDailySales units per day over the sales period, two decimals
     * @param target            the stock to reach, after the {@code maxQty} cap
     * @param position          available-to-promise plus on order plus incoming
     * @param need              the unrounded need, at least zero
     * @param roundedFor        {@code PACK_SIZE}, {@code MOQ}, {@code MOQ_AND_PACK_SIZE}, or null when no rounding
     *                          applied
     * @param cappedAtMax       the sales-based target was above {@code maxQty} and was lowered to it
     */
    public record Suggestion(long quantity, BigDecimal averageDailySales, long target, long position, long need,
                             String roundedFor, boolean cappedAtMax) {
    }
}
