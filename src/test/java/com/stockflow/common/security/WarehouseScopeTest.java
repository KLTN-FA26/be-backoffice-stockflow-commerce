package com.stockflow.common.security;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/** BR-SEC-002 (SCRUM-457): which callers {@link WarehouseScope} limits, and how. */
class WarehouseScopeTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();

    private static CurrentUser clerkOf(UUID warehouseId, String prefix) {
        return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.WAREHOUSE, Set.of(prefix), Set.of(warehouseId));
    }

    @Test
    void theSystemAndUnlimitedCallersAreNotRestricted() {
        assertThat(WarehouseScope.restriction()).isEmpty();
        assertThatNoException().isThrownBy(() -> WarehouseScope.requireLocation("HN-A01-1-B"));
        WithCurrentUser.run(TestUsers.builder().scope(DataScope.ALL).build(), () -> {
            assertThat(WarehouseScope.restriction()).isEmpty();
            WarehouseScope.requireWarehouse(HN);
        });
    }

    @Test
    void aClerkReachesOnlyTheirWarehouseByPrefixOrId() {
        WithCurrentUser.run(clerkOf(HCM, "HCM"), () -> {
            WarehouseScope.requireLocation("HCM-A01-2-03");
            WarehouseScope.requireWarehouse(HCM);
            WarehouseScope.requireEither(HN, HCM);
            assertThatThrownBy(() -> WarehouseScope.requireLocation("HN-RCV01"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
            assertThatThrownBy(() -> WarehouseScope.requireWarehouse(HN)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> WarehouseScope.requireEither(HN, UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class);
        });
    }

    @Test
    void anEndpointDeclaringANarrowerScopeIsNotWidenedOrRestrictedByWarehouses() {
        // effective = the narrower of endpoint and user: an OWN endpoint stays OWN, not WAREHOUSE.
        WithCurrentUser.run(clerkOf(HCM, "HCM"), DataScope.OWN,
                () -> assertThat(WarehouseScope.restriction()).isEmpty());
    }

    @Test
    void aWarehouseSensitiveEndpointDoesNotCutAnUnlimitedCallerDown() {
        CurrentUser planner = new CurrentUser(UUID.randomUUID(), "planner", Set.of(Role.INVENTORY_PLANNER),
                Set.of(), DataScope.ALL, Set.of(), Set.of());
        WithCurrentUser.run(planner, DataScope.WAREHOUSE, () -> {
            assertThat(WarehouseScope.restriction()).isEmpty();
            WarehouseScope.requireLocation("HN-RCV01");
        });
        WithCurrentUser.run(clerkOf(HCM, "HCM"), DataScope.WAREHOUSE,
                () -> assertThat(WarehouseScope.restriction()).isPresent());
    }

    @Test
    void theWarehousePrefixIsEverythingBeforeTheFirstDash() {
        assertThat(WarehouseScope.prefixOf("HCM-A01-2-03")).isEqualTo("HCM");
        assertThat(WarehouseScope.prefixOf("HN-RCV01")).isEqualTo("HN");
        assertThat(WarehouseScope.prefixOf("HCM")).isEqualTo("HCM");
        assertThat(WarehouseScope.prefixOf(null)).isNull();
    }
}
