package com.stockflow.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Waits for a condition that an asynchronous listener will eventually make true.
 *
 * <p>Ten lines instead of a dependency, and the reason it exists at all is that the alternative —
 * {@code Thread.sleep(500)} — is how a test suite becomes untrustworthy. A fixed sleep is either
 * too short, in which case the test fails intermittently and someone eventually marks it
 * {@code @Disabled}, or too long, in which case every run pays for it.</p>
 */
public final class Await {

    private Await() {
    }

    public static void until(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + description, ex);
            }
        }
        throw new AssertionError(
                "Timed out after %s waiting for: %s".formatted(timeout, description));
    }
}
