package com.stockflow.identity;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.RoleSummary;
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
 * Boots <b>only</b> the identity module and the modules it declares in {@code package-info.java} (warehouse).
 *
 * <p>The point is the boundary: if identity gains a dependency its {@code package-info.java} does not declare, this
 * context cannot start and the build says so, instead of an import nobody noticed in review. Fix the declaration
 * or the code; do not widen this test.</p>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class IdentityModuleTest {

    @Autowired IdentityService identity;
    @Autowired ApplicationContext context;

    @Test
    @DisplayName("identity starts and answers through its api with the other modules absent")
    void bootsInIsolation() {
        assertThat(identity.listRoles()).extracting(RoleSummary::code).contains("CUSTOMER", "SYSTEM_ADMIN");
    }

    /** The negative half: a module identity has no business with is genuinely not in this context. */
    @Test
    @DisplayName("a module outside identity's declared dependencies is not in this context")
    void unrelatedModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.order.api.OrderService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
