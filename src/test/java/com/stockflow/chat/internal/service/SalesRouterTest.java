package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.customer.api.Responsible;
import com.stockflow.customer.api.ResponsibleKind;
import com.stockflow.customer.api.ResponsibleSales;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The routing of a new conversation (sales-owner plan §2.3, kltn-docs 16 §4.1 step 2, BR-12): whoever acts for the
 * customer today by {@code ResponsibleSales}, while they answer customers; otherwise the queue. {@code customer}'s rule
 * itself is pinned by {@code SalesOwnershipIntegrationTest}; here it is stood in for.
 */
class SalesRouterTest {

    /** 23:30 UTC is already the next day in Vietnam: the cover is judged on the business day, not the UTC one. */
    private static final Instant NOW = Instant.parse("2026-11-28T23:30:00Z");

    private final ResponsibleSales responsible = mock(ResponsibleSales.class);
    private final AgentDirectory agents = mock(AgentDirectory.class);
    private final SalesRouter router = new SalesRouter(responsible, agents, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID customer = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID cover = UUID.randomUUID();
    private final Conversation conversation = Conversation.open(UUID.randomUUID(), customer, null, null,
            Actor.customer(UUID.randomUUID()), null, NOW, Duration.ofMinutes(15));

    @Test
    @DisplayName("2.3 / case 3: an owner at work gets the customer's new conversation")
    void theOwner() {
        answers(new Responsible(customer, ResponsibleKind.OWNER, owner, owner, List.of()));
        when(agents.answersCustomers(owner)).thenReturn(true);

        assertThat(router.agentFor(conversation)).contains(owner);
        verify(responsible).responsibleFor(eq(customer), eq(BusinessCalendar.date(NOW)));
    }

    @Test
    @DisplayName("2.3 / BR-S07 / case 5: the owner's cover in force today gets it, not the owner")
    void theCover() {
        answers(new Responsible(customer, ResponsibleKind.COVER, owner, cover, List.of()));
        when(agents.answersCustomers(owner)).thenReturn(true);
        when(agents.answersCustomers(cover)).thenReturn(true);

        assertThat(router.agentFor(conversation)).contains(cover);
    }

    @Test
    @DisplayName("2.3 / case 2: a customer with no owner waits in the queue - never every salesperson, nor the managers")
    void noOwnerIsTheQueue() {
        UUID manager = UUID.randomUUID();
        answers(new Responsible(customer, ResponsibleKind.MANAGERS, null, null, List.of(manager)));
        when(agents.answersCustomers(any())).thenReturn(true);

        assertThat(router.agentFor(conversation)).isEmpty();
    }

    @Test
    @DisplayName("BR-S08 / case 8: an owner whose account is locked counts as gone - the queue")
    void aLockedOwnerIsTheQueue() {
        UUID manager = UUID.randomUUID();
        answers(new Responsible(customer, ResponsibleKind.MANAGERS, owner, null, List.of(manager)));

        assertThat(router.agentFor(conversation)).isEmpty();
    }

    @Test
    @DisplayName("16 §4.1: an acting user who no longer answers customers gets nothing - the queue")
    void anActingUserWhoDoesNotAnswerIsTheQueue() {
        answers(new Responsible(customer, ResponsibleKind.OWNER, owner, owner, List.of()));
        when(agents.answersCustomers(owner)).thenReturn(false);

        assertThat(router.agentFor(conversation)).isEmpty();
    }

    @Test
    @DisplayName("an unknown customer (no answer from ResponsibleSales) waits in the queue")
    void unknownCustomer() {
        when(responsible.responsibleFor(eq(customer), any())).thenReturn(Optional.empty());

        assertThat(router.agentFor(conversation)).isEmpty();
    }

    private void answers(Responsible answer) {
        when(responsible.responsibleFor(eq(customer), any())).thenReturn(Optional.of(answer));
    }
}
