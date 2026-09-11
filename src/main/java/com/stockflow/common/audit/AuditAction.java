package com.stockflow.common.audit;

/**
 * The closed set of things an audit entry can record.
 *
 * <p>An enum rather than free text for the same reason
 * {@link com.stockflow.common.security.Action} is: a free-text column fills up with
 * {@code "approve"}, {@code "APPROVED"} and {@code "approval"} meaning one thing, and then no
 * report can group by it and no auditor can trust it.</p>
 *
 * <p>Distinct from {@code security.Action}, which is about <i>permission</i> to do something.
 * This is about <i>having done</i> it, and the two sets differ: there is no {@code VIEW_PAGE} here,
 * and there is a {@code LOGIN} that no permission guards.</p>
 */
public enum AuditAction {

    CREATE,
    UPDATE,
    DELETE,

    /** A business sign-off: a purchase order, a refund, a stock write-off. */
    APPROVE,
    REJECT,

    /** Bulk data leaving the system. Audited even though it is a read. */
    EXPORT,

    /** A state transition that is not a plain update: cancel, ship, close. */
    TRANSITION,

    LOGIN,
    LOGOUT,

    /** A role or permission was granted or revoked. */
    GRANT,
    REVOKE;

    /**
     * Whether an entry for this action should be kept beyond the ordinary retention window.
     *
     * <p>Security-relevant actions are the ones an investigation needs a year later, long after
     * routine entries have been purged.</p>
     */
    public boolean isSecurityRelevant() {
        return switch (this) {
            case LOGIN, LOGOUT, GRANT, REVOKE, EXPORT, DELETE -> true;
            case CREATE, UPDATE, APPROVE, REJECT, TRANSITION -> false;
        };
    }
}
