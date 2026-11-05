package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.inventory.api.TransferOrderQueries;
import com.stockflow.inventory.api.TransferOrderQueries.TransferOrderSummary;
import com.stockflow.order.api.ReturnQueries;
import com.stockflow.order.api.ReturnQueries.ReturnSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The transfer and return queues (design §4.2, C10): what a row carries and what is worked out for the model. */
class FindTransferOrdersAndReturnsToolTest {

    /** 05/11/2026, 10:00 in Vietnam. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), ZoneOffset.UTC);

    @Nested
    @DisplayName("findTransferOrders")
    class Transfers {

        private final TransferOrderQueries transfers = mock(TransferOrderQueries.class);
        private final FindTransferOrdersTool tool = new FindTransferOrdersTool(transfers, CLOCK);

        private FindTransferOrdersTool.Answer ask(String status, String order) {
            return tool.run(ToolArguments.parse(new ObjectMapper(), "{\"status\":\"" + status
                    + "\",\"transferNumber\":\"\",\"order\":\"" + order + "\",\"limit\":10}"));
        }

        private TransferOrderSummary transfer(String number, String status, LocalDate expected) {
            return new TransferOrderSummary(number, "HCM", "HN", status, expected,
                    Instant.parse("2026-11-01T02:00:00Z"), null, null, null);
        }

        @Test
        @DisplayName("OPEN is every status before COMPLETED, CLOSED and CANCELLED; a total above the rows is truncated")
        void open() {
            when(transfers.transfers(any(), isNull(), eq(false), eq(10))).thenReturn(PageResponse.of(
                    List.of(transfer("TO-1", "IN_TRANSIT", null)), 0, 10, 12));

            FindTransferOrdersTool.Answer answer = ask("OPEN", "NEWEST_FIRST");

            verify(transfers).transfers(TransferOrderQueries.OPEN_STATUSES, null, false, 10);
            assertThat(answer.allLateListed()).as("newest first and cut: a late one may be left out").isFalse();
            assertThat(TransferOrderQueries.OPEN_STATUSES).doesNotContain("COMPLETED", "CLOSED", "CANCELLED");
            assertThat(answer.total()).isEqualTo(12);
            assertThat(answer.truncated()).isTrue();
            assertThat(answer.transfers()).singleElement().satisfies(row -> {
                assertThat(row.fromWarehouseCode()).isEqualTo("HCM");
                assertThat(row.toWarehouseCode()).isEqualTo("HN");
            });
        }

        @Test
        @DisplayName("late is worked out on today's date in Vietnam while goods are awaited, a partly received "
                + "transfer included; lateRows counts them")
        void late() {
            when(transfers.transfers(any(), isNull(), eq(false), eq(10))).thenReturn(PageResponse.of(List.of(
                    transfer("TO-ROAD", "IN_TRANSIT", LocalDate.of(2026, 11, 2)),
                    transfer("TO-NOT-LEFT", "APPROVED", LocalDate.of(2026, 11, 4)),
                    transfer("TO-HALF", "PARTIALLY_RECEIVED", LocalDate.of(2026, 11, 1)),
                    transfer("TO-TODAY", "IN_TRANSIT", LocalDate.of(2026, 11, 5)),
                    transfer("TO-IN", "RECEIVED", LocalDate.of(2026, 10, 1)),
                    transfer("TO-NO-DATE", "PICKING", null)), 0, 10, 6));

            FindTransferOrdersTool.Answer answer = ask("ANY", "NEWEST_FIRST");

            verify(transfers).transfers(List.of(), null, false, 10);
            assertThat(answer.transfers()).extracting(FindTransferOrdersTool.Row::late)
                    .containsExactly(true, true, true, false, false, false);
            assertThat(answer.transfers()).extracting(FindTransferOrdersTool.Row::daysLate)
                    .containsExactly(3L, 1L, 4L, null, null, null);
            assertThat(answer.lateRows()).isEqualTo(3);
            assertThat(answer.allLateListed()).isTrue();
        }

        @Test
        @DisplayName("listed by expected date, a cut list whose last row is not yet due holds every late transfer; "
                + "one whose last row is late may not")
        void expectedFirst() {
            when(transfers.transfers(any(), isNull(), eq(true), eq(10))).thenReturn(PageResponse.of(List.of(
                    transfer("TO-A", "IN_TRANSIT", LocalDate.of(2026, 11, 2)),
                    transfer("TO-B", "APPROVED", LocalDate.of(2026, 11, 9))), 0, 10, 30));

            FindTransferOrdersTool.Answer answer = ask("OPEN", "EXPECTED_FIRST");

            assertThat(answer.truncated()).isTrue();
            assertThat(answer.lateRows()).isEqualTo(1);
            assertThat(answer.allLateListed()).isTrue();

            when(transfers.transfers(any(), isNull(), eq(true), eq(10))).thenReturn(PageResponse.of(List.of(
                    transfer("TO-A", "IN_TRANSIT", LocalDate.of(2026, 11, 2)),
                    transfer("TO-C", "IN_TRANSIT", LocalDate.of(2026, 11, 3))), 0, 10, 30));
            assertThat(ask("OPEN", "EXPECTED_FIRST").allLateListed()).isFalse();
        }
    }

    @Nested
    @DisplayName("findReturns")
    class Returns {

        private final ReturnQueries returns = mock(ReturnQueries.class);
        private final CurrentUserProvider users = mock(CurrentUserProvider.class);
        private final CustomerService customers = mock(CustomerService.class);
        private final FindReturnsTool tool = new FindReturnsTool(returns, users, customers, CLOCK);

        private FindReturnsTool.Answer ask(String status, String customer, String order) {
            return tool.run(ToolArguments.parse(new ObjectMapper(), "{\"status\":\"" + status + "\",\"customer\":\""
                    + customer + "\",\"number\":\"\",\"order\":\"" + order + "\",\"limit\":20}"));
        }

        private void user(Role role, boolean mayListCustomers) {
            Set<PermissionCode> permissions = mayListCustomers
                    ? Set.of(PermissionCode.of(ToolPermissions.CUSTOMERS, Action.READ)) : Set.of();
            when(users.current()).thenReturn(Optional.of(new CurrentUser(UUID.randomUUID(), "staff", Set.of(role),
                    permissions, role == Role.CUSTOMER ? DataScope.OWN : DataScope.ALL, Set.of())));
        }

        /** 00:00 in Vietnam of a day of November 2026. */
        private static Instant tooLateFrom(int day) {
            return LocalDate.of(2026, 11, day).atStartOfDay(ZoneOffset.ofHours(7)).toInstant();
        }

        private static ReturnSummary rma(String number, UUID customer, String status, Instant deadline) {
            return new ReturnSummary(number, "SO-20261020-000001", customer, status, "PRINT_DEFECT", "REPLACEMENT",
                    "PORTAL", Instant.parse("2026-10-30T02:00:00Z"), deadline, null, null, null, "VND", null);
        }

        @Test
        @DisplayName("a portal customer is refused, as on the returns screen: the list is every customer's")
        void backOfficeOnly() {
            user(Role.CUSTOMER, false);

            assertThatThrownBy(() -> ask("OPEN", "", "NEWEST_FIRST")).isInstanceOfSatisfying(BusinessException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            verify(returns, never()).returns(any(), any(), any(), anyBoolean(), anyInt());
        }

        @Test
        @DisplayName("the last day to return is the day before the stored deadline, the start of the first day too "
                + "late; days to it for an approved return only; DEADLINE_FIRST asks for the nearest first; names "
                + "only for a user who may list customers")
        void deadlines() {
            user(Role.WAREHOUSE_STAFF, false);
            UUID customer = UUID.randomUUID();
            // As ReturnPolicy.endOfDays stores them: 00:00 in Vietnam of the first day too late.
            when(returns.returns(any(), isNull(), isNull(), eq(true), eq(20))).thenReturn(PageResponse.of(List.of(
                    rma("RMA-1", customer, "AWAITING_RETURN", tooLateFrom(7)),
                    rma("RMA-2", customer, "AWAITING_RETURN", tooLateFrom(5)),
                    rma("RMA-3", customer, "RECEIVED", tooLateFrom(3))), 0, 20, 3));

            FindReturnsTool.Answer answer = ask("ANY", "", "DEADLINE_FIRST");

            verify(returns).returns(List.of(), null, null, true, 20);
            assertThat(answer.returns()).extracting(FindReturnsTool.Row::lastDayToReturn).containsExactly(
                    LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 2));
            assertThat(answer.returns()).extracting(FindReturnsTool.Row::daysToDeadline).containsExactly(1L, -1L,
                    null);
            assertThat(answer.returns()).extracting(FindReturnsTool.Row::customer).containsOnlyNulls();
            verify(customers, never()).fullNames(any());
        }

        @Test
        @DisplayName("a customer named exactly filters the list and rows carry its name for Sales; OPEN is the five "
                + "statuses still being worked on")
        void byCustomer() {
            user(Role.SALES_STAFF, true);
            UUID abc = UUID.randomUUID();
            when(customers.list(any())).thenReturn(PageResponse.of(List.of(new CustomerSummary(abc, null,
                    "Công ty ABC", null, null, null, null, "ACTIVE", 0, Instant.now())), 0, 50, 1));
            when(returns.returns(any(), eq(abc), isNull(), eq(false), eq(20))).thenReturn(PageResponse.of(
                    List.of(rma("RMA-9", abc, "REQUESTED", null)), 0, 20, 1));
            when(customers.fullNames(any())).thenReturn(Map.of(abc, "Công ty ABC"));

            FindReturnsTool.Answer answer = ask("OPEN", "công ty abc", "NEWEST_FIRST");

            verify(returns).returns(ReturnQueries.OPEN_STATUSES, abc, null, false, 20);
            assertThat(answer.customer().id()).isEqualTo(abc);
            assertThat(answer.returns()).singleElement().satisfies(row -> {
                assertThat(row.customer().name()).isEqualTo("Công ty ABC");
                assertThat(row.daysToDeadline()).isNull();
            });
        }

        @Test
        @DisplayName("a customer the text does not name gives a note, no returns and no count")
        void customerNotNamed() {
            user(Role.SALES_STAFF, true);
            when(customers.list(any())).thenReturn(PageResponse.of(List.of(), 0, 50, 0));

            FindReturnsTool.Answer answer = ask("ANY", "khách lạ", "NEWEST_FIRST");

            assertThat(answer.returns()).isEmpty();
            assertThat(answer.note()).isNotBlank();
            assertThat(answer.total()).isNull();
            verify(returns, never()).returns(any(), any(), any(), anyBoolean(), anyInt());
        }

        @Test
        @DisplayName("a user who may not list customers cannot filter by a name or tax code: the same note whether "
                + "such a customer exists or not, and no customer is looked up")
        void noCustomerFilterWithoutTheList() {
            user(Role.WAREHOUSE_STAFF, false);

            FindReturnsTool.Answer byTaxCode = ask("ANY", "0312345678", "NEWEST_FIRST");

            assertThat(byTaxCode.returns()).isEmpty();
            assertThat(byTaxCode.total()).isNull();
            assertThat(byTaxCode.customer()).isNull();
            assertThat(byTaxCode.note()).contains("customer-customers:READ");
            verify(customers, never()).list(any());
            verify(returns, never()).returns(any(), any(), any(), anyBoolean(), anyInt());
        }
    }
}
