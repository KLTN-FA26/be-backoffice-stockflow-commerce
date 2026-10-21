package com.stockflow.common.security;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/**
 * Guards for back-office endpoints whose rows are not filtered by data scope.
 *
 * <p>A customer holds some of the same permissions as staff — {@code sales-orders:READ} for their
 * own orders, say — and the CUSTOMER role's data scope is ALL: what keeps them to their own rows is
 * the OWN scope their endpoints declare. A back-office list declared with scope ALL and read through
 * a plain repository would therefore hand a customer every row. Such an endpoint calls
 * {@link #requireBackOffice} first, as {@code OrderController} does for the order list.</p>
 */
public final class ScopeGuards {

    private ScopeGuards() {
    }

    /** Refuses a caller who is only a customer (403): this endpoint shows other people's records. */
    public static void requireBackOffice(CurrentUser user) {
        if (!isBackOffice(user)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This list is for the back office");
        }
    }

    /**
     * Whether the caller works for the shop rather than for one customer: anyone signed in who is not a portal
     * customer ({@link CurrentUser#isPortalCustomer}). A data-scope answer - every customer's rows, or only the caller's
     * own customer's - for an endpoint whose permission a customer may hold too. Whether the caller may act at all is
     * the endpoint's permission, never this.
     *
     * <p>Defined as "not a portal customer" rather than "holds a known staff role" because roles created at run time
     * (ADR-0008) are not {@link Role} constants: {@code CurrentUserProvider} leaves them out of {@code roles()}, and a
     * staff member holding only such a role must still be served by whatever its permissions allow. CUSTOMER is never
     * combined with another role (IdentityServiceImpl refuses it: ROLE_NOT_ASSIGNABLE), so the two cannot overlap.</p>
     */
    public static boolean isBackOffice(CurrentUser user) {
        return user != null && !user.isPortalCustomer();
    }
}
