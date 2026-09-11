package com.stockflow.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;

import java.util.Arrays;
import java.util.Map;

/**
 * Makes asynchronous work observable and its failures visible.
 *
 * <h2>Carrying the MDC across the thread boundary</h2>
 *
 * <p>The correlation id lives in a {@code ThreadLocal}, so the moment work moves to another thread
 * — an {@code @Async} method, an {@code @ApplicationModuleListener} — it is gone. Every log line
 * the listener writes then has an empty correlation id, and the one place you most want to trace a
 * request (the part that happened after the response was already sent) is the one place you
 * cannot. {@link #mdcPropagatingTaskDecorator} copies the context onto the worker thread and
 * restores whatever was there afterwards.</p>
 *
 * <p>The restore matters as much as the copy: worker threads are pooled, so leaving the context
 * behind would tag the next unrelated task with this request's correlation id.</p>
 *
 * <h2>Why the executor itself is not replaced</h2>
 *
 * <p>{@link #getAsyncExecutor()} deliberately returns null. {@code spring.threads.virtual.enabled}
 * is on, so Spring Boot supplies a virtual-thread executor; returning a
 * {@code ThreadPoolTaskExecutor} here would silently override it and put the application back on a
 * bounded pool of platform threads. Boot applies a {@link TaskDecorator} bean to whichever executor
 * it builds, so declaring one is enough — and it keeps working if the virtual-thread setting is
 * ever flipped.</p>
 *
 * <h2>Failures that would otherwise disappear</h2>
 *
 * <p>An exception from a {@code void @Async} method has nowhere to go: there is no caller left to
 * receive it. Spring's default logs it at a level and with a message that is easy to miss.
 * {@link #getAsyncUncaughtExceptionHandler()} logs it at {@code ERROR} with the method and its
 * arguments, so a silently failing listener is findable.</p>
 */
@Configuration(proxyBeanMethods = false)
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Null on purpose - see the class javadoc. Overriding this would disable virtual threads.
     */
    @Override
    public java.util.concurrent.Executor getAsyncExecutor() {
        return null;
    }

    @Bean
    public TaskDecorator mdcPropagatingTaskDecorator() {
        return runnable -> {
            // Captured on the SUBMITTING thread, at submission time. Reading it inside the
            // returned Runnable would read the worker's context, which is exactly what is missing.
            Map<String, String> submitterContext = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (submitterContext != null) {
                    MDC.setContextMap(submitterContext);
                }
                try {
                    runnable.run();
                } finally {
                    // Restore rather than clear: on a pooled thread the previous context belongs
                    // to whatever ran before, and clearing it would break that task's logging too.
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> log.error(
                "Uncaught exception in async method {}.{} with arguments {}",
                method.getDeclaringClass().getSimpleName(), method.getName(),
                Arrays.toString(params), throwable);
    }
}
