package com.stockflow.notification.internal.service;

import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.domain.EmailDeliveryException;
import com.stockflow.notification.internal.domain.EmailProvider;
import com.stockflow.notification.internal.domain.EmailReceipt;
import com.stockflow.notification.internal.domain.EmailTransport;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

/**
 * E-mail through Spring's {@link JavaMailSender}: Mailpit on a laptop ({@code docker compose up}), or any SMTP relay a
 * deployment chooses instead of Resend.
 *
 * <p>A text-only message goes as a {@link SimpleMailMessage}, as every mail did before the transport existed; one with
 * HTML as {@code multipart/alternative}, so a client that shows no HTML still shows the text. The {@link MimeMessage}
 * comes from the sender, never {@code new MimeMessage(...)}: the sender's session carries {@code mail.from}, without
 * which JavaMail derives the Message-ID from a reverse DNS lookup (see {@code application.yml}).</p>
 *
 * <p>SMTP has no idempotency key: a message whose acknowledgement was lost may be sent twice. Accepted locally; on a
 * server the provider is Resend.</p>
 */
final class SmtpEmailTransport implements EmailTransport {

    private final JavaMailSender mail;

    SmtpEmailTransport(JavaMailSender mail) {
        this.mail = mail;
    }

    @Override
    public EmailProvider provider() {
        return EmailProvider.SMTP;
    }

    @Override
    public EmailReceipt send(Email email) {
        try {
            if (email.html() == null) {
                var message = new SimpleMailMessage();
                message.setFrom(email.from());
                message.setTo(email.to().toArray(String[]::new));
                if (email.replyTo() != null) {
                    message.setReplyTo(email.replyTo());
                }
                message.setSubject(email.subject());
                message.setText(email.text());
                mail.send(message);
            } else {
                MimeMessage message = mail.createMimeMessage();
                var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
                helper.setFrom(email.from());
                helper.setTo(email.to().toArray(String[]::new));
                if (email.replyTo() != null) {
                    helper.setReplyTo(email.replyTo());
                }
                helper.setSubject(email.subject());
                helper.setText(email.text(), email.html());
                mail.send(message);
            }
            return new EmailReceipt(EmailProvider.SMTP, null);
        } catch (MailAuthenticationException refused) {
            throw EmailDeliveryException.misconfigured("SMTP_AUTH_FAILED", refused);
        } catch (MailParseException | MailPreparationException | MessagingException malformed) {
            throw EmailDeliveryException.rejected("SMTP_MESSAGE_INVALID", malformed);
        } catch (MailSendException failed) {
            throw recipientsRefused(failed)
                    ? EmailDeliveryException.rejected("SMTP_RECIPIENT_REFUSED", failed)
                    : EmailDeliveryException.retryable("SMTP_SEND_FAILED", null, failed);
        } catch (MailException failed) {
            throw EmailDeliveryException.retryable("SMTP_SEND_FAILED", null, failed);
        }
    }

    /** The server answered and refused every recipient: sending again would be refused again. */
    private static boolean recipientsRefused(MailSendException failed) {
        var causes = failed.getFailedMessages().values();
        return !causes.isEmpty() && causes.stream().allMatch(cause -> cause instanceof SendFailedException refused
                && refused.getInvalidAddresses() != null && refused.getInvalidAddresses().length > 0
                && (refused.getValidSentAddresses() == null || refused.getValidSentAddresses().length == 0));
    }
}
