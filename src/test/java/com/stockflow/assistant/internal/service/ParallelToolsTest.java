package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.DataScopeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelToolsTest {

    @AfterEach
    void clear() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    @DisplayName("every call runs as the user who asked, with the request's MDC, on a thread of its own")
    void callsRunAsTheUser() throws Exception {
        Authentication user = new TestingAuthenticationToken("minh.vo", null, "inventory-stock-items:READ");
        SecurityContextHolder.getContext().setAuthentication(user);
        MDC.put("correlationId", "c-42");
        CyclicBarrier together = new CyclicBarrier(2);
        List<String> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        ParallelTools tools = tools(arguments -> {
            try {
                together.await(10, TimeUnit.SECONDS);
            } catch (Exception notTogether) {
                return Map.of("ran", "alone");
            }
            seen.add(SecurityContextHolder.getContext().getAuthentication().getName() + " " + MDC.get("correlationId"));
            return Map.of("ran", "together");
        });

        List<ToolExecution> ran = tools.run(calls(2), ToolChannel.CHAT, finished -> { });

        assertThat(ran).extracting(ToolExecution::result).containsOnly("{\"ran\":\"together\"}");
        assertThat(seen).containsExactly("minh.vo c-42", "minh.vo c-42");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(user);
    }

    @Test
    @DisplayName("the data scope in force goes with each call: with security off, the request filter's system scope, "
            + "without which every scoped read on a worker would refuse")
    void callsRunInTheCallersScope() {
        DataScopeContext.setSystemScope(new CurrentUser(UUID.randomUUID(), "local-dev", Set.of(), Set.of(),
                DataScope.ALL, Set.of()));
        CyclicBarrier together = new CyclicBarrier(3);
        ParallelTools tools = tools(arguments -> {
            try {
                together.await(10, TimeUnit.SECONDS);
            } catch (Exception notTogether) {
                return Map.of("scope", "alone");
            }
            return Map.of("scope", DataScopeContext.current().map(scope -> scope.user().username()).orElse("none"));
        });

        List<ToolExecution> ran = tools.run(calls(3), ToolChannel.CHAT, finished -> { });

        assertThat(ran).extracting(ToolExecution::result).containsOnly("{\"scope\":\"local-dev\"}");
    }

    @Test
    @DisplayName("at most four calls of a batch run at once; the executions come back in the order of the calls")
    void aBatchIsBounded() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        ParallelTools tools = tools(arguments -> {
            int now = running.incrementAndGet();
            most.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return Map.of("sku", arguments.text("sku", 64));
        });

        List<ToolExecution> ran = tools.run(calls(8), ToolChannel.CHAT, finished -> { });

        assertThat(most.get()).isBetween(2, ParallelTools.PER_BATCH);
        assertThat(ran).extracting(execution -> execution.call().id())
                .containsExactly("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8");
        assertThat(ran).extracting(ToolExecution::result).containsExactly("{\"sku\":\"S-1\"}", "{\"sku\":\"S-2\"}",
                "{\"sku\":\"S-3\"}", "{\"sku\":\"S-4\"}", "{\"sku\":\"S-5\"}", "{\"sku\":\"S-6\"}",
                "{\"sku\":\"S-7\"}", "{\"sku\":\"S-8\"}");
    }

    @Test
    @DisplayName("across batches, at most eight calls run at once on the instance")
    void theInstanceIsBounded() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ParallelTools tools = tools(arguments -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return Map.of();
        });
        List<Thread> questions = new ArrayList<>();
        for (int question = 0; question < 3; question++) {
            questions.add(Thread.ofVirtual().start(() -> tools.run(calls(4), ToolChannel.CHAT, finished -> { })));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (running.get() < ParallelTools.AT_ONCE && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(100);

        assertThat(running.get()).isEqualTo(ParallelTools.AT_ONCE);
        release.countDown();
        for (Thread question : questions) {
            question.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertThat(most.get()).isEqualTo(ParallelTools.AT_ONCE);
    }

    @Test
    @DisplayName("each call is reported as it ends, on the caller's thread; a batch of one runs on the caller's thread")
    void reportsOnTheCallersThread() {
        Thread caller = Thread.currentThread();
        List<Boolean> ranHere = java.util.Collections.synchronizedList(new ArrayList<>());
        ParallelTools tools = tools(arguments -> {
            ranHere.add(Thread.currentThread() == caller);
            return Map.of();
        });
        List<Boolean> reportedHere = new ArrayList<>();

        tools.run(calls(1), ToolChannel.CHAT, finished -> reportedHere.add(Thread.currentThread() == caller));
        tools.run(calls(3), ToolChannel.CHAT, finished -> reportedHere.add(Thread.currentThread() == caller));

        assertThat(ranHere.getFirst()).as("one call runs here").isTrue();
        assertThat(reportedHere).containsExactly(true, true, true, true);
    }

    @Test
    @DisplayName("a tool that fails is its own outcome; the other calls of the batch still answer")
    void aFailureIsItsCallsAlone() {
        ParallelTools tools = tools(arguments -> {
            if (arguments.text("sku", 64).equals("S-2")) {
                throw new IllegalStateException("database gone");
            }
            return Map.of("ok", true);
        });

        List<ToolExecution> ran = tools.run(calls(3), ToolChannel.CHAT, finished -> { });

        assertThat(ran).extracting(ToolExecution::outcome)
                .containsExactly(ToolOutcome.OK, ToolOutcome.FAILED, ToolOutcome.OK);
    }

    @Test
    @DisplayName("interrupted while waiting, the calls not finished are answered as stopped and the interrupt is kept; "
            + "the ones that finished keep their results")
    void interruptedWhileWaiting() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        ParallelTools tools = tools(arguments -> {
            if (arguments.text("sku", 64).equals("S-2")) {
                try {
                    never.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Map.of("late", true);
            }
            return Map.of("ok", true);
        });
        java.util.concurrent.atomic.AtomicReference<List<ToolExecution>> ran =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptKept = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch firstDone = new CountDownLatch(1);
        Thread caller = Thread.ofVirtual().start(() -> {
            ran.set(tools.run(calls(2), ToolChannel.CHAT, finished -> firstDone.countDown()));
            interruptKept.set(Thread.currentThread().isInterrupted());
        });

        assertThat(firstDone.await(10, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(10));
        never.countDown();

        assertThat(ran.get()).extracting(ToolExecution::outcome).containsExactly(ToolOutcome.OK, ToolOutcome.FAILED);
        assertThat(ran.get().get(1).result()).startsWith("NOT_RUN: the answer was stopped");
        assertThat(interruptKept).isTrue();
    }

    @Test
    @DisplayName("once the executor shuts down, a batch still answers: its calls run on the caller's thread")
    void afterShutdownCallsRunHere() throws Exception {
        ParallelTools tools = tools(arguments -> Map.of("ok", true));
        tools.shutdown();

        assertThat(tools.run(calls(3), ToolChannel.CHAT, finished -> { })).extracting(ToolExecution::outcome)
                .containsExactly(ToolOutcome.OK, ToolOutcome.OK, ToolOutcome.OK);
    }

    @Test
    @DisplayName("an exception from around a tool is that call's FAILED; the batch is still answered whole")
    void anUnexpectedFailureIsItsCallsOutcome() {
        AssistantTools broken = org.mockito.Mockito.mock(AssistantTools.class);
        org.mockito.Mockito.when(broken.execute(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("telemetry broke"));

        List<ToolExecution> ran = new ParallelTools(broken).run(calls(2), ToolChannel.CHAT, finished -> { });

        assertThat(ran).extracting(ToolExecution::outcome).containsExactly(ToolOutcome.FAILED, ToolOutcome.FAILED);
        assertThat(new ParallelTools(broken).run(calls(1), ToolChannel.CHAT, finished -> { }))
                .extracting(ToolExecution::outcome).containsExactly(ToolOutcome.FAILED);
    }

    private static List<ToolCall> calls(int count) {
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            calls.add(new ToolCall("c" + i, "probe", "{\"sku\":\"S-" + i + "\"}"));
        }
        return calls;
    }

    private static ParallelTools tools(Function<ToolArguments, Object> body) {
        ToolSpec spec = new ToolSpec("probe", "test tool", ToolSchema.object().string("sku", "a SKU").build());
        AssistantTool tool = new AssistantTool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public String mirrors() {
                return null;
            }

            @Override
            public Object run(ToolArguments arguments) {
                return body.apply(arguments);
            }
        };
        return new ParallelTools(new AssistantTools(List.of(tool), new ObjectMapper()));
    }
}
