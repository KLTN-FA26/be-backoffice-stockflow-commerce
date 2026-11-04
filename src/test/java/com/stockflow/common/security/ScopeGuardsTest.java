package com.stockflow.common.security;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.support.TestUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A back-office list is refused to a caller who is only a customer, whatever their data scope. */
class ScopeGuardsTest {

    @Test
    @DisplayName("a customer is refused even with scope ALL; any staff role passes")
    void onlyBackOffice() {
        CurrentUser customerWithAll = TestUsers.builder().role(Role.CUSTOMER).scope(DataScope.ALL).build();
        assertThatThrownBy(() -> ScopeGuards.requireBackOffice(customerWithAll))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> ScopeGuards.requireBackOffice(TestUsers.customer())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ScopeGuards.requireBackOffice(null)).isInstanceOf(BusinessException.class);
        assertThatCode(() -> ScopeGuards.requireBackOffice(TestUsers.admin())).doesNotThrowAnyException();
        assertThatCode(() -> ScopeGuards.requireBackOffice(
                TestUsers.builder().role(Role.SALES_STAFF).scope(DataScope.ALL).build())).doesNotThrowAnyException();
    }
}
