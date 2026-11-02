package com.stockflow.notification.internal.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stockflow.notification.api.CustomerContact;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.internal.domain.EmailDeliveryException;
import com.stockflow.notification.internal.domain.EmailTransport;
import com.stockflow.notification.internal.service.CustomerEmailDelivery.CustomerEmailDeferred;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.notification.internal.service.EmailFixtures.cancelled;
import static com.stockflow.notification.internal.service.EmailFixtures.placed;
import static com.stockflow.notification.internal.service.EmailFixtures.properties;
import static com.stockflow.notification.internal.service.EmailFixtures.shipment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A customer message carries names, addresses, amounts and - on a cancellation event - what somebody typed. Its
 * delivery is traced by the message's reference, the template and the outcome; nothing it says, and not whom it is to,
 * reaches the log, on success or failure.
 */
class CustomerEmailLoggingTest {

    private static final String NAME = "Công ty TNHH Bí Mật Nguyễn Văn A";
    private static final String ADDRESS = "secret-buyer@cup-maker.vn";

    private final Logger logs = (Logger) LoggerFactory.getLogger("com.stockflow");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previous;

    private final CustomerEmailLog log = mock(CustomerEmailLog.class);
    private final CustomerDirectory customers = mock(CustomerDirectory.class);
    private final UUID customer = UUID.randomUUID();

    @BeforeEach
    void captureLogs() {
        previous = logs.getLevel();
        logs.setLevel(Level.TRACE);
        appender.start();
        logs.addAppender(appender);
        when(log.state(anyString())).thenReturn(new CustomerEmailLog.State(false, false, 0));
        when(customers.contact(customer)).thenReturn(Optional.of(new CustomerContact(customer, NAME, ADDRESS)));
    }

    @AfterEach
    void release() {
        logs.detachAppender(appender);
        logs.setLevel(previous);
    }

    private OrderNotificationListener listener(EmailTransport transport) {
        var delivery = new CustomerEmailDelivery(log, customers, new EmailTemplates(), transport, properties(),
                Clock.systemUTC());
        return new OrderNotificationListener(new CustomerEmailComposer(properties()), delivery);
    }

    private String logged() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    @DisplayName("sent messages are logged by reference and outcome only")
    void successLeaksNothing() {
        var listener = listener(new RecordingEmailTransport());
        listener.on(placed(customer, "contact-secret@cup-maker.vn"));
        listener.on(shipment(customer, "HANDED_OVER", null, "TRACK-777 Express"));
        listener.on(cancelled(customer, "CUSTOMER_REQUEST", new BigDecimal("1200000"), BigDecimal.ZERO));

        assertThat(logged()).contains("customer-mail:order.placed:", "customer-mail:order.cancelled:");
        assertThat(logged()).doesNotContain(NAME, "Nguyễn", ADDRESS, "contact-secret", "TRACK-777", "1.200.000",
                "anh Ba", "0901234567", "Kính gửi", "SO-20261024-0001");
    }

    @Test
    @DisplayName("a failure worth retrying: the thrown exception and the logs name the reference, never the address")
    void failureLeaksNothing() {
        EmailTransport refusing = mock(EmailTransport.class);
        when(refusing.send(any())).thenThrow(EmailDeliveryException.retryable("SMTP_SEND_FAILED", null,
                new MailSendException("550 <" + ADDRESS + ">: Recipient address rejected")));

        assertThatThrownBy(() -> listener(refusing).on(placed(customer, null)))
                .isInstanceOf(CustomerEmailDeferred.class)
                .hasNoCause()
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain(ADDRESS, NAME));
        assertThat(logged()).contains("SMTP_SEND_FAILED").doesNotContain(ADDRESS, NAME);
    }
}
