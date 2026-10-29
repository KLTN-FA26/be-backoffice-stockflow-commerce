package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.production.api.ProductionOrderDetail;
import com.stockflow.production.api.ProductionOrderQueries;
import com.stockflow.production.api.ProductionOrderView;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The production orders tool without a database (design §4.1): what it asks the production module for, and what it
 * hands the model - numbers people type in, LSX fields out, and nothing of the customer order but its number.
 */
class FindProductionOrdersToolTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String ORDER_NUMBER = "SO-20261020-000123";
    private static final Instant CREATED = Instant.parse("2026-10-20T03:00:00Z");

    private final RecordingProduction production = new RecordingProduction();
    private final OrderService orders = mock(OrderService.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final FindProductionOrdersTool tool = new FindProductionOrdersTool(production, orders, users);

    @BeforeEach
    void signedIn() {
        when(users.current()).thenReturn(Optional.of(TestUsers.builder().role(Role.ORDER_COORDINATOR)
                .scope(DataScope.ALL).can("production-orders", Action.READ).can("sales-orders", Action.READ).build()));
    }

    @Test
    @DisplayName("a customer order number is matched exactly, and its LSX are asked for by that order's id")
    void bySalesOrder() {
        UUID order = UUID.randomUUID();
        // A longer number containing the one asked for comes first: only the exact number is this order.
        when(orders.list(any())).thenReturn(PageResponse.of(List.of(summary(UUID.randomUUID(), ORDER_NUMBER + "9"),
                summary(order, ORDER_NUMBER)), 0, 20, 2));
        production.found = new ProductionOrderQueries.Found(List.of(
                view("LSX-20261020-0001", "ON_HOLD", "FILE_REJECTED"), view("LSX-20261020-0002", "PRINTING", null)), 2);

        FindProductionOrdersTool.Answer answer = tool.run(arguments("", ORDER_NUMBER.toLowerCase(Locale.ROOT), "ANY",
                "ANY", 20));

        assertThat(production.criteria.salesOrderId()).isEqualTo(order);
        assertThat(production.criteria.statuses()).isEmpty();
        assertThat(production.criteria.overdueOnly()).isFalse();
        assertThat(production.criteria.limit()).isEqualTo(20);
        assertThat(answer.salesOrderNumber()).isEqualTo(ORDER_NUMBER);
        assertThat(answer.productionOrders()).extracting(FindProductionOrdersTool.Row::productionOrderNumber)
                .containsExactly("LSX-20261020-0001", "LSX-20261020-0002");
        FindProductionOrdersTool.Row held = answer.productionOrders().getFirst();
        assertThat(List.of(held.status(), held.holdReason(), held.warehouseCode(), held.blankSku()))
                .containsExactly("ON_HOLD", "FILE_REJECTED", "HCM", "CUP-12OZ");
        assertThat(List.of(held.plannedUnits(), held.goodUnitsPrinted())).containsExactly(20_000, 12_000);
        assertThat(answer.total()).isEqualTo(2);
        assertThat(answer.truncated()).isFalse();
        assertThat(answer.detail()).isNull();
        assertThat(answer.note()).isNull();
    }

    @Test
    @DisplayName("an LSX number returns that one LSX with its counts, its hold and its history; the filters are "
            + "ignored")
    void byNumber() {
        production.detail = detail(view("LSX-20261020-0001", "ON_HOLD", "BLANKS_SHORT"), history(3));

        FindProductionOrdersTool.Answer answer = tool.run(arguments(" LSX-20261020-0001 ", ORDER_NUMBER, "QC",
                "OVERDUE", 5));

        assertThat(production.number).isEqualTo("LSX-20261020-0001");
        assertThat(production.criteria).as("a lookup by number lists nothing").isNull();
        verifyNoInteractions(orders);
        assertThat(answer.productionOrders()).singleElement()
                .extracting(FindProductionOrdersTool.Row::salesOrderNumber).isEqualTo(ORDER_NUMBER);
        assertThat(answer.total()).isEqualTo(1);
        FindProductionOrdersTool.Detail detail = answer.detail();
        assertThat(List.of(detail.blanksIssued(), detail.unitsPrinted(), detail.unitsScrapped(),
                detail.blanksStillNeeded())).containsExactly(10_000, 6_000, 200, 4_200);
        assertThat(detail.hold()).isEqualTo(new FindProductionOrdersTool.Hold("BLANKS_SHORT",
                "Thiếu 4.200 phôi, chờ PO về", "READY", CREATED.plusSeconds(3)));
        assertThat(detail.history()).hasSize(3);
        assertThat(detail.historyTruncated()).isFalse();
    }

    @Test
    @DisplayName("a long history keeps the latest changes and says so")
    void historyIsCapped() {
        int changes = FindProductionOrdersTool.MAX_HISTORY + 5;
        production.detail = detail(view("LSX-20261020-0001", "PRINTING", null), history(changes));

        FindProductionOrdersTool.Detail detail = tool.run(arguments("LSX-20261020-0001", "", "ANY", "ANY", 1))
                .detail();

        assertThat(detail.history()).hasSize(FindProductionOrdersTool.MAX_HISTORY);
        assertThat(detail.history().getLast().at()).isEqualTo(CREATED.plusSeconds(changes));
        assertThat(detail.historyTruncated()).isTrue();
    }

    @Test
    @DisplayName("for a user who may not read orders, an order number nobody has reads exactly like an order with no "
            + "LSX they may see")
    void orderNumbersRevealNothingWithoutSalesOrders() {
        CurrentUser qc = TestUsers.builder().role(Role.QC_STAFF).scope(DataScope.ALL)
                .can("production-orders", Action.READ).build();
        when(users.current()).thenReturn(Optional.of(qc));
        String typed = ORDER_NUMBER.toLowerCase(Locale.ROOT);

        when(orders.list(any())).thenReturn(PageResponse.of(List.of(summary(UUID.randomUUID(), ORDER_NUMBER + "9")),
                0, 20, 1));
        FindProductionOrdersTool.Answer nobodys = tool.run(arguments("", typed, "ANY", "ANY", 10));
        when(orders.list(any())).thenReturn(PageResponse.of(List.of(summary(UUID.randomUUID(), ORDER_NUMBER)), 0, 20,
                1));
        FindProductionOrdersTool.Answer withoutLsx = tool.run(arguments("", typed, "ANY", "ANY", 10));

        assertThat(nobodys).isEqualTo(withoutLsx);
        assertThat(nobodys.salesOrderNumber()).as("as typed, not as stored").isEqualTo(typed);
        assertThat(nobodys.note()).contains("neither whether the order exists");
        assertThat(tool.run(arguments("", typed, "QC", "ANY", 10)).note()).contains("neither whether the order exists");
    }

    @Test
    @DisplayName("an unknown LSX number is NOT_FOUND, and so is an unknown order number for a user who may read orders")
    void unknownNumbers() {
        when(orders.list(any())).thenReturn(PageResponse.of(List.of(summary(UUID.randomUUID(), ORDER_NUMBER + "9")),
                0, 20, 1));

        assertThatThrownBy(() -> tool.run(arguments("LSX-20261020-9999", "", "ANY", "ANY", 10)))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("LSX-20261020-9999");
        assertThatThrownBy(() -> tool.run(arguments("", ORDER_NUMBER, "ANY", "ANY", 10)))
                .isInstanceOf(NotFoundException.class).hasMessageContaining(ORDER_NUMBER);
        assertThat(production.criteria).isNull();
    }

    @Test
    @DisplayName("OPEN, ANY and one status become the statuses asked for; OVERDUE asks for late work only")
    void filters() {
        tool.run(arguments("", "", "OPEN", "OVERDUE", 50));
        assertThat(production.criteria.statuses()).isEqualTo(ProductionOrderQueries.OPEN_STATUSES);
        assertThat(production.criteria.overdueOnly()).isTrue();
        assertThat(production.criteria.salesOrderId()).isNull();

        tool.run(arguments("", "", "QC", "ANY", 10));
        assertThat(production.criteria.statuses()).containsExactly("QC");
        assertThat(production.criteria.overdueOnly()).isFalse();

        tool.run(arguments("", "", "ANY", "ANY", 10));
        assertThat(production.criteria.statuses()).isEmpty();

        assertThatThrownBy(() -> tool.run(arguments("", "", "LATE", "ANY", 10)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("status");
        assertThatThrownBy(() -> tool.run(arguments("", "", "ANY", "ANY", FindProductionOrdersTool.MAX_ROWS + 1)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("limit");
    }

    @Test
    @DisplayName("a list cut at the limit says so, with the total")
    void truncated() {
        production.found = new ProductionOrderQueries.Found(List.of(view("LSX-20261020-0001", "QC", null),
                view("LSX-20261020-0002", "QC", null)), 7);

        FindProductionOrdersTool.Answer answer = tool.run(arguments("", "", "QC", "ANY", 2));

        assertThat(answer.productionOrders()).hasSize(2);
        assertThat(answer.total()).isEqualTo(7);
        assertThat(answer.truncated()).isTrue();
    }

    @Test
    @DisplayName("a portal customer, or nobody signed in, is refused before any order is looked up")
    void backOfficeOnly() {
        when(users.current()).thenReturn(Optional.of(TestUsers.customer()));
        assertThatThrownBy(() -> tool.run(arguments("", ORDER_NUMBER, "ANY", "ANY", 10)))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        when(users.current()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tool.run(arguments("", ORDER_NUMBER, "ANY", "ANY", 10)))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(orders);
        assertThat(production.criteria).isNull();
        assertThat(production.number).isNull();
    }

    @Test
    @DisplayName("what reaches the model carries no ids, and of the customer order only its number")
    void noIdsAndNothingOfTheOrder() throws Exception {
        when(orders.list(any())).thenReturn(PageResponse.of(List.of(summary(UUID.randomUUID(), ORDER_NUMBER)), 0, 20,
                1));
        production.found = new ProductionOrderQueries.Found(List.of(view("LSX-20261020-0001", "PRINTING", null)), 1);
        production.detail = detail(view("LSX-20261020-0001", "ON_HOLD", "FILE_REJECTED"), history(2));

        String list = JSON.writeValueAsString(tool.run(arguments("", ORDER_NUMBER, "ANY", "ANY", 10)));
        String one = JSON.writeValueAsString(tool.run(arguments("LSX-20261020-0001", "", "ANY", "ANY", 10)));

        for (String result : List.of(list, one)) {
            assertThat(result).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    .doesNotContain("IN_PRODUCTION", "Chị Lan", "lan@example.com", "0909000111")
                    .contains("LSX-20261020-0001", ORDER_NUMBER);
        }
    }

    @Test
    @DisplayName("an empty list says why when it is not plainly 'nothing matches', without the order's status")
    void emptyLists() {
        when(orders.list(any())).thenReturn(PageResponse.of(List.of(summary(UUID.randomUUID(), ORDER_NUMBER)), 0, 20,
                1));

        assertThat(tool.run(arguments("", ORDER_NUMBER, "ANY", "ANY", 10)).note())
                .contains("released to production").doesNotContain("IN_PRODUCTION");
        assertThat(tool.run(arguments("", ORDER_NUMBER, "OPEN", "ANY", 10)).note())
                .contains("status ANY and due ANY");
        assertThat(tool.run(arguments("", "", "QC", "ANY", 10)).note()).as("nothing matches: nothing to explain")
                .isNull();

        CurrentUser qc = TestUsers.builder().role(Role.QC_STAFF).scope(DataScope.WAREHOUSE).warehouses("HCM").build();
        when(users.current()).thenReturn(Optional.of(qc));
        FindProductionOrdersTool.Answer limited = WithCurrentUser.call(qc, DataScope.ALL,
                () -> tool.run(arguments("", "", "QC", "ANY", 10)));
        assertThat(limited.note()).contains("warehouses HCM");
    }

    @Test
    @DisplayName("the status choice is OPEN, ANY and every status the production module knows")
    void vocabulary() {
        assertThat(FindProductionOrdersTool.STATUSES).startsWith("OPEN", "ANY")
                .containsAll(ProductionOrderQueries.STATUSES)
                .hasSize(ProductionOrderQueries.STATUSES.size() + 2);
        assertThat(ProductionOrderQueries.STATUSES).containsAll(ProductionOrderQueries.OPEN_STATUSES)
                .doesNotContainAnyElementsOf(List.of("OPEN", "ANY"));
        assertThat(ProductionOrderQueries.OPEN_STATUSES).doesNotContain("COMPLETED", "CANCELLED");
    }

    // ------------------------------------------------------------------------------------------------------------

    private static ToolArguments arguments(String productionOrderNumber, String salesOrderNumber, String status,
                                           String due, int limit) {
        var node = JSON.createObjectNode().put("productionOrderNumber", productionOrderNumber)
                .put("salesOrderNumber", salesOrderNumber).put("status", status).put("due", due).put("limit", limit);
        return ToolArguments.of(node);
    }

    private static ProductionOrderView view(String number, String status, String holdReason) {
        return new ProductionOrderView(number, "ORDER", "IN_HOUSE", status, holdReason, "HCM", "CUP-12OZ", 20_000,
                12_000, LocalDate.of(2026, 11, 5), false, ORDER_NUMBER, null, null, CREATED);
    }

    private static ProductionOrderDetail detail(ProductionOrderView order,
                                                List<ProductionOrderDetail.StatusChange> history) {
        ProductionOrderDetail.Hold hold = "ON_HOLD".equals(order.status())
                ? new ProductionOrderDetail.Hold(order.holdReason(), "Thiếu 4.200 phôi, chờ PO về", "READY",
                CREATED.plusSeconds(3))
                : null;
        return new ProductionOrderDetail(order, null, 10_000, 6_000, 200, 4_200, hold, CREATED.plusSeconds(1), null,
                null, null, history);
    }

    /** {@code changes} status changes, one second apart, the last at {@code CREATED + changes}. */
    private static List<ProductionOrderDetail.StatusChange> history(int changes) {
        return IntStream.rangeClosed(1, changes)
                .mapToObj(second -> new ProductionOrderDetail.StatusChange(second == 1 ? null : "READY", "PRINTING",
                        "round " + second, CREATED.plusSeconds(second)))
                .toList();
    }

    /** A customer order as the order list returns it, contact details included: they must not reach the answer. */
    private static OrderSummary summary(UUID id, String number) {
        return new OrderSummary(id, number, UUID.randomUUID(), OrderStatus.IN_PRODUCTION, Money.vnd(1_000_000),
                List.of(), CREATED, null, null, null, "Chị Lan", "lan@example.com", "0909000111", null, null);
    }

    /** Answers with what a test set, and keeps what it was asked. */
    private static final class RecordingProduction implements ProductionOrderQueries {

        ProductionOrderDetail detail;
        Found found = new Found(List.of(), 0);
        String number;
        Criteria criteria;

        @Override
        public Optional<ProductionOrderDetail> byNumber(String orderNumber, CurrentUser user) {
            number = orderNumber;
            return Optional.ofNullable(detail);
        }

        @Override
        public Found find(Criteria asked, CurrentUser user) {
            criteria = asked;
            return found;
        }
    }
}
