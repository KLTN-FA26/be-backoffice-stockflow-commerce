package com.stockflow.notification.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.EmailProvider;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.domain.ProviderStatus;
import com.stockflow.notification.internal.domain.WebhookSignature;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Resend's delivery reports: verified first, then recorded on the message they name, or acknowledged and ignored. */
class ResendWebhookServiceTest {

    private static final String SECRET = "whsec_" + Base64.getEncoder()
            .encodeToString("webhook-secret-for-the-unit-test".getBytes(StandardCharsets.UTF_8));
    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");

    private final DeliveryLogJpaRepository logs = mock(DeliveryLogJpaRepository.class);
    private final WebhookSignature signer = new WebhookSignature(SECRET, Duration.ofMinutes(5));

    private ResendWebhookService service(String secret) {
        var properties = new EmailProperties(EmailProvider.RESEND, EmailFixtures.FROM, null,
                Locale.forLanguageTag("vi"), "StockFlow",
                new EmailProperties.Customer(true, 10, Duration.ofMinutes(1), Duration.ofHours(6)),
                new EmailProperties.Resend("re_key", "https://api.resend.com", secret, Duration.ofMinutes(5), 2, 2));
        return new ResendWebhookService(logs, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), properties);
    }

    private static byte[] report(String type, String emailId, String createdAt) {
        return ("{\"type\":\"" + type + "\",\"created_at\":\"" + createdAt + "\",\"data\":{\"email_id\":\"" + emailId
                + "\",\"to\":[\"buyer@cup-maker.vn\"],\"subject\":\"Đơn hàng SO-1\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private ResendWebhookService.Outcome receive(ResendWebhookService service, byte[] body) {
        return service.receive("msg_1", Long.toString(NOW.getEpochSecond()), signer.sign("msg_1", NOW, body), body);
    }

    private static DeliveryLogJpaEntity sentRow() {
        var row = new DeliveryLogJpaEntity(Identifiers.newId(), "order.placed", NotificationChannel.EMAIL,
                "buyer@cup-maker.vn", DeliveryStatus.SENT, null, NOW, "customer-mail:order.placed:o-1");
        row.recordProvider(EmailProvider.RESEND, "e-1");
        return row;
    }

    @Test
    @DisplayName("a signed 'delivered' is recorded on the message it names, at the time Resend reported it")
    void delivered() {
        var row = sentRow();
        when(logs.findByProviderAndProviderMessageId(EmailProvider.RESEND, "e-1")).thenReturn(Optional.of(row));

        assertThat(receive(service(SECRET), report("email.delivered", "e-1", "2026-10-24T07:59:30.123Z")))
                .isEqualTo(ResendWebhookService.Outcome.RECORDED);
        assertThat(row.getProviderStatus()).isEqualTo(ProviderStatus.DELIVERED);
        assertThat(row.getProviderStatusAt()).isEqualTo(Instant.parse("2026-10-24T07:59:30.123Z"));
    }

    @Test
    @DisplayName("a late 'delayed' after 'bounced' changes nothing; a repeated report changes nothing")
    void outOfOrder() {
        var row = sentRow();
        when(logs.findByProviderAndProviderMessageId(EmailProvider.RESEND, "e-1")).thenReturn(Optional.of(row));
        var service = service(SECRET);
        receive(service, report("email.bounced", "e-1", "2026-10-24T07:59:00Z"));
        assertThat(receive(service, report("email.delivery_delayed", "e-1", "2026-10-24T07:59:30Z")))
                .isEqualTo(ResendWebhookService.Outcome.UNCHANGED);
        assertThat(receive(service, report("email.bounced", "e-1", "2026-10-24T07:59:00Z")))
                .isEqualTo(ResendWebhookService.Outcome.UNCHANGED);
        assertThat(row.getProviderStatus()).isEqualTo(ProviderStatus.BOUNCED);
    }

    @Test
    @DisplayName("an unknown message, an untracked type or a signed body that is not JSON is acknowledged and ignored")
    void ignored() {
        when(logs.findByProviderAndProviderMessageId(any(), anyString())).thenReturn(Optional.empty());
        var service = service(SECRET);
        assertThat(receive(service, report("email.delivered", "someone-elses", "2026-10-24T07:59:00Z")))
                .isEqualTo(ResendWebhookService.Outcome.IGNORED);
        assertThat(receive(service, report("email.opened", "e-1", "2026-10-24T07:59:00Z")))
                .isEqualTo(ResendWebhookService.Outcome.IGNORED);
        assertThat(receive(service, "not json".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(ResendWebhookService.Outcome.IGNORED);
    }

    @Test
    @DisplayName("unsigned, wrongly signed or replayed: 401 before anything is read or written")
    void refused() {
        var service = service(SECRET);
        byte[] body = report("email.delivered", "e-1", "2026-10-24T07:59:00Z");
        assertThatThrownBy(() -> service.receive(null, null, null, body))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID));
        String forged = new WebhookSignature("whsec_" + Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofMinutes(5)).sign("msg_1", NOW, body);
        assertThatThrownBy(() -> service.receive("msg_1", Long.toString(NOW.getEpochSecond()), forged, body))
                .isInstanceOf(BusinessException.class);
        Instant old = NOW.minus(Duration.ofHours(1));
        assertThatThrownBy(() -> service.receive("msg_1", Long.toString(old.getEpochSecond()),
                signer.sign("msg_1", old, body), body)).isInstanceOf(BusinessException.class);
        verify(logs, never()).findByProviderAndProviderMessageId(any(), anyString());
    }

    @Test
    @DisplayName("no secret configured: 503, so Resend keeps the reports and retries once it is set")
    void notConfigured() {
        byte[] body = report("email.delivered", "e-1", "2026-10-24T07:59:00Z");
        assertThatThrownBy(() -> receive(service(null), body))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.WEBHOOK_UNAVAILABLE));
    }

    @Test
    @DisplayName("a secret that is not a Resend signing secret stops the application at startup")
    void malformedSecret() {
        assertThatThrownBy(() -> service("whsec_!!!")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_WEBHOOK_SECRET").hasMessageNotContaining("!!!");
    }
}
