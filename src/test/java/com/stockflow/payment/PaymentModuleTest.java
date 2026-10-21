package com.stockflow.payment;

import com.stockflow.payment.api.PaymentService;
import com.stockflow.support.DemoData;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots <b>only</b> the payment module and the modules it declares in {@code package-info.java} (customer).
 *
 * <p>The point is the boundary: if payment gains a dependency its {@code package-info.java} does not declare, this
 * context cannot start and the build says so, instead of an import nobody noticed in review. Fix the declaration
 * or the code; do not widen this test.</p>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class PaymentModuleTest {

    @Autowired PaymentService payment;
    @Autowired ApplicationContext context;
    // A dependency of a bootstrapped dependency, not of this module: explicit ports only, the slice must not boot more.
    @MockitoBean com.stockflow.identity.api.IdentityService identityServiceMock;
    // customer (bootstrapped) invites portal accounts and mails the invitation (SCRUM-429): its ports, not the modules.
    @MockitoBean com.stockflow.identity.api.PortalAccountService portalAccountServiceMock;
    @MockitoBean com.stockflow.notification.api.NotificationService notificationServiceMock;
    // G2-3: payment's own SPI, implemented by the order module (which depends on payment, not the other way round).
    @MockitoBean com.stockflow.payment.api.OrderPayables orderPayablesMock;

    @Test
    @DisplayName("payment starts and answers through its api with the other modules absent")
    void bootsInIsolation() {
        assertThat(payment.creditPosition(DemoData.CUSTOMER_ID)).isNotNull();
    }

    /** The negative half: a module payment has no business with is genuinely not in this context. */
    @Test
    @DisplayName("a module outside payment's declared dependencies is not in this context")
    void unrelatedModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.order.api.OrderService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
