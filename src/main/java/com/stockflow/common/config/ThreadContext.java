package com.stockflow.common.config;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.slf4j.MDC;
import com.stockflow.common.security.DataScopeContext;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * What a worker thread must carry from the thread that hands it work: the signed-in user, the request's locale, the
 * log's MDC (correlation id, trace id) and the current trace, so a span the worker opens is a child of the request's.
 * Each is a thread-local, and a virtual thread starts with none of them.
 *
 * <p><b>{@link #carry} does not carry the data scope.</b> {@code DataScopeContext} is installed by the permission
 * aspect around each guarded call, from the user in the security context, and restored after it; a worker gets it the
 * same way, by calling through a guarded proxy. Carrying it by default would hand any worker the scope of whatever
 * call happened to be running when the work was handed over. {@link #carryWithScope} does carry it, for a worker that
 * does what this thread would otherwise have done itself, in its place.</p>
 *
 * <p>What the worker had before is put back when the task ends, so a pooled thread never keeps one task's user for
 * the next.</p>
 */
public final class ThreadContext {

    /**
     * The current observation only. Spring Security registers an accessor of its own for the security context, which
     * a full capture would install over the fresh copy below; the locale and the MDC are copied by hand as well.
     */
    private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder()
            .captureKeyPredicate(ObservationThreadLocalAccessor.KEY::equals).build();

    private ThreadContext() {
    }

    /** {@code task}, to run on another thread as this one would. */
    public static Runnable carry(Runnable task) {
        Callable<Void> callable = carry(() -> {
            task.run();
            return null;
        });
        return () -> {
            try {
                callable.call();
            } catch (RuntimeException | Error failed) {
                throw failed;
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        };
    }

    /** {@code task}, to run on another thread as this one would. */
    public static <T> Callable<T> carry(Callable<T> task) {
        SecurityContext security = SecurityContextHolder.getContext();
        // The locale itself, read now: the request's LocaleContext may resolve it from the request lazily, and a worker
        // that outlives the request (a closed tab, a timed-out stream) would read a recycled request.
        LocaleContext locale = new SimpleLocaleContext(LocaleContextHolder.getLocale());
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        ContextSnapshot trace = SNAPSHOTS.captureAll();
        return () -> {
            SecurityContext previousSecurity = SecurityContextHolder.getContext();
            LocaleContext previousLocale = LocaleContextHolder.getLocaleContext();
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            SecurityContext carried = SecurityContextHolder.createEmptyContext();
            carried.setAuthentication(security.getAuthentication());
            SecurityContextHolder.setContext(carried);
            LocaleContextHolder.setLocaleContext(locale);
            setMdc(mdc);
            try (ContextSnapshot.Scope scope = trace.setThreadLocals()) {
                return task.call();
            } finally {
                SecurityContextHolder.setContext(previousSecurity);
                LocaleContextHolder.setLocaleContext(previousLocale);
                setMdc(previousMdc);
            }
        };
    }

    /**
     * {@code task}, to run on another thread exactly as it would run here, the data scope in force included: for a
     * worker that takes over a piece of this thread's own work — one tool call of a step that runs beside the others,
     * a whole answer streamed while the request thread returns. A guarded call on the worker sets its own scope and
     * puts this one back, as it would here. Where nothing sets one — the security aspect switched off, in local
     * development and tests — it is the system scope the request filter installed, without which every scoped read
     * on the worker would refuse ({@code UnsecuredDataScopeFilter}).
     */
    public static <T> Callable<T> carryWithScope(Callable<T> task) {
        DataScopeContext.Scope scope = DataScopeContext.current().orElse(null);
        Callable<T> carried = carry(task);
        return () -> {
            DataScopeContext.Scope previous = DataScopeContext.current().orElse(null);
            DataScopeContext.restore(scope);
            try {
                return carried.call();
            } finally {
                DataScopeContext.restore(previous);
            }
        };
    }

    /** {@code task}, to run on another thread exactly as it would run here: {@link #carryWithScope(Callable)}. */
    public static Runnable carryWithScope(Runnable task) {
        Callable<Void> callable = carryWithScope(() -> {
            task.run();
            return null;
        });
        return () -> {
            try {
                callable.call();
            } catch (RuntimeException | Error failed) {
                throw failed;
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        };
    }

    private static void setMdc(Map<String, String> values) {
        if (values == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(values);
        }
    }
}
