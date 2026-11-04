package com.stockflow.identity.internal.service;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.SecuredIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code IdentityService.notificationRecipients}: who may be told about something in a warehouse (SCRUM-147). By role;
 * a person whose broadest scope is ALL, or who is assigned to the warehouse; never a locked account, never a customer.
 */
@SecuredIntegrationTest
class NotificationRecipientsIntegrationTest {

    @Autowired IdentityService identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @MockitoBean JwtDecoder decoder;

    private AlertFixtures fixtures;
    private UUID hcm;
    private AlertFixtures.Warehouse other;
    private UUID planner;
    private UUID admin;
    private UUID managerHcm;
    private UUID managerOther;
    private UUID managerNowhere;
    private UUID staffHcm;
    private UUID buyer;

    @BeforeEach
    void setUp() {
        fixtures = new AlertFixtures(jdbc, tx, entityManager);
        hcm = fixtures.hcm();
        other = fixtures.otherWarehouse();
        planner = fixtures.staff("INVENTORY_PLANNER");
        admin = fixtures.staff("SYSTEM_ADMIN");
        managerHcm = fixtures.staff("WAREHOUSE_MANAGER", hcm);
        managerOther = fixtures.staff("WAREHOUSE_MANAGER", other.id());
        managerNowhere = fixtures.staff("WAREHOUSE_MANAGER");
        staffHcm = fixtures.staff("WAREHOUSE_STAFF", hcm);
        buyer = fixtures.staff("PROCUREMENT_STAFF");
    }

    private List<UUID> ids(Set<String> roles, UUID warehouse) {
        return identities.notificationRecipients(roles, warehouse).stream().map(StaffContact::userId).toList();
    }

    @Test
    void systemWideMeansPeopleWhoseScopeIsAllAndNobodyLimitedToWarehouses() {
        List<UUID> found = ids(Set.of("INVENTORY_PLANNER", "WAREHOUSE_MANAGER", "SYSTEM_ADMIN"), null);
        assertThat(found).contains(planner, admin);
        assertThat(found).doesNotContain(managerHcm, managerOther, managerNowhere);
    }

    @Test
    void forAWarehouseItIsTheScopeAllRolesPlusThoseAssignedToIt() {
        List<UUID> found = ids(Set.of("INVENTORY_PLANNER", "WAREHOUSE_MANAGER", "SYSTEM_ADMIN"), hcm);
        assertThat(found).contains(planner, admin, managerHcm);
        assertThat(found).doesNotContain(managerOther, managerNowhere, staffHcm, buyer);
        assertThat(ids(Set.of("WAREHOUSE_MANAGER"), other.id())).contains(managerOther).doesNotContain(managerHcm, planner);
    }

    @Test
    void onlyTheRolesAskedForCount() {
        List<UUID> found = ids(Set.of("WAREHOUSE_STAFF"), hcm);
        assertThat(found).contains(staffHcm).doesNotContain(managerHcm, planner, admin);
        assertThat(ids(Set.of("PROCUREMENT_STAFF"), null)).contains(buyer);
        assertThat(ids(Set.of(), hcm)).isEmpty();
        assertThat(identities.notificationRecipients(null, hcm)).isEmpty();
    }

    @Test
    void aLockedOrDisabledAccountIsNeverTold() {
        UUID locked = fixtures.staff("INVENTORY_PLANNER");
        UUID disabled = fixtures.staff("WAREHOUSE_MANAGER", hcm);
        fixtures.setStatus(locked, "LOCKED");
        fixtures.setStatus(disabled, "DISABLED");
        List<UUID> found = ids(Set.of("INVENTORY_PLANNER", "WAREHOUSE_MANAGER"), hcm);
        assertThat(found).contains(planner, managerHcm).doesNotContain(locked, disabled);
    }

    @Test
    void aCustomerIsNeverToldEvenWhenTheyAlsoHoldAStaffRoleOrTheCustomerRoleIsAskedFor() {
        UUID customer = fixtures.staff("CUSTOMER");
        UUID both = fixtures.staff("INVENTORY_PLANNER");
        fixtures.addRole(both, "CUSTOMER");
        assertThat(ids(Set.of("CUSTOMER", "INVENTORY_PLANNER"), null)).contains(planner).doesNotContain(customer, both);
        assertThat(ids(Set.of("CUSTOMER"), hcm)).doesNotContain(customer);
    }

    @Test
    void aClerkWhoIsAlsoAPlannerHasTheBroadestScopeSoSeesEveryWarehouse() {
        UUID both = fixtures.staff("WAREHOUSE_MANAGER", hcm);
        fixtures.addRole(both, "INVENTORY_PLANNER");
        assertThat(ids(Set.of("WAREHOUSE_MANAGER"), other.id())).contains(both);
        assertThat(ids(Set.of("WAREHOUSE_MANAGER"), null)).contains(both);
    }

    @Test
    void aWarehouseNobodyIsAssignedToStillReachesTheScopeAllRoles() {
        UUID empty = UUID.randomUUID();
        assertThat(ids(Set.of("INVENTORY_PLANNER", "WAREHOUSE_MANAGER"), empty)).contains(planner).doesNotContain(managerHcm);
    }

    @Test
    void theContactCarriesTheEmailAddressAndNothingElse() {
        StaffContact contact = identities.notificationRecipients(Set.of("INVENTORY_PLANNER"), null).stream()
                .filter(c -> c.userId().equals(planner)).findFirst().orElseThrow();
        assertThat(contact.email()).isEqualTo("test-" + planner + "@example.com");
    }
}
