package com.stockflow.assistant.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.fulfillment.api.OrderPicking;
import com.stockflow.fulfillment.api.OrderPickingQueries;
import com.stockflow.order.api.HandoverBlock;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStockCoverage;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ProductionOrderQueries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Why is this order not moving" (contract §1.1): every reason of the handover check, who must act on each, the
 * sentences from the bundle, what was not checked, and the order list's scope.
 */
class OrderNextStepsTest {

    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final Instant PLACED = Instant.parse("2026-10-20T02:00:00Z");

    private final OrderService orders = mock(OrderService.class);
    private final ProductionOrderQueries productionOrders = mock(ProductionOrderQueries.class);
    private final OrderStockCoverage stockCoverage = mock(OrderStockCoverage.class);
    private final OrderPickingQueries picking = mock(OrderPickingQueries.class);
    private final OrderBlockers blockers = new OrderBlockers(orders, productionOrders, stockCoverage, picking,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final OrderNextSteps nextSteps = new OrderNextSteps(blockers, bundles());

    @BeforeEach
    void vietnamese() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
    }

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("a packed batch both unpaid and before its window gets both reasons: paying alone would not let it go")
    void everyReasonOfTheHandoverCheck() {
        OrderSummary order = found(order(OrderStatus.PACKED, null));
        when(orders.handoverBlocks(anyCollection(), any())).thenReturn(List.of(new HandoverBlock(order.orderId(),
                order.orderNumber(), "ORDER_BALANCE_NOT_PAID",
                List.of("ORDER_BALANCE_NOT_PAID", "DELIVERY_BATCH_WINDOW_NOT_OPEN"), BigDecimal.valueOf(437_580),
                "VND", 2, LocalDate.parse("2026-11-20"))));

        OrderNextSteps.NextSteps steps = nextSteps.of(order.orderNumber(),
                staff(Role.SALES_MANAGER, "sales-orders:READ", "payment-installments:READ", "production-orders:READ",
                        "sales-order-shipping-warehouses:READ"));

        assertThat(steps.blockers()).extracting(OrderNextSteps.Step::code)
                .containsExactly("INSTALLMENT_DUE", "DELIVERY_WINDOW_NOT_OPEN");
        OrderNextSteps.Step due = steps.blockers().getFirst();
        assertThat(due.waitingOn()).isEqualTo("CUSTOMER");
        assertThat(due.amountDue()).isEqualByComparingTo("437580");
        assertThat(due.batchSeq()).isEqualTo(2);
        assertThat(due.detail()).contains("đợt giao 2", "437.580 VND");
        assertThat(due.href()).isEqualTo("/admin/orders/" + order.orderId());
        OrderNextSteps.Step window = steps.blockers().get(1);
        assertThat(window.waitingOn()).isEqualTo("SALES");
        assertThat(window.detail()).contains("20/11/2026");
        assertThat(steps.statusLabel()).isEqualTo("Đã kiểm thùng");
        assertThat(steps.notChecked()).isEmpty();
        assertThat(steps.movingNormally()).isFalse();
    }

    @Test
    @DisplayName("an old block that names one reason only is still read")
    void aBlockWithoutTheListStillCounts() {
        OrderSummary order = found(order(OrderStatus.PICKING, null));
        when(orders.handoverBlocks(anyCollection(), any())).thenReturn(List.of(new HandoverBlock(order.orderId(),
                order.orderNumber(), "SOMETHING_NEW", null, BigDecimal.ZERO, "VND", null, null)));

        OrderBlockers.Blockers found = blockers.of(order, Optional.of(staff(Role.SALES_STAFF, "sales-orders:READ")),
                false);

        assertThat(found.reasons()).extracting(OrderBlockers.Blocker::code).containsExactly("HANDOVER_REFUSED");
        assertThat(found.reasons().getFirst().refusalCode()).isEqualTo("SOMETHING_NEW");
    }

    @Test
    @DisplayName("without the instalment permission the reason stays, its amount goes, and INSTALLMENTS is not checked")
    void hiddenAmountIsNotChecked() {
        OrderSummary order = found(order(OrderStatus.PACKED, null));
        when(orders.handoverBlocks(anyCollection(), any())).thenReturn(List.of(new HandoverBlock(order.orderId(),
                order.orderNumber(), "ORDER_BALANCE_NOT_PAID", List.of("ORDER_BALANCE_NOT_PAID"),
                BigDecimal.valueOf(437_580), "VND", null, null)));

        OrderNextSteps.NextSteps steps = nextSteps.of(order.orderNumber(), staff(Role.SALES_STAFF,
                "sales-orders:READ"));

        OrderNextSteps.Step due = steps.blockers().getFirst();
        assertThat(due.amountDue()).isNull();
        assertThat(due.detail()).doesNotContain("437").contains("đơn hàng");
        assertThat(steps.notChecked()).containsExactly(OrderBlockers.STOCK_HELD, OrderBlockers.PRODUCTION_ORDERS,
                OrderNextSteps.INSTALLMENTS);
        assertThat(steps.movingNormally()).isFalse();
    }

    @Test
    @DisplayName("a credit hold waits on accounting and links to the credit holds page; a fraud hold on the manager")
    void holdsByWhoLiftsThem() {
        CurrentUser sales = staff(Role.SALES_STAFF, "sales-orders:READ", "production-orders:READ",
                "sales-order-shipping-warehouses:READ");
        OrderSummary credit = found(order(OrderStatus.ON_HOLD, HoldReason.CREDIT));

        OrderNextSteps.Step held = nextSteps.of(credit.orderNumber(), sales).blockers().getFirst();

        assertThat(held.code()).isEqualTo("ON_HOLD");
        assertThat(held.waitingOn()).isEqualTo("ACCOUNTANT");
        assertThat(held.href()).isEqualTo("/admin/orders/credit-holds");
        assertThat(held.title()).isEqualTo("Tạm giữ vì công nợ");

        OrderSummary fraud = found(order(OrderStatus.ON_HOLD, HoldReason.FRAUD));
        assertThat(nextSteps.of(fraud.orderNumber(), sales).blockers().getFirst().waitingOn())
                .isEqualTo("SALES_MANAGER");
    }

    @Test
    @DisplayName("an order being picked, its stock held, nothing refused and everything checked, is moving normally")
    void movingNormally() {
        OrderSummary order = found(order(OrderStatus.PICKING, null));
        when(stockCoverage.coverage(order.orderId())).thenReturn(Optional.of(new OrderStockCoverage.Coverage(true,
                "HCM", true, List.of(), List.of())));
        when(orders.handoverBlocks(anyCollection(), any())).thenReturn(List.of());

        OrderNextSteps.NextSteps steps = nextSteps.of(order.orderNumber(), staff(Role.SALES_MANAGER,
                "sales-orders:READ", "production-orders:READ", "sales-order-shipping-warehouses:READ"));

        assertThat(steps.blockers()).isEmpty();
        assertThat(steps.notChecked()).isEmpty();
        assertThat(steps.movingNormally()).isTrue();
    }

    @Test
    @DisplayName("finding 2: a confirmed order whose every carton is held waits for the warehouse manager to release "
            + "it - it is not moving normally")
    void heldInFullWaitsForTheRelease() {
        OrderSummary order = found(order(OrderStatus.CONFIRMED, null, null));
        when(stockCoverage.coverage(order.orderId())).thenReturn(Optional.of(new OrderStockCoverage.Coverage(true,
                "HCM", true, List.of(new OrderStockCoverage.Line("CUP-12OZ", 10, 10, 0)), List.of())));

        OrderNextSteps.NextSteps steps = nextSteps.of(order.orderNumber(), staff(Role.SALES_MANAGER,
                "sales-orders:READ", "production-orders:READ", "sales-order-shipping-warehouses:READ"));

        assertThat(steps.blockers()).singleElement().satisfies(step -> {
            assertThat(step.code()).isEqualTo("AWAITING_RELEASE");
            assertThat(step.waitingOn()).isEqualTo("WAREHOUSE");
            assertThat(step.title()).isEqualTo("Chờ kho xuất đơn hàng");
        });
        assertThat(steps.movingNormally()).isFalse();
    }

    @Test
    @DisplayName("finding 2: a partly fulfilled order whose next batch is held in full and may go waits for its release; "
            + "one whose batch before still has goods to hand over does not")
    void nextBatchWaitsForTheRelease() {
        OrderSummary order = found(order(OrderStatus.PARTIALLY_FULFILLED, null));
        List<OrderStockCoverage.Batch> batches = List.of(
                new OrderStockCoverage.Batch(1, null, null, true, List.of(new OrderStockCoverage.Line("CUP", 5, 5, 0))),
                new OrderStockCoverage.Batch(2, null, null, true, List.of(new OrderStockCoverage.Line("CUP", 5, 5, 0))));
        when(orders.handoverBlocks(anyCollection(), any())).thenReturn(List.of());
        CurrentUser manager = staff(Role.SALES_MANAGER, "sales-orders:READ", "production-orders:READ",
                "sales-order-shipping-warehouses:READ");

        when(stockCoverage.coverage(order.orderId())).thenReturn(Optional.of(new OrderStockCoverage.Coverage(true,
                "HCM", true, List.of(), batches, 2)));
        OrderNextSteps.Step step = nextSteps.of(order.orderNumber(), manager).blockers().getFirst();
        assertThat(step.code()).isEqualTo("AWAITING_RELEASE");
        assertThat(step.batchSeq()).isEqualTo(2);
        assertThat(step.title()).isEqualTo("Chờ kho xuất đợt giao 2");

        when(stockCoverage.coverage(order.orderId())).thenReturn(Optional.of(new OrderStockCoverage.Coverage(true,
                "HCM", true, List.of(), batches, null)));
        OrderNextSteps.NextSteps waiting = nextSteps.of(order.orderNumber(), manager);
        assertThat(waiting.blockers()).isEmpty();
        assertThat(waiting.movingNormally()).isTrue();
    }

    @Test
    @DisplayName("finding 2: a released order waits for the warehouse to pick it; with the pick lists, the task's "
            + "status is said")
    void releasedWaitsForThePick() {
        OrderSummary order = found(order(OrderStatus.READY_TO_FULFILL, null));
        when(stockCoverage.coverage(order.orderId())).thenReturn(Optional.of(new OrderStockCoverage.Coverage(true,
                "HCM", true, List.of(), List.of())));
        CurrentUser sales = staff(Role.SALES_MANAGER, "sales-orders:READ", "production-orders:READ",
                "sales-order-shipping-warehouses:READ");

        OrderNextSteps.NextSteps steps = nextSteps.of(order.orderNumber(), sales);
        assertThat(steps.blockers()).singleElement().satisfies(step -> {
            assertThat(step.code()).isEqualTo("AWAITING_PICK");
            assertThat(step.waitingOn()).isEqualTo("WAREHOUSE");
            assertThat(step.detail()).isEqualTo("Đơn đã được xuất cho kho và đang chờ phiếu lấy hàng.");
        });
        assertThat(steps.movingNormally()).isFalse();
        verify(picking, never()).pickingOf(any(), any());

        CurrentUser warehouse = staff(Role.WAREHOUSE_MANAGER, "sales-orders:READ", "production-orders:READ",
                "sales-order-shipping-warehouses:READ", "fulfillment-pick-lists:READ");
        when(picking.pickingOf(order.orderId(), warehouse)).thenReturn(Optional.of(new OrderPicking(UUID.randomUUID(),
                "PENDING", null, null, List.of())));
        assertThat(nextSteps.of(order.orderNumber(), warehouse).blockers().getFirst().detail())
                .contains("chưa bắt đầu");
    }

    @Test
    @DisplayName("finding 3: a shipment that came back is not delivered again on the order: Sales cancel what was not "
            + "delivered, and what the customer still wants goes on a new order")
    void deliveryFailedIsNotRedelivered() {
        OrderSummary order = found(order(OrderStatus.ON_HOLD, HoldReason.DELIVERY_FAILED));

        OrderNextSteps.Step held = nextSteps.of(order.orderNumber(), staff(Role.SALES_STAFF, "sales-orders:READ"))
                .blockers().getFirst();

        assertThat(held.waitingOn()).isEqualTo("SALES");
        assertThat(held.nextStep()).doesNotContain("giao lại hoặc").contains("huỷ phần chưa giao", "đơn mới");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(nextSteps.of(order.orderNumber(), staff(Role.SALES_STAFF, "sales-orders:READ")).blockers()
                .getFirst().nextStep()).doesNotContain("deliver again or").contains("new order");
    }

    @Test
    @DisplayName("an order delivered is not moving normally nor blocked: its status says where it is")
    void notLive() {
        OrderSummary order = found(order(OrderStatus.DELIVERED, null));

        OrderNextSteps.NextSteps steps = nextSteps.of(order.orderNumber(), staff(Role.SALES_STAFF,
                "sales-orders:READ"));

        assertThat(steps.blockers()).isEmpty();
        assertThat(steps.notChecked()).isEmpty();
        assertThat(steps.movingNormally()).isFalse();
        assertThat(steps.statusLabel()).isEqualTo("Đã giao");
    }

    @Test
    @DisplayName("an unsigned draft waits on Sales, one sent and not signed on the customer")
    void contractByStatus() {
        CurrentUser sales = staff(Role.SALES_STAFF, "sales-orders:READ");
        OrderSummary draft = found(order(OrderStatus.DRAFT, null));
        assertThat(nextSteps.of(draft.orderNumber(), sales).blockers().getFirst().waitingOn()).isEqualTo("SALES");

        OrderSummary sent = found(order(OrderStatus.PENDING_CONTRACT, null));
        OrderNextSteps.Step unsigned = nextSteps.of(sent.orderNumber(), sales).blockers().getFirst();
        assertThat(unsigned.code()).isEqualTo("CONTRACT_NOT_SIGNED");
        assertThat(unsigned.waitingOn()).isEqualTo("CUSTOMER");
        assertThat(unsigned.title()).isEqualTo("Chờ khách ký hợp đồng");
    }

    @Test
    @DisplayName("a caller limited to warehouses looks the order up within them: the list's own scope, else 404")
    void warehouseLimit() {
        UUID warehouse = UUID.randomUUID();
        CurrentUser manager = new CurrentUser(UUID.randomUUID(), "wm", Set.of(Role.WAREHOUSE_MANAGER),
                Set.of(PermissionCode.parse("sales-orders:READ")), DataScope.WAREHOUSE, Set.of("HCM"),
                Set.of(warehouse));
        when(orders.list(any())).thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        assertThatThrownBy(() -> nextSteps.of("SO-20261020-000042", manager)).isInstanceOf(NotFoundException.class);

        ArgumentCaptor<ListOrdersQuery> query = ArgumentCaptor.forClass(ListOrdersQuery.class);
        verify(orders).list(query.capture());
        assertThat(query.getValue().warehouseIds()).containsExactly(warehouse);
        assertThat(query.getValue().search()).isEqualTo("SO-20261020-000042");
    }

    // ------------------------------------------------------------------------------------------------------------

    private OrderSummary found(OrderSummary order) {
        when(orders.list(any())).thenReturn(PageResponse.of(List.of(order), 0, 20, 1));
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));
        return order;
    }

    private static OrderSummary order(OrderStatus status, HoldReason hold) {
        return order(status, hold, PLACED);
    }

    private static OrderSummary order(OrderStatus status, HoldReason hold, Instant releasedAt) {
        String number = "SO-20261020-" + String.format("%06d", Math.abs(UUID.randomUUID().hashCode() % 1_000_000));
        OrderSummary.Lifecycle lifecycle = new OrderSummary.Lifecycle(hold,
                hold == null ? null : OrderStatus.CONFIRMED, hold == null ? null : "ghi chú nội bộ", PLACED, null,
                null, null, null, null, BigDecimal.valueOf(1_000_000), BigDecimal.ZERO, List.of(), List.of(), false);
        return new OrderSummary(UUID.randomUUID(), number, UUID.randomUUID(), status, Money.vnd(1_000_000),
                List.of(), PLACED, "sales", null, null, null, null, null, null, null, UUID.randomUUID(), releasedAt,
                null, lifecycle);
    }

    private static CurrentUser staff(Role role, String... permissions) {
        return new CurrentUser(UUID.randomUUID(), role.name().toLowerCase(Locale.ROOT), Set.of(role),
                Arrays.stream(permissions).map(PermissionCode::parse).collect(Collectors.toSet()), DataScope.ALL,
                Set.of());
    }

    private static Messages bundles() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return new Messages(source);
    }
}
