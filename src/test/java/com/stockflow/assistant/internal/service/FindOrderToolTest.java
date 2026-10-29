package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Money;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.order.api.BalancePolicy;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.ShipmentHandover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What one order tells the model (design §4.2): the money that keeps a packed order in the warehouse, the shipments
 * of a split order, whether its prints are ready - and never the customer's contacts, typed or not.
 */
class FindOrderToolTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant PLACED = Instant.parse("2026-10-20T02:00:00Z");

    private final OrderService orders = mock(OrderService.class);
    private final FindOrderTool tool = new FindOrderTool(orders, mock(CurrentUserProvider.class));

    @Test
    @DisplayName("a packed deposit order with its balance due before delivery says what must be paid before it leaves")
    void balanceDueBeforeHandover() {
        OrderSummary order = order(OrderStatus.PACKED, PaymentTerm.DEPOSIT,
                new OrderSummary.Payment(BigDecimal.valueOf(300_000), BigDecimal.valueOf(300_000), PLACED, null,
                        PaymentStatus.PARTIALLY_PAID, null, BalancePolicy.BEFORE_DELIVERY),
                lifecycle(null, null, BigDecimal.valueOf(700_000), BigDecimal.valueOf(700_000), List.of(), false,
                        true),
                List.of(line(UUID.randomUUID(), "CUP-12OZ", false)));

        FindOrderTool.Answer answer = find(order, List.of());

        assertThat(answer.status()).isEqualTo("PACKED");
        assertThat(answer.hold()).isNull();
        FindOrderTool.Payment payment = answer.payment();
        assertThat(payment.term()).isEqualTo("DEPOSIT");
        assertThat(payment.status()).isEqualTo("PARTIALLY_PAID");
        assertThat(payment.balancePolicy()).isEqualTo("BEFORE_DELIVERY");
        assertThat(payment.total()).isEqualByComparingTo("1000000");
        assertThat(payment.currency()).isEqualTo("VND");
        assertThat(payment.paidAmount()).isEqualByComparingTo("300000");
        assertThat(payment.depositRequired()).isEqualByComparingTo("300000");
        assertThat(payment.amountDue()).isEqualByComparingTo("700000");
        assertThat(payment.amountDueBeforeHandover()).isEqualByComparingTo("700000");
        assertThat(payment.effectiveTotal()).as("nothing was closed out of it").isNull();
        assertThat(answer.printLinesReady()).as("an order with no printed line has nothing to say about prints")
                .isNull();
        assertThat(answer.allowPartialShipment()).isFalse();
    }

    @Test
    @DisplayName("a split order lists its shipments oldest first, with outcome and SKU, and never their carriage")
    void shipments() throws Exception {
        UUID cups = UUID.randomUUID();
        UUID lids = UUID.randomUUID();
        OrderSummary.Shipment delivered = shipment(OrderShipmentStatus.DELIVERED, null, PLACED.plusSeconds(86_400),
                cups, 60, "GHN-111");
        OrderSummary.Shipment failed = shipment(OrderShipmentStatus.DELIVERY_FAILED,
                DeliveryFailureReason.CUSTOMER_ABSENT, PLACED.plusSeconds(2 * 86_400), lids, 40, "GHN-222");
        OrderSummary order = order(OrderStatus.PARTIALLY_FULFILLED, PaymentTerm.CREDIT,
                new OrderSummary.Payment(BigDecimal.ZERO, null, null, null, PaymentStatus.ON_CREDIT, 30, null),
                lifecycle(null, null, BigDecimal.valueOf(1_000_000), BigDecimal.ZERO, List.of(failed, delivered),
                        true, true),
                List.of(line(cups, "CUP-12OZ", false), line(lids, "LID-90", false)));

        FindOrderTool.Answer answer = find(order, List.of());

        assertThat(answer.shipments()).extracting(FindOrderTool.Shipment::status)
                .containsExactly("DELIVERED", "DELIVERY_FAILED");
        FindOrderTool.Shipment last = answer.shipments().get(1);
        assertThat(last.failureReason()).isEqualTo("CUSTOMER_ABSENT");
        assertThat(last.lines()).containsExactly(new FindOrderTool.ShipmentLine("LID-90", 40));
        assertThat(answer.shipmentsTruncated()).isFalse();
        assertThat(answer.payment().creditTermDays()).isEqualTo(30);
        assertThat(JSON.writeValueAsString(answer)).doesNotContain("GHN-111", "GHN-222", "Giao Hàng Nhanh");
    }

    @Test
    @DisplayName("only the latest shipments are listed, and the answer says so")
    void shipmentsAreCapped() {
        UUID cups = UUID.randomUUID();
        List<OrderSummary.Shipment> many = new ArrayList<>();
        for (int day = 1; day <= FindOrderTool.MAX_SHIPMENTS + 2; day++) {
            many.add(shipment(OrderShipmentStatus.DELIVERED, null, PLACED.plusSeconds(day * 86_400L), cups, day,
                    "S-" + day));
        }
        OrderSummary order = order(OrderStatus.DELIVERED, PaymentTerm.PREPAID,
                new OrderSummary.Payment(BigDecimal.valueOf(1_000_000), null, null, PLACED, PaymentStatus.PAID, null,
                        null),
                lifecycle(null, null, BigDecimal.ZERO, BigDecimal.ZERO, many, true, true),
                List.of(line(cups, "CUP-12OZ", false)));

        FindOrderTool.Answer answer = find(order, List.of());

        assertThat(answer.shipments()).hasSize(FindOrderTool.MAX_SHIPMENTS);
        assertThat(answer.shipments().getFirst().lines().getFirst().units()).as("the oldest two are left out")
                .isEqualTo(3);
        assertThat(answer.shipmentsTruncated()).isTrue();
    }

    @Test
    @DisplayName("an order whose prints are still being made says so, and its printed lines are marked")
    void printsNotReady() {
        UUID print = UUID.randomUUID();
        OrderSummary order = order(OrderStatus.PARTIALLY_FULFILLED, PaymentTerm.DEPOSIT,
                new OrderSummary.Payment(BigDecimal.valueOf(300_000), BigDecimal.valueOf(300_000), PLACED, null,
                        PaymentStatus.PARTIALLY_PAID, null, BalancePolicy.ON_DELIVERY),
                lifecycle(null, null, BigDecimal.valueOf(700_000), BigDecimal.ZERO, List.of(), true, false),
                List.of(line(UUID.randomUUID(), "CUP-12OZ", false), line(print, "CUP-12OZ", true)));

        FindOrderTool.Answer answer = find(order, List.of());

        assertThat(answer.printLinesReady()).isFalse();
        assertThat(answer.allowPartialShipment()).isTrue();
        assertThat(answer.lines()).extracting(FindOrderTool.Line::printed).containsExactly(null, true);
        assertThat(answer.payment().amountDueBeforeHandover()).as("the carrier collects the balance")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("contacts typed into the hold note and the history are masked; the contact block never leaves")
    void noContacts() throws Exception {
        OrderSummary order = order(OrderStatus.ON_HOLD, PaymentTerm.PREPAID,
                new OrderSummary.Payment(BigDecimal.valueOf(1_000_000), null, null, PLACED, PaymentStatus.PAID, null,
                        null),
                lifecycle(HoldReason.ADDRESS, "Sai địa chỉ, gọi 0903 123 456 hoặc a@khach.vn", BigDecimal.ZERO,
                        BigDecimal.ZERO, List.of(), false, true),
                List.of(line(UUID.randomUUID(), "CUP-12OZ", false)));
        List<OrderStatusChange> history = List.of(
                new OrderStatusChange(null, OrderStatus.CONFIRMED, null, PLACED),
                new OrderStatusChange(OrderStatus.CONFIRMED, OrderStatus.ON_HOLD,
                        "ADDRESS: khách đổi địa chỉ, SĐT mới 0909888777", PLACED.plusSeconds(3_600)));

        FindOrderTool.Answer answer = find(order, history);

        assertThat(answer.hold().reason()).isEqualTo("ADDRESS");
        assertThat(answer.hold().note()).isEqualTo("Sai địa chỉ, gọi [phone] hoặc [e-mail]");
        assertThat(answer.history().get(1).reason()).isEqualTo("ADDRESS: khách đổi địa chỉ, SĐT mới [phone]");
        assertThat(JSON.writeValueAsString(answer)).doesNotContain("Nguyễn Văn A", "a@khach.vn", "0903123456",
                "0903 123 456", "0909888777", "12 Lê Lợi");
    }

    // ------------------------------------------------------------------------------------------------------------

    private FindOrderTool.Answer find(OrderSummary order, List<OrderStatusChange> history) {
        when(orders.list(any())).thenReturn(PageResponse.of(List.of(order), 0, 20, 1));
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));
        when(orders.history(order.orderId())).thenReturn(history);
        return tool.run(ToolArguments.parse(new ObjectMapper(),
                "{\"orderNumber\":\"" + order.orderNumber().toLowerCase(java.util.Locale.ROOT) + "\"}"));
    }

    /** One order of 1,000,000 VND, with its contact block and address, as findById returns it. */
    @Test
    @DisplayName("a cancelled order owes nothing more: no amounts due, and what was kept, the fee and the refund instead")
    void cancelledOrderOwesNothing() {
        OrderSummary order = new OrderSummary(UUID.randomUUID(), "SO-20261020-000043", UUID.randomUUID(),
                OrderStatus.CANCELLED, Money.vnd(45_000_000), List.of(line(UUID.randomUUID(), "CUP-12OZ", true)),
                PLACED, "sales", null, null, "Nguyễn Văn A", "a@khach.vn", "0903123456", null, null,
                PaymentTerm.DEPOSIT, UUID.randomUUID(), PLACED,
                new OrderSummary.Payment(BigDecimal.valueOf(13_500_000), BigDecimal.valueOf(13_500_000), PLACED, null,
                        PaymentStatus.PARTIALLY_PAID, null, BalancePolicy.BEFORE_DELIVERY),
                new OrderSummary.Cancellation(CancellationReasonCode.CUSTOMER_REQUEST, "Khách đổi mẫu, gọi 0903 123 456",
                        BigDecimal.valueOf(10_000_000), "PRINTED", null, BigDecimal.valueOf(10_000_000)),
                lifecycle(null, null, BigDecimal.valueOf(31_500_000), BigDecimal.valueOf(31_500_000), List.of(), false,
                        true));

        FindOrderTool.Answer answer = find(order, List.of());

        assertThat(answer.payment().amountDue()).isNull();
        assertThat(answer.payment().amountDueBeforeHandover()).isNull();
        assertThat(answer.payment().amountDueOnDelivery()).isNull();
        assertThat(answer.cancellation().retainedAmount()).isEqualByComparingTo("10000000");
        assertThat(answer.cancellation().refundable()).isEqualByComparingTo("3500000");
        assertThat(answer.cancellation().feeStillOwed()).isEqualByComparingTo("0");
        assertThat(answer.cancellation().reason()).contains("[phone]").doesNotContain("0903");
        assertThat(answer.printLinesReady()).as("a cancelled order prints nothing").isNull();
    }

    @Test
    @DisplayName("an order not released to production says nothing about its prints being ready")
    void unreleasedPrintsAreNotReady() {
        OrderSummary order = new OrderSummary(UUID.randomUUID(), "SO-20261020-000044", UUID.randomUUID(),
                OrderStatus.CONFIRMED, Money.vnd(1_000_000), List.of(line(UUID.randomUUID(), "CUP-12OZ", true)),
                PLACED, "sales", null, null, null, null, null, null, null, PaymentTerm.PREPAID, UUID.randomUUID(),
                null, new OrderSummary.Payment(BigDecimal.valueOf(1_000_000), null, null, PLACED, PaymentStatus.PAID,
                null, null), null,
                lifecycle(null, null, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), false, true));

        assertThat(find(order, List.of()).printLinesReady()).isNull();
        assertThat(find(order, List.of()).cancellation()).isNull();
    }

    @Test
    @DisplayName("an order handed over whole, with no shipment recorded, has every unit shipped and delivered")
    void wholeOrderHandover() {
        Instant shipped = Instant.parse("2026-10-20T03:00:00Z");
        Instant delivered = Instant.parse("2026-10-21T03:00:00Z");
        OrderSummary.Lifecycle life = new OrderSummary.Lifecycle(null, null, null, PLACED, shipped, delivered, null,
                null, null, BigDecimal.ZERO, BigDecimal.valueOf(1_000_000), BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, false, true);
        OrderSummary order = order(OrderStatus.DELIVERED, PaymentTerm.PREPAID,
                new OrderSummary.Payment(BigDecimal.valueOf(1_000_000), null, null, PLACED, PaymentStatus.PAID, null,
                        null), life, List.of(line(UUID.randomUUID(), "CUP-12OZ", false)));

        FindOrderTool.Line line = find(order, List.of()).lines().getFirst();

        assertThat(line.shipped()).isEqualTo(100);
        assertThat(line.delivered()).isEqualTo(100);
    }

    private static OrderSummary order(OrderStatus status, PaymentTerm term, OrderSummary.Payment payment,
                                      OrderSummary.Lifecycle lifecycle, List<OrderSummary.LineSummary> lines) {
        return new OrderSummary(UUID.randomUUID(), "SO-20261020-000042", UUID.randomUUID(), status,
                Money.vnd(1_000_000), lines, PLACED, "sales", null, null, "Nguyễn Văn A", "a@khach.vn", "0903123456",
                new OrderSummary.AddressSummary("Nguyễn Văn A", "0903123456", "12 Lê Lợi", null, null, null, null,
                        null, "VN", null),
                null, term, UUID.randomUUID(), PLACED, payment, null, lifecycle);
    }

    private static OrderSummary.Lifecycle lifecycle(HoldReason hold, String note, BigDecimal due,
                                                    BigDecimal dueBeforeHandover,
                                                    List<OrderSummary.Shipment> shipments,
                                                    boolean allowPartialShipment, boolean printLinesReady) {
        return new OrderSummary.Lifecycle(hold, hold == null ? null : OrderStatus.PACKED, note, PLACED, null, null,
                null, null, null, BigDecimal.ZERO, BigDecimal.valueOf(1_000_000), due, dueBeforeHandover, List.of(),
                shipments, BigDecimal.ZERO, BigDecimal.ZERO, allowPartialShipment, printLinesReady);
    }

    private static OrderSummary.LineSummary line(UUID id, String sku, boolean printed) {
        return new OrderSummary.LineSummary(id, sku, 100, Money.vnd(5_000), Money.vnd(500_000), List.of(),
                printed ? UUID.randomUUID() : null, printed ? "a".repeat(64) : null);
    }

    private static OrderSummary.Shipment shipment(OrderShipmentStatus status, DeliveryFailureReason reason,
                                                  Instant handedOver, UUID line, int units, String code) {
        return new OrderSummary.Shipment(UUID.randomUUID(), status, BigDecimal.valueOf(500_000), BigDecimal.ZERO,
                handedOver, status == OrderShipmentStatus.HANDED_OVER ? null : handedOver.plusSeconds(3_600), reason,
                List.of(new OrderSummary.ShipmentLine(line, units)), BigDecimal.ZERO,
                new ShipmentHandover.Carriage(code, 1, "Giao Hàng Nhanh", "PARCEL"));
    }
}
