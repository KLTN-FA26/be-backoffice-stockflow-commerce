package com.stockflow.common.security;

import java.util.Optional;

/**
 * Carries the row scope required by the endpoint currently being served, from the guard that
 * declared it down to the query that must apply it.
 *
 * <h2>Why a thread-local, which is normally a bad idea</h2>
 *
 * <p>The scope is declared on the controller — {@code @RequiresPermission(scope = WAREHOUSE)} —
 * and needed in the repository, several layers below. The alternative is to thread a
 * {@code DataScope} parameter through every service and query method between them. That was
 * considered and rejected for a specific reason: it makes applying the scope <b>optional</b>. Any
 * author who does not pass the parameter gets a query that compiles, runs, and returns every row.
 * A thread-local makes the scope ambient, and lets {@link ScopedJpaRepository} fail when it is
 * missing rather than quietly returning too much.</p>
 *
 * <p>The cost of a thread-local is real and is handled explicitly:</p>
 * <ul>
 *   <li><b>Leaking between requests.</b> Servlet threads are pooled, so a value left behind is
 *       inherited by the next request — a user seeing another user's scope. {@link #clear()} is
 *       called from a {@code finally} in {@link RequiresPermissionAspect}, and the filter chain
 *       clears it again at the end of the request as a backstop.</li>
 *   <li><b>Not crossing to async threads.</b> An {@code @Async} listener or a {@code @Scheduled}
 *       job runs on a different thread and sees no scope at all. That is the correct behaviour, not
 *       a limitation: a background job acts as the system, not as a user, and should call the
 *       unscoped repository methods deliberately rather than inherit whoever happened to trigger
 *       it.</li>
 * </ul>
 *
 * <p>Virtual threads (enabled in {@code application.yml}) do not change any of this — a
 * {@code ThreadLocal} on a virtual thread is per-request by construction, which is if anything
 * safer than the pooled case.</p>
 */
public final class DataScopeContext {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private DataScopeContext() {
    }

    /**
     * What the current request is allowed to see.
     *
     * @param required the scope the endpoint declared
     * @param user     the authenticated caller, whose own scope caps {@code required}
     */
    public record Scope(DataScope required, CurrentUser user) {

        public Scope {
            java.util.Objects.requireNonNull(required, "required");
            java.util.Objects.requireNonNull(user, "user");
        }

        /**
         * The scope actually applied: the narrower of what the endpoint asked for and what the user
         * holds.
         *
         * <p>Taking the minimum is the whole safety property. An endpoint declaring
         * {@code scope = ALL} does not grant ALL — it says "this endpoint is capable of returning
         * everything, if the caller is entitled to everything". A clerk with WAREHOUSE scope
         * calling it still gets WAREHOUSE. Getting this backwards would turn every admin endpoint
         * into a data leak.</p>
         */
        public DataScope effective() {
            return required.isBroaderThan(user.scope()) ? user.scope() : required;
        }
    }

    /** Called by {@link RequiresPermissionAspect} once the permission check has passed. */
    public static void set(DataScope required, CurrentUser user) {
        CURRENT.set(new Scope(required, user));
    }

    public static Optional<Scope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The scope in force, or {@link DataScope#ALL} when there is no request context.
     *
     * <p>Used by code that runs outside a request — a scheduled job, an event listener, a
     * migration. Those act as the system and legitimately see everything. It is a separate method
     * from {@link #current()} so that "no scope" and "system scope" cannot be confused at a call
     * site: {@link ScopedJpaRepository} treats the first as an error.</p>
     */
    public static DataScope systemOrCurrent() {
        return current().map(Scope::effective).orElse(DataScope.ALL);
    }

    /**
     * MUST be called in a {@code finally} block by whoever called {@link #set}.
     *
     * <p>On a pooled thread, forgetting this hands the next request the previous user's scope.</p>
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Put back a scope captured before {@link #set}, or clear if there was none.
     *
     * <p>The counterpart to {@code set} for advice that may nest. {@link #clear()} on its own is
     * only correct at the outermost level: an inner guarded call finishing would otherwise wipe the
     * scope its caller is still relying on, and every scoped query after that point in the outer
     * method throws.</p>
     *
     * @param previous the value {@link #current()} returned before {@code set}, or {@code null}
     */
    public static void restore(Scope previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /**
     * Install a scope for a thread that has none — a background job, a test, or local development
     * with the security aspect switched off.
     *
     * <p>Named for what it is. Anything that calls this is asserting that it has authorised the
     * work by some other means, and every call site should be findable with one grep.</p>
     */
    public static void setSystemScope(CurrentUser systemUser) {
        CURRENT.set(new Scope(DataScope.ALL, systemUser));
    }

    /**
     * Runs {@code action} with no scope in force, then restores whatever was there.
     *
     * <h2>What "unscoped" means here, precisely</h2>
     *
     * <p>It removes the thread-local. It does <b>not</b> widen the scope to ALL. So inside the
     * supplier, {@link #systemOrCurrent()} answers {@link DataScope#ALL} — but a
     * {@code ScopedJpaRepository.findAllInScope} call still throws {@link ScopeViolationException},
     * because {@link DataScopeSpecifications#forCurrentUser} requires a scope to be present and
     * treats its absence as a programming error rather than as permission.</p>
     *
     * <p>That is deliberate. Reading across the boundary is done by calling the repository's
     * {@code *Unscoped} methods, which say so at the call site. This helper covers the other half:
     * running work that must not inherit the caller's scope, where silently picking one up would be
     * the bug — publishing an event, or invoking a service that decides its own scope.</p>
     *
     * <p>Named so it is greppable, because every use of it is a decision somebody should be able to
     * find and review.</p>
     */
    public static <T> T callUnscoped(java.util.function.Supplier<T> action) {
        Scope saved = CURRENT.get();
        CURRENT.remove();
        try {
            return action.get();
        } finally {
            restore(saved);
        }
    }
}
