package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.BusinessHours;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.ConversationStatus;
import com.stockflow.chat.internal.domain.ReadMarkers;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a conversation's view offers the staff member looking at it - the moves, replying, taking it, handing it on -
 * is what the aggregate accepts from them: the agent in charge, a colleague who only watches, a supervisor, and a
 * conversation nobody has. Every move offered is then made on a copy, and every move withheld is refused, so the view
 * and {@link Conversation#changeStatus} cannot drift apart.
 */
class ChatViewsTest {

    private static final Instant T0 = Instant.parse("2026-11-20T02:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(7);
    private static final Actor PORTAL = Actor.customer(Identifiers.newId());
    private static final Actor AGENT = Actor.agent(Identifiers.newId(), false);
    private static final Actor OTHER_AGENT = Actor.agent(Identifiers.newId(), false);
    private static final Actor SUPERVISOR = Actor.agent(Identifiers.newId(), true);
    private static final Set<ConversationStatus> ASKED_FOR = EnumSet.of(ConversationStatus.ACTIVE,
            ConversationStatus.WAITING_ON_CUSTOMER, ConversationStatus.WAITING_ON_INTERNAL,
            ConversationStatus.ESCALATED, ConversationStatus.RESOLVED);

    private ChatViews views;

    @BeforeEach
    void setUp() {
        ChatProperties properties = mock(ChatProperties.class);
        when(properties.businessHours()).thenReturn(new BusinessHours(ZoneId.of("Asia/Ho_Chi_Minh"),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY), LocalTime.of(8, 0), LocalTime.of(17, 30)));
        views = new ChatViews(mock(ConversationRepository.class), mock(ChatMessageRepository.class),
                mock(ReadMarkers.class), mock(ContextResolver.class), mock(AgentDirectory.class),
                mock(ChatPresence.class), mock(CustomerService.class), new ObjectMapper(), properties,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("the agent in charge is offered the moves, replying and handing on; not taking it")
    void theAgentInCharge() {
        ChatConversationView view = row(active(), AGENT);
        assertThat(view.nextStatuses()).containsExactlyInAnyOrder(ConversationStatus.WAITING_ON_CUSTOMER,
                ConversationStatus.WAITING_ON_INTERNAL, ConversationStatus.ESCALATED, ConversationStatus.RESOLVED);
        assertThat(view.canReply()).isTrue();
        assertThat(view.canClaim()).isFalse();
        assertThat(view.canAssign()).isTrue();
        assertOffersWhatTheAggregateAccepts(this::active, AGENT);
    }

    @Test
    @DisplayName("a colleague who is not the assignee only watches: no move, no reply, no hand-on (CHAT_NOT_ASSIGNEE)")
    void aColleagueWhoOnlyWatches() {
        ChatConversationView view = row(active(), OTHER_AGENT);
        assertThat(view.nextStatuses()).isEmpty();
        assertThat(view.canReply()).isFalse();
        assertThat(view.canClaim()).isFalse();
        assertThat(view.canAssign()).isFalse();
        assertOffersWhatTheAggregateAccepts(this::active, OTHER_AGENT);
        assertThatThrownBy(() -> active().acceptAgentMessage(OTHER_AGENT, T0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CHAT_NOT_ASSIGNEE));
    }

    @Test
    @DisplayName("a supervisor is offered what the agent in charge is, and takes an escalated conversation further")
    void aSupervisor() {
        ChatConversationView view = row(active(), SUPERVISOR);
        assertThat(view.nextStatuses()).isEqualTo(row(active(), AGENT).nextStatuses());
        assertThat(view.canReply()).isTrue();
        assertThat(view.canAssign()).isTrue();
        assertOffersWhatTheAggregateAccepts(this::active, SUPERVISOR);

        assertThat(row(escalated(), SUPERVISOR).nextStatuses())
                .containsExactlyInAnyOrder(ConversationStatus.ACTIVE, ConversationStatus.RESOLVED);
        assertThat(row(escalated(), AGENT).nextStatuses()).as("escalated: the supervisor's").isEmpty();
        assertThat(row(escalated(), AGENT).canReply()).as("the agent still answers the customer").isTrue();
        assertOffersWhatTheAggregateAccepts(this::escalated, SUPERVISOR);
        assertOffersWhatTheAggregateAccepts(this::escalated, AGENT);
    }

    @Test
    @DisplayName("a conversation nobody has: any agent may take it, only a supervisor assigns or answers it")
    void nobodyHasIt() {
        ChatConversationView agent = row(queued(), AGENT);
        assertThat(agent.nextStatuses()).isEmpty();
        assertThat(agent.canClaim()).isTrue();
        assertThat(agent.canReply()).isFalse();
        assertThat(agent.canAssign()).isFalse();
        ChatConversationView supervisor = row(queued(), SUPERVISOR);
        assertThat(supervisor.canClaim()).isTrue();
        assertThat(supervisor.canAssign()).isTrue();
        assertThat(supervisor.canReply()).isTrue();
        assertOffersWhatTheAggregateAccepts(this::queued, AGENT);

        Conversation taken = queued();
        taken.claim(AGENT, T0);
        assertThat(row(taken, AGENT).canReply()).isTrue();
        assertThatThrownBy(() -> queued().handTo(AGENT, OTHER_AGENT.userId(), null, T0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a resolved conversation: nothing to reply or move; the customer and the realtime row get no viewer flags")
    void endedAndOthers() {
        Conversation resolved = active();
        resolved.changeStatus(AGENT, ConversationStatus.RESOLVED, null, T0, WINDOW);
        ChatConversationView view = row(resolved, AGENT);
        assertThat(view.nextStatuses()).isEmpty();
        assertThat(view.canReply()).isFalse();
        assertThat(view.canAssign()).as("resolved, not closed: it may still be handed on").isTrue();

        ChatConversationView customer = views.rows(List.of(active()), PORTAL.userId(), null).get(0);
        assertThat(customer.nextStatuses()).isNull();
        assertThat(customer.canReply()).isNull();
        assertThat(customer.canClaim()).isNull();
        assertThat(customer.canAssign()).isNull();

        ChatConversationView broadcast = views.staffBroadcastRow(active());
        assertThat(broadcast.nextStatuses()).as("the agent in charge's").isEqualTo(row(active(), AGENT).nextStatuses());
        assertThat(broadcast.canReply()).isNull();
        assertThat(broadcast.canClaim()).isNull();
        assertThat(broadcast.canAssign()).isNull();
        assertThat(views.staffBroadcastRow(queued()).nextStatuses()).isEmpty();
    }

    /** Each move offered is accepted on a fresh copy; each move withheld is refused. */
    private void assertOffersWhatTheAggregateAccepts(java.util.function.Supplier<Conversation> fresh, Actor viewer) {
        Set<ConversationStatus> offered = row(fresh.get(), viewer).nextStatuses();
        for (ConversationStatus target : ASKED_FOR) {
            Conversation c = fresh.get();
            if (offered.contains(target)) {
                c.changeStatus(viewer, target, "ly do", T0, WINDOW);
                assertThat(c.status()).isEqualTo(target);
            } else {
                assertThatThrownBy(() -> c.changeStatus(viewer, target, "ly do", T0, WINDOW))
                        .as(target + " withheld from the view is refused").isInstanceOf(BusinessException.class);
            }
        }
        boolean replies = row(fresh.get(), viewer).canReply();
        Conversation c = fresh.get();
        if (replies) {
            c.acceptAgentMessage(viewer, T0);
            c.requireInCharge(viewer, false);
        } else {
            assertThatThrownBy(() -> c.acceptAgentMessage(viewer, T0)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> c.requireInCharge(viewer, false)).isInstanceOf(BusinessException.class);
        }
    }

    private ChatConversationView row(Conversation c, Actor viewer) {
        return views.rows(List.of(c), viewer.userId(), viewer).get(0);
    }

    private Conversation queued() {
        Conversation c = Conversation.open(Identifiers.newId(), Identifiers.newId(), "Hoi hang", null, PORTAL, null,
                T0, Duration.ofMinutes(30));
        c.route(null, T0);
        return c;
    }

    private Conversation active() {
        Conversation c = queued();
        c.claim(AGENT, T0);
        c.acceptAgentMessage(AGENT, T0);
        return c;
    }

    private Conversation escalated() {
        Conversation c = active();
        c.changeStatus(AGENT, ConversationStatus.ESCALATED, "Giam gia vuot han muc", T0, WINDOW);
        return c;
    }
}
