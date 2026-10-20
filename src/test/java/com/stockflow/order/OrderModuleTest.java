package com.stockflow.order;

import com.stockflow.order.api.OrderService;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots <b>only</b> the order module and the modules it declares in {@code package-info.java} (customer, catalog, inventory, design, warehouse and payment).
 *
 * <p>The point is the boundary: if order gains a dependency its {@code package-info.java} does not declare, this
 * context cannot start and the build says so, instead of an import nobody noticed in review. Fix the declaration
 * or the code; do not widen this test.</p>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class OrderModuleTest {

    @Autowired OrderService order;
    @Autowired ApplicationContext context;
    // A dependency of a bootstrapped dependency, not of this module: explicit ports only, the slice must not boot more.
    @MockitoBean com.stockflow.product.api.ProductService productServiceMock;
    @MockitoBean com.stockflow.identity.api.IdentityService identityServiceMock;
    @MockitoBean com.stockflow.product.api.ProductPublication productPublicationMock;
    // customer (bootstrapped) invites portal accounts and mails the invitation (SCRUM-429): its ports, not the modules.
    @MockitoBean com.stockflow.identity.api.PortalAccountService portalAccountServiceMock;
    @MockitoBean com.stockflow.notification.api.NotificationService notificationServiceMock;

    @Test
    @DisplayName("order starts and answers through its api with the other modules absent")
    void bootsInIsolation() {
        assertThat(order.findById(UUID.randomUUID())).isEmpty();
    }

    /** The negative half: a module order has no business with is genuinely not in this context. */
    @Test
    @DisplayName("a module outside order's declared dependencies is not in this context")
    void unrelatedModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.procurement.api.ProcurementService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
