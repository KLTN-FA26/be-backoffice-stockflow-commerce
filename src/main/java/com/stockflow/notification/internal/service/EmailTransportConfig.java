package com.stockflow.notification.internal.service;

import com.stockflow.common.http.RestClientFactory;
import com.stockflow.notification.internal.domain.EmailTransport;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;

/**
 * The one {@link EmailTransport}, chosen by {@code stockflow.notification.email.provider}.
 *
 * <p>One switch rather than a {@code @ConditionalOnProperty} per implementation: an unknown provider fails binding with
 * the property's name in the message, where a set of conditions would leave no transport and a "no qualifying bean"
 * error that names neither the property nor its value. And the Resend transport checks its key and sender in its
 * constructor, so a server configured for Resend without a key does not start.</p>
 */
@Configuration(proxyBeanMethods = false)
class EmailTransportConfig {

    @Bean
    EmailTransport emailTransport(EmailProperties properties, RestClientFactory clients, Clock clock,
                                  ObjectProvider<JavaMailSender> mail) {
        EmailTransport transport = switch (properties.provider()) {
            case RESEND -> new ResendEmailTransport(clients, properties, clock);
            case SMTP -> new SmtpEmailTransport(mail.getIfAvailable(() -> {
                throw new IllegalStateException("stockflow.notification.email.provider is smtp but no mail server is"
                        + " configured (spring.mail.host)");
            }));
            case RECORDING -> new RecordingEmailTransport();
        };
        LoggerFactory.getLogger(EmailTransportConfig.class).info("E-mail is sent through {}", transport.provider());
        return transport;
    }
}
