package com.stockflow.notification;

import com.stockflow.notification.api.NotificationService;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots <b>only</b> the notification module and the modules it declares in {@code package-info.java} (nothing).
 *
 * <p>The point is the boundary: if notification gains a dependency its {@code package-info.java} does not declare, this
 * context cannot start and the build says so, instead of an import nobody noticed in review. Fix the declaration
 * or the code; do not widen this test.</p>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class NotificationModuleTest {

    @Autowired NotificationService notification;
    @Autowired ApplicationContext context;

    @Test
    @DisplayName("notification starts and answers through its api with the other modules absent")
    void bootsInIsolation() {
        assertThat(notification.purchaseOrderDeliveryStatuses(Set.of(UUID.randomUUID()))).isEmpty();
    }

    /** The negative half: a module notification has no business with is genuinely not in this context. */
    @Test
    @DisplayName("a module outside notification's declared dependencies is not in this context")
    void unrelatedModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.order.api.OrderService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
