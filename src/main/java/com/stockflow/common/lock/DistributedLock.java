package com.stockflow.common.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A mutual exclusion lock that holds across application instances.
 *
 * <h2>When this is the right tool, and when it is not</h2>
 *
 * <p>Reach for a database row lock first. {@code SELECT ... FOR UPDATE} is transactional, released
 * automatically on commit or rollback, and cannot be leaked — which is why reserving stock uses one
 * rather than this. A distributed lock has none of those properties and is only needed when there
 * is <b>no row to lock</b>:</p>
 * <ul>
 *   <li>a job that must run on one instance only (use {@code @SchedulerLock}, not this);</li>
 *   <li>coordinating a call to an external system that has no transaction of its own;</li>
 *   <li>guarding a resource that is not in the database at all — a file, an outbound connection.</li>
 * </ul>
 *
 * <h2>What a lease-based lock cannot promise</h2>
 *
 * <p>The lease expires. If the holder is paused — a long garbage-collection pause, a suspended VM,
 * a network partition — the lease can expire while it still believes it holds the lock, and a
 * second holder acquires. Both then run. This is inherent to every lease-based lock, not a defect
 * of this implementation, and it means <b>the lock must not be the only thing preventing a
 * correctness problem</b>. Use it to avoid duplicated work, and make the work itself idempotent so
 * that duplication is merely wasteful.</p>
 */
public interface DistributedLock {

    /**
     * Try once, without waiting.
     *
     * @param leaseTime how long the lock is held if the holder dies without releasing. Long enough
     *                  to cover the slowest plausible run of the work, or a second holder starts
     *                  while the first is still going.
     * @return a handle to close, or empty if somebody else holds it
     */
    Optional<Handle> tryAcquire(String name, Duration leaseTime);

    /**
     * Run {@code action} only if the lock can be taken, releasing it afterwards.
     *
     * <p>The form to prefer: try-with-resources on a {@link Handle} is easy to get right, and just
     * as easy to forget entirely.</p>
     *
     * @return the action's result, or empty if the lock was held elsewhere
     */
    @SuppressWarnings("try") // the handle is held for its release, not referenced in the body
    default <T> Optional<T> runIfAcquired(String name, Duration leaseTime, Supplier<T> action) {
        Optional<Handle> handle = tryAcquire(name, leaseTime);
        if (handle.isEmpty()) {
            return Optional.empty();
        }
        // try-with-resources is here for the guaranteed release in its implicit finally, not to
        // use the handle - so the action's exception still propagates with the lock released.
        try (Handle ignored = handle.get()) {
            return Optional.ofNullable(action.get());
        }
    }

    /**
     * A held lock. {@code close()} releases it and never throws, so it is safe in a
     * try-with-resources alongside a body that is already failing.
     */
    interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}
