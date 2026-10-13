package com.stockflow.notification.internal.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.contracts.PaymentFailed;
import com.stockflow.contracts.ShipmentDispatched;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What a notification says can carry what a customer typed, so the log of a delivery names the recipient,
 * the template and the sizes, and never the subject or the body.
 */
class NotificationSenderLoggingTest {

    private static final String SECRET_SUBJECT = "Subject-with-private-words";
    private static final String SECRET_BODY = "Body mentioning Nguyen Van A and the gate code 4711";

    private final Logger notificationLogs = (Logger) LoggerFactory.getLogger("com.stockflow.notification");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private NotificationSender sender;

    @BeforeEach
    void captureLogs() {
        previousLevel = notificationLogs.getLevel();
        notificationLogs.setLevel(Level.TRACE);
        appender.start();
        notificationLogs.addAppender(appender);
        sender = new NotificationSender(mock(JavaMailSender.class), mock(RestClientFactory.class), "noreply@example.com", "");
    }

    @AfterEach
    void release() {
        notificationLogs.detachAppender(appender);
        notificationLogs.setLevel(previousLevel);
    }

    private String logged() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void sendLogsRecipientTemplateAndLengthsOnly() {
        UUID recipient = UUID.randomUUID();

        sender.send(recipient, "order.placed", SECRET_SUBJECT, SECRET_BODY);

        assertThat(appender.list).hasSize(1);
        assertThat(logged()).contains(recipient.toString(), "order.placed",
                "subjectLength=" + SECRET_SUBJECT.length(), "bodyLength=" + SECRET_BODY.length());
        assertThat(logged()).doesNotContain(SECRET_SUBJECT, SECRET_BODY, "Nguyen", "4711", "private words");
    }

    @Test
    void guestSendNeitherLogsTheEmailAddressNorTheText() {
        sender.send(null, "guest@example.com", "order.placed", SECRET_SUBJECT, SECRET_BODY);

        assertThat(logged()).contains("guest-email-present=true");
        assertThat(logged()).doesNotContain("guest@example.com", SECRET_SUBJECT, SECRET_BODY);
    }

    @Test
    void listenersNeverPutTheirTextOrTheEmailInTheLog() {
        var listener = new OrderNotificationListener(sender);
        String failure = "Card declined for Tran Thi B, note: leave at the back door";

        listener.on(new OrderPlaced(UUID.randomUUID(), UUID.randomUUID(), "buyer-secret@example.com", "SO-1",
                List.of(), BigDecimal.TEN, "VND"));
        listener.on(new PaymentFailed(UUID.randomUUID(), UUID.randomUUID(), "DECLINED", failure));
        listener.on(new ShipmentDispatched(UUID.randomUUID(), UUID.randomUUID(), "GHN", "TRACK-777"));

        assertThat(appender.list).isNotEmpty();
        assertThat(logged()).contains("order.placed", "payment.failed", "shipment.dispatched");
        assertThat(logged()).doesNotContain("Tran Thi B", "back door", "Card declined", "buyer-secret@example.com",
                "TRACK-777", "We have received", "Your order is on its way");
    }
}
