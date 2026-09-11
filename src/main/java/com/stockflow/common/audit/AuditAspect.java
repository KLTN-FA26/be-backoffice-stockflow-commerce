package com.stockflow.common.audit;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.web.CorrelationIdFilter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Turns {@link Auditable} into an entry in the audit trail.
 *
 * <h2>Ordering: inside the permission guard, outside the transaction</h2>
 *
 * <p>{@code @Order(100)} places this <b>inside</b> {@code RequiresPermissionAspect} (order 0). That
 * is deliberate: a request rejected for lack of permission never reaches the method, so it produces
 * no audit entry from here. Failed <i>authorisation</i> is a security-log concern, not a business
 * audit one, and mixing the two makes the audit table unreadable.</p>
 *
 * <p>It is <b>outside</b> {@code @Transactional}, and not by choice — Spring's transaction advisor
 * is registered at {@code Ordered.LOWEST_PRECEDENCE} ({@code Integer.MAX_VALUE}) unless
 * {@code @EnableTransactionManagement(order = ...)} says otherwise, and this application does not
 * declare that annotation at all. No order value can put an aspect inside it. Anything claiming
 * "the audit entry commits in the same transaction as the change" would therefore be false, whether
 * the annotation sits on a controller or on a service.</p>
 *
 * <p>What actually happens is better than the alternative reading suggests: {@code proceed()}
 * returns only after the audited method's transaction has committed, so a SUCCESS entry always
 * describes a change that is already durable. The remaining gap is the reverse one — a change that
 * commits and an audit write that then fails leaves no entry. {@code AuditTrail}'s contract accepts
 * that trade knowingly: a missing audit row is recoverable from the log, and the entry is written
 * there in full; a lost business transaction is not.</p>
 *
 * <h2>Where to put {@code @Auditable}</h2>
 *
 * <p>Either the controller method (next to the permission guard, where it usually reads most
 * naturally) or the service method. The transactional semantics are the same in both places, for
 * the ordering reason above. Prefer whichever gives the {@code resourceId} expression a clean
 * argument to read.</p>
 *
 * <p>Every entry, success or failure, commits in its own transaction — see {@code AuditWriter}.</p>
 */
@Aspect
@Component
@Order(100)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    /** Parsing SpEL is not free and the expressions are fixed at compile time. */
    private static final ConcurrentMap<String, Expression> EXPRESSIONS = new ConcurrentHashMap<>();

    private final AuditTrail auditTrail;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public AuditAspect(AuditTrail auditTrail, CurrentUserProvider currentUserProvider, Clock clock) {
        this.auditTrail = auditTrail;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    @Around("@annotation(com.stockflow.common.audit.Auditable)")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Auditable auditable = AnnotationUtils.findAnnotation(method, Auditable.class);
        if (auditable == null) {
            return joinPoint.proceed();
        }

        try {
            Object result = joinPoint.proceed();
            record(auditable, joinPoint, method, AuditEntry.Outcome.SUCCESS, null);
            return result;
        } catch (Throwable failure) {
            if (auditable.includeFailures()) {
                record(auditable, joinPoint, method, AuditEntry.Outcome.FAILURE, failure);
            }
            throw failure;
        }
    }

    private void record(Auditable auditable, ProceedingJoinPoint joinPoint, Method method,
                        AuditEntry.Outcome outcome, Throwable failure) {
        try {
            Optional<CurrentUser> user = currentUserProvider.current();
            auditTrail.record(new AuditEntry(
                    user.map(CurrentUser::userId).orElse(null),
                    user.map(CurrentUser::username).orElse("system"),
                    auditable.action(),
                    auditable.resourceType(),
                    resolveResourceId(auditable, joinPoint, method),
                    outcome,
                    MDC.get(CorrelationIdFilter.MDC_KEY),
                    clientAddress(),
                    describe(failure),
                    clock.instant()));
        } catch (RuntimeException ex) {
            // Belt and braces. AuditTrail.record is contractually not allowed to throw, but this
            // aspect wraps business operations and must not be the thing that fails one.
            log.error("Failed to record audit entry for {}.{}",
                    method.getDeclaringClass().getSimpleName(), method.getName(), ex);
        }
    }

    /**
     * Evaluates the {@code resourceId} expression against the method's arguments.
     *
     * <p>A bad expression must never fail the audited operation — approving a purchase order should
     * not break because somebody wrote {@code "#od"} instead of {@code "#id"}. It returns null and
     * logs, so the entry is still written and the mistake is visible.</p>
     */
    private String resolveResourceId(Auditable auditable, ProceedingJoinPoint joinPoint,
                                     Method method) {
        String expression = auditable.resourceId();
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Expression parsed = EXPRESSIONS.computeIfAbsent(expression, PARSER::parseExpression);
            Object value = parsed.getValue(new MethodBasedEvaluationContext(
                    joinPoint.getTarget(), method, joinPoint.getArgs(), PARAMETER_NAMES));
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException ex) {
            log.warn("Audit resourceId expression '{}' on {}.{} could not be evaluated: {}",
                    expression, method.getDeclaringClass().getSimpleName(), method.getName(),
                    ex.toString());
            return null;
        }
    }

    /**
     * The exception type and message, as the entry's details.
     *
     * <p>The message only — no stack trace. A stack trace in an audit row is noise that makes the
     * table expensive to store and hard to read; the correlation id on the entry leads to the log,
     * where the trace already is.</p>
     */
    private static String describe(Throwable failure) {
        if (failure == null) {
            return null;
        }
        String message = failure.getMessage();
        return "{\"error\":\"%s\",\"message\":%s}".formatted(
                failure.getClass().getSimpleName(), quote(message));
    }

    /** Minimal JSON string escaping, so a message containing a quote cannot break the details. */
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Null outside a web request - a scheduled job has no client address, and that is fine. */
    private static String clientAddress() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
