package com.stockflow.notification.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import com.stockflow.notification.internal.repository.PoDeliveryControlRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * SCRUM-117: the notice of an approved amendment is a {@code PurchaseOrderSent} like the order's, and the listener and
 * the sender treat it as its own delivery - not skipped for a suppressed order, mailed with the revision and the
 * changes, posted with its own idempotency key.
 */
class PurchaseOrderAmendmentNoticeTest {

    private static final UUID PO = UUID.randomUUID();
    private static final PurchaseOrderSent.Buyer BUYER = new PurchaseOrderSent.Buyer("Buyer Company",
            "Company Address", "Purchasing Contact", "0901234567", "buyer@example.com", "Receiving Warehouse");

    private static PurchaseOrderSent amendment(String channel, String recipient) {
        return new PurchaseOrderSent(PO, "PO-1", UUID.randomUUID(), channel, recipient, BigDecimal.valueOf(160),
                "VND", LocalDate.of(2026, 10, 1), 30,
                List.of(new PurchaseOrderSent.Line("CHAIR-1", "Chair", 2, BigDecimal.valueOf(80))), 0, BUYER, false,
                null, 2, "Supplier can deliver less",
                List.of(new PurchaseOrderSent.Change(1, "CHAIR-1", "quantity", "3", "2")));
    }

    @Test
    void amendmentNoticeDeliveredForSuppressedOrder() {
        var control = mock(PoDeliveryControlRepository.class);
        var logs = mock(DeliveryLogJpaRepository.class);
        var sender = mock(NotificationSender.class);
        var event = amendment("EMAIL", "supplier@example.com");
        // The supplier answered (suppressed) and a recovery moved the generation on: neither stops an amendment.
        when(control.lock(PO)).thenReturn(new PoDeliveryControlRepository.Control(3, true));
        when(logs.tryLockDelivery(anyString())).thenReturn(true);

        new PurchaseOrderNotificationListener(sender, logs, Clock.systemUTC(), mock(DeliveryAttemptRecorder.class),
                control).on(event);

        verify(sender).sendPurchaseOrder(event);
        var logged = ArgumentCaptor.forClass(DeliveryLogJpaEntity.class);
        verify(logs).saveAndFlush(logged.capture());
        assertThat(logged.getValue().getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(logged.getValue().getExternalReference()).isEqualTo("purchase-order:" + PO + ":revision:2");
        assertThat(logged.getValue().getTemplateCode()).isEqualTo("purchase-order.amended");
    }

    @Test
    void failedAmendmentAttemptIsLoggedUnderTheAmendmentTemplate() {
        var control = mock(PoDeliveryControlRepository.class);
        var logs = mock(DeliveryLogJpaRepository.class);
        var sender = mock(NotificationSender.class);
        var event = amendment("EMAIL", "supplier@example.com");
        when(control.lock(PO)).thenReturn(new PoDeliveryControlRepository.Control(0, false));
        when(logs.tryLockDelivery(anyString())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("invalid")).when(sender).sendPurchaseOrder(event);

        new PurchaseOrderNotificationListener(sender, logs, Clock.systemUTC(), new DeliveryAttemptRecorder(logs),
                control).on(event);

        var logged = ArgumentCaptor.forClass(DeliveryLogJpaEntity.class);
        verify(logs).saveAndFlush(logged.capture());
        assertThat(logged.getValue().getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(logged.getValue().getTemplateCode()).isEqualTo("purchase-order.amended");
    }

    @Test
    void emailCarriesTheRevisionTheReasonAndTheChanges() {
        var mail = mock(JavaMailSender.class);
        var sender = new NotificationSender(mail, mock(RestClientFactory.class), "buyer@example.com", "");

        sender.sendPurchaseOrder(amendment("EMAIL", "supplier@example.com"));

        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertThat(message.getValue().getSubject()).contains("PO-1", "sửa đổi", "bản 2");
        assertThat(message.getValue().getText()).contains("Supplier can deliver less", "Dòng 1 CHAIR-1: quantity 3",
                "→ 2", "Buyer Company", "CHAIR-1", "160 VND", "30 ngày");
    }

    @Test
    void apiAmendmentCarriesItsOwnIdempotencyKey() {
        var factory = mock(RestClientFactory.class);
        var builder = RestClient.builder().baseUrl("https://supplier.example.com/po");
        var server = MockRestServiceServer.bindTo(builder).build();
        when(factory.forService("supplier-po-api", "https://supplier.example.com/po")).thenReturn(builder);
        var sender = new NotificationSender(mock(JavaMailSender.class), factory, "buyer@example.com",
                "supplier.example.com");
        server.expect(requestTo("https://supplier.example.com/po"))
                .andExpect(header("Idempotency-Key", "purchase-order:" + PO + ":revision:2"))
                .andExpect(jsonPath("$.type").value("AMENDMENT"))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.changes[0].field").value("quantity"))
                .andRespond(withSuccess());

        sender.sendPurchaseOrder(amendment("API", "https://supplier.example.com/po"));

        server.verify();
    }

    /**
     * Review focus 4: a publication written before the amendment components existed sits in {@code event_publication}
     * as JSON without them, and is read back by the registry with the application's mapper. It must read as the order
     * itself, not as an amendment.
     */
    @Test
    void oldPublicationStillDeserializes() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode stored = (ObjectNode) mapper.readTree(mapper.writeValueAsString(
                amendment("EMAIL", "supplier@example.com")));
        stored.remove(List.of("amendmentRevisionNo", "amendmentReason", "changes"));

        PurchaseOrderSent read = mapper.readValue(stored.toString(), PurchaseOrderSent.class);

        assertThat(read.amendment()).isFalse();
        assertThat(read.changes()).isEmpty();
        assertThat(read.operationReference()).isEqualTo("purchase-order:" + PO);
        assertThat(read.templateCode()).isEqualTo("purchase-order.sent");
        var mail = mock(JavaMailSender.class);
        new NotificationSender(mail, mock(RestClientFactory.class), "buyer@example.com", "").sendPurchaseOrder(read);
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertThat(message.getValue().getSubject()).doesNotContain("sửa đổi").contains("PO-1");
    }
}
