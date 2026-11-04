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
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 * <p>What actually happens is better than the alternative reading suggests: when the audited method
 * begins its own transaction, {@code proceed()} returns only after it has committed, so the SUCCESS
 * entry describes a change that is already durable. The remaining gap is the reverse one — a change
 * that commits and an audit write that then fails leaves no entry. {@code AuditTrail}'s contract
 * accepts that trade knowingly: a missing audit row is recoverable from the log, and the entry is
 * written there in full; a lost business transaction is not.</p>
 *
 * <h2>An audited method inside someone else's transaction</h2>
 *
 * <p>A method can also join a transaction its caller began: one module's {@code api} called from
 * another module's {@code @Transactional} method, or the assistant creating a draft purchase order
 * in the transaction that marks the suggestion confirmed (ADR-0011). Its change then commits with
 * the caller's, later, or not at all. Writing SUCCESS when {@code proceed()} returns would record a
 * creation that a rollback afterwards undid — a goods receipt whose lines the receipt screen refused
 * would stay in the trail as created. So when the method joined a transaction still active as it
 * returns, the entry is built then (who, when, which record) and written once that transaction
 * completes: as SUCCESS if it committed; if it did not, as a FAILURE saying it was rolled back,
 * because the attempt is still something an investigator looks for. A failure is written at once,
 * as before.</p>
 *
 * <p>"Joined" is read from the method's own {@code @Transactional}: one declared
 * {@code REQUIRES_NEW} committed its own work, and one declared {@code NOT_SUPPORTED} or
 * {@code NEVER} ran outside any, so both are recorded at once whatever their caller does next; so is
 * a call made while synchronization is active with no actual transaction. One limit remains: a
 * method that commits through a {@code TransactionTemplate} of its own, with no such declaration,
 * is recorded by its caller's outcome when it has a transactional caller. No audited method does
 * that from inside a transaction today.</p>
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

    /** Reads a method's own {@code @Transactional}, as the transaction advisor does; it caches per method. */
    private static final AnnotationTransactionAttributeSource TRANSACTIONS =
            new AnnotationTransactionAttributeSource(false);

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

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            if (auditable.includeFailures()) {
                write(entry(auditable, joinPoint, method, AuditEntry.Outcome.FAILURE, failure, null), method);
            }
            throw failure;
        }
        AuditEntry success = entry(auditable, joinPoint, method, AuditEntry.Outcome.SUCCESS, null, result);
        if (!joinedEnclosingTransaction(joinPoint, method) || !deferred(auditable, success, method)) {
            write(success, method);
        }
        return result;
    }

    /**
     * Whether the method's change commits with a transaction that is still active as it returns: one is (with
     * synchronization), and the method did not declare a transaction of its own or none at all.
     */
    private static boolean joinedEnclosingTransaction(ProceedingJoinPoint joinPoint, Method method) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionAttribute own = TRANSACTIONS.getTransactionAttribute(method,
                AopUtils.getTargetClass(joinPoint.getTarget()));
        if (own == null) {
            return true;
        }
        int propagation = own.getPropagationBehavior();
        return propagation != TransactionDefinition.PROPAGATION_REQUIRES_NEW
                && propagation != TransactionDefinition.PROPAGATION_NOT_SUPPORTED
                && propagation != TransactionDefinition.PROPAGATION_NEVER;
    }

    /**
     * Writes {@code success} once the transaction the audited method joined completes; false when no synchronization
     * could be registered, and the entry is written now instead.
     */
    private boolean deferred(Auditable auditable, AuditEntry success, Method method) {
        if (success == null) {
            return true;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        write(success, method);
                    } else if (auditable.includeFailures()) {
                        write(rolledBack(success, status), method);
                    }
                }
            });
            return true;
        } catch (IllegalStateException notActive) {
            return false;
        }
    }

    /** The entry of an operation that returned, in a transaction that then did not commit. */
    private static AuditEntry rolledBack(AuditEntry success, int status) {
        String why = status == TransactionSynchronization.STATUS_ROLLED_BACK
                ? "the enclosing transaction rolled back" : "the enclosing transaction's outcome is unknown";
        return new AuditEntry(success.actorId(), success.actorName(), success.action(), success.resourceType(),
                success.resourceId(), AuditEntry.Outcome.FAILURE, success.correlationId(), success.clientAddress(),
                "{\"error\":\"RolledBack\",\"message\":%s}".formatted(quote(why)), success.occurredAt());
    }

    /** The entry, as of now; null when it could not be built, which is logged and never fails the operation. */
    private AuditEntry entry(Auditable auditable, ProceedingJoinPoint joinPoint, Method method,
                             AuditEntry.Outcome outcome, Throwable failure, Object result) {
        try {
            Optional<CurrentUser> user = currentUserProvider.current();
            return new AuditEntry(
                    user.map(CurrentUser::userId).orElse(null),
                    user.map(CurrentUserProvider::auditName).orElse("system"),
                    auditable.action(),
                    auditable.resourceType(),
                    resolveResourceId(auditable, joinPoint, method, result),
                    outcome,
                    MDC.get(CorrelationIdFilter.MDC_KEY),
                    clientAddress(),
                    describe(failure),
                    clock.instant());
        } catch (RuntimeException ex) {
            log.error("Failed to build audit entry for {}.{}",
                    method.getDeclaringClass().getSimpleName(), method.getName(), ex);
            return null;
        }
    }

    private void write(AuditEntry entry, Method method) {
        if (entry == null) {
            return;
        }
        try {
            auditTrail.record(entry);
        } catch (RuntimeException ex) {
            // Belt and braces. AuditTrail.record is contractually not allowed to throw, but this
            // aspect wraps business operations and must not be the thing that fails one.
            log.error("Failed to record audit entry for {}.{}",
                    method.getDeclaringClass().getSimpleName(), method.getName(), ex);
        }
    }

    /**
     * Evaluates the {@code resourceId} expression against the method's arguments, with the return
     * value as {@code #result}.
     *
     * <p>{@code #result} is what makes a creation auditable: the new record's id is generated
     * inside the method, so no argument names it. It is null when the method threw — there is no
     * record to name — which is why such expressions use the null-safe {@code #result?.id()}.</p>
     *
     * <p>A bad expression must never fail the audited operation — approving a purchase order should
     * not break because somebody wrote {@code "#od"} instead of {@code "#id"}. It returns null and
     * logs, so the entry is still written and the mistake is visible.</p>
     */
    private String resolveResourceId(Auditable auditable, ProceedingJoinPoint joinPoint,
                                     Method method, Object result) {
        String expression = auditable.resourceId();
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Expression parsed = EXPRESSIONS.computeIfAbsent(expression, PARSER::parseExpression);
            MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                    joinPoint.getTarget(), method, joinPoint.getArgs(), PARAMETER_NAMES);
            context.setVariable("result", result);
            Object value = parsed.getValue(context);
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
