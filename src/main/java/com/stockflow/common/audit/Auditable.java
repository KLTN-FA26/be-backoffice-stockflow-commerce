package com.stockflow.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records that this operation happened, who did it, and to what.
 *
 * <h2>What this is, next to the {@code created_by}/{@code last_modified_by} columns</h2>
 *
 * <p>{@link com.stockflow.common.persistence.AuditableEntity} answers "who last touched this row".
 * That is not enough for BRD 3.19.3, for two reasons: it keeps only the <b>latest</b> change, so
 * the sequence is lost, and it says nothing about operations that change no row — an export, a
 * failed approval, a permission grant that was refused. The audit trail is an append-only record of
 * <i>actions</i>; the columns are a property of <i>rows</i>. Both are needed.</p>
 *
 * <pre>
 * &#64;PostMapping("/{id}/approval")
 * &#64;Auditable(action = AuditAction.APPROVE, resourceType = "purchase-order", resourceId = "#id")
 * ApiResponse&lt;Void&gt; approve(&#64;PathVariable UUID id) { ... }
 * </pre>
 *
 * <h2>What to annotate</h2>
 *
 * <p>Money, stock, permissions and anything a regulator or an auditor would ask about: approvals,
 * price changes, stock adjustments, role grants, exports, cancellations. Not reads of ordinary
 * data — auditing every {@code GET} produces a table nobody can search and hides the entries that
 * matter. An export is the exception: it is a read, and it takes the whole table out of the
 * building.</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    AuditAction action();

    /** What kind of thing was acted on: {@code "purchase-order"}, {@code "user"}, {@code "price"}. */
    String resourceType();

    /**
     * SpEL over the method's arguments naming the affected record, e.g. {@code "#id"} or
     * {@code "#command.orderId()"}.
     *
     * <p>Optional, because some audited actions have no single subject — a bulk price import, a
     * report export. An entry with no resource id is still worth having: it says the action
     * happened.</p>
     */
    String resourceId() default "";

    /**
     * Whether to record an entry when the method throws.
     *
     * <p>Default true, and worth keeping. A <b>refused</b> approval or a failed permission change is
     * often more interesting than a successful one — a run of them is what an intrusion looks
     * like.</p>
     */
    boolean includeFailures() default true;
}
