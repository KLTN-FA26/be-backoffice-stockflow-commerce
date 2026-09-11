package com.stockflow.common.security;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Enforces {@link RequiresPermission}, and establishes the {@link DataScope} the guarded endpoint
 * declared.
 *
 * <p>Two responsibilities in one aspect on purpose: the permission answers "may you do this?" and
 * the scope answers "to which rows?", and they must be decided together from the same annotation.
 * Splitting them across two advices would allow a state where one ran and the other did not.</p>
 *
 * <p>It is bound to the same {@code stockflow.security.enabled} switch as the filter chain, so
 * there is never a state where the security chain is off but the aspect still rejects everything -
 * a mismatch that would waste an afternoon of somebody's life.</p>
 *
 * <h2>Why {@code @Around} and not {@code @Before}</h2>
 *
 * <p>The scope lives in a thread-local, and a thread-local set without a matching clear leaks into
 * the next request on the same pooled thread — one user reading another user's rows, intermittently
 * and untraceably. {@code @Before} has no way to run cleanup. {@code @Around} gives the
 * {@code finally} block that makes the pairing impossible to get wrong.</p>
 *
 * <h2>Ordering</h2>
 *
 * <p>{@code @Order(0)} so this runs outside any other advice on the same method — in particular
 * outside {@code @Transactional}. Rejecting an unauthorised call before a database transaction
 * opens saves a connection, and means a denied request cannot leave a transaction to roll back.</p>
 */
@Aspect
@Component
@Order(0)
@ConditionalOnProperty(prefix = "stockflow.security", name = "enabled", havingValue = "true")
public class RequiresPermissionAspect {

    private final PermissionChecker permissionChecker;
    private final CurrentUserProvider currentUserProvider;

    public RequiresPermissionAspect(PermissionChecker permissionChecker,
                                    CurrentUserProvider currentUserProvider) {
        this.permissionChecker = permissionChecker;
        this.currentUserProvider = currentUserProvider;
    }

    @Around("@annotation(com.stockflow.common.security.RequiresPermission)")
    public Object enforce(ProceedingJoinPoint joinPoint) throws Throwable {
        RequiresPermission required = resolve(joinPoint);

        // 1. May the caller perform this action at all? Throws PermissionDeniedException (403).
        permissionChecker.require(PermissionCode.of(required.resource(), required.action()));

        // 2. On which rows? Established here and read much further down by ScopedJpaRepository.
        CurrentUser user = currentUserProvider.current().orElseThrow(() -> new ScopeViolationException(
                ("Permission %s:%s was granted but no authenticated user is present. This means the "
                        + "permission arrived as an authority without a JWT behind it, which should "
                        + "be impossible - check StockflowJwtAuthenticationConverter.")
                        .formatted(required.resource(), required.action())));

        // Save and restore rather than clear. A guarded method calling another guarded method
        // through a proxy would otherwise have its scope wiped by the inner finally, and every
        // subsequent scoped query in the OUTER method would throw ScopeViolationException - a 403
        // on a request the caller was entitled to make. Nothing does that today; this is the base
        // class for endpoints nobody has written yet.
        DataScopeContext.Scope previous = DataScopeContext.current().orElse(null);
        DataScopeContext.set(required.scope(), user);
        try {
            return joinPoint.proceed();
        } finally {
            // Non-negotiable. Servlet threads are pooled; a scope left behind is inherited by the
            // next request on this thread, which is one user reading another user's rows.
            DataScopeContext.restore(previous);
        }
    }

    /**
     * Re-read the annotation the pointcut matched on — and fail closed if it cannot be found.
     *
     * <h2>Why this is not just {@code findAnnotation(signature.getMethod())}</h2>
     *
     * <p>{@code MethodSignature.getMethod()} returns the method the {@code MethodInvocation}
     * carries, which under a JDK dynamic proxy is the <b>interface</b> method. For a bean behind an
     * interface with the annotation on the implementation, the pointcut fires and that lookup
     * returns {@code null}. Spring Boot defaults {@code spring.aop.proxy-target-class} to true, so
     * CGLIB is used and the situation does not arise today — but it is one line of configuration
     * away, and a security guard whose failure mode is "carry on unguarded" is the wrong shape
     * regardless. {@code getMostSpecificMethod} against the real target class resolves it properly;
     * throwing covers whatever is left.</p>
     */
    private static RequiresPermission resolve(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object target = joinPoint.getTarget();
        Method specific = target == null
                ? method
                : AopUtils.getMostSpecificMethod(method, AopProxyUtils.ultimateTargetClass(target));
        RequiresPermission required =
                AnnotatedElementUtils.findMergedAnnotation(specific, RequiresPermission.class);
        if (required == null) {
            throw new IllegalStateException(
                    ("@RequiresPermission advice fired on %s#%s but the annotation could not be "
                            + "resolved. Refusing to run the method unguarded.")
                            .formatted(specific.getDeclaringClass().getName(), specific.getName()));
        }
        return required;
    }
}
