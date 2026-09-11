package com.stockflow.common.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates the identifiers every aggregate in this system uses.
 *
 * <h2>Why not {@code UUID.randomUUID()}</h2>
 *
 * <p>A random (version 4) UUID has no relationship to time, so consecutive inserts land at random
 * positions in the primary-key B-tree. Postgres then has to read, split and write pages scattered
 * across the whole index instead of appending to the rightmost one. On a small table nobody
 * notices; on a few million rows the index stops fitting in cache and write throughput falls off a
 * cliff, and the cause is invisible in application code — the symptom is "inserts got slow".</p>
 *
 * <p>{@link #newId()} returns a <b>UUID version 7</b>: the first 48 bits are the Unix timestamp in
 * milliseconds, so ids generated close together sort close together and inserts append. It is a
 * standard UUID in every other respect — same 128 bits, same {@code uuid} column, same
 * {@code UUID} type in Java — so nothing downstream has to know.</p>
 *
 * <h2>Layout (RFC 9562 §5.7)</h2>
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                       unix_ts_ms (48 bits)                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  unix_ts_ms   |  ver  |     counter (12 bits)                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |var|                    random (62 bits)                       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <p>The 12-bit counter is what makes ids generated <i>within the same millisecond</i> still
 * ascend. Without it, a burst of inserts in one millisecond would be randomly ordered among
 * themselves, and the clock going backwards (NTP correction) could produce ids that sort before
 * ones already written.</p>
 *
 * <h2>Uniqueness</h2>
 *
 * <p>62 bits of {@link SecureRandom} per id. Two instances generating in the same millisecond
 * collide with probability around 2<sup>-62</sup> per pair — far below the probability of a disk
 * silently corrupting the row. No coordination between instances is needed, which is the whole
 * reason for using UUIDs rather than a database sequence.</p>
 */
public final class Identifiers {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Guards the millisecond-and-counter pair; both must move together or ids can go backwards. */
    private static final AtomicLong LAST_STATE = new AtomicLong(0);

    private static final int COUNTER_BITS = 12;
    private static final long MAX_COUNTER = (1L << COUNTER_BITS) - 1;

    private Identifiers() {
    }

    /** A new time-ordered identifier. This is the only id factory application code should call. */
    public static UUID newId() {
        return newId(System.currentTimeMillis());
    }

    /** Testable entry point: {@code newId(clock.millis())}. */
    public static UUID newId(Clock clock) {
        return newId(clock.millis());
    }

    static UUID newId(long unixMillis) {
        long state = nextState(unixMillis);
        long millis = state >>> COUNTER_BITS;
        long counter = state & MAX_COUNTER;

        // high 64 bits: 48-bit timestamp | version 7 | 12-bit counter
        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16
                | 0x7000L
                | counter;

        // low 64 bits: variant 10 | 62 random bits
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;

        return new UUID(msb, lsb);
    }

    /**
     * Advances the (millisecond, counter) pair atomically and monotonically.
     *
     * <p>Three cases, and the second and third are the ones that matter:</p>
     * <ul>
     *   <li>the clock moved forward — reset the counter to a small random start, so two instances
     *       starting the same millisecond do not produce identical prefixes;</li>
     *   <li>the same millisecond — increment the counter, which keeps ids ascending within a burst;</li>
     *   <li>the clock moved <b>backwards</b> (an NTP step, a VM migration) — keep the previous
     *       millisecond and increment anyway. Emitting the earlier timestamp would produce ids that
     *       sort before rows already written, undoing the append-only property this class exists
     *       for. Time-ordering is a storage optimisation, not a clock reading, so borrowing from
     *       the future here is the right trade.</li>
     * </ul>
     */
    private static long nextState(long unixMillis) {
        while (true) {
            long previous = LAST_STATE.get();
            long previousMillis = previous >>> COUNTER_BITS;
            long previousCounter = previous & MAX_COUNTER;

            long millis;
            long counter;
            if (unixMillis > previousMillis) {
                millis = unixMillis;
                // Start low but not at zero: a fixed start would make the first id of every
                // millisecond identical in its top 60 bits across instances.
                counter = RANDOM.nextInt(1 << 8);
            } else {
                millis = previousMillis;
                counter = previousCounter + 1;
                if (counter > MAX_COUNTER) {
                    // 4096 ids in one millisecond. Roll into the next millisecond rather than
                    // wrapping the counter, which would make ids repeat within that millisecond.
                    millis = previousMillis + 1;
                    counter = 0;
                }
            }

            long next = (millis << COUNTER_BITS) | counter;
            if (LAST_STATE.compareAndSet(previous, next)) {
                return next;
            }
        }
    }

    /**
     * The instant encoded in a version-7 id.
     *
     * <p>Useful for "when was this row created" without reading a column, and for spotting an id
     * that came from somewhere else.</p>
     *
     * @throws IllegalArgumentException if the id is not version 7
     */
    public static java.time.Instant timestampOf(UUID id) {
        if (id.version() != 7) {
            throw new IllegalArgumentException(
                    "Not a version 7 UUID (got version %d): %s".formatted(id.version(), id));
        }
        return java.time.Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }

    /** True when the id was produced by {@link #newId()} rather than, say, a client. */
    public static boolean isTimeOrdered(UUID id) {
        return id.version() == 7;
    }
}
