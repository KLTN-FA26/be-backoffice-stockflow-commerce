package com.stockflow.notification.internal.service;

import com.stockflow.contracts.OrderDelivered;
import com.stockflow.contracts.OrderRemainderClosed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.stockflow.notification.internal.service.EmailFixtures.cancelled;
import static com.stockflow.notification.internal.service.EmailFixtures.placed;
import static com.stockflow.notification.internal.service.EmailFixtures.properties;
import static com.stockflow.notification.internal.service.EmailFixtures.shipment;
import static org.assertj.core.api.Assertions.assertThat;

/** Which message each order event calls for, and what it says - in Vietnamese, with Vietnamese money and time. */
class CustomerEmailComposerTest {

    private final CustomerEmailComposer composer = new CustomerEmailComposer(properties());
    private final UUID customer = UUID.randomUUID();

    /** The subject and the plain-text part: everything a customer reads. */
    private static String all(EmailContent content) {
        return content.subject() + "\n" + EmailTemplates.text(content);
    }

    @Test
    @DisplayName("order placed: number, total and lines in dong, greeted by the customer's name, keyed by the order")
    void orderPlaced() {
        var event = placed(customer, "contact@cup-maker.vn");
        CustomerMessage message = composer.orderPlaced(event).orElseThrow();

        assertThat(message.reference()).isEqualTo("customer-mail:order.placed:" + event.orderId());
        assertThat(message.customerId()).isEqualTo(customer);
        assertThat(message.guestAddress()).as("a customer's order is addressed from the directory").isNull();
        EmailContent content = message.draft().write("Công ty TNHH In Ly An Phát");
        assertThat(content.subject()).isEqualTo("Đơn hàng SO-20261024-0001 đã được tiếp nhận");
        assertThat(content.greeting()).isEqualTo("Kính gửi Công ty TNHH In Ly An Phát,");
        assertThat(content.facts()).extracting(EmailContent.Fact::value)
                .containsExactly("SO-20261024-0001", "1.500.000 ₫");
        assertThat(content.table().rows()).containsExactly(
                List.of("CUP-PET-500", "2000", "625 ₫", "1.250.000 ₫"),
                List.of("LID-PET-90", "2000", "125 ₫", "250.000 ₫"));
        assertThat(content.table().note()).isNull();
        assertThat(all(content)).contains("Trân trọng,", "StockFlow");
    }

    @Test
    @DisplayName("regression (review of G4-2a): a guest order's confirmation goes to the contact e-mail it carries")
    void guestOrder() {
        CustomerMessage message = composer.orderPlaced(placed(null, "guest@cup-maker.vn")).orElseThrow();
        assertThat(message.customerId()).isNull();
        assertThat(message.guestAddress()).isEqualTo("guest@cup-maker.vn");
        assertThat(message.draft().write(null).greeting()).isEqualTo("Kính gửi Quý khách,");
    }

    @Test
    @DisplayName("09 §4.1 step 3: handed over - the carrier and the packages, in Vietnam's time, no tracking or load code")
    void handedOver() {
        var event = shipment(customer, "HANDED_OVER", null, "Giao Hàng Nhanh");
        CustomerMessage message = composer.shipmentChanged(event).orElseThrow();

        assertThat(message.reference()).isEqualTo("customer-mail:shipment.handed-over:" + event.eventId());
        String text = all(message.draft().write("Công ty A"));
        assertThat(text).contains("Đơn hàng SO-20261024-0001 đã được bàn giao cho đơn vị vận chuyển",
                "Đơn vị vận chuyển: Giao Hàng Nhanh", "Số kiện hàng: 3", "Thời điểm bàn giao: 14:05 24/10/2026",
                "Mã lô giao hàng: SO-20261024-0001-S1");
        assertThat(text).doesNotContain("LD-20261024-0001", "tracking", "null");
    }

    @Test
    @DisplayName("a carrier not chosen yet is left out rather than printed as nothing")
    void handedOverWithoutCarrier() {
        String text = all(composer.shipmentChanged(shipment(customer, "HANDED_OVER", null, null)).orElseThrow()
                .draft().write("A"));
        assertThat(text).doesNotContain("Đơn vị vận chuyển:");
    }

    @Test
    @DisplayName("delivered and not delivered (with a reason) are told; packing, return and cancellation are not")
    void otherShipmentSteps() {
        assertThat(all(composer.shipmentChanged(shipment(customer, "DELIVERED", null, "GHN")).orElseThrow()
                .draft().write("A"))).contains("đã được giao thành công", "Thời điểm giao hàng: 14:05 24/10/2026");
        assertThat(all(composer.shipmentChanged(shipment(customer, "DELIVERY_FAILED", "REFUSED", "GHN"))
                .orElseThrow().draft().write("A")))
                .contains("Giao hàng đơn SO-20261024-0001 chưa thành công", "Lý do: Người nhận từ chối nhận hàng",
                        "Bộ phận chăm sóc khách hàng sẽ liên hệ");
        for (String silent : List.of("READY_TO_DISPATCH", "RETURNED", "CANCELLED", "SOMETHING_NEW")) {
            assertThat(composer.shipmentChanged(shipment(customer, silent, null, "GHN"))).as(silent).isEmpty();
        }
    }

    @Test
    @DisplayName("a shipment change with no event id is still keyed per shipment step, within the log's 100 characters")
    void shipmentChangeWithoutEventId() {
        var base = shipment(customer, "DELIVERY_FAILED", "OTHER", "GHN");
        var event = new com.stockflow.contracts.ShipmentStatusChanged(null, base.shipmentId(), base.shipmentCode(),
                base.orderId(), base.orderNumber(), customer, base.warehouseId(), base.deliveryMode(),
                base.previousStatus(), base.status(), base.failureReasonCode(), base.packageCount(), base.carrierName(),
                base.loadCode(), base.recordedBy(), base.occurredAt());
        String reference = composer.shipmentChanged(event).orElseThrow().reference();
        assertThat(reference).isEqualTo("customer-mail:shipment.delivery-failed:" + base.shipmentId() + "@"
                + base.occurredAt().toEpochMilli());
        assertThat(reference.length()).isLessThanOrEqualTo(CustomerMessage.MAX_REFERENCE_LENGTH);
    }

    @Test
    @DisplayName("an order delivered through shipments was told per shipment; one delivered whole is told once")
    void orderDelivered() {
        UUID order = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-24T02:00:00Z");
        assertThat(composer.orderDelivered(new OrderDelivered(order, "SO-1", customer, "CREDIT", BigDecimal.TEN,
                BigDecimal.ZERO, "VND", 30, at, UUID.randomUUID(), BigDecimal.TEN))).isEmpty();
        CustomerMessage whole = composer.orderDelivered(new OrderDelivered(order, "SO-1", customer, "PREPAID",
                BigDecimal.TEN, BigDecimal.TEN, "VND", null, at)).orElseThrow();
        assertThat(whole.reference()).isEqualTo("customer-mail:order.delivered:" + order);
        assertThat(all(whole.draft().write("A"))).contains("Đơn hàng SO-1 đã được giao thành công",
                "Thời điểm giao hàng: 09:00 24/10/2026");
    }

    @Test
    @DisplayName("cancelled: the reason as a sentence and the refund - never the note somebody typed")
    void cancelledWithRefund() {
        var event = cancelled(customer, "CUSTOMER_REQUEST", new BigDecimal("1200000"), BigDecimal.ZERO);
        String text = all(composer.orderCancelled(event).orElseThrow().draft().write("Công ty A"));
        assertThat(text).contains("Đơn hàng SO-20261024-0001 đã bị huỷ", "Lý do: Theo yêu cầu của Quý khách",
                "Số tiền hoàn lại: 1.200.000 ₫", "bộ phận kế toán xử lý", "Thời điểm huỷ: 23:30 24/10/2026");
        assertThat(text).doesNotContain("Phí cần thanh toán", "anh Ba", "0901234567", "giao nhầm kho");
    }

    @Test
    @DisplayName("cancelled with a fee still owed (Q6a): the fee and what to do about it")
    void cancelledWithFeeOwed() {
        String text = all(composer.orderCancelled(cancelled(customer, "PRODUCTION_ISSUE", BigDecimal.ZERO,
                new BigDecimal("350000"))).orElseThrow().draft().write("A"));
        assertThat(text).contains("Phí cần thanh toán: 350.000 ₫", "Lý do: Phát sinh sự cố trong quá trình sản xuất");
        assertThat(text).doesNotContain("Số tiền hoàn lại");
    }

    @Test
    @DisplayName("suspected fraud is never named to the customer: no reason line, only 'contact Sales'")
    void undisclosedReason() {
        String text = all(composer.orderCancelled(cancelled(customer, "SUSPECTED_FRAUD", BigDecimal.ZERO, null))
                .orElseThrow().draft().write("A"));
        assertThat(text).doesNotContain("Lý do:", "FRAUD", "gian lận");
        assertThat(text).contains("vui lòng liên hệ nhân viên kinh doanh");
    }

    @Test
    @DisplayName("part of the order closed: which lines, at most 50 listed, and the refund; one message per closure")
    void remainderClosed() {
        UUID closure = UUID.randomUUID();
        List<OrderRemainderClosed.Line> lines = IntStream.rangeClosed(1, 60)
                .mapToObj(i -> new OrderRemainderClosed.Line(UUID.randomUUID(), "SKU-" + i, i)).toList();
        var event = new OrderRemainderClosed(UUID.randomUUID(), "SO-9", customer, "OUT_OF_STOCK", lines,
                new BigDecimal("500000"), new BigDecimal("1500000"), new BigDecimal("2000000"),
                new BigDecimal("500000"), "VND", UUID.randomUUID(), Instant.parse("2026-10-24T03:00:00Z"), closure);
        CustomerMessage message = composer.remainderClosed(event).orElseThrow();

        assertThat(message.reference()).isEqualTo("customer-mail:order.remainder-closed:" + closure);
        EmailContent content = message.draft().write("A");
        assertThat(content.table().rows()).hasSize(CustomerEmailComposer.MAX_LINES)
                .first().isEqualTo(List.of("SKU-1", "1"));
        assertThat(content.table().note()).isEqualTo("… và 10 dòng hàng khác.");
        assertThat(all(content)).contains("Số tiền hoàn lại: 500.000 ₫", "Lý do: Không đủ hàng để đáp ứng đơn hàng");
    }

    @Test
    @DisplayName("dong are whole and dotted (1.250.000 ₫); another currency keeps two decimals and its code")
    void money() {
        assertThat(CustomerEmailComposer.money(new BigDecimal("1250000"), "VND")).isEqualTo("1.250.000 ₫");
        assertThat(CustomerEmailComposer.money(new BigDecimal("1250000.50"), "VND")).isEqualTo("1.250.001 ₫");
        assertThat(CustomerEmailComposer.money(new BigDecimal("999"), null)).isEqualTo("999 ₫");
        assertThat(CustomerEmailComposer.money(new BigDecimal("1234.5"), "usd")).isEqualTo("1.234,50 USD");
        assertThat(CustomerEmailComposer.money(null, "VND")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\t"})
    @DisplayName("no name to greet by: 'Quý khách'")
    void anonymousGreeting(String name) {
        assertThat(CustomerEmailComposer.displayName(name)).isNull();
    }

    @Test
    @DisplayName("a very long or multi-line name is one line of at most 120 characters")
    void longNames() {
        String name = CustomerEmailComposer.displayName("Công ty\r\nTNHH " + "Bao Bì ".repeat(40));
        assertThat(name).doesNotContain("\n", "\r").startsWith("Công ty TNHH Bao Bì").endsWith("…");
        assertThat(name.codePointCount(0, name.length())).isLessThanOrEqualTo(CustomerEmailComposer.MAX_NAME_LENGTH);
        assertThat(CustomerEmailComposer.displayName("Công ty TNHH In Ly An Phát")).isEqualTo("Công ty TNHH In Ly An Phát");
    }

    @Test
    @DisplayName("with a reply-to the footer invites a reply; without, it says not to reply")
    void footer() {
        var withReply = new CustomerEmailComposer(properties("sales@stockflow.vn", true, 10));
        assertThat(withReply.orderPlaced(placed(customer, null)).orElseThrow().draft().write("A").footer())
                .contains("có thể trả lời trực tiếp");
        assertThat(composer.orderPlaced(placed(customer, null)).orElseThrow().draft().write("A").footer())
                .contains("vui lòng không trả lời");
    }
}
