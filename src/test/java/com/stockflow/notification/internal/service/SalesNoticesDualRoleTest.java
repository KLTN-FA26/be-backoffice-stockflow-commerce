package com.stockflow.notification.internal.service;

import com.stockflow.identity.api.SalesStaff;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.identity.api.StaffMember;
import com.stockflow.notification.api.SalesAudiences;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Review fix 6 of the sales-owner work (17 BR-30 as changed on 2026-11-28): a notice addressed by permission drops the
 * salespeople who do not look after the customer - and only them. A holder who also holds another role (a Production
 * Coordinator or a Sales Manager given SALES_STAFF too) is told as that role, as before customers had owners.
 */
class SalesNoticesDualRoleTest {

    private final SalesStaff salesStaff = mock(SalesStaff.class);
    private final SalesNotices sales = new SalesNotices(mock(SalesAudiences.class), mock(StaffDirectory.class),
            salesStaff);

    private static StaffMember member(UUID id, String... roles) {
        return new StaffMember(id, "staff-" + id, java.util.Arrays.stream(roles)
                .map(code -> new StaffMember.Role(code, code)).toList());
    }

    @Test
    @DisplayName("BR-30: a coordinator or manager who also holds SALES_STAFF keeps the delay alert; a plain other "
            + "salesperson does not; the owner is told through the audience")
    void dualRoleHoldersAreKept() {
        UUID owner = UUID.randomUUID();
        UUID plainOther = UUID.randomUUID();
        UUID coordinatorInSales = UUID.randomUUID();
        UUID managerInSales = UUID.randomUUID();
        UUID coordinator = UUID.randomUUID();
        when(salesStaff.activeSalesAgents()).thenReturn(List.of(member(owner, "SALES_STAFF"),
                member(plainOther, "SALES_STAFF"), member(coordinatorInSales, "PRODUCTION_COORDINATOR", "SALES_STAFF"),
                member(managerInSales, "SALES_MANAGER", "SALES_STAFF")));
        List<StaffContact> holders = List.of(new StaffContact(owner, null), new StaffContact(plainOther, null),
                new StaffContact(coordinatorInSales, null), new StaffContact(managerInSales, null),
                new StaffContact(coordinator, null));

        List<UUID> told = sales.exceptOtherSales(holders, new SalesAudience(List.of(owner), false));

        assertThat(told).containsExactlyInAnyOrder(owner, coordinatorInSales, managerInSales, coordinator);
    }
}
