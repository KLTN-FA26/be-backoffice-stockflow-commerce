package com.stockflow.notification.internal.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stockflow.common.http.RestClientFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The plain e-mail of an inventory alert: addressed, worded by the caller, and never written to the log. */
class NotificationSenderInventoryAlertEmailTest {

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final NotificationSender sender = new NotificationSender(mail, mock(RestClientFactory.class), "noreply@example.com", "");

    @Test
    void sendsOneMessageFromTheConfiguredSenderToTheRecipient() {
        sender.sendInventoryAlertEmail("planner@example.com", "2 SKU low", "SKU: A, B");
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("noreply@example.com");
        assertThat(sent.getValue().getTo()).containsExactly("planner@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("2 SKU low");
        assertThat(sent.getValue().getText()).isEqualTo("SKU: A, B");
    }

    @Test
    void aMailServerFailureIsLeftToTheCaller() {
        doThrow(new MailSendException("down")).when(mail).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> sender.sendInventoryAlertEmail("a@example.com", "s", "b")).isInstanceOf(MailSendException.class);
    }

    @Test
    void neitherTheSubjectNorTheBodyIsLogged() {
        Logger logs = (Logger) LoggerFactory.getLogger("com.stockflow.notification");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level previous = logs.getLevel();
        logs.setLevel(Level.TRACE);
        appender.start();
        logs.addAppender(appender);
        try {
            sender.sendInventoryAlertEmail("a@example.com", "Secret-subject", "Secret-body");
        } finally {
            logs.detachAppender(appender);
            logs.setLevel(previous);
        }
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .noneMatch(m -> m.contains("Secret-subject") || m.contains("Secret-body"));
    }
}
