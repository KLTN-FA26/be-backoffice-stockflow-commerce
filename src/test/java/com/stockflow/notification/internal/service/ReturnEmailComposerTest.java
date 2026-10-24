package com.stockflow.notification.internal.service;

import com.stockflow.contracts.ReturnApproved;
import com.stockflow.contracts.ReturnClosed;
import com.stockflow.contracts.ReturnReceived;
import com.stockflow.contracts.ReturnRejected;
import com.stockflow.contracts.ReturnRequested;
import com.stockflow.contracts.ReturnResolved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.stockflow.notification.internal.service.EmailFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;

/** The customer's messages along a return (RMA, SCRUM-243): one per step, in Vietnamese, named by the RMA number. */
class ReturnEmailComposerTest {

    private static final String RMA = "RMA-20261025-0001";
    private static final String ORDER = "SO-20261024-0001";
    private static final Instant AT = Instant.parse("2026-10-25T07:05:00Z");   // 14:05 in Ho Chi Minh City

    private final CustomerEmailComposer composer = new CustomerEmailComposer(properties());
    private final UUID returnId = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();

    private static String all(EmailContent content) {
        return content.subject() + "\n" + EmailTemplates.text(content);
    }

    @Test
    @DisplayName("claim received: RMA and order numbers, the reason and the wish in words, the lines; goods not sent yet")
    void requested() {
        var event = new ReturnRequested(returnId, RMA, UUID.randomUUID(), ORDER, customer, "PRINT_DEFECT",
                "REPLACEMENT", "PORTAL", List.of(new ReturnRequested.Line(UUID.randomUUID(), "CUP-PET-500", 120)),
                customer, AT);
        CustomerMessage message = composer.returnRequested(event).orElseThrow();

        assertThat(message.reference()).isEqualTo("customer-mail:return.requested:" + returnId);
        assertThat(message.customerId()).isEqualTo(customer);
        EmailContent content = message.draft().write("Công ty TNHH In Ly An Phát");
        assertThat(content.subject()).isEqualTo("Đã tiếp nhận yêu cầu đổi trả " + RMA);
        assertThat(content.facts()).extracting(EmailContent.Fact::value).containsExactly(RMA, ORDER,
                "Lỗi in (nhoè, mờ, lệch hoặc sai màu)", "In lại / đổi hàng mới", "14:05 25/10/2026");
        assertThat(content.table().rows()).containsExactly(List.of("CUP-PET-500", "120"));
        assertThat(all(content)).contains("Vui lòng chưa gửi hàng về").doesNotContain("null");
    }

    @Test
    @DisplayName("approved with goods to send: the deadline in Vietnam's time, how to send, and that some lines were cut")
    void approvedToSendBack() {
        var event = new ReturnApproved(returnId, RMA, UUID.randomUUID(), ORDER, customer, true,
                Instant.parse("2026-11-08T16:59:00Z"),
                List.of(new ReturnApproved.Line(UUID.randomUUID(), "CUP-PET-500", 120, 80),
                        new ReturnApproved.Line(UUID.randomUUID(), "LID-PET-90", 50, 50)),
                UUID.randomUUID(), AT);
        String text = all(composer.returnApproved(event).orElseThrow().draft().write("Công ty A"));

        assertThat(text).contains("Yêu cầu đổi trả " + RMA + " đã được duyệt", "Hạn gửi hàng về: 23:59 08/11/2026",
                "ghi rõ mã yêu cầu đổi trả bên ngoài kiện", "được duyệt với số lượng thấp hơn đề nghị");
        assertThat(composer.returnApproved(event).orElseThrow().draft().write(null).table().rows())
                .containsExactly(List.of("CUP-PET-500", "120", "80"), List.of("LID-PET-90", "50", "50"));
    }

    @Test
    @DisplayName("approved on the photos alone: no goods to send, no deadline, nothing said about cut lines")
    void approvedWithoutReturn() {
        var event = new ReturnApproved(returnId, RMA, UUID.randomUUID(), ORDER, customer, false, null,
                List.of(new ReturnApproved.Line(UUID.randomUUID(), "CUP-PET-500", 10, 10)), UUID.randomUUID(), AT);
        String text = all(composer.returnApproved(event).orElseThrow().draft().write(null));

        assertThat(text).contains("không cần gửi hàng về").doesNotContain("Hạn gửi hàng về", "thấp hơn đề nghị");
    }

    @Test
    @DisplayName("refused at inspection: why in a sentence, never the inspector's notes; Sales follows up")
    void rejectedAtInspection() {
        var event = new ReturnRejected(returnId, RMA, UUID.randomUUID(), ORDER, customer, "INSPECTION",
                UUID.randomUUID(), AT);
        CustomerMessage message = composer.returnRejected(event).orElseThrow();

        assertThat(message.reference()).isEqualTo("customer-mail:return.rejected:" + returnId);
        assertThat(all(message.draft().write(null))).contains("không được chấp nhận",
                "không phát hiện lỗi sản xuất", "Nhân viên kinh doanh phụ trách sẽ liên hệ");
    }

    @Test
    @DisplayName("goods in: approved against received per line, a wrong item shown as what came, and the discrepancy said")
    void receivedWithDiscrepancy() {
        var event = new ReturnReceived(returnId, RMA, UUID.randomUUID(), ORDER, customer, "HCM-QC01",
                List.of(new ReturnReceived.Line(UUID.randomUUID(), "CUP-PET-500", "CUP-PET-700", 80, 80),
                        new ReturnReceived.Line(UUID.randomUUID(), "LID-PET-90", null, 50, 45)),
                true, UUID.randomUUID(), AT);
        EmailContent content = composer.returnReceived(event).orElseThrow().draft().write(null);

        assertThat(content.table().rows()).containsExactly(List.of("CUP-PET-500 → CUP-PET-700", "80", "80"),
                List.of("LID-PET-90", "50", "45"));
        assertThat(all(content)).contains("Thời điểm nhận hàng: 14:05 25/10/2026", "có chênh lệch")
                .doesNotContain("HCM-QC01");
    }

    @Test
    @DisplayName("settled: replacement order, amounts in dong only where non-zero, and the adjustment invoice when due")
    void resolved() {
        var event = new ReturnResolved(returnId, RMA, UUID.randomUUID(), ORDER, customer, UUID.randomUUID(),
                "SO-20261025-0007", new BigDecimal("300000"), new BigDecimal("1250000"), BigDecimal.ZERO, "VND", true,
                List.of(new ReturnResolved.Line(UUID.randomUUID(), "CUP-PET-500", 80, 30, 50,
                        new BigDecimal("1550000"))), UUID.randomUUID(), AT);
        EmailContent content = composer.returnResolved(event).orElseThrow().draft().write(null);
        String text = all(content);

        assertThat(text).contains("Đơn hàng thay thế: SO-20261025-0007", "Số tiền trừ vào công nợ: 300.000 ₫",
                "Số tiền hoàn lại: 1.250.000 ₫", "không phải thanh toán thêm", "hoá đơn điều chỉnh")
                .doesNotContain("ghi có vào tài khoản", "null");
        assertThat(content.table().rows()).containsExactly(List.of("CUP-PET-500", "80", "30", "50"));
    }

    @Test
    @DisplayName("withdrawn says by whom; expired says the goods never came; any other outcome calls for nothing")
    void closed() {
        var byCustomer = new ReturnClosed(returnId, RMA, UUID.randomUUID(), ORDER, customer, "CANCELLED", true,
                customer, AT);
        CustomerMessage cancelled = composer.returnClosed(byCustomer).orElseThrow();
        assertThat(cancelled.reference()).isEqualTo("customer-mail:return.cancelled:" + returnId);
        assertThat(all(cancelled.draft().write(null))).contains("đã được huỷ", "theo đề nghị của Quý khách");

        var bySales = new ReturnClosed(returnId, RMA, UUID.randomUUID(), ORDER, customer, "CANCELLED", false,
                UUID.randomUUID(), AT);
        assertThat(all(composer.returnClosed(bySales).orElseThrow().draft().write(null)))
                .contains("bộ phận kinh doanh huỷ");

        var expired = new ReturnClosed(returnId, RMA, UUID.randomUUID(), ORDER, customer, "EXPIRED", false, null, AT);
        CustomerMessage message = composer.returnClosed(expired).orElseThrow();
        assertThat(message.reference()).isEqualTo("customer-mail:return.expired:" + returnId);
        assertThat(all(message.draft().write(null))).contains("đã hết hiệu lực", "kèm mã vận đơn");

        assertThat(composer.returnClosed(new ReturnClosed(returnId, RMA, UUID.randomUUID(), ORDER, customer,
                "SOMETHING_NEW", false, null, AT))).isEmpty();
    }
}
