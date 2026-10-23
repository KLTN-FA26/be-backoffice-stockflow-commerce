package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Spreads a counted variance over the receipt layers of a slot.
 *
 * <p>A slot can hold several layers (same SKU and lot received at different times); stock is
 * adjusted per layer, so one delta must be split. A shortage takes the layers' <em>free</em> units
 * first, in FEFO order - the order stock leaves in, so the layer the missing units most plausibly
 * came from - and only then units held for customers, again in FEFO order. Writing a held unit
 * down while a free one of the same slot stands on the books would ask for an adjustment that
 * approval refuses (it cannot cut into a hold), when the books can absorb the shortage without
 * touching any customer. A surplus goes to the newest layer, the one least likely to be about to
 * expire, so found stock is never presented as older than it can be proven to be.</p>
 *
 * <p>The holds are read when the variance is asked; they can move before it is approved. Approval
 * then refuses loudly rather than posting into a hold, and the manager rejects and recounts, which
 * splits again over the holds as they stand by then.</p>
 */
public final class CountVariance {

    /** A layer as it stands now: {@code reserved} of its {@code onHand} units are held for customers. */
    public record Layer(StockItemId id, Instant receivedAt, LocalDate expiryDate, int onHand, int reserved) { }

    /** The part of a delta that lands on one layer. */
    public record Share(StockItemId id, Instant receivedAt, int delta) { }

    private static final Comparator<Layer> FEFO = Comparator
            .comparing(Layer::expiryDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
            .thenComparing(Layer::receivedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()));

    private static final Comparator<Layer> NEWEST = Comparator
            .comparing(Layer::receivedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()));

    private CountVariance() {
    }

    /**
     * @return one share per layer touched, in FEFO order of the layers
     * @throws BusinessException {@code CYCLE_COUNT_STALE} when a shortage exceeds what the layers hold now (stock left
     *         the slot after it was counted; posting it would drive a layer negative);
     *         {@code CYCLE_COUNT_UNKNOWN_LAYER} when a surplus has no layer to receive it
     */
    public static List<Share> split(int delta, List<Layer> layers) {
        if (delta == 0) {
            return List.of();
        }
        if (delta > 0) {
            return layers.stream().max(NEWEST)
                    .map(l -> List.of(new Share(l.id(), l.receivedAt(), delta)))
                    .orElseThrow(() -> new BusinessException(ErrorCode.CYCLE_COUNT_UNKNOWN_LAYER,
                            "There is no receipt layer to put the surplus on"));
        }
        List<Layer> fefo = layers.stream().sorted(FEFO).toList();
        int[] taken = new int[fefo.size()];
        int remaining = -delta;
        // Free units first, then held ones: each pass in FEFO order.
        for (int i = 0; i < fefo.size() && remaining > 0; i++) {
            int free = Math.max(0, fefo.get(i).onHand() - Math.max(0, fefo.get(i).reserved()));
            int take = Math.min(remaining, free);
            taken[i] += take;
            remaining -= take;
        }
        for (int i = 0; i < fefo.size() && remaining > 0; i++) {
            int left = Math.max(0, fefo.get(i).onHand() - taken[i]);
            int take = Math.min(remaining, left);
            taken[i] += take;
            remaining -= take;
        }
        if (remaining > 0) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_STALE,
                    "The shortage is larger than the stock now in the slot; count the line again");
        }
        List<Share> shares = new ArrayList<>();
        for (int i = 0; i < fefo.size(); i++) {
            if (taken[i] > 0) {
                shares.add(new Share(fefo.get(i).id(), fefo.get(i).receivedAt(), -taken[i]));
            }
        }
        return List.copyOf(shares);
    }
}
