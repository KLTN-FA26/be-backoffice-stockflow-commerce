package com.stockflow.identity.internal.service;

import com.stockflow.common.security.Roles;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.identity.api.StaffMember;
import com.stockflow.identity.internal.entity.RoleJpaEntity;
import com.stockflow.identity.internal.entity.UserJpaEntity;
import com.stockflow.identity.internal.entity.UserRoleJpaEntity;
import com.stockflow.identity.internal.repository.RoleJpaRepository;
import com.stockflow.identity.internal.repository.UserJpaRepository;
import com.stockflow.identity.internal.repository.UserRoleJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link SalesStaff}: who may look after customers, read from the accounts and their roles. Every method is one or a
 * few statements whatever the answer, and none reveals more than a name and roles.
 */
@Service
@Transactional(readOnly = true)
class SalesStaffDirectory implements SalesStaff {

    private final UserJpaRepository users;
    private final UserRoleJpaRepository userRoles;
    private final RoleJpaRepository roles;

    SalesStaffDirectory(UserJpaRepository users, UserRoleJpaRepository userRoles, RoleJpaRepository roles) {
        this.users = users;
        this.userRoles = userRoles;
        this.roles = roles;
    }

    @Override
    public boolean isActiveSalesAgent(UUID userId) {
        return userId != null && users.isActiveStaffHoldingAnyRole(userId, SALES_ROLES);
    }

    @Override
    public List<StaffMember> activeSalesAgents() {
        List<UserJpaEntity> found = users.findActiveStaffHoldingAnyRole(SALES_ROLES);
        if (found.isEmpty()) {
            return List.of();
        }
        List<UserRoleJpaEntity> rows = userRoles.findByUserIdIn(found.stream().map(UserJpaEntity::getId).toList());
        Map<UUID, RoleJpaEntity> roleById = new HashMap<>();
        roles.findAllById(rows.stream().map(UserRoleJpaEntity::getRoleId).distinct().toList())
                .forEach(role -> roleById.put(role.getId(), role));
        Map<UUID, List<StaffMember.Role>> held = new HashMap<>();
        rows.forEach(row -> {
            RoleJpaEntity role = roleById.get(row.getRoleId());
            if (role != null) {
                held.computeIfAbsent(row.getUserId(), id -> new ArrayList<>())
                        .add(new StaffMember.Role(role.getCode(), role.getName()));
            }
        });
        held.values().forEach(list -> list.sort(Comparator.comparing(StaffMember.Role::code)));
        return found.stream().map(user -> new StaffMember(user.getId(), displayName(user),
                held.getOrDefault(user.getId(), List.of()))).toList();
    }

    @Override
    public List<UUID> activeSalesManagers() {
        return ids(Set.of(Roles.SALES_MANAGER));
    }

    @Override
    public List<UUID> activeSystemAdmins() {
        return ids(Set.of(Roles.SYSTEM_ADMIN));
    }

    @Override
    public boolean holdsPermission(UUID userId, String permission) {
        return userId != null && permission != null && !permission.isBlank()
                && users.isActiveAndHolds(userId, permission.strip());
    }

    private List<UUID> ids(Set<String> roleCodes) {
        return users.findActiveStaffHoldingAnyRole(roleCodes).stream().map(UserJpaEntity::getId).toList();
    }

    private static String displayName(UserJpaEntity user) {
        return user.getFullName() == null || user.getFullName().isBlank() ? user.getUsername() : user.getFullName();
    }
}
