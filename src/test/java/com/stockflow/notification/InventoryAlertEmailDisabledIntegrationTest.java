package com.stockflow.notification;

import com.stockflow.contracts.InventoryAlertsRaised;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.Await;
import com.stockflow.support.SecuredIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * The default, {@code stockflow.notification.inventory-alert-email.enabled=false}: the alert reaches the inbox and no
 * e-mail is sent, and nothing breaks. The flag on is {@link InventoryAlertEmailFlagIntegrationTest}.
 */
// A mocked mail sender leaves the mail health indicator with nothing to check, so it is switched off for this context.
@SecuredIntegrationTest
// SMTP over the mocked sender, so "nothing sent" is checked on the transport that would send it (the test profile's
// default is the in-memory recorder); customer order mail is off, it is not what this test is about.
@org.springframework.test.context.TestPropertySource(properties = {"management.health.mail.enabled=false",
        "stockflow.notification.email.provider=smtp", "stockflow.notification.email.customer.enabled=false"})
class InventoryAlertEmailDisabledIntegrationTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean JavaMailSender mail;

    private AlertFixtures fixtures;

    @BeforeEach
    void setUp() {
        reset(mail);
        fixtures = new AlertFixtures(jdbc, tx, entityManager);
    }

    @Test
    void theInboxGetsTheMessageAndNoMailIsSent() {
        UUID planner = fixtures.staff("INVENTORY_PLANNER");
        tx.executeWithoutResult(s -> publisher.publishEvent(new InventoryAlertsRaised(UUID.randomUUID(), "REORDER", null,
                List.of(new InventoryAlertsRaised.Item(UUID.randomUUID(), "SKU-OFF", null, null, 1, 5, false)), Instant.now())));
        Await.until("inbox message", Duration.ofSeconds(20), () -> fixtures.messages(planner) == 1);

        assertThat(fixtures.messages(planner)).isEqualTo(1);
        verify(mail, never()).send(any(SimpleMailMessage.class));
        verify(mail, never()).send(any(SimpleMailMessage[].class));
        verify(mail, never()).createMimeMessage();
    }
}
