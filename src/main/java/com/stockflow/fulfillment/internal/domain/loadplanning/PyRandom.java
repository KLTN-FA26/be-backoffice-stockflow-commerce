package com.stockflow.fulfillment.internal.domain.loadplanning;

import java.util.List;

/**
 * The pseudo-random generator of CPython's {@code random.Random} (MT19937, seeded the way CPython seeds an integer),
 * with the four calls the planner uses: {@code random()}, {@code uniform}, {@code choice} and {@code shuffle}.
 *
 * <p>Why not {@link java.util.Random}: the planner is a port of an earlier Python solver, and reproducing its draws
 * bit for bit is what lets the regression tests compare the two on the same seed. {@code java.util.Random} would make
 * every randomised step diverge from the first draw.</p>
 */
final class PyRandom {

    private static final int N = 624;
    private static final int M = 397;

    private final int[] mt = new int[N];
    private int mti;

    PyRandom(long seed) {
        long magnitude = Math.abs(seed);
        int words = magnitude == 0 ? 1 : (64 - Long.numberOfLeadingZeros(magnitude) + 31) / 32;
        int[] key = new int[words];
        for (int i = 0; i < words; i++) {
            key[i] = (int) (magnitude >>> (32 * i));
        }
        initByArray(key);
    }

    private void initGenrand(int s) {
        mt[0] = s;
        for (mti = 1; mti < N; mti++) {
            mt[mti] = 1812433253 * (mt[mti - 1] ^ (mt[mti - 1] >>> 30)) + mti;
        }
    }

    private void initByArray(int[] key) {
        initGenrand(19650218);
        int i = 1;
        int j = 0;
        for (int k = Math.max(N, key.length); k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
            i++;
            j++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
            if (j >= key.length) {
                j = 0;
            }
        }
        for (int k = N - 1; k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
            i++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
        }
        mt[0] = 0x80000000;
        mti = N;
    }

    private int nextInt32() {
        if (mti >= N) {
            int kk;
            for (kk = 0; kk < N - M; kk++) {
                int y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + M] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            for (; kk < N - 1; kk++) {
                int y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            int y = (mt[N - 1] & 0x80000000) | (mt[0] & 0x7fffffff);
            mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            mti = 0;
        }
        int y = mt[mti++];
        y ^= y >>> 11;
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= y >>> 18;
        return y;
    }

    /** {@code random.random()}: 53 random bits in [0, 1). */
    double random() {
        long a = (nextInt32() & 0xffffffffL) >>> 5;
        long b = (nextInt32() & 0xffffffffL) >>> 6;
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0);
    }

    /** {@code random.uniform(a, b)}. */
    double uniform(double a, double b) {
        return a + (b - a) * random();
    }

    /** {@code Random._randbelow(n)} of CPython 3.9: rejection sampling on {@code n.bit_length()} bits. */
    int randBelow(int n) {
        int k = 32 - Integer.numberOfLeadingZeros(n);
        long r = getRandBits(k);
        while (r >= n) {
            r = getRandBits(k);
        }
        return (int) r;
    }

    private long getRandBits(int k) {
        return (nextInt32() & 0xffffffffL) >>> (32 - k);
    }

    /** {@code random.choice(seq)}. */
    <T> T choice(List<T> seq) {
        return seq.get(randBelow(seq.size()));
    }

    /** {@code random.shuffle(x)}: Fisher-Yates from the end, in place. */
    <T> void shuffle(List<T> x) {
        for (int i = x.size() - 1; i > 0; i--) {
            int j = randBelow(i + 1);
            T tmp = x.get(i);
            x.set(i, x.get(j));
            x.set(j, tmp);
        }
    }
}
