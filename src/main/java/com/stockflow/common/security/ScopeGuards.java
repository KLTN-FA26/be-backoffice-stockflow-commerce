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
        boolean staff = user != null && user.roles().stream().anyMatch(role -> role != Role.CUSTOMER);
        if (!staff) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This list is for the back office");
        }
    }
}
