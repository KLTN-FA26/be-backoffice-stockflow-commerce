package com.stockflow.common.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bit manipulation with no type system to catch a mistake, so it is tested exhaustively rather
 * than reviewed.
 */
class IdentifiersTest {

    @Test
    @DisplayName("every id is a well-formed version 7 UUID")
    void versionAndVariantBits() {
        for (int i = 0; i < 1_000; i++) {
            UUID id = Identifiers.newId();
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);   // RFC 4122 variant, the '10' bit pattern
        }
    }

    @Test
    @DisplayName("the embedded timestamp is the time the id was made")
    void timestampRoundTrips() {
        long before = System.currentTimeMillis();
        UUID id = Identifiers.newId();
        long after = System.currentTimeMillis();

        assertThat(Identifiers.timestampOf(id).toEpochMilli())
                .isBetween(before - 1, after + 1);
    }

    @Test
    @DisplayName("ids made in a burst still ascend - this is the whole point of the class")
    void burstIsMonotonic() {
        List<UUID> ids = new ArrayList<>(20_000);
        for (int i = 0; i < 20_000; i++) {
            ids.add(Identifiers.newId());
        }
        for (int i = 1; i < ids.size(); i++) {
            assertThat(sortKey(ids.get(i)))
                    .as("id %d went backwards", i)
                    .isGreaterThanOrEqualTo(sortKey(ids.get(i - 1)));
        }
    }

    @Test
    @DisplayName("200 000 ids, no duplicates")
    void noDuplicates() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 200_000; i++) {
            assertThat(seen.add(Identifiers.newId())).as("duplicate at %d", i).isTrue();
        }
    }

    @Test
    @DisplayName("sixteen threads generating at once produce no collisions")
    void concurrentGenerationIsUnique() throws Exception {
        int threads = 16;
        int perThread = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<UUID>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                List<UUID> out = new ArrayList<>(perThread);
                for (int i = 0; i < perThread; i++) {
                    out.add(Identifiers.newId());
                }
                return out;
            }));
        }
        Set<UUID> all = new HashSet<>();
        int total = 0;
        for (Future<List<UUID>> future : futures) {
            for (UUID id : future.get()) {
                all.add(id);
                total++;
            }
        }
        pool.shutdown();
        assertThat(all).as("collisions across threads").hasSize(total);
    }

    @Test
    @DisplayName("a random UUID is not accepted as time-ordered")
    void rejectsNonVersion7() {
        assertThat(Identifiers.isTimeOrdered(Identifiers.newId())).isTrue();
        assertThat(Identifiers.isTimeOrdered(UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> Identifiers.timestampOf(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version 7");
    }

    /** The 60 meaningful bits - timestamp then counter - with the version nibble removed. */
    private static long sortKey(UUID id) {
        long msb = id.getMostSignificantBits();
        return ((msb >>> 16) << 12) | (msb & 0x0FFFL);
    }
}
