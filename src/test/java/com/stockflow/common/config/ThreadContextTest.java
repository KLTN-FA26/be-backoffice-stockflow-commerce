package com.stockflow.common.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker sees what the thread that handed it work saw — the user, the locale, the MDC, the current observation —
 * and keeps none of it once the task is done.
 */
class ThreadContextTest {

    @AfterEach
    void clear() {
        com.stockflow.common.security.DataScopeContext.clear();
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
        MDC.clear();
    }

    @Test
    @DisplayName("the user, locale, MDC and current observation travel with the task")
    void carries() throws Exception {
        Authentication user = new TestingAuthenticationToken("planner", "n/a", "inventory-alerts:READ");
        SecurityContextHolder.getContext().setAuthentication(user);
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
        MDC.put("correlationId", "c-1");
        ObservationRegistry registry = ObservationRegistry.create();
        Observation answer = Observation.start("assistant.answer", registry);
        AtomicReference<Observation> seen = new AtomicReference<>();
        Callable<String> task;
        try (Observation.Scope scope = answer.openScope()) {
            task = ThreadContext.carry(() -> {
                seen.set(registry.getCurrentObservation());
                return SecurityContextHolder.getContext().getAuthentication().getName() + " "
                        + LocaleContextHolder.getLocale() + " " + MDC.get("correlationId");
            });
        }
        answer.stop();

        try (ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(worker.submit(task).get()).isEqualTo("planner vi c-1");
        }
        assertThat(seen.get()).isSameAs(answer);
    }

    @Test
    @DisplayName("the locale is read when the work is handed over: a lazy locale context is never read on the worker")
    void localeIsReadNow() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        LocaleContextHolder.setLocaleContext(() -> {
            reads.incrementAndGet();
            return Locale.forLanguageTag("vi");
        });
        Callable<Locale> task = ThreadContext.carry((Callable<Locale>) LocaleContextHolder::getLocale);
        int readsWhenHandedOver = reads.get();

        try (ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(worker.submit(task).get()).isEqualTo(Locale.forLanguageTag("vi"));
        }
        assertThat(reads.get()).as("the worker read the copy, not the request").isEqualTo(readsWhenHandedOver);
    }

    @Test
    @DisplayName("carry leaves the data scope behind; carryWithScope takes it along, and the worker keeps none of it")
    void dataScope() throws Exception {
        com.stockflow.common.security.CurrentUser system = new com.stockflow.common.security.CurrentUser(
                java.util.UUID.randomUUID(), "local-dev", java.util.Set.of(), java.util.Set.of(),
                com.stockflow.common.security.DataScope.ALL, java.util.Set.of());
        com.stockflow.common.security.DataScopeContext.setSystemScope(system);
        Callable<Boolean> without = ThreadContext.carry(
                () -> com.stockflow.common.security.DataScopeContext.current().isPresent());
        Callable<String> with = ThreadContext.carryWithScope(
                () -> com.stockflow.common.security.DataScopeContext.current().orElseThrow().user().username());

        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            assertThat(worker.submit(without).get()).isFalse();
            assertThat(worker.submit(with).get()).isEqualTo("local-dev");
            assertThat(worker.submit(() -> com.stockflow.common.security.DataScopeContext.current().isPresent())
                    .get()).as("put back after the task, on a pooled thread").isFalse();
        }
    }

    @Test
    @DisplayName("a thread that runs the task keeps nothing of it afterwards")
    void restores() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("planner", "n/a"));
        MDC.put("correlationId", "c-1");
        Runnable task = ThreadContext.carry(() -> { });
        SecurityContextHolder.clearContext();
        MDC.clear();

        task.run();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(MDC.get("correlationId")).isNull();
    }
}
