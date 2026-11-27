package com.stockflow.notification.internal.service;

import com.stockflow.contracts.CreditRequestSubmitted;
import com.stockflow.contracts.DelayAlertOpened;
import com.stockflow.contracts.OrderCancellationRequested;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.identity.api.StaffMember;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.SalesAudiences;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who on Sales' side hears a customer's notices since the sales-owner plan (2026-11-28; kltn-docs 00 §6, 15 BR-22, 17
 * BR-30, 18 BR-20): the customer's owner, the owner's cover, or - with no active owner - the Sales Managers marked "cần
 * phân công". Never every salesperson (D6). The people a notice names by their role (the coordinator, the managers, a
 * warehouse's manager, the accountant) are unchanged.
 */
class SalesRecipientsOfNoticesTest {

    private final SalesAudiences audiences = mock(SalesAudiences.class);
    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final SalesStaff salesStaff = mock(SalesStaff.class);
    private final Inbox inbox = mock(Inbox.class);
    private final SalesNotices sales = new SalesNotices(audiences, staff, salesStaff);

    private final UUID owner = UUID.randomUUID();
    private final UUID otherSales = UUID.randomUUID();
    private final UUID coordinator = UUID.randomUUID();
    private final UUID salesManager = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private List<Collection<UUID>> postedTo(int times, ArgumentCaptor<InboxMessage> messages) {
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        verify(inbox, times(times)).post(to.capture(), messages.capture());
        return to.getAllValues();
    }

    @Nested
    @DisplayName("delay alerts (17 BR-30)")
    class DelayAlerts {

        private final DelayAlertNotificationListener listener = new DelayAlertNotificationListener(staff, sales, inbox);
        private final UUID order = UUID.randomUUID();
        private final DelayAlertOpened alert = new DelayAlertOpened(UUID.randomUUID(), "PRODUCTION_LATE", order,
                "SO-1", UUID.randomUUID(), "PO-1", null, null, null, LocalDate.of(2026, 12, 1), null, Instant.now());

        DelayAlerts() {
            when(staff.activeHolders(DelayAlertNotificationListener.PRODUCTION_AUDIENCE, null)).thenReturn(List.of(
                    new StaffContact(coordinator, null), new StaffContact(salesManager, null),
                    new StaffContact(owner, null), new StaffContact(otherSales, null)));
            when(salesStaff.activeSalesAgents()).thenReturn(List.of(
                    new StaffMember(owner, "Owner", List.of()),
                    new StaffMember(otherSales, "Other", List.of())));
        }

        @Test
        @DisplayName("the coordinator and the Sales Manager by their permission, of Sales only the owner; never the "
                + "other salespeople")
        void theOwnerNotTheOtherSalespeople() {
            when(audiences.ofOrder(order)).thenReturn(Optional.of(new SalesAudience(List.of(owner), false)));

            listener.on(alert);

            ArgumentCaptor<InboxMessage> messages = ArgumentCaptor.forClass(InboxMessage.class);
            List<Collection<UUID>> to = postedTo(2, messages);
            assertThat(to.get(0)).containsExactly(coordinator, salesManager);
            assertThat(to.get(1)).containsExactly(owner);
            assertThat(messages.getAllValues().get(1).body()).doesNotContain("Cần phân công");
        }

        @Test
        @DisplayName("no active owner: the managers read it marked 'cần phân công', once each")
        void noOwnerTheManagersMarked() {
            when(audiences.ofOrder(order)).thenReturn(Optional.of(new SalesAudience(List.of(salesManager), true)));

            listener.on(alert);

            ArgumentCaptor<InboxMessage> messages = ArgumentCaptor.forClass(InboxMessage.class);
            List<Collection<UUID>> to = postedTo(2, messages);
            assertThat(to.get(0)).containsExactly(coordinator);
            assertThat(to.get(1)).containsExactly(salesManager);
            assertThat(messages.getAllValues().get(1).body()).contains("Cần phân công");
        }
    }

    @Nested
    @DisplayName("cancellation requests (17 §4.8 step 28)")
    class CancellationRequests {

        private final NotificationSender sender = mock(NotificationSender.class);
        private final StaffNotices notices = mock(StaffNotices.class);
        private final OrderCancellationRequestNotificationListener listener =
                new OrderCancellationRequestNotificationListener(sender, sales, notices);
        private final OrderCancellationRequested request = new OrderCancellationRequested(UUID.randomUUID(),
                UUID.randomUUID(), "SO-2", "CUSTOMER_REQUEST", Instant.now());

        @Test
        @DisplayName("the owner hears, by permission no longer: every holder of the decision is not told")
        void theOwner() {
            when(audiences.ofOrder(request.orderId())).thenReturn(Optional.of(new SalesAudience(List.of(owner),
                    false)));
            when(staff.activeContact(owner)).thenReturn(Optional.of(new StaffContact(owner, "o@example.com")));

            listener.on(request);

            verify(notices).deliver(eq(List.of(new StaffContact(owner, "o@example.com"))), any(), any());
            verify(staff, never()).activeHolders(any(), any());
        }

        @Test
        @DisplayName("no active owner: the managers, marked 'cần phân công'")
        void theManagersMarked() {
            when(audiences.ofOrder(request.orderId())).thenReturn(Optional.of(new SalesAudience(List.of(salesManager),
                    true)));
            when(staff.activeContact(salesManager)).thenReturn(Optional.empty());

            listener.on(request);

            ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
            verify(notices).deliver(eq(List.of(new StaffContact(salesManager, null))), message.capture(), any());
            assertThat(message.getValue().body()).contains("SO-2").contains("Cần phân công");
        }
    }

    @Nested
    @DisplayName("credit requests (15 §4.3 step 11a)")
    class CreditRequests {

        private final CreditRequestNotificationListener listener = new CreditRequestNotificationListener(staff, sales,
                mock(CustomerDirectory.class), inbox);
        private final UUID customer = UUID.randomUUID();

        private CreditRequestSubmitted submitted(CreditRequestSubmitted.Stage stage, UUID by) {
            return new CreditRequestSubmitted(UUID.randomUUID(), customer, stage, new BigDecimal("50000000"), 30,
                    "VND", by, Instant.now());
        }

        @Test
        @DisplayName("the Sales step: the customer's owner gives the opinion, not every holder of the right")
        void theSalesStepGoesToTheOwner() {
            when(audiences.ofCustomer(customer)).thenReturn(Optional.of(new SalesAudience(List.of(owner), false)));

            listener.on(submitted(CreditRequestSubmitted.Stage.AWAITING_SALES, UUID.randomUUID()));

            ArgumentCaptor<InboxMessage> messages = ArgumentCaptor.forClass(InboxMessage.class);
            assertThat(postedTo(1, messages).getFirst()).containsExactly(owner);
            verify(staff, never()).activeHolders(eq(CreditRequestNotificationListener.SALES_AUDIENCE), any());
        }

        @Test
        @DisplayName("the accountant's step is unchanged: the holders of the decision, whoever asked excepted")
        void theAccountantsStepIsUnchanged() {
            UUID accountant = UUID.randomUUID();
            when(staff.activeHolders(CreditRequestNotificationListener.ACCOUNTANT_AUDIENCE, null))
                    .thenReturn(List.of(new StaffContact(accountant, null), new StaffContact(owner, null)));

            listener.on(submitted(CreditRequestSubmitted.Stage.AWAITING_DECISION, owner));

            ArgumentCaptor<InboxMessage> messages = ArgumentCaptor.forClass(InboxMessage.class);
            assertThat(postedTo(1, messages).getFirst()).containsExactly(accountant);
            verify(audiences, never()).ofCustomer(any());
        }

        @Test
        @DisplayName("no one to tell at the Sales step: nothing posted")
        void nobody() {
            when(audiences.ofCustomer(customer)).thenReturn(Optional.empty());

            listener.on(submitted(CreditRequestSubmitted.Stage.AWAITING_SALES, UUID.randomUUID()));

            verify(inbox, never()).post(anyList(), any());
        }
    }
}
