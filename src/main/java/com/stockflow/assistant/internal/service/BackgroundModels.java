package com.stockflow.assistant.internal.service;

/**
 * The models the assistant's watch answers its own questions on (design §9.1): {@code stockflow.assistant.watch.models},
 * cheapest first, built as the questions' router is (same provider rules, same data policy), or the questions' router
 * itself when no list is set.
 *
 * <p>A holder rather than a second {@link ModelRouter} bean: one router type injected by type stays unambiguous, and
 * only a router built here is closed here.</p>
 *
 * @param owned whether {@link #router} was built for the watch, and so closed with it
 */
record BackgroundModels(ModelRouter router, boolean owned) implements AutoCloseable {

    @Override
    public void close() throws Exception {
        if (owned) {
            router.close();
        }
    }
}
