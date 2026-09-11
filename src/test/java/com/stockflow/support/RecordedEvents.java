package com.stockflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Records every cross-module event the application publishes during a test.
 *
 * <p>Deliberately built from plain Spring rather than a test framework helper. A test that asserts
 * "placing an order announces {@code OrderPlaced}" should not depend on a parameter resolver being
 * registered — and this way the assertion also proves the event really reaches an ordinary
 * listener, which is what other modules are.</p>
 *
 * <p>The listener is a plain {@code @EventListener}, so it runs <b>synchronously inside the
 * publisher's transaction</b> — before commit. That is on purpose: it makes recording deterministic
 * even though the real listeners are asynchronous. {@link #awaitAtLeast} exists for the cases where
 * the assertion is about something an async listener did.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordedEvents {

    @Component
    public static class Recorder {

        private final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        void record(Object event) {
            if (event.getClass().getPackageName().equals("com.stockflow.contracts")) {
                events.add(event);
            }
        }

        public <T> List<T> ofType(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }

        public <T> List<T> matching(Class<T> type, Predicate<T> predicate) {
            return ofType(type).stream().filter(predicate).toList();
        }

        public void clear() {
            events.clear();
        }

        /**
         * Polls until at least {@code count} matching events have arrived, or the timeout expires.
         *
         * <p>Polling rather than {@code Thread.sleep}: a fixed sleep is either too short, and the
         * test is flaky, or too long, and the suite crawls. This returns as soon as the condition
         * holds.</p>
         */
        public <T> List<T> awaitAtLeast(Class<T> type, Predicate<T> predicate,
                                        int count, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            List<T> found = matching(type, predicate);
            while (found.size() < count && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while awaiting " + type.getSimpleName(), ex);
                }
                found = matching(type, predicate);
            }
            return found;
        }
    }
}
