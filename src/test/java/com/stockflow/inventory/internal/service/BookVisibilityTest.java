package com.stockflow.inventory.internal.service;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.internal.controller.InventoryResources;
import com.stockflow.support.TestUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The blind count's one rule (SCRUM-146, kltn-docs 20 BR-07), shared by the stock screens and the published queries
 * the assistant reads: by permission, never by role, and closed when there is no caller.
 */
class BookVisibilityTest {

    @Test
    @DisplayName("only inventory-cycle-counts:APPROVE reads the books under a count; counting alone does not, a role "
            + "name does not, and no caller does not")
    void byPermission() {
        assertThat(BookVisibility.of(Optional.of(TestUsers.builder().role(Role.WAREHOUSE_STAFF).scope(DataScope.ALL)
                .can("inventory-cycle-counts", Action.APPROVE).build()))).isEqualTo(BookVisibility.FULL);
        assertThat(BookVisibility.of(Optional.of(TestUsers.builder().role(Role.WAREHOUSE_STAFF).scope(DataScope.ALL)
                .can("inventory-cycle-counts", Action.UPDATE).build()))).isEqualTo(BookVisibility.HIDE_COUNTED_SLOTS);
        assertThat(BookVisibility.of(Optional.of(TestUsers.builder().role(Role.WAREHOUSE_MANAGER)
                .scope(DataScope.ALL).build()))).isEqualTo(BookVisibility.HIDE_COUNTED_SLOTS);
        assertThat(BookVisibility.of(Optional.empty())).isEqualTo(BookVisibility.HIDE_COUNTED_SLOTS);
    }

    @Test
    @DisplayName("the resource it asks about is the one the cycle-count screens are guarded by")
    void sameResourceAsTheScreens() {
        assertThat(BookVisibility.CYCLE_COUNTS).isEqualTo(InventoryResources.CYCLE_COUNTS);
    }
}
