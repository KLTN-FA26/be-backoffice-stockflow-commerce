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
 * {@link SecuredIntegrationTest} that also starts from a goods receipt against a production order
 * ({@link ProductionReceivables}): the real filter chain and a {@code MockMvc}, with the upload scanner and the customer
 * e-mail off and the mail sender mocked, as {@link ProductionReceiptTest} has them. Every class carrying it shares one
 * context, apart from the plain secured ones: the properties differ, so a test that does not need production orders
 * should keep {@link SecuredIntegrationTest}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SecuredIntegrationTest
@Import(ProductionReceivables.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
@MockitoBean(types = JavaMailSenderImpl.class)
public @interface SecuredProductionReceiptTest {
}
