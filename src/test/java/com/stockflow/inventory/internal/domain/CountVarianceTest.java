package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.CountVariance.Layer;
import com.stockflow.inventory.internal.domain.CountVariance.Share;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CountVarianceTest {

    private static final Instant T1 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-01T00:00:00Z");

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    private static Layer layer(Instant receivedAt, int onHand) {
        return layer(receivedAt, onHand, 0);
    }

    private static Layer layer(Instant receivedAt, int onHand, int reserved) {
        return new Layer(StockItemId.newId(), receivedAt, onHand, reserved);
    }

    @Test
    void surplusGoesToTheNewestLayer() {
        Layer old = layer(T1, 10);
        Layer recent = layer(T2, 5);

        List<Share> shares = CountVariance.split(3, List.of(old, recent));

        assertThat(shares).containsExactly(new Share(recent.id(), T2, 3));
    }

    @Test
    void shortageFollowsFifo() {
        Layer aug = layer(T0, 2);
        Layer sep = layer(T1, 10);
        Layer oct = layer(T2, 4);

        List<Share> shares = CountVariance.split(-5, List.of(oct, sep, aug));

        assertThat(shares).containsExactly(new Share(aug.id(), T0, -2), new Share(sep.id(), T1, -3));
    }

    @Test
    void anUndatedLayerIsTakenFirstUnlikeAllocation() {
        // The shortage takes stock with no receipt time first, as the oldest it can be; StockAllocator gives such
        // stock last. Pinned so the difference stays deliberate.
        Layer undated = layer(null, 3);
        Layer dated = layer(T1, 10);

        assertThat(CountVariance.split(-4, List.of(dated, undated)))
                .containsExactly(new Share(undated.id(), null, -3), new Share(dated.id(), T1, -1));
    }

    @Test
    void shortageTakesFreeUnitsBeforeHeldOnes() {
        // FIFO would start with sep, but all of it is held for customers; oct has 6 free of 10.
        Layer sep = layer(T1, 4, 4);
        Layer oct = layer(T2, 10, 4);

        assertThat(CountVariance.split(-5, List.of(sep, oct))).as("free units only, from the layer that has them")
                .containsExactly(new Share(oct.id(), T2, -5));
        assertThat(CountVariance.split(-8, List.of(sep, oct)))
                .as("every free unit first, then held ones in FIFO order: sep's held before oct's")
                .containsExactly(new Share(sep.id(), T1, -2), new Share(oct.id(), T2, -6));
        assertThat(CountVariance.split(-14, List.of(sep, oct))).as("everything on hand, held or not")
                .containsExactly(new Share(sep.id(), T1, -4), new Share(oct.id(), T2, -10));
        assertThatThrownBy(() -> CountVariance.split(-15, List.of(sep, oct)))
                .as("still stale only beyond what is on hand")
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CYCLE_COUNT_STALE));
    }

    @Test
    void shortageCappedPerLayer() {
        List<Layer> layers = List.of(layer(T1, 2),
                layer(T2, 3), layer(T2, 1));

        List<Share> shares = CountVariance.split(-6, layers);

        assertThat(shares).allSatisfy(s -> {
            Layer l = layers.stream().filter(x -> x.id().equals(s.id())).findFirst().orElseThrow();
            assertThat(-s.delta()).isLessThanOrEqualTo(l.onHand());
        });
        assertThat(shares.stream().mapToInt(Share::delta).sum()).isEqualTo(-6);
    }

    @Test
    void shortageBeyondEveryLayerIsStale() {
        List<Layer> layers = List.of(layer(T1, 2), layer(T2, 1));
        assertThatThrownBy(() -> CountVariance.split(-5, layers))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CYCLE_COUNT_STALE));
    }

    @Test
    void zeroDeltaHasNoShares() {
        assertThat(CountVariance.split(0, List.of(layer(T1, 2)))).isEmpty();
    }

    @Test
    void surplusWithNoLayerIsUnknown() {
        assertThatThrownBy(() -> CountVariance.split(2, List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CYCLE_COUNT_UNKNOWN_LAYER));
    }
}
