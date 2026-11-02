package com.stockflow.notification.internal.service;

import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.domain.EmailDeliveryException;
import com.stockflow.notification.internal.domain.EmailProvider;
import jakarta.mail.Address;
import jakarta.mail.Multipart;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The local transport: Mailpit through JavaMailSender. */
class SmtpEmailTransportTest {

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final SmtpEmailTransport transport = new SmtpEmailTransport(mail);

    @Test
    @DisplayName("a text-only message goes as the simple message every mail was before the transport existed")
    void plainText() {
        var receipt = transport.send(new Email("StockFlow <no-reply@stockflow.vn>", List.of("a@cup-maker.vn"),
                "sales@stockflow.vn", "Subject", "Body", null, "key-is-ignored", null));
        var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("StockFlow <no-reply@stockflow.vn>");
        assertThat(sent.getValue().getTo()).containsExactly("a@cup-maker.vn");
        assertThat(sent.getValue().getReplyTo()).isEqualTo("sales@stockflow.vn");
        assertThat(sent.getValue().getText()).isEqualTo("Body");
        assertThat(receipt.provider()).isEqualTo(EmailProvider.SMTP);
        assertThat(receipt.messageId()).isNull();
    }

    @Test
    @DisplayName("with HTML: multipart/alternative from the sender's own MimeMessage, Vietnamese subject intact")
    void html() throws Exception {
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        transport.send(new Email("StockFlow <no-reply@stockflow.vn>", List.of("a@cup-maker.vn"), null,
                "Đơn hàng SO-1 đã được tiếp nhận", "Văn bản", "<p>Văn bản</p>", null, null));
        var sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(sent.capture());
        MimeMessage message = sent.getValue();
        assertThat(message.getSubject()).isEqualTo("Đơn hàng SO-1 đã được tiếp nhận");
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("no-reply@stockflow.vn");
        assertThat(message.getContent()).isInstanceOf(Multipart.class);
    }

    @Test
    @DisplayName("refused credentials: misconfigured; a server down: retried; every recipient refused: rejected")
    void failures() throws Exception {
        Email email = Email.plain("no-reply@stockflow.vn", List.of("a@cup-maker.vn"), null, "s", "t");
        doThrow(new MailAuthenticationException("535")).when(mail).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> transport.send(email)).isInstanceOfSatisfying(EmailDeliveryException.class,
                failed -> assertThat(failed.kind()).isEqualTo(EmailDeliveryException.Kind.MISCONFIGURED));

        doThrow(new MailSendException("Connection refused")).when(mail).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> transport.send(email)).isInstanceOfSatisfying(EmailDeliveryException.class,
                failed -> assertThat(failed.code()).isEqualTo("SMTP_SEND_FAILED"));

        var refusedAll = new SendFailedException("550 no such user", null, new Address[0],
                new Address[0], new Address[]{new InternetAddress("a@cup-maker.vn")});
        doThrow(new MailSendException(Map.of(new Object(), refusedAll))).when(mail).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> transport.send(email)).isInstanceOfSatisfying(EmailDeliveryException.class,
                failed -> {
                    assertThat(failed.kind()).isEqualTo(EmailDeliveryException.Kind.REJECTED);
                    assertThat(failed.code()).isEqualTo("SMTP_RECIPIENT_REFUSED");
                });
    }
}
