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
 * adjusted per layer, so one delta must be split. A shortage follows FEFO, the order stock leaves
 * in, because that is the layer the missing units most plausibly came from. A surplus goes to the
 * newest layer, the one least likely to be about to expire, so found stock is never presented as
 * older than it can be proven to be.</p>
 */
public final class CountVariance {

    /** A layer as it stands now. */
    public record Layer(StockItemId id, Instant receivedAt, LocalDate expiryDate, int onHand) { }

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
        int remaining = -delta;
        List<Share> shares = new ArrayList<>();
        for (Layer layer : layers.stream().sorted(FEFO).toList()) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(remaining, layer.onHand());
            if (take > 0) {
                shares.add(new Share(layer.id(), layer.receivedAt(), -take));
                remaining -= take;
            }
        }
        if (remaining > 0) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_STALE,
                    "The shortage is larger than the stock now in the slot; count the line again");
        }
        return List.copyOf(shares);
    }
}
