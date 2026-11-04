package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.common.config.ThreadContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a batch of tool calls at once: a model step's calls (design §3.1), the today card's fixed reads. The calls of
 * one batch do not depend on each other — the prompt asks the model to call together what it can look up together —
 * so their waits overlap instead of adding up.
 *
 * <p><b>Each call runs as it would on the caller's thread.</b> A worker is handed the security context, the locale,
 * the MDC, the trace and the data scope in force ({@link ThreadContext#carryWithScope}): the tool's own
 * {@code @RequiresPermission} checks the user and sets the scope it needs, then puts the handed one back, as on the
 * caller's thread; with the security aspect switched off, the handed scope is the system scope the request filter
 * installed, without which every scoped read would refuse. Nothing else of the request is used by a tool.</p>
 *
 * <p><b>Bounded twice.</b> At most {@value #PER_BATCH} calls of one batch run at a time, and at most
 * {@value #AT_ONCE} across the instance: each holds a database connection while it reads, and the connection pool
 * (10 to 30 by profile) is the screens' too. A call waiting for a place waits on a virtual thread; a batch of one runs
 * on the caller's thread, under the same instance-wide bound.</p>
 *
 * <p><b>Every call gets an execution.</b> A tool's own failure is its outcome already ({@link AssistantTools}); what
 * fails around it here becomes one too, so a caller always has a result for each call and an audit row for each run.
 * An unexpected exception from one call is that call's {@code FAILED}. Interrupted while waiting, the calls not yet
 * finished are cancelled and answered {@code FAILED} as stopped, and the interrupt is kept for the caller. A call the
 * executor refuses, as it shuts down, runs on the caller's thread instead.</p>
 *
 * <p><b>The order is the caller's.</b> The executions come back in the order of the calls, whichever finished first,
 * so what a model is sent does not depend on timing. Only {@code finished} sees completion order: it is told of each
 * call as it ends, always on the caller's thread, so a streamed answer's emitter is written by one thread.</p>
 */
@Component
class ParallelTools {

    private static final Logger log = LoggerFactory.getLogger(ParallelTools.class);

    /** Calls of one batch running at once: four keep one question from holding much of the connection pool. */
    static final int PER_BATCH = 4;

    /** Calls running at once across every question and card of this instance, below every profile's pool. */
    static final int AT_ONCE = 8;

    private final AssistantTools tools;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    /** Fair, so a question that has waited is not overtaken by every later one. */
    private final Semaphore places = new Semaphore(AT_ONCE, true);

    ParallelTools(AssistantTools tools) {
        this.tools = tools;
    }

    /**
     * @param calls    run through {@link AssistantTools#execute(ToolCall, ToolChannel)}
     * @param finished told of each execution as it ends, on this thread
     * @return one execution per call, in the order of {@code calls}; never throws but an {@link Error}
     */
    List<ToolExecution> run(List<ToolCall> calls, ToolChannel channel, Consumer<ToolExecution> finished) {
        if (calls.isEmpty()) {
            return List.of();
        }
        ToolExecution[] executions = new ToolExecution[calls.size()];
        if (calls.size() == 1) {
            executions[0] = guarded(calls.getFirst(), channel);
            finished.accept(executions[0]);
            return List.of(executions);
        }
        Semaphore batch = new Semaphore(PER_BATCH);
        CompletionService<Done> done = new ExecutorCompletionService<>(threads);
        List<Future<Done>> running = new ArrayList<>(calls.size());
        int submitted = 0;
        for (int i = 0; i < calls.size(); i++) {
            int index = i;
            try {
                // The worker hands its execution back through the future and never writes the array: a call the
                // caller stopped waiting for cannot overwrite the answer it was given instead.
                running.add(done.submit(ThreadContext.carryWithScope(() -> {
                    batch.acquire();
                    try {
                        return new Done(index, guarded(calls.get(index), channel));
                    } finally {
                        batch.release();
                    }
                })));
                submitted++;
            } catch (RejectedExecutionException shuttingDown) {
                executions[index] = guarded(calls.get(index), channel);
                finished.accept(executions[index]);
            }
        }
        try {
            for (int ended = 0; ended < submitted; ended++) {
                Done next = next(done);
                executions[next.index()] = next.execution();
                finished.accept(next.execution());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.forEach(call -> call.cancel(true));
            for (int i = 0; i < executions.length; i++) {
                if (executions[i] == null) {
                    executions[i] = stopped(calls.get(i));
                    finished.accept(executions[i]);
                }
            }
        }
        return List.of(executions);
    }

    /**
     * One call, once one of the instance's places is free. Whatever it throws but an {@link Error} is its
     * {@code FAILED}: the call is answered, and the others of its batch are not lost with it.
     */
    private ToolExecution guarded(ToolCall call, ToolChannel channel) {
        try {
            places.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return stopped(call);
        }
        try {
            return tools.execute(call, channel);
        } catch (RuntimeException failed) {
            log.error("Assistant tool {} failed outside its own handling", call.name(), failed);
            return new ToolExecution(call, ToolOutcome.FAILED, "FAILED: the lookup could not be completed", 0);
        } finally {
            places.release();
        }
    }

    /** One call ended: where it goes, and how it ended. */
    private record Done(int index, ToolExecution execution) {
    }

    /** The next call to end; a task fails only with an {@link Error}, or interrupted once cancelled. */
    private static Done next(CompletionService<Done> done) throws InterruptedException {
        try {
            return done.take().get();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            if (failed.getCause() instanceof InterruptedException interrupted) {
                // Only a task cancelled by its caller is interrupted, and that caller no longer takes.
                throw interrupted;
            }
            throw new IllegalStateException("a tool task failed outside its call", failed.getCause());
        }
    }

    /** A call the caller stopped waiting for: answered, so the step and its record stay whole. */
    private static ToolExecution stopped(ToolCall call) {
        return new ToolExecution(call, ToolOutcome.FAILED, "NOT_RUN: the answer was stopped before this lookup "
                + "finished", 0);
    }

    /** Lets running calls finish, briefly: an answer still in progress then runs its next calls on its own thread. */
    @PreDestroy
    void shutdown() throws InterruptedException {
        threads.shutdown();
        threads.awaitTermination(10, TimeUnit.SECONDS);
    }
}
