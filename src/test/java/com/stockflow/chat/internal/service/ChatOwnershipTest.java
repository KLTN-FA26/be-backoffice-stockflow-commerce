package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.ClaimOutcome;
import com.stockflow.customer.api.CustomerSalesOwner;
import com.stockflow.customer.api.Responsible;
import com.stockflow.customer.api.ResponsibleKind;
import com.stockflow.customer.api.ResponsibleSales;
import com.stockflow.customer.api.SalesOwnerState;
import com.stockflow.customer.api.SalesOwnership;
import com.stockflow.identity.api.SalesStaff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The branches of the Sales owner's rules in the chat that the HTTP tests do not reach (sales-owner plan BR-S05,
 * BR-S12; 16 BR-12, BR-13): the claim rule switched off by configuration, the refusal carrying the owner's name, and
 * who gets a conversation the staff open. {@code ChatSalesOwnerIntegrationTest} runs the rest against the database.
 */
class ChatOwnershipTest {

    private static final Instant NOW = Instant.parse("2026-11-28T03:00:00Z");

    private final SalesOwnership ownership = mock(SalesOwnership.class);
    private final ResponsibleSales responsible = mock(ResponsibleSales.class);
    private final SalesStaff salesStaff = mock(SalesStaff.class);
    private final AgentDirectory agents = mock(AgentDirectory.class);
    private final SalesOwnerNotices notices = mock(SalesOwnerNotices.class);
    private final ChatProperties properties = mock(ChatProperties.class);
    private final ChatOwnership rules = new ChatOwnership(ownership, responsible, salesStaff, agents, notices,
            properties, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID customer = UUID.randomUUID();
    private final UUID sales = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final Conversation conversation = Conversation.open(UUID.randomUUID(), customer, null, null,
            Actor.customer(UUID.randomUUID()), null, NOW, Duration.ofMinutes(15));

    @Test
    @DisplayName("16 BR-13: with claim-makes-owner off, a salesperson's claim of an unowned customer takes the "
            + "conversation only")
    void claimRuleOff() {
        when(properties.claimMakesOwner()).thenReturn(false);
        when(salesStaff.isActiveSalesAgent(sales)).thenReturn(true);
        when(ownership.mayActFor(eq(sales), eq(customer), any())).thenReturn(true);

        rules.claimed(Actor.agent(sales, false), conversation);

        verify(ownership, never()).claimIfUnowned(any(), any(), any());
        verify(notices, never()).claimed(any(), any());
    }

    @Test
    @DisplayName("BR-S05 / case 18: the customer module's refusal reaches the caller naming the owner")
    void claimOfAnOwnedCustomerNamesTheOwner() {
        when(properties.claimMakesOwner()).thenReturn(true);
        when(salesStaff.isActiveSalesAgent(sales)).thenReturn(true);
        when(ownership.claimIfUnowned(eq(customer), eq(sales), any()))
                .thenThrow(new BusinessException(ErrorCode.CUSTOMER_UNASSIGNED_CLAIM_ONLY));
        when(ownership.ownerOf(customer)).thenReturn(Optional.of(new CustomerSalesOwner(customer, owner,
                SalesOwnerState.ASSIGNED, NOW, 3)));
        when(agents.names(List.of(owner))).thenReturn(Map.of(owner, "Sale A"));

        assertThatThrownBy(() -> rules.claimed(Actor.agent(sales, false), conversation))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.CUSTOMER_UNASSIGNED_CLAIM_ONLY);
                    assertThat(refusal.details()).containsEntry("salesOwnerName", "Sale A")
                            .containsEntry("salesOwnerId", owner);
                    assertThat(refusal.messageKey()).isEqualTo("refusal.chat.claimOwned");
                });
    }

    @Test
    @DisplayName("BR-S05: a claim that made the salesperson the owner tells the managers; one already theirs does not")
    void managersToldOnlyOfAClaim() {
        when(properties.claimMakesOwner()).thenReturn(true);
        when(salesStaff.isActiveSalesAgent(sales)).thenReturn(true);
        when(ownership.claimIfUnowned(eq(customer), eq(sales), any())).thenReturn(ClaimOutcome.CLAIMED,
                ClaimOutcome.ALREADY_OWNER, ClaimOutcome.COVERING);

        rules.claimed(Actor.agent(sales, false), conversation);
        rules.claimed(Actor.agent(sales, false), conversation);
        rules.claimed(Actor.agent(sales, false), conversation);

        verify(notices).claimed(conversation, sales);
    }

    @Test
    @DisplayName("D42: a manager claiming (no Sales agent) is never made the owner; refused only where they may not act")
    void aManagerIsNotMadeOwner() {
        UUID manager = UUID.randomUUID();
        when(properties.claimMakesOwner()).thenReturn(true);
        when(salesStaff.isActiveSalesAgent(manager)).thenReturn(false);
        when(ownership.mayActFor(eq(manager), eq(customer), any())).thenReturn(true);

        rules.claimed(Actor.agent(manager, true), conversation);

        verify(ownership, never()).claimIfUnowned(any(), any(), any());
        UUID accountant = UUID.randomUUID();
        when(ownership.mayActFor(eq(accountant), eq(customer), any())).thenReturn(false);
        assertThatThrownBy(() -> rules.claimed(Actor.agent(accountant, false), conversation))
                .extracting("errorCode").isEqualTo(ErrorCode.CUSTOMER_UNASSIGNED_CLAIM_ONLY);
    }

    @Test
    @DisplayName("16 §4.1 table: the owner or cover who opens has it; a manager's goes to whoever acts; nobody acting - "
            + "the opener")
    void whoGetsAStaffOpenedConversation() {
        UUID cover = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        when(responsible.responsibleFor(eq(customer), any())).thenReturn(Optional.of(new Responsible(customer,
                ResponsibleKind.COVER, owner, cover, List.of())));
        when(agents.answersCustomers(any())).thenReturn(true);

        assertThat(rules.agentForOpening(Actor.agent(owner, false), customer)).as("the owner on leave opens it")
                .isEqualTo(owner);
        assertThat(rules.agentForOpening(Actor.agent(cover, false), customer)).isEqualTo(cover);
        assertThat(rules.agentForOpening(Actor.agent(manager, true), customer)).as("one customer, one contact")
                .isEqualTo(cover);
        verify(ownership, never()).requireMayActFor(eq(manager), any(), any());

        when(responsible.responsibleFor(eq(customer), any())).thenReturn(Optional.of(new Responsible(customer,
                ResponsibleKind.MANAGERS, null, null, List.of(manager))));
        assertThat(rules.agentForOpening(Actor.agent(sales, false), customer)).as("no owner: the opener")
                .isEqualTo(sales);
        when(agents.answersCustomers(sales)).thenReturn(false);
        assertThat(rules.agentForOpening(Actor.agent(sales, false), customer)).as("who answers nobody: the queue")
                .isNull();
    }

    @Test
    @DisplayName("BR-S12 / 16 BR-12: another salesperson opening is refused before anything is decided")
    void anotherSalespersonIsRefused() {
        doThrow(new BusinessException(ErrorCode.CUSTOMER_OWNED_BY_OTHER_SALES)).when(ownership)
                .requireMayActFor(eq(sales), eq(customer), any());

        assertThatThrownBy(() -> rules.agentForOpening(Actor.agent(sales, false), customer))
                .extracting("errorCode").isEqualTo(ErrorCode.CUSTOMER_OWNED_BY_OTHER_SALES);
        verify(responsible, never()).responsibleFor(any(UUID.class), any());
    }
}
