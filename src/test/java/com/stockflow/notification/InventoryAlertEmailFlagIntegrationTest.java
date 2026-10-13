package com.stockflow.notification;

import com.stockflow.contracts.InventoryAlertsRaised;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.Await;
import com.stockflow.support.SecuredIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * {@code stockflow.notification.inventory-alert-email.enabled=true} (open question Q3): the people newly told in-app get
 * the same text by e-mail, once a day like the inbox message, and a mail server that is down fails nothing. The flag
 * off is {@link InventoryAlertEmailDisabledIntegrationTest}.
 */
@SecuredIntegrationTest
// A mocked mail sender leaves the mail health indicator with nothing to check, so it is switched off for this context.
@TestPropertySource(properties = {"stockflow.notification.inventory-alert-email.enabled=true", "management.health.mail.enabled=false"})
class InventoryAlertEmailFlagIntegrationTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean JavaMailSender mail;

    private AlertFixtures fixtures;
    private UUID planner;
    private UUID customer;
    private UUID buyer;

    @BeforeEach
    void setUp() {
        reset(mail);
        fixtures = new AlertFixtures(jdbc, tx, entityManager);
        planner = fixtures.staff("INVENTORY_PLANNER");
        customer = fixtures.staff("CUSTOMER");
        buyer = fixtures.staff("PROCUREMENT_STAFF");
    }

    private void publishReorder(String sku) {
        tx.executeWithoutResult(s -> publisher.publishEvent(new InventoryAlertsRaised(UUID.randomUUID(), "REORDER", null,
                List.of(new InventoryAlertsRaised.Item(UUID.randomUUID(), sku, null, null, 1, 5, false)), Instant.now())));
    }

    private List<SimpleMailMessage> sent() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        try {
            org.mockito.Mockito.verify(mail, org.mockito.Mockito.atLeast(0)).send(captor.capture());
        } catch (org.mockito.exceptions.base.MockitoException e) {
            return List.of();
        }
        return captor.getAllValues();
    }

    private long mailsTo(UUID user) {
        String address = "test-" + user + "@example.com";
        return sent().stream().filter(m -> m.getTo() != null && List.of(m.getTo()).contains(address)).count();
    }

    @Test
    void theNewlyToldPlannerIsMailedTheSameTextAndNobodyElseIs() {
        publishReorder("SKU-MAIL");
        Await.until("the planner's e-mail", Duration.ofSeconds(20), () -> mailsTo(planner) == 1);

        SimpleMailMessage message = sent().stream().filter(m -> List.of(m.getTo()).contains("test-" + planner + "@example.com"))
                .findFirst().orElseThrow();
        assertThat(message.getSubject()).isEqualTo(fixtures.titles(planner).get(0));
        assertThat(message.getText()).isEqualTo(jdbc.queryForObject(
                "SELECT body FROM notification.user_notification WHERE recipient_id = ?", String.class, planner));
        assertThat(mailsTo(customer)).isZero();
        assertThat(mailsTo(buyer)).isZero();
    }

    @Test
    void aSecondBatchTheSameDayIsNeitherMessagedNorMailedAgain() {
        publishReorder("SKU-ONE");
        Await.until("first e-mail", Duration.ofSeconds(20), () -> mailsTo(planner) == 1);
        publishReorder("SKU-TWO");
        // A different kind afterwards proves the second REORDER has been processed (events are delivered in order).
        tx.executeWithoutResult(s -> publisher.publishEvent(new InventoryAlertsRaised(UUID.randomUUID(), "SAFETY", null,
                List.of(new InventoryAlertsRaised.Item(UUID.randomUUID(), "SKU-S", null, null, 1, 5, false)), Instant.now())));
        Await.until("safety message", Duration.ofSeconds(20), () -> fixtures.messages(planner) == 2);
        assertThat(mailsTo(planner)).isEqualTo(2);   // one REORDER + one SAFETY, not three
    }

    @Test
    void aMailServerThatIsDownStillLeavesTheInboxMessage() {
        doThrow(new MailSendException("smtp down")).when(mail).send(any(SimpleMailMessage.class));
        publishReorder("SKU-DOWN");
        Await.until("inbox message despite the failing mail server", Duration.ofSeconds(20), () -> fixtures.messages(planner) == 1);
        assertThat(fixtures.messages(planner)).isEqualTo(1);
    }
}
