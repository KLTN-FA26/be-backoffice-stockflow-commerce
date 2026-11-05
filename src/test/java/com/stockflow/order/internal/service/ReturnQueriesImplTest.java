package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.ReturnQueries;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link ReturnQueries} reads the returns screen's own back-office list and hands on a row without its ids. */
class ReturnQueriesImplTest {

    private final ReturnRequestQueries returns = mock(ReturnRequestQueries.class);
    private final com.stockflow.common.security.CurrentUserProvider users =
            mock(com.stockflow.common.security.CurrentUserProvider.class);
    private final ReturnQueriesImpl queries = new ReturnQueriesImpl(returns, users);

    @Test
    @DisplayName("the api's statuses are the domain's, and the open ones are those ReturnStatus.isOpen names")
    void vocabulary() {
        assertThat(ReturnQueries.STATUSES).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ReturnStatus.values()).map(Enum::name).toList());
        assertThat(ReturnQueries.OPEN_STATUSES).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ReturnStatus.values()).filter(ReturnStatus::isOpen).map(Enum::name).toList());
    }

    @Test
    @DisplayName("DEADLINE_FIRST sorts by the deadline, nearest first; otherwise the screen's own order")
    void order() {
        UUID customer = UUID.randomUUID();
        ReturnRequestQueries.Item item = new ReturnRequestQueries.Item(UUID.randomUUID(), "RMA-20261101-0001",
                UUID.randomUUID(), "SO-20261020-000001", customer, ReturnStatus.AWAITING_RETURN,
                ReturnReason.PRINT_DEFECT, ReturnResolution.REPLACEMENT, "PORTAL",
                Instant.parse("2026-11-01T02:00:00Z"), Instant.parse("2026-11-08T17:00:00Z"), null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "VND", false, null, null, 3);
        when(returns.list(List.of(ReturnStatus.AWAITING_RETURN), customer, null, "RMA", null, 0, 10,
                "returnDeadline,asc;requestedAt,desc")).thenReturn(PageResponse.of(List.of(item), 0, 10, 1));
        when(returns.list(List.of(), null, null, null, null, 0, 10, null))
                .thenReturn(PageResponse.of(List.of(), 0, 10, 0));

        var page = queries.returns(List.of("AWAITING_RETURN"), customer, " RMA ", true, 10);

        assertThat(page.items()).singleElement().satisfies(found -> {
            assertThat(found.status()).isEqualTo("AWAITING_RETURN");
            assertThat(found.reason()).isEqualTo("PRINT_DEFECT");
            assertThat(found.requestedResolution()).isEqualTo("REPLACEMENT");
            assertThat(found.customerId()).isEqualTo(customer);
        });
        assertThat(queries.returns(List.of(), null, "  ", false, 10).items()).isEmpty();
    }

    @Test
    @DisplayName("a portal customer is refused here, as on the screen: the list is every customer's")
    void backOfficeOnly() {
        when(users.current()).thenReturn(java.util.Optional.of(new com.stockflow.common.security.CurrentUser(
                UUID.randomUUID(), "customer", java.util.Set.of(com.stockflow.common.security.Role.CUSTOMER),
                java.util.Set.of(), com.stockflow.common.security.DataScope.OWN, java.util.Set.of())));

        assertThatThrownBy(() -> queries.returns(List.of(), null, null, false, 5))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(returns, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("a status outside the list or a limit out of range is VALIDATION_FAILED, before anything is read")
    void refusals() {
        assertThatThrownBy(() -> queries.returns(List.of("REFUNDED"), null, null, false, 5))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> queries.returns(List.of(), null, null, false, 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(returns, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }
}
