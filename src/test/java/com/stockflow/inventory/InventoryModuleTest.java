package com.stockflow.inventory;

import com.stockflow.inventory.api.InventoryService;

import com.stockflow.common.domain.Sku;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots <b>only</b> the inventory module, with every other module absent.
 *
 * <p>This is a capability a plain monolith does not have at all: {@code @ApplicationModuleTest}
 * starts inventory's beans plus the shared ones, and nothing else. Two consequences worth
 * having:</p>
 * <ul>
 *   <li><b>It runs in a second or two</b>, because thirteen modules' beans, listeners and
 *       schedulers never start.</li>
 *   <li><b>It fails loudly if inventory has an undeclared dependency.</b> If someone injects an
 *       order bean into an inventory class, the context cannot start — the boundary violation
 *       surfaces as a broken test rather than as an import nobody noticed in review.</li>
 * </ul>
 */
@ApplicationModuleTest
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class InventoryModuleTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");

    @Autowired InventoryService inventory;
    @Autowired ApplicationContext context;

    @Test
    @DisplayName("inventory starts and serves queries with no other module present")
    void bootsInIsolation() {
        assertThat(inventory.availableToPromise(SOFA)).isPositive();
        assertThat(inventory.availabilityOf(SOFA)).isNotEmpty();
    }

    /**
     * The negative half, and the more informative one: order really is absent from this context.
     *
     * <p>If this ever starts passing by finding the bean, inventory has acquired a dependency on
     * order that {@code package-info.java} does not declare.</p>
     */
    @Test
    @DisplayName("the order module is genuinely not in this context")
    void otherModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.order.api.OrderService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
