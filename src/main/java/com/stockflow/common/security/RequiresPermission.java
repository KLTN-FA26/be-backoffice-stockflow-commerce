package com.stockflow.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Guards one endpoint with one permission.
 *
 * <p>Chosen over {@code @PreAuthorize("hasPermission('stock-items','CREATE')")} for one concrete
 * reason: {@code action} here is a real {@link Action} enum, so a typo is a compile error. Inside
 * a SpEL string it is a runtime surprise that shows up as a 403 nobody can explain.</p>
 *
 * <p>The resource stays a String constant only because Java annotation arguments must be
 * compile-time constants - the same trade-off as {@link Roles}. Always reference a constant from
 * the service's own resource class, never a bare literal.</p>
 */
@Documented
/*
 * METHOD only, deliberately.
 *
 * TYPE was allowed here originally and it is a trap: the aspect's pointcut is
 * `@annotation(RequiresPermission)`, which matches method-level annotations and nothing else
 * (`@within` is the one that matches types). A class-level @RequiresPermission therefore compiles,
 * passes the startup permission-catalogue validation, logs nothing - and guards NOTHING. Every
 * endpoint on that controller runs with no permission check and no data scope, while reading, to
 * any reviewer, as more carefully guarded than an unannotated one.
 *
 * Removing TYPE turns that silent hole into a compile error.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    String resource();

    Action action();

    /**
     * Row scope this endpoint needs. Enforcement lives in the query layer; declaring it here makes
     * the requirement visible and lets the catalog report which endpoints are scope-sensitive.
     */
    DataScope scope() default DataScope.ALL;
}
