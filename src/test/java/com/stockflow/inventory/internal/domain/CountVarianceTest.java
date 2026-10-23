package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.CountVariance.Layer;
import com.stockflow.inventory.internal.domain.CountVariance.Share;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CountVarianceTest {

    private static final Instant T1 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-01T00:00:00Z");

    private static Layer layer(Instant receivedAt, LocalDate expiry, int onHand) {
        return new Layer(StockItemId.newId(), receivedAt, expiry, onHand);
    }

    @Test
    void surplusGoesToTheNewestLayer() {
        Layer old = layer(T1, null, 10);
        Layer recent = layer(T2, null, 5);

        List<Share> shares = CountVariance.split(3, List.of(old, recent));

        assertThat(shares).containsExactly(new Share(recent.id(), T2, 3));
    }

    @Test
    void shortageFollowsFefo() {
        Layer nov = layer(T2, LocalDate.of(2026, 11, 1), 2);
        Layer dec = layer(T1, LocalDate.of(2026, 12, 1), 10);
        Layer none = layer(T1, null, 4);

        List<Share> shares = CountVariance.split(-5, List.of(none, dec, nov));

        assertThat(shares).containsExactly(new Share(nov.id(), T2, -2), new Share(dec.id(), T1, -3));
    }

    @Test
    void shortageCappedPerLayer() {
        List<Layer> layers = List.of(layer(T1, LocalDate.of(2026, 11, 1), 2),
                layer(T2, LocalDate.of(2026, 12, 1), 3), layer(T2, null, 1));

        List<Share> shares = CountVariance.split(-6, layers);

        assertThat(shares).allSatisfy(s -> {
            Layer l = layers.stream().filter(x -> x.id().equals(s.id())).findFirst().orElseThrow();
            assertThat(-s.delta()).isLessThanOrEqualTo(l.onHand());
        });
        assertThat(shares.stream().mapToInt(Share::delta).sum()).isEqualTo(-6);
    }

    @Test
    void shortageBeyondEveryLayerIsStale() {
        List<Layer> layers = List.of(layer(T1, null, 2), layer(T2, null, 1));
        assertThatThrownBy(() -> CountVariance.split(-5, layers))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CYCLE_COUNT_STALE));
    }

    @Test
    void zeroDeltaHasNoShares() {
        assertThat(CountVariance.split(0, List.of(layer(T1, null, 2)))).isEmpty();
    }

    @Test
    void surplusWithNoLayerIsUnknown() {
        assertThatThrownBy(() -> CountVariance.split(2, List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CYCLE_COUNT_UNKNOWN_LAYER));
    }
}
