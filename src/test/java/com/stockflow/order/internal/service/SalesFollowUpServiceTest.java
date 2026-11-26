package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.identity.api.StaffUser;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.order.internal.domain.ContractCreditFigures;
import com.stockflow.order.internal.domain.ContractCreditReviews;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.SalesFollowUps;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A10 (kltn-docs 14 §6) and WF-02 FLOW-1..4: whose turn it is on a contract or an order is told once each - to the
 * salesperson who sent the contract while that account is active, to the Sales team as a hand-over once it is not, and
 * an order held for credit to whoever may decide it.
 */
class SalesFollowUpServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");

    private final SalesFollowUps followUps = mock(SalesFollowUps.class);
    private final Inbox inbox = mock(Inbox.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final ContractCreditReviews creditReviews = mock(ContractCreditReviews.class);
    private final UUID sales = UUID.randomUUID();

    @BeforeEach
    void anActiveSalesperson() {
        when(identity.user(sales)).thenReturn(user(sales, "ACTIVE"));
        when(inbox.post(any(), any())).thenReturn(1);
    }

    private SalesFollowUpService service(Duration unsigned, Duration unpaid) {
        return new SalesFollowUpService(followUps, inbox, new SalesRecipients(identity), identity, payments,
                Clock.fixed(NOW, ZoneOffset.UTC), unsigned, unpaid, creditReviews, Duration.ofHours(24));
    }

    private static StaffUser user(UUID id, String status) {
        return new StaffUser(id, "sale", null, "Sale", status, List.of("SALES_STAFF"), List.of(), false, null, null,
                NOW, null, null, 0);
    }

    @Test
    @DisplayName("past N days (3 by default): one notice per contract and per order, to its salesperson, keyed once")
    void remindsTheSalespersonOnce() {
        UUID contract = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID signedContract = UUID.randomUUID();
        when(followUps.unsignedSince(eq(NOW.minus(Duration.ofDays(3))), anyInt())).thenReturn(List.of(
                new SalesFollowUps.UnsignedContract(contract, "HD-20261108-0001-1", UUID.randomUUID(),
                        "SO-20261108-0001", sales, NOW.minus(Duration.ofDays(5)))));
        when(followUps.unpaidSince(eq(NOW.minus(Duration.ofDays(2))), anyInt())).thenReturn(List.of(
                new SalesFollowUps.UnpaidOrder(order, "SO-20261109-0002", signedContract, "HD-20261109-0002-1",
                        sales, NOW.minus(Duration.ofDays(2)))));

        SalesFollowUpService.Reminded reminded = service(Duration.ofDays(3), Duration.ofDays(2)).remind();

        assertThat(reminded).isEqualTo(new SalesFollowUpService.Reminded(1, 1, 0, 0, 0, 0, 0, 2));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> told = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, times(2)).post(to.capture(), told.capture());
        assertThat(to.getAllValues()).allSatisfy(recipients -> assertThat(recipients).containsExactly(sales));
        assertThat(told.getAllValues()).extracting(InboxMessage::dedupeKey)
                .containsExactly("contract-unsigned:" + contract, "order-unpaid:" + signedContract);
        assertThat(told.getAllValues()).extracting(InboxMessage::category)
                .containsOnly(SalesFollowUpService.CATEGORY);
        assertThat(told.getAllValues()).extracting(InboxMessage::body)
                .allSatisfy(body -> assertThat(body).doesNotContain("không còn hoạt động"));
        assertThat(told.getAllValues().getFirst().title()).contains("HD-20261108-0001-1", "3");
        assertThat(told.getAllValues().get(1).title()).contains("SO-20261109-0002", "2");
        assertThat(told.getAllValues().get(1).link()).isEqualTo("/admin/orders/" + order);
        verify(identity).user(sales);
    }

    @Test
    @DisplayName("FLOW-4: a locked or deleted salesperson's notices go to every active Sales account, as a hand-over")
    void aLockedSalespersonHandsOver() {
        UUID locked = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        when(identity.user(locked)).thenReturn(user(locked, "LOCKED"));
        when(identity.user(gone)).thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND, "gone"));
        when(identity.notificationRecipients(Set.of("SALES_STAFF"), null))
                .thenReturn(List.of(new StaffContact(teamA, "a@x.vn"), new StaffContact(teamB, null)));
        when(followUps.unsignedSince(any(), anyInt())).thenReturn(List.of(
                new SalesFollowUps.UnsignedContract(UUID.randomUUID(), "HD-1", UUID.randomUUID(), "SO-1", locked, NOW),
                new SalesFollowUps.UnsignedContract(UUID.randomUUID(), "HD-2", UUID.randomUUID(), "SO-2", locked, NOW)));
        when(followUps.confirmedWithoutWarehouse(anyInt())).thenReturn(List.of(
                new SalesFollowUps.WaitingOrder(UUID.randomUUID(), "SO-3", gone, NOW)));

        service(Duration.ofDays(3), Duration.ofDays(3)).remind();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> told = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, times(3)).post(to.capture(), told.capture());
        assertThat(to.getAllValues()).allSatisfy(r -> assertThat(r).containsExactly(teamA, teamB));
        assertThat(told.getAllValues()).extracting(InboxMessage::body)
                .allSatisfy(body -> assertThat(body).endsWith("Sale phụ trách không còn hoạt động: một người trong "
                        + "nhóm tiếp nhận việc này."));
        verify(identity, times(1)).user(locked);
        verify(identity, times(1)).notificationRecipients(Set.of("SALES_STAFF"), null);
    }

    @Test
    @DisplayName("FLOW-1a, 2, 3: held for credit to its deciders; confirmed without a warehouse and held for payment to"
            + " Sales; paid again told once more, the hold left to Sales")
    void tellsWhoseTurnItIs() {
        UUID credit = UUID.randomUUID();
        UUID unplaced = UUID.randomUUID();
        UUID shortOrder = UUID.randomUUID();
        UUID paidAgain = UUID.randomUUID();
        UUID accountant = UUID.randomUUID();
        Instant heldAt = Instant.parse("2026-11-12T02:00:00Z");
        when(identity.notificationRecipientsHolding(SalesFollowUpService.DECIDES_CREDIT, null))
                .thenReturn(List.of(new StaffContact(accountant, "kt@x.vn")));
        when(followUps.heldFor(eq("CREDIT"), anyInt())).thenReturn(List.of(
                new SalesFollowUps.WaitingOrder(credit, "SO-C", sales, heldAt)));
        when(followUps.confirmedWithoutWarehouse(anyInt())).thenReturn(List.of(
                new SalesFollowUps.WaitingOrder(unplaced, "SO-W", sales, heldAt)));
        when(followUps.heldAfterVoidedReceipt(anyInt())).thenReturn(List.of(
                new SalesFollowUps.WaitingOrder(shortOrder, "SO-P", sales, heldAt),
                new SalesFollowUps.WaitingOrder(paidAgain, "SO-R", sales, heldAt)));
        when(payments.installmentStandings(List.of(shortOrder, paidAgain))).thenReturn(Map.of(
                shortOrder, standing(shortOrder, false), paidAgain, standing(paidAgain, true)));

        SalesFollowUpService.Reminded reminded = service(Duration.ofDays(3), Duration.ofDays(3)).remind();

        assertThat(reminded).isEqualTo(new SalesFollowUpService.Reminded(0, 0, 1, 1, 2, 1, 0, 5));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> told = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, times(5)).post(to.capture(), told.capture());
        assertThat(told.getAllValues()).extracting(InboxMessage::dedupeKey).containsExactly(
                "credit-hold:" + credit + ":" + heldAt,
                "confirmed:" + unplaced,
                "payment-hold:" + shortOrder + ":" + heldAt,
                "payment-hold:" + paidAgain + ":" + heldAt,
                "payment-restored:" + paidAgain + ":" + heldAt);
        assertThat(to.getAllValues().getFirst()).containsExactly(accountant);
        assertThat(to.getAllValues().subList(1, 5)).allSatisfy(r -> assertThat(r).containsExactly(sales));
        assertThat(told.getAllValues()).extracting(InboxMessage::severity).containsExactly(InboxSeverity.WARN,
                InboxSeverity.INFO, InboxSeverity.WARN, InboxSeverity.WARN, InboxSeverity.INFO);
        assertThat(told.getAllValues().getFirst().link()).isEqualTo("/admin/orders/credit-holds");
        assertThat(told.getAllValues().getFirst().body()).contains("SO-C", "12/11/2026");
        assertThat(told.getAllValues().get(4).title()).contains("SO-R");
    }

    @Test
    @DisplayName("FLOW-1a: with nobody holding the credit decision, nothing is posted and the run goes on")
    void nobodyDecidesCredit() {
        when(identity.notificationRecipientsHolding(any(), any())).thenReturn(List.of());
        when(followUps.heldFor(eq("CREDIT"), anyInt())).thenReturn(List.of(
                new SalesFollowUps.WaitingOrder(UUID.randomUUID(), "SO-C", sales, NOW)));

        SalesFollowUpService.Reminded reminded = service(Duration.ofDays(3), Duration.ofDays(3)).remind();

        assertThat(reminded.creditHolds()).isEqualTo(1);
        assertThat(reminded.newlyTold()).isZero();
        verify(inbox, never()).post(any(), any());
    }

    @Test
    @DisplayName("FLOW-1b: the credit decision goes to the salesperson, keyed once per order, without the note")
    void creditDecisionToTheSalesperson() {
        UUID order = UUID.randomUUID();

        service(Duration.ofDays(3), Duration.ofDays(3)).creditDecided(order, "SO-9", sales, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> told = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(to.capture(), told.capture());
        assertThat(to.getValue()).containsExactly(sales);
        assertThat(told.getValue().dedupeKey()).isEqualTo("credit-decided:" + order);
        assertThat(told.getValue().severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(told.getValue().body()).contains("SO-9", "CREDIT_REJECTED");
    }

    @Test
    @DisplayName("15 BR-11: a contract waiting for accounting past a day is told again to the deciders, not to whoever "
            + "asked, once per review")
    void contractsWaitingForAccountingAreToldAgain() {
        UUID review = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID accountant = UUID.randomUUID();
        Instant submitted = Instant.parse("2026-11-11T09:00:00Z");
        when(identity.notificationRecipientsHolding(SalesFollowUpService.DECIDES_CREDIT, null)).thenReturn(List.of(
                new StaffContact(accountant, "kt@x.vn"), new StaffContact(sales, "sale@x.vn")));
        when(creditReviews.waitingSince(eq(NOW.minus(Duration.ofHours(24))), anyInt())).thenReturn(List.of(
                new ContractCreditReviews.Waiting(review, UUID.randomUUID(), "HD-7", 3, order, "SO-7",
                        UUID.randomUUID(), new ContractCreditFigures(CreditChecks.Outcome.OVER_LIMIT,
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO), sales, submitted)));

        SalesFollowUpService.Reminded reminded = service(Duration.ofDays(3), Duration.ofDays(3)).remind();

        assertThat(reminded.creditReviews()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> told = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(to.capture(), told.capture());
        assertThat(to.getValue()).containsExactly(accountant);
        assertThat(told.getValue().dedupeKey()).isEqualTo("contract-credit-review:" + review + ":waiting");
        assertThat(told.getValue().link()).isEqualTo("/admin/orders/credit-holds");
        assertThat(told.getValue().body()).contains("HD-7", "SO-7", "11/11/2026");
    }

    @Test
    @DisplayName("15 BR-11: a contract put in front of accounting is told to the deciders, whoever asked excepted, "
            + "with why")
    void aContractWaitingIsToldToTheDeciders() {
        UUID review = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID accountant = UUID.randomUUID();
        when(identity.notificationRecipientsHolding(SalesFollowUpService.DECIDES_CREDIT, null)).thenReturn(List.of(
                new StaffContact(accountant, "kt@x.vn"), new StaffContact(sales, "sale@x.vn")));

        service(Duration.ofDays(3), Duration.ofDays(3)).contractCreditReviewRequested(review, order, "SO-8", "HD-8",
                "OVERDUE", sales);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> told = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(to.capture(), told.capture());
        assertThat(to.getValue()).containsExactly(accountant);
        assertThat(told.getValue().dedupeKey()).isEqualTo("contract-credit-review:" + review + ":requested");
        assertThat(told.getValue().body()).contains("HD-8", "SO-8", "quá hạn");
    }

    @Test
    @DisplayName("15 BR-11: accounting's decision on a contract waiting goes to the salesperson who asked, keyed once")
    void contractCreditDecisionToTheSalesperson() {
        UUID review = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        service(Duration.ofDays(3), Duration.ofDays(3)).contractCreditDecided(review, order, "SO-9", "HD-9", sales,
                false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> told = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(to.capture(), told.capture());
        assertThat(to.getValue()).containsExactly(sales);
        assertThat(told.getValue().dedupeKey()).isEqualTo("contract-credit-review:" + review + ":refused");
        assertThat(told.getValue().severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(told.getValue().title()).contains("HD-9");
        assertThat(told.getValue().link()).isEqualTo("/admin/orders/" + order);
    }

    @Test
    @DisplayName("a zero or negative wait is a configuration error, refused at startup")
    void waitsArePositive() {
        assertThatThrownBy(() -> service(Duration.ZERO, Duration.ofDays(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(Duration.ofDays(3), Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static InstallmentStanding standing(UUID orderId, boolean onSigningPaid) {
        return new InstallmentStanding(orderId, true, onSigningPaid, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.TEN, onSigningPaid ? BigDecimal.TEN : BigDecimal.ZERO, null);
    }
}
