package com.stockflow.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts the SQL statements Hibernate prepares on the calling thread, for a test that asserts how many statements a
 * request runs (a flat count per page, no N+1).
 *
 * <p>Why not {@code SessionFactory.getStatistics()}: those statistics are the whole SessionFactory's, so the
 * application's own work on other threads in the same context - the after-commit listeners of the events a test has
 * just caused, the scheduled jobs - is counted in a measurement it happens to overlap. On CI, where the suite is long
 * and the machine slower, that noise was large and steady enough that "the least of a few rounds" still measured a page
 * of one at 20 statements beside a page of three at 7 (PaymentListLookupsApiIntegrationTest, 2026-11-28). A MockMvc
 * request runs on the test's own thread, so counting that thread counts the request and nothing else.</p>
 *
 * <p>Use: put {@link #PROPERTY} in the test class's {@code @TestPropertySource}, then {@link #reset()}, run the request,
 * and read {@link #count()}. Hibernate creates the inspector from the class name; the count lives in a thread local,
 * so the instance it creates and the test share it.</p>
 */
public final class ThreadStatements implements StatementInspector {

    /** The property that makes Hibernate call this inspector for every statement it prepares. */
    public static final String PROPERTY =
            "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.stockflow.support.ThreadStatements";

    private static final ThreadLocal<long[]> COUNT = ThreadLocal.withInitial(() -> new long[1]);

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        return sql;
    }

    /** Starts counting this thread's statements from zero. */
    public static void reset() {
        COUNT.get()[0] = 0;
    }

    /** The statements this thread prepared since {@link #reset()}. */
    public static long count() {
        return COUNT.get()[0];
    }
}
