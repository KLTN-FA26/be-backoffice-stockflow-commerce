package com.stockflow.notification.internal.service;

import com.stockflow.common.http.RestClientFactory;
import com.stockflow.contracts.PurchaseOrderSent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationSenderTest {
    private PurchaseOrderSent event(String channel, String recipient) {
        return new PurchaseOrderSent(UUID.randomUUID(), "PO-1", UUID.randomUUID(), channel, recipient,
                BigDecimal.valueOf(200), "VND", LocalDate.of(2026, 10, 1), 30,
                List.of(new PurchaseOrderSent.Line("CHAIR-1", "Chair", 2, BigDecimal.valueOf(100))));
    }
    @Test void emailIncludesActualOrderLinesAndCommercialTerms() {
        var mail = mock(JavaMailSender.class);
        var sender = new NotificationSender(mail, mock(RestClientFactory.class), "buyer@example.com", "");
        sender.sendPurchaseOrder(event("EMAIL", "supplier@example.com"));
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertThat(message.getValue().getText()).contains("CHAIR-1", "quantity=2", "100 VND", "30 days", "200 VND");
    }
    @Test void unapprovedHostCannotReceiveOrderData() {
        var clients = mock(RestClientFactory.class);
        var sender = new NotificationSender(mock(JavaMailSender.class), clients, "buyer@example.com", "supplier.example.com");
        for (String url : List.of("https://127.0.0.1/", "https://supplier.example.com.evil.test/", "https://user@supplier.example.com/",
                "https://supplier.example.com/?token=secret", "http://supplier.example.com/")) {
            assertThatThrownBy(() -> sender.sendPurchaseOrder(event("API", url))).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(clients);
    }

    @Test void apiCarriesStableIdempotencyKeyAndRedirectIsNotSuccess() {
        var factory = mock(RestClientFactory.class);
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://supplier.example.com/po");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        when(factory.forService("supplier-po-api", "https://supplier.example.com/po")).thenReturn(builder);
        var sender = new NotificationSender(mock(JavaMailSender.class), factory, "buyer@example.com", "supplier.example.com");
        var event = event("API", "https://supplier.example.com/po");
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://supplier.example.com/po"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Idempotency-Key", "purchase-order:" + event.purchaseOrderId()))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.lines[0].sku").value("CHAIR-1"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.FOUND));
        assertThatThrownBy(() -> sender.sendPurchaseOrder(event)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
}
