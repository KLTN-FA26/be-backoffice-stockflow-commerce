package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Spreads a counted variance over the receipt layers of a slot.
 *
 * <p>A slot can hold several layers (same SKU and lot received at different times); stock is
 * adjusted per layer, so one delta must be split. A shortage takes the layers' <em>free</em> units
 * first, oldest receipt first - FIFO, the order dated stock leaves in (kltn-docs 01 BR-17), so the layer the missing
 * units most plausibly came from - and only then units held for customers, again oldest first.</p>
 *
 * <p>A layer with no receipt time (stock older than timed receipts) is taken <em>first</em> here, as the oldest it
 * can be. That is deliberately not the order it leaves in: {@link StockAllocator#comparator()} gives such stock
 * <em>last</em>, so as not to change any SKU's allocation order. Only for dated layers do the two orders agree.</p>
 *
 * <p>Writing a held unit
 * down while a free one of the same slot stands on the books would ask for an adjustment that
 * approval refuses (it cannot cut into a hold), when the books can absorb the shortage without
 * touching any customer. A surplus goes to the newest layer, so found stock is never presented as older than it
 * can be proven to be.</p>
 *
 * <p>The holds are read when the variance is asked; they can move before it is approved. Approval
 * then refuses loudly rather than posting into a hold, and the manager rejects and recounts, which
 * splits again over the holds as they stand by then.</p>
 */
public final class CountVariance {

    /** A layer as it stands now: {@code reserved} of its {@code onHand} units are held for customers. */
    public record Layer(StockItemId id, Instant receivedAt, int onHand, int reserved) { }

    /** The part of a delta that lands on one layer. */
    public record Share(StockItemId id, Instant receivedAt, int delta) { }

    private static final Comparator<Layer> FIFO = Comparator
            .comparing(Layer::receivedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()));

    private static final Comparator<Layer> NEWEST = Comparator
            .comparing(Layer::receivedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()));

    private CountVariance() {
    }

    /**
     * @return one share per layer touched, in FIFO order of the layers
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
        List<Layer> fifo = layers.stream().sorted(FIFO).toList();
        int[] taken = new int[fifo.size()];
        int remaining = -delta;
        // Free units first, then held ones: each pass in FIFO order.
        for (int i = 0; i < fifo.size() && remaining > 0; i++) {
            int free = Math.max(0, fifo.get(i).onHand() - Math.max(0, fifo.get(i).reserved()));
            int take = Math.min(remaining, free);
            taken[i] += take;
            remaining -= take;
        }
        for (int i = 0; i < fifo.size() && remaining > 0; i++) {
            int left = Math.max(0, fifo.get(i).onHand() - taken[i]);
            int take = Math.min(remaining, left);
            taken[i] += take;
            remaining -= take;
        }
        if (remaining > 0) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_STALE,
                    "The shortage is larger than the stock now in the slot; count the line again");
        }
        List<Share> shares = new ArrayList<>();
        for (int i = 0; i < fifo.size(); i++) {
            if (taken[i] > 0) {
                shares.add(new Share(fifo.get(i).id(), fifo.get(i).receivedAt(), -taken[i]));
            }
        }
        return List.copyOf(shares);
    }
}
