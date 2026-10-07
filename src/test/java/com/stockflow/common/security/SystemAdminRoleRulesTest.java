package com.stockflow.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SYSTEM_ADMIN passes every role check, in code and in Spring Security alike. */
class SystemAdminRoleRulesTest {

    private static CurrentUser userWith(Role... roles) {
        return new CurrentUser(UUID.randomUUID(), "u", Set.of(roles), Set.of(), DataScope.ALL, Set.of());
    }

    @Test
    void aSystemAdminActsAsEveryRole() {
        CurrentUser admin = userWith(Role.SYSTEM_ADMIN);

        assertThat(Role.values()).allSatisfy(role -> assertThat(admin.hasRole(role)).isTrue());
        assertThat(admin.hasAnyRole(Role.WAREHOUSE_STAFF, Role.QC_STAFF)).isTrue();
    }

    @Test
    void anyOtherRoleStaysWhatItIs() {
        CurrentUser staff = userWith(Role.WAREHOUSE_STAFF);

        assertThat(staff.hasRole(Role.WAREHOUSE_STAFF)).isTrue();
        assertThat(staff.hasRole(Role.WAREHOUSE_MANAGER)).isFalse();
        assertThat(staff.hasRole(Role.SYSTEM_ADMIN)).isFalse();
        assertThat(staff.hasAnyRole(Role.QC_STAFF, Role.ORDER_COORDINATOR)).isFalse();
    }

    @Test
    void theHierarchyGivesTheAdminEveryRoleAuthorityAndNobodyElseAnyMore() {
        RoleHierarchy hierarchy = RoleHierarchyConfig.roleHierarchy();

        List<String> reachable = hierarchy.getReachableGrantedAuthorities(
                        List.of(new SimpleGrantedAuthority(Role.SYSTEM_ADMIN.authority())))
                .stream().map(GrantedAuthority::getAuthority).toList();
        assertThat(reachable).containsAll(Arrays.stream(Role.values()).map(Role::authority).toList());

        List<String> staffReach = hierarchy.getReachableGrantedAuthorities(
                        List.of(new SimpleGrantedAuthority(Role.WAREHOUSE_STAFF.authority())))
                .stream().map(GrantedAuthority::getAuthority).toList();
        assertThat(staffReach).containsExactly(Role.WAREHOUSE_STAFF.authority());
    }
}
