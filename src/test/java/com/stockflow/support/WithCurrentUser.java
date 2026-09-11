package com.stockflow.support;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.DataScopeContext;

/**
 * Installs a {@link CurrentUser} and a {@link DataScope} for the duration of one block, then puts
 * the thread back exactly as it was found.
 *
 * <h2>What this is for</h2>
 *
 * <p>Testing a scoped repository directly. {@code ScopedJpaRepository} reads the scope from a
 * thread-local that {@code RequiresPermissionAspect} normally sets — and the aspect only runs on a
 * guarded controller method. So a test calling the repository or a query service is, from the
 * scope's point of view, a background job with no scope at all, and it gets a
 * {@code ScopeViolationException}. That is correct behaviour and it makes the scope untestable
 * below the HTTP layer without something like this.</p>
 *
 * <pre>
 * WithCurrentUser.run(TestUsers.customer(), DataScope.OWN, () -&gt; {
 *     assertThat(orders.findAllInScope(null)).allMatch(o -&gt; o.getCustomerId().equals(me));
 * });
 * </pre>
 *
 * <h2>Why it restores rather than clears</h2>
 *
 * <p>JUnit reuses threads across tests. Clearing unconditionally would work today and break the
 * moment somebody nests two of these or runs one inside a test that had already set a scope —
 * producing a failure in a <i>different</i> test, which is the worst kind to debug. Saving and
 * restoring makes nesting correct by construction.</p>
 *
 * <p>Note what it does <b>not</b> do: it does not populate Spring Security's
 * {@code SecurityContextHolder}, so {@code PermissionChecker} still sees an anonymous caller. This
 * covers data scope, not the permission matrix. For an end-to-end permission test, drive the
 * endpoint over HTTP with a real token.</p>
 */
public final class WithCurrentUser {

    private WithCurrentUser() {
    }

    /** Runs {@code action} with the given user and scope in force. */
    public static void run(CurrentUser user, DataScope scope, Runnable action) {
        call(user, scope, () -> {
            action.run();
            return null;
        });
    }

    /** Runs {@code action} with the user's own scope, which is the usual case. */
    public static void run(CurrentUser user, Runnable action) {
        run(user, user.scope(), action);
    }

    public static <T> T call(CurrentUser user, DataScope scope, java.util.function.Supplier<T> action) {
        DataScopeContext.Scope previous = DataScopeContext.current().orElse(null);
        DataScopeContext.set(scope, user);
        try {
            return action.get();
        } finally {
            // Restore, do not clear - see the class javadoc.
            if (previous == null) {
                DataScopeContext.clear();
            } else {
                DataScopeContext.set(previous.required(), previous.user());
            }
        }
    }
}
