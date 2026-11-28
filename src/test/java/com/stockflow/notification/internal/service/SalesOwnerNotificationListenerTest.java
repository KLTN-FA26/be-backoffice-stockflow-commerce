package com.stockflow.notification.internal.service;

import com.stockflow.contracts.CustomersNeedSalesOwner;
import com.stockflow.contracts.OwnerChangeRequestDecided;
import com.stockflow.contracts.OwnerChangeRequested;
import com.stockflow.contracts.StaffAccountDeactivated;
import com.stockflow.contracts.StaffAccountReactivated;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.notification.api.CustomerContact;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.SalesAudiences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review finding C1: the sales-owner events had no listener, so the managers, the salespeople and the customer were
 * never told. Plan §10.5 step 4, BR-S08, BR-S10, D44.
 */
class SalesOwnerNotificationListenerTest {

    private final SalesStaff salesStaff = mock(SalesStaff.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final CustomerDirectory customers = mock(CustomerDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final SalesOwnerNotificationListener listener = new SalesOwnerNotificationListener(
            new SalesNotices(mock(SalesAudiences.class), mock(StaffDirectory.class), salesStaff), salesStaff, identity,
            customers, inbox);

    private final UUID manager = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-11-28T03:00:00Z");

    @BeforeEach
    void setUp() {
        when(salesStaff.activeSalesManagers()).thenReturn(List.of(manager));
        when(identity.displayNames(anyCollection())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return ids.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> "Sale " + id.toString()
                    .substring(0, 4)));
        });
        when(customers.contact(customer)).thenReturn(Optional.of(new CustomerContact(customer, "Cong ty A", null)));
    }

    private record Posted(List<UUID> to, InboxMessage message) {
    }

    @SuppressWarnings("unchecked")
    private List<Posted> posted(int times) {
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, times(times)).post(to.capture(), message.capture());
        List<Posted> all = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            all.add(new Posted(List.copyOf(to.getAllValues().get(i)), message.getAllValues().get(i)));
        }
        return all;
    }

    @Test
    @DisplayName("C1 / BR-S08 / D44: a released book tells the managers once, with the counts, not once per customer")
    void aReleasedBookTellsTheManagersOnce() {
        CustomersNeedSalesOwner released = new CustomersNeedSalesOwner(owner, "LOCKED",
                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), 2, null, at);

        listener.on(released);

        Posted notice = posted(1).get(0);
        assertThat(notice.to()).containsExactly(manager);
        assertThat(notice.message().body()).contains("3 khách", "2 khách tiềm năng", "đã bị khoá");
        assertThat(notice.message().link()).isEqualTo("/admin/sales/assignment");
        assertThat(notice.message().dedupeKey()).isEqualTo("sales-book-released:" + owner + ":" + at.toEpochMilli());
        assertThat(SalesOwnerNotificationListener.released(new CustomersNeedSalesOwner(owner,
                StaffAccountDeactivated.SALES_ROLE_REVOKED, List.of(), 1, null, at), "B").body())
                .contains("không còn vai trò Sale");
    }

    @Test
    @DisplayName("C1 / case 25: with no Sales Manager active, the System Admins are told")
    void noManagerTheAdmins() {
        UUID admin = UUID.randomUUID();
        when(salesStaff.activeSalesManagers()).thenReturn(List.of());
        when(salesStaff.activeSystemAdmins()).thenReturn(List.of(admin));

        listener.on(new OwnerChangeRequested(UUID.randomUUID(), customer, at));

        assertThat(posted(1).get(0).to()).containsExactly(admin);
    }

    @Test
    @DisplayName("C1 / §10.5 step 2: a request to change salesperson tells the managers only, by the customer's name")
    void aRequestTellsTheManagers() {
        UUID request = UUID.randomUUID();

        listener.on(new OwnerChangeRequested(request, customer, at));

        Posted notice = posted(1).get(0);
        assertThat(notice.to()).containsExactly(manager);
        assertThat(notice.message().title()).isEqualTo("Khách Cong ty A yêu cầu đổi Sale phụ trách");
        assertThat(notice.message().link()).isEqualTo("/admin/sales/owner-change-requests");
        assertThat(notice.message().dedupeKey()).isEqualTo("owner-change-requested:" + request);
    }

    @Test
    @DisplayName("C1 / §10.5 step 4: an approval tells both salespeople; the customer hears it from the chat")
    void anApprovalTellsBothSalespeople() {
        UUID newOwner = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        when(salesStaff.isActiveSalesAgent(owner)).thenReturn(true);

        listener.on(new OwnerChangeRequestDecided(request, customer, true, owner, newOwner, at));

        List<Posted> notices = posted(2);
        assertThat(notices.get(0).to()).containsExactly(newOwner);
        assertThat(notices.get(0).message().title()).isEqualTo("Bạn được giao phụ trách khách Cong ty A");
        assertThat(notices.get(1).to()).containsExactly(owner);
        assertThat(notices.get(1).message().title()).isEqualTo("Khách Cong ty A đã chuyển sang Sale khác");
        assertThat(notices.get(1).message().body()).contains("Sale " + newOwner.toString().substring(0, 4));
        verify(customers, never()).portalAccount(any());
    }

    @Test
    @DisplayName("C1: a former owner who left is not told of the approval")
    void aFormerOwnerWhoLeftIsNotTold() {
        UUID newOwner = UUID.randomUUID();
        when(salesStaff.isActiveSalesAgent(owner)).thenReturn(false);

        listener.on(new OwnerChangeRequestDecided(UUID.randomUUID(), customer, true, owner, newOwner, at));

        assertThat(posted(1).get(0).to()).containsExactly(newOwner);
    }

    @Test
    @DisplayName("C1 / §10.5 step 4: a rejection tells the customer to read the answer, which the notice never copies")
    void aRejectionTellsTheCustomer() {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        when(customers.portalAccount(customer)).thenReturn(Optional.of(account));

        listener.on(new OwnerChangeRequestDecided(request, customer, false, owner, null, at));

        Posted notice = posted(1).get(0);
        assertThat(notice.to()).containsExactly(account);
        assertThat(notice.message().link()).isEqualTo("/account");
        assertThat(notice.message().dedupeKey()).isEqualTo("owner-change-rejected:" + request);
    }

    @Test
    @DisplayName("C1 / BR-S10: a salesperson back tells the managers that nothing returned by itself; others say nothing")
    void aSalespersonBack() {
        UUID warehouse = UUID.randomUUID();
        when(salesStaff.isActiveSalesAgent(owner)).thenReturn(true);
        when(salesStaff.isActiveSalesAgent(warehouse)).thenReturn(false);

        listener.on(new StaffAccountReactivated(warehouse, null, at));
        listener.on(new StaffAccountReactivated(owner, null, at));

        Posted notice = posted(1).get(0);
        assertThat(notice.to()).containsExactly(manager);
        assertThat(notice.message().body()).contains("không tự chuyển về");
    }

    @Test
    void namesThatAreMissingDoNotBreakTheNotice() {
        when(identity.displayNames(anyCollection())).thenReturn(Map.of());

        listener.on(new CustomersNeedSalesOwner(owner, "DISABLED", List.of(UUID.randomUUID()), 0, null, at));

        assertThat(posted(1).get(0).message().title()).contains("-");
    }
}
