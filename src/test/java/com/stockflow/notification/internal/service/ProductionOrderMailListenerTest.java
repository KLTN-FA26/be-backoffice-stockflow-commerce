package com.stockflow.notification.internal.service;

import com.stockflow.common.security.SignedLinkProperties;
import com.stockflow.common.security.SignedLinks;
import com.stockflow.contracts.ProductionOrderSampleApproved;
import com.stockflow.contracts.ProductionOrderSent;
import com.stockflow.contracts.ProductionOrderStopped;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.ProductionOrderDirectory;
import com.stockflow.notification.api.ProductionOrderMail;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.domain.EmailDeliveryException;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The messages to a production order's producer (4A-3, QD-7): one per message, whatever the redeliveries; nothing by
 * e-mail for a producer handed the slip; never an order after its stop; a transient failure kept for the retry.
 */
class ProductionOrderMailListenerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final UUID ID = UUID.fromString("0192f000-0000-7000-8000-0000000000aa");
    private static final UUID COORDINATOR = UUID.fromString("0192f000-0000-7000-8000-0000000000bb");
    private static final UUID DESIGN = UUID.fromString("0192f000-0000-7000-8000-0000000000cc");

    private final ProductionOrderDirectory directory = mock(ProductionOrderDirectory.class);
    private final RecordingEmailTransport transport = new RecordingEmailTransport();
    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final DeliveryLogJpaRepository logs = mock(DeliveryLogJpaRepository.class);
    private final DeliveryAttemptRecorder attempts = mock(DeliveryAttemptRecorder.class);
    private final SignedLinks links = new SignedLinks(new SignedLinkProperties(
            Base64.getEncoder().encodeToString(new byte[32]), "https://api.stockflow.vn"));
    private ProductionOrderMailListener listener;

    @BeforeEach
    void setUp() {
        listener = listener(transport);
        when(logs.tryLockDelivery(anyString())).thenReturn(true);
        when(staff.activeContact(COORDINATOR)).thenReturn(Optional.of(new StaffContact(COORDINATOR,
                "dieuphoi@stockflow.vn")));
    }

    private ProductionOrderMailListener listener(com.stockflow.notification.internal.domain.EmailTransport using) {
        return new ProductionOrderMailListener(directory, new ProductionOrderMessages(EmailFixtures.properties(), links),
                using, staff, logs, Clock.fixed(NOW, ZoneOffset.UTC), attempts);
    }

    private static ProductionOrderMail mail(String channel, String email, String status, String stopReason) {
        return new ProductionOrderMail(ID, "PRD-20261110-0001", "Xưởng in Bình Dương", channel, email, COORDINATOR,
                status, LocalDate.of(2026, 11, 30), "Kho HCM", "12 Nguyễn Huệ", true, new BigDecimal("1250"), "VND",
                "Giao buổi sáng", stopReason,
                List.of(new ProductionOrderMail.Line("CUP-1", "Ly in logo", 12, 50, 600, "600 x 400 x 350 mm",
                        "Dán nhãn mã đơn", new ProductionOrderMail.File("CUP-1 print file",
                        "/api/v1/production-order-files/" + ID + "/print-files/" + DESIGN))),
                new ProductionOrderMail.File("PRD-20261110-0001.pdf", "/api/v1/production-order-files/" + ID + "/slip"),
                NOW.plus(Duration.ofDays(30)));
    }

    /** What a stop reads: no line, no size, no file. */
    private static ProductionOrderMail stop(String status, String reason) {
        return new ProductionOrderMail(ID, "PRD-20261110-0001", "Xưởng in Bình Dương", "EMAIL", "don@xuong.vn",
                COORDINATOR, status, LocalDate.of(2026, 11, 30), null, null, true, null, null, null, reason, List.of(),
                null, null);
    }

    private static ProductionOrderSent sent() {
        return new ProductionOrderSent(UUID.randomUUID(), ID, "PRD-20261110-0001", UUID.randomUUID(),
                LocalDate.of(2026, 11, 30), 12, new BigDecimal("1.008"), NOW);
    }

    private DeliveryLogJpaEntity savedRow() {
        ArgumentCaptor<DeliveryLogJpaEntity> row = ArgumentCaptor.forClass(DeliveryLogJpaEntity.class);
        verify(logs).saveAndFlush(row.capture());
        return row.getValue();
    }

    @Test
    @DisplayName("sent: one e-mail to the producer with the slip and print-file links, the reply to the coordinator")
    void sentOnce() {
        when(directory.mail(eq(ID), any())).thenReturn(Optional.of(mail("EMAIL", "don@xuong.vn", "SENT", null)));

        listener.on(sent());

        assertThat(transport.sent()).singleElement().satisfies(email -> {
            assertThat(email.to()).containsExactly("don@xuong.vn");
            assertThat(email.replyTo()).isEqualTo("dieuphoi@stockflow.vn");
            assertThat(email.idempotencyKey()).isEqualTo("production-order:" + ID + ":sent");
            assertThat(email.category()).isEqualTo("production_order_sent");
            assertThat(email.subject()).contains("PRD-20261110-0001", "30/11/2026");
            assertThat(email.text())
                    .contains("Kính gửi Xưởng in Bình Dương", "Kho HCM - 12 Nguyễn Huệ", "Ngày cần hàng: 30/11/2026")
                    .contains("CUP-1 Ly in logo: 12 thùng x 50 cái/thùng = 600 cái", "Dán nhãn mã đơn")
                    .contains("Giao buổi sáng", "1250 VND")
                    .contains("https://api.stockflow.vn/api/v1/production-order-files/" + ID + "/slip?expires=")
                    .contains("/print-files/" + DESIGN + "?expires=", "&signature=")
                    .as("the links' expiry, in Vietnam's time").contains("10:00 10/12/2026");
        });
        DeliveryLogJpaEntity row = savedRow();
        assertThat(row.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(row.getExternalReference()).isEqualTo("production-order:" + ID + ":sent");
        assertThat(row.getRecipient()).isEqualTo("don@xuong.vn");
        assertThat(row.getTemplateCode()).isEqualTo("production_order_sent");
    }

    @Test
    @DisplayName("a redelivered event, or a retry after it went: nothing is sent again")
    void neverTwice() {
        when(directory.mail(eq(ID), any())).thenReturn(Optional.of(mail("EMAIL", "don@xuong.vn", "SENT", null)));
        when(logs.existsByExternalReference("production-order:" + ID + ":sent")).thenReturn(true);

        listener.on(sent());

        assertThat(transport.sent()).isEmpty();
        verify(logs, never()).saveAndFlush(any());
        verify(directory, never()).mail(any(), any());
    }

    @Test
    @DisplayName("another message of the same production order is being sent: wait, the event is tried again")
    void oneAtATime() {
        when(logs.tryLockDelivery("production-order:" + ID)).thenReturn(false);

        assertThatThrownBy(() -> listener.on(sent())).isInstanceOf(IllegalStateException.class);
        assertThat(transport.sent()).isEmpty();
    }

    @Test
    @DisplayName("a producer the coordinator hands the slip: recorded as skipped, no e-mail")
    void pdfChannel() {
        when(directory.mail(eq(ID), any())).thenReturn(Optional.of(mail("PDF", null, "SENT", null)));

        listener.on(sent());

        assertThat(transport.sent()).isEmpty();
        DeliveryLogJpaEntity row = savedRow();
        assertThat(row.getStatus()).isEqualTo(DeliveryStatus.SKIPPED);
        assertThat(row.getError()).isEqualTo("PRODUCER_CHANNEL_PDF");
        assertThat(row.isTerminal()).isTrue();
        assertThat(row.getOperationReference()).isEqualTo("production-order:" + ID + ":sent");
    }

    @Test
    @DisplayName("the order was stopped before its 'sent' went out: closed unsent, so it never follows the stop")
    void sentAfterStopIsClosed() {
        when(directory.mail(eq(ID), any())).thenReturn(Optional.of(mail("EMAIL", "don@xuong.vn", "CANCELLED", "Khách huỷ")));

        listener.on(sent());
        listener.on(new ProductionOrderSampleApproved(UUID.randomUUID(), ID, "PRD-20261110-0001", NOW));

        assertThat(transport.sent()).isEmpty();
        ArgumentCaptor<DeliveryLogJpaEntity> rows = ArgumentCaptor.forClass(DeliveryLogJpaEntity.class);
        verify(logs, org.mockito.Mockito.times(2)).saveAndFlush(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(row.getError()).isEqualTo(ProductionOrderMailListener.SUPERSEDED_BY_STOP);
            assertThat(row.isTerminal()).isTrue();
        });
    }

    @Test
    @DisplayName("stopped: the producer is told to stop, with the reason, and no link")
    void stopped() {
        when(logs.existsByExternalReference("production-order:" + ID + ":sent")).thenReturn(true);
        when(directory.stop(ID)).thenReturn(Optional.of(stop("CANCELLED", "Khách huỷ đơn")));

        listener.on(new ProductionOrderStopped(UUID.randomUUID(), ID, "PRD-20261110-0001", "CANCELLED", NOW));

        assertThat(transport.sent()).singleElement().satisfies(email -> {
            assertThat(email.subject()).contains("PRD-20261110-0001", "ĐÃ HUỶ");
            assertThat(email.text()).contains("đã bị huỷ", "Lý do: Khách huỷ đơn").doesNotContain("signature=");
            assertThat(email.idempotencyKey()).isEqualTo("production-order:" + ID + ":stopped");
            assertThat(email.category()).isEqualTo("production_order_stopped");
        });
        verify(directory, never()).mail(any(), any());
    }

    @Test
    @DisplayName("closed after mass printing started: 'closed, deliver nothing more'")
    void closed() {
        when(logs.existsByExternalReference("production-order:" + ID + ":sample-approved")).thenReturn(true);
        when(directory.stop(ID)).thenReturn(Optional.of(stop("CLOSED", "Xưởng dừng")));

        listener.on(new ProductionOrderStopped(UUID.randomUUID(), ID, "PRD-20261110-0001", "CLOSED", NOW));

        assertThat(transport.sent()).singleElement().satisfies(email -> {
            assertThat(email.subject()).contains("ĐÃ ĐÓNG");
            assertThat(email.text()).contains("đã được đóng", "Lý do: Xưởng dừng");
        });
    }

    @Test
    @DisplayName("sample approved: 'print the batch', with fresh links to the print files")
    void sampleApproved() {
        when(directory.mail(eq(ID), any())).thenReturn(Optional.of(mail("EMAIL", "don@xuong.vn", "IN_PRODUCTION", null)));

        listener.on(new ProductionOrderSampleApproved(UUID.randomUUID(), ID, "PRD-20261110-0001", NOW));

        assertThat(transport.sent()).singleElement().satisfies(email -> {
            assertThat(email.subject()).contains("mẫu in đã duyệt");
            assertThat(email.text()).contains("bắt đầu in hàng loạt", "/print-files/" + DESIGN + "?expires=")
                    .doesNotContain("/slip?expires=");
            assertThat(email.idempotencyKey()).isEqualTo("production-order:" + ID + ":sample-approved");
        });
    }

    @Test
    @DisplayName("a failure worth retrying: recorded, and thrown so the publication stays for the retry")
    void transientFailure() {
        when(directory.mail(eq(ID), any())).thenReturn(Optional.of(mail("EMAIL", "don@xuong.vn", "SENT", null)));
        var failing = mock(com.stockflow.notification.internal.domain.EmailTransport.class);
        var down = EmailDeliveryException.retryable("RESEND_UNAVAILABLE", null, null);
        when(failing.send(any(Email.class))).thenThrow(down);

        assertThatThrownBy(() -> listener(failing).on(sent())).isSameAs(down);
        verify(attempts).failed(eq("don@xuong.vn"), any(), eq(down), eq("production-order:" + ID + ":sent"),
                eq("production_order_sent"), eq(false), eq(0));
    }

    @Test
    @DisplayName("refused for good, or the fifth failure: recorded as final, not thrown")
    void permanentFailure() {
        when(directory.mail(eq(ID), any())).thenReturn(Optional.of(mail("EMAIL", "don@xuong.vn", "SENT", null)));
        var failing = mock(com.stockflow.notification.internal.domain.EmailTransport.class);
        when(failing.send(any(Email.class))).thenThrow(EmailDeliveryException.rejected("RECIPIENT_INVALID", null));

        listener(failing).on(sent());

        verify(attempts).failed(anyString(), any(), any(), anyString(), anyString(), eq(true), anyInt());
        when(logs.countByOperationReferenceAndDeliveryGenerationAndStatus(anyString(), anyInt(), any()))
                .thenReturn((long) RecordedDelivery.RETRIED_FAILURES);
        var stillDown = mock(com.stockflow.notification.internal.domain.EmailTransport.class);
        when(stillDown.send(any(Email.class)))
                .thenThrow(EmailDeliveryException.retryable("RESEND_UNAVAILABLE", null, null));
        listener(stillDown).on(sent());
        verify(attempts, org.mockito.Mockito.times(2)).failed(anyString(), any(), any(), anyString(), anyString(),
                anyBoolean(), anyInt());
    }

    @Test
    @DisplayName("no public address configured: the e-mail says the coordinator sends the files, with no link")
    void noLinks() {
        when(directory.mail(eq(ID), any())).thenReturn(Optional.of(mail("EMAIL", "don@xuong.vn", "SENT", null)));
        var unlinked = new ProductionOrderMailListener(directory, new ProductionOrderMessages(
                EmailFixtures.properties(), new SignedLinks(new SignedLinkProperties(null, null))), transport, staff,
                logs, Clock.fixed(NOW, ZoneOffset.UTC), attempts);

        unlinked.on(sent());

        assertThat(transport.sent()).singleElement().satisfies(email -> assertThat(email.text())
                .contains("Điều phối sản xuất sẽ gửi phiếu và file in").doesNotContain("signature="));
    }

    @Test
    @DisplayName("nothing reached the producer by e-mail: there is nothing to stop, and no stop is sent")
    void nothingToStop() {
        when(directory.stop(ID)).thenReturn(Optional.of(stop("CANCELLED", "Huỷ")));

        listener.on(new ProductionOrderStopped(UUID.randomUUID(), ID, "PRD-20261110-0001", "CANCELLED", NOW));

        assertThat(transport.sent()).isEmpty();
        assertThat(savedRow().getError()).isEqualTo("NOTHING_SENT_TO_STOP");
    }

    @Test
    @DisplayName("every attempt at one message is the same message: the links expire a fixed time after the event")
    void sameMessageOnEveryAttempt() {
        var issued = ArgumentCaptor.forClass(Instant.class);
        when(directory.mail(eq(ID), issued.capture())).thenReturn(Optional.of(mail("EMAIL", "don@xuong.vn", "SENT",
                null)));
        var event = sent();

        listener.on(event);

        assertThat(issued.getValue()).as("the event's time, not the attempt's").isEqualTo(event.sentAt());
    }

    @Test
    @DisplayName("the production order cannot be read: a failed attempt, so the attempt limit applies")
    void unreadable() {
        var broken = new IllegalStateException("no producer");
        when(directory.mail(eq(ID), any())).thenThrow(broken);

        assertThatThrownBy(() -> listener.on(sent())).isSameAs(broken);
        verify(attempts).failed(eq("-"), any(), eq(broken), eq("production-order:" + ID + ":sent"),
                eq("production_order_sent"), eq(false), eq(0));
        assertThat(transport.sent()).isEmpty();
    }

    @Test
    @DisplayName("a stop never reads the lines: a SKU's missing packaging cannot keep the producer from being told")
    void stopReadsNoLines() {
        when(logs.existsByExternalReference("production-order:" + ID + ":sent")).thenReturn(true);
        when(directory.mail(any(), any())).thenThrow(new IllegalStateException("no packaging for CUP-1"));
        when(directory.stop(ID)).thenReturn(Optional.of(stop("CANCELLED", "Khách huỷ")));

        listener.on(new ProductionOrderStopped(UUID.randomUUID(), ID, "PRD-20261110-0001", "CANCELLED", NOW));

        assertThat(transport.sent()).singleElement().satisfies(email -> assertThat(email.subject()).contains("ĐÃ HUỶ"));
    }

    @Test
    @DisplayName("a production order no longer there: settled as skipped, never retried")
    void gone() {
        when(directory.mail(eq(ID), any())).thenReturn(Optional.empty());

        listener.on(sent());

        assertThat(transport.sent()).isEmpty();
        assertThat(savedRow().getError()).isEqualTo("PRODUCTION_ORDER_NOT_FOUND");
    }
}
