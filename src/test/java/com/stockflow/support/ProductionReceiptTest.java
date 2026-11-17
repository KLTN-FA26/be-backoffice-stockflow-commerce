package com.stockflow.support;

import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link IntegrationTest} with a real Postgres and {@link ProductionReceivables}: what a test needs to start from a
 * goods receipt against a production order. The upload scanner is off (there is no ClamAV in tests; a confirmed design
 * and a signed contract store files), the customer e-mail is off and the mail sender is a mock, as in
 * {@code ProductionReceiptIntegrationTest}, so every class with this annotation shares one application context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@IntegrationTest
@Import({PostgresContainer.class, ProductionReceivables.class})
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
@MockitoBean(types = JavaMailSenderImpl.class)
public @interface ProductionReceiptTest {
}
