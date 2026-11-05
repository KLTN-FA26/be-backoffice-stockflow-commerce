package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.api.BalancePolicy;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentStatus;
import com.stockflow.order.api.PaymentTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The order work queues (design §4.2): what a list row carries, what it leaves out, and how a hold reason is found. */
class FindOrdersToolTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String NOTE = "Chờ khách chuyển khoản phần còn lại, gọi anh Hùng 0912 345 678 hoặc "
            + "hung@khach.vn trước 17h, nếu không thì chuyển sang tuần sau";

    private final OrderService orders = mock(OrderService.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final com.stockflow.customer.api.CustomerService customers =
            mock(com.stockflow.customer.api.CustomerService.class);
    private final FindOrdersTool tool = new FindOrdersTool(orders, users, customers);

    private FindOrdersTool.Answer ask(String arguments) {
        return tool.run(ToolArguments.parse(new ObjectMapper(), arguments));
    }

    @Test
    @DisplayName("ANY is every status but DRAFT, newest first, filtered by the day placed, with the total of matches")
    void listsByStatusAndDayPlaced() {
        when(orders.list(any())).thenReturn(PageResponse.of(
                List.of(row("SO-1", OrderStatus.PENDING_PAYMENT, null, null), row("SO-2", OrderStatus.CONFIRMED, null,
                        null)), 0, 2, 7));

        FindOrdersTool.Answer answer = ask("{\"status\":\"ANY\",\"holdReason\":\"ANY\",\"placedFrom\":\"2026-10-26\","
                + "\"placedTo\":\"2026-10-28\",\"limit\":2}");

        ArgumentCaptor<ListOrdersQuery> query = ArgumentCaptor.forClass(ListOrdersQuery.class);
        verify(orders).list(query.capture());
        assertThat(query.getValue().statuses()).doesNotContain(OrderStatus.DRAFT)
                .hasSize(OrderStatus.values().length - 1);
        assertThat(query.getValue().placedFrom()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(query.getValue().placedTo()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(query.getValue().size()).isEqualTo(2);
        assertThat(query.getValue().search()).as("the screen's text search matches names and phones").isNull();
        assertThat(query.getValue().sort()).isEqualTo(FindOrdersTool.NEWEST_FIRST);
        assertThat(answer.orders()).extracting(FindOrdersTool.Row::orderNumber).containsExactly("SO-1", "SO-2");
        assertThat(answer.total()).isEqualTo(7);
        assertThat(answer.truncated()).isTrue();
    }

    @Test
    @DisplayName("a row carries the payment and the hold, never the contact block or the address; the note is masked "
            + "and cut")
    void rowLeavesContactsOut() throws Exception {
        when(orders.list(any())).thenReturn(PageResponse.of(
                List.of(row("SO-9", OrderStatus.ON_HOLD, HoldReason.PAYMENT, NOTE)), 0, 50, 1));

        FindOrdersTool.Answer answer = ask("{\"status\":\"ON_HOLD\",\"holdReason\":\"ANY\",\"placedFrom\":\"\","
                + "\"placedTo\":\"\",\"limit\":50}");

        FindOrdersTool.Row row = answer.orders().getFirst();
        assertThat(row.paymentTerm()).isEqualTo("DEPOSIT");
        assertThat(row.paymentStatus()).isEqualTo("PARTIALLY_PAID");
        assertThat(row.hold().reason()).isEqualTo("PAYMENT");
        assertThat(row.hold().statusBeforeHold()).isEqualTo("CONFIRMED");
        assertThat(row.hold().note()).contains("[phone]", "[e-mail]").endsWith("…")
                .hasSizeLessThanOrEqualTo(FindOrdersTool.NOTE_LENGTH + 1);
        String json = JSON.writeValueAsString(answer);
        assertThat(json).doesNotContain("Nguyễn Văn A", "a@khach.vn", "0903123456", "12 Lê Lợi", "0912 345 678",
                "hung@khach.vn");
        assertThat(answer.truncated()).isFalse();
    }

    @Test
    @DisplayName("a hold reason is looked for over every page of held orders: total counts each match, the rows stop "
            + "at the limit")
    void holdReasonOverPages() {
        List<OrderSummary> first = new ArrayList<>();
        IntStream.range(0, FindOrdersTool.SCAN_PAGE).forEach(i -> first.add(row("SO-A" + i, OrderStatus.ON_HOLD,
                i % 50 == 0 ? HoldReason.CREDIT : HoldReason.INVENTORY, null)));
        List<OrderSummary> second = List.of(row("SO-B1", OrderStatus.ON_HOLD, HoldReason.CREDIT, null),
                row("SO-B2", OrderStatus.ON_HOLD, HoldReason.ADDRESS, "sai địa chỉ"));
        when(orders.list(any())).thenAnswer(call -> ((ListOrdersQuery) call.getArgument(0)).page() == 0
                ? PageResponse.of(first, 0, FindOrdersTool.SCAN_PAGE, 102)
                : PageResponse.of(second, 1, FindOrdersTool.SCAN_PAGE, 102));

        FindOrdersTool.Answer answer = ask("{\"status\":\"ANY\",\"holdReason\":\"CREDIT\",\"placedFrom\":\"\","
                + "\"placedTo\":\"\",\"limit\":2}");

        assertThat(answer.orders()).extracting(FindOrdersTool.Row::orderNumber).containsExactly("SO-A0", "SO-A50");
        assertThat(answer.total()).isEqualTo(3);
        assertThat(answer.truncated()).isTrue();
        assertThat(answer.note()).isNull();
        ArgumentCaptor<ListOrdersQuery> query = ArgumentCaptor.forClass(ListOrdersQuery.class);
        verify(orders, times(2)).list(query.capture());
        assertThat(query.getAllValues()).allSatisfy(asked -> {
            assertThat(asked.statuses()).containsExactly(OrderStatus.ON_HOLD);
            assertThat(asked.size()).isEqualTo(FindOrdersTool.SCAN_PAGE);
            assertThat(asked.sort()).isEqualTo(FindOrdersTool.NEWEST_FIRST);
        });
    }

    @Test
    @DisplayName("the scan of held orders stops at its bound and says total may count fewer than match")
    void holdReasonScanIsBounded() {
        when(orders.list(any())).thenAnswer(call -> {
            int page = ((ListOrdersQuery) call.getArgument(0)).page();
            return PageResponse.of(List.of(row("SO-" + page, OrderStatus.ON_HOLD, HoldReason.FRAUD, null)), page,
                    FindOrdersTool.SCAN_PAGE, 5_000);
        });

        FindOrdersTool.Answer answer = ask("{\"status\":\"ON_HOLD\",\"holdReason\":\"FRAUD\",\"placedFrom\":\"\","
                + "\"placedTo\":\"\",\"limit\":50}");

        verify(orders, times(FindOrdersTool.MAX_SCANNED / FindOrdersTool.SCAN_PAGE)).list(any());
        assertThat(answer.total()).isEqualTo(FindOrdersTool.MAX_SCANNED / FindOrdersTool.SCAN_PAGE);
        assertThat(answer.truncated()).isTrue();
        assertThat(answer.note()).contains(String.valueOf(FindOrdersTool.MAX_SCANNED), "FRAUD");
    }

    @Test
    @DisplayName("a hold reason beside another status, or a day not written YYYY-MM-DD, is refused before any query")
    void refusesBadFilters() {
        for (String bad : List.of(
                "{\"status\":\"CONFIRMED\",\"holdReason\":\"CREDIT\",\"placedFrom\":\"\",\"placedTo\":\"\","
                        + "\"limit\":5}",
                "{\"status\":\"ANY\",\"holdReason\":\"ANY\",\"placedFrom\":\"28/10/2026\",\"placedTo\":\"\","
                        + "\"limit\":5}",
                "{\"status\":\"DRAFT\",\"holdReason\":\"ANY\",\"placedFrom\":\"\",\"placedTo\":\"\",\"limit\":5}",
                "{\"status\":\"ANY\",\"holdReason\":\"ANY\",\"placedFrom\":\"\",\"placedTo\":\"\",\"limit\":51}")) {
            assertThatThrownBy(() -> ask(bad)).as(bad)
                    .isInstanceOfSatisfying(BusinessException.class,
                            refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        verify(orders, never()).list(any());
    }

    @Test
    @DisplayName("a portal customer is refused, as on the order screen: the list is every customer's")
    void backOfficeOnly() {
        when(users.current()).thenReturn(Optional.of(new CurrentUser(UUID.randomUUID(), "customer",
                Set.of(Role.CUSTOMER), Set.of(), DataScope.OWN, Set.of())));

        assertThatThrownBy(() -> ask("{\"status\":\"ANY\",\"holdReason\":\"ANY\",\"placedFrom\":\"\",\"placedTo\":\"\","
                + "\"limit\":5}"))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(orders, never()).list(any());
        verify(users, atLeastOnce()).current();
    }

    @Test
    @DisplayName("a customer named exactly filters the list by it, and rows name their customer for Sales, who may "
            + "list customers; one the text does not name exactly returns candidates and no orders")
    void byCustomer() {
        UUID abc = UUID.randomUUID();
        when(users.current()).thenReturn(Optional.of(new CurrentUser(UUID.randomUUID(), "sales",
                Set.of(Role.SALES_STAFF), Set.of(com.stockflow.common.security.PermissionCode.of(
                ToolPermissions.CUSTOMERS, com.stockflow.common.security.Action.READ)), DataScope.ALL, Set.of())));
        when(customers.list(any())).thenReturn(PageResponse.of(List.of(summary(abc, "Công ty ABC")), 0, 50, 1));
        OrderSummary order = row("SO-20261027-000001", OrderStatus.CONFIRMED, null, null);
        when(orders.list(any())).thenReturn(PageResponse.of(List.of(order), 0, 5, 1));
        when(customers.fullNames(any())).thenReturn(java.util.Map.of(order.customerId(), "Công ty ABC"));

        FindOrdersTool.Answer answer = ask("{\"status\":\"ANY\",\"holdReason\":\"ANY\",\"placedFrom\":\"\","
                + "\"placedTo\":\"\",\"customer\":\"công ty abc\",\"limit\":5}");

        org.mockito.ArgumentCaptor<ListOrdersQuery> query = org.mockito.ArgumentCaptor.forClass(ListOrdersQuery.class);
        verify(orders).list(query.capture());
        assertThat(query.getValue().customerId()).isEqualTo(abc);
        assertThat(answer.customer().name()).isEqualTo("Công ty ABC");
        assertThat(answer.orders()).singleElement()
                .satisfies(found -> assertThat(found.customer().name()).isEqualTo("Công ty ABC"));

        when(customers.list(any())).thenReturn(PageResponse.of(List.of(summary(UUID.randomUUID(), "Công ty ABC Hà Nội"),
                summary(UUID.randomUUID(), "Công ty ABC Sài Gòn")), 0, 50, 2));
        FindOrdersTool.Answer vague = ask("{\"status\":\"ANY\",\"holdReason\":\"ANY\",\"placedFrom\":\"\","
                + "\"placedTo\":\"\",\"customer\":\"ABC\",\"limit\":5}");
        assertThat(vague.orders()).isEmpty();
        assertThat(vague.candidates()).hasSize(2);
        assertThat(vague.note()).contains("ask which one");
        assertThat(vague.total()).as("nothing was counted, so no 0 to read as none").isNull();
        assertThat(vague.truncated()).isNull();
        verify(orders).list(any());
    }

    @Test
    @DisplayName("rows carry no customer for a caller who may not list customers, and the names are never read")
    void noNamesWithoutTheCustomerList() {
        when(users.current()).thenReturn(Optional.of(new CurrentUser(UUID.randomUUID(), "coordinator",
                Set.of(Role.ORDER_COORDINATOR), Set.of(), DataScope.ALL, Set.of())));
        when(orders.list(any())).thenReturn(PageResponse.of(List.of(row("SO-20261027-000002", OrderStatus.CONFIRMED,
                null, null)), 0, 5, 1));

        FindOrdersTool.Answer answer = ask("{\"status\":\"ANY\",\"holdReason\":\"ANY\",\"placedFrom\":\"\","
                + "\"placedTo\":\"\",\"customer\":\"\",\"limit\":5}");

        assertThat(answer.orders()).singleElement().satisfies(found -> assertThat(found.customer()).isNull());
        verify(customers, never()).fullNames(any());
    }

    private static com.stockflow.customer.api.CustomerSummary summary(UUID id, String name) {
        return new com.stockflow.customer.api.CustomerSummary(id, null, name, null, null, null, null, "ACTIVE", 0,
                Instant.now());
    }

    /** A list row as the order module builds it: contact block and address included, no lines. */
    private static OrderSummary row(String number, OrderStatus status, HoldReason holdReason, String note) {
        Instant placed = Instant.parse("2026-10-27T03:00:00Z");
        return new OrderSummary(UUID.randomUUID(), number, UUID.randomUUID(), status, Money.vnd(1_250_000), List.of(),
                placed, "sales", null, null, "Nguyễn Văn A", "a@khach.vn", "0903123456",
                new OrderSummary.AddressSummary("Nguyễn Văn A", "0903123456", "12 Lê Lợi", null, null, null, null,
                        null, "VN", null),
                null, PaymentTerm.DEPOSIT, null, null,
                new OrderSummary.Payment(BigDecimal.valueOf(375_000), BigDecimal.valueOf(375_000), placed, null,
                        PaymentStatus.PARTIALLY_PAID, null, BalancePolicy.BEFORE_DELIVERY),
                null,
                new OrderSummary.Lifecycle(holdReason, holdReason == null ? null : OrderStatus.CONFIRMED, note, null,
                        null, null, null, null, null, BigDecimal.ZERO, null, null, null, List.of(), List.of()));
    }
}
