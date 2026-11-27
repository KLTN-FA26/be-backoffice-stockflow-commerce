package com.stockflow.chat.internal.domain;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the Sales owner does to a conversation, without a container (sales-owner plan BR-S05, S06, S08; kltn-docs 16
 * BR-13, BR-14, §5): back to the queue when the agent's account stops working, following the customer's new owner,
 * and a claim that is the caller's already.
 */
class ConversationOwnerMovesTest {

    private static final Instant T0 = Instant.parse("2026-11-28T02:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(14);
    private static final Actor PORTAL = Actor.customer(Identifiers.newId());
    private static final Actor AGENT = Actor.agent(Identifiers.newId(), false);
    private static final Actor OTHER = Actor.agent(Identifiers.newId(), false);
    private static final Actor SUPERVISOR = Actor.agent(Identifiers.newId(), true);

    @ParameterizedTest(name = "BR-S08 / 16 §5: {0} with the locked agent goes back to the queue, flagged")
    @EnumSource(value = ConversationStatus.class, names = {"ASSIGNED", "ACTIVE", "WAITING_ON_CUSTOMER",
            "WAITING_ON_INTERNAL", "ESCALATED", "REOPENED"})
    void everyOpenStateGoesBackToTheQueue(ConversationStatus state) {
        Conversation c = in(state);
        c.pullLog();

        assertThat(c.requeueFrom(AGENT.userId(), T0.plusSeconds(60))).isTrue();

        assertThat(c.status()).isEqualTo(ConversationStatus.QUEUED);
        assertThat(c.assignedAgentId()).isNull();
        assertThat(c.snapshot().assignedAt()).as("ck_conversation_assigned_at").isNull();
        assertThat(c.requeuedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(c.pullLog()).extracting(LogEntry::type)
                .containsExactly(ConversationEventType.STATUS_CHANGED, ConversationEventType.QUEUED);
        assertThat(c.requeueFrom(AGENT.userId(), T0.plusSeconds(61))).as("delivered again: nothing left").isFalse();
    }

    @Test
    @DisplayName("BR-S08: an ended conversation, another agent's, or nobody's stays as it is")
    void onlyTheAgentsOpenOnes() {
        Conversation resolved = in(ConversationStatus.ACTIVE);
        resolved.changeStatus(AGENT, ConversationStatus.RESOLVED, null, T0, WINDOW);
        assertThat(resolved.requeueFrom(AGENT.userId(), T0)).isFalse();
        assertThat(resolved.status()).isEqualTo(ConversationStatus.RESOLVED);
        assertThat(resolved.assignedAgentId()).isEqualTo(AGENT.userId());

        Conversation others = in(ConversationStatus.ACTIVE);
        assertThat(others.requeueFrom(OTHER.userId(), T0)).isFalse();
        assertThat(others.requeueFrom(null, T0)).isFalse();
        assertThat(others.status()).isEqualTo(ConversationStatus.ACTIVE);
    }

    @Test
    @DisplayName("BR-S08: whoever takes a flagged conversation next clears the flag - a claim, an assignment, the owner")
    void theFlagLastsUntilSomeoneHasIt() {
        Conversation claimed = requeued();
        claimed.claim(OTHER, T0);
        assertThat(claimed.requeuedAt()).isNull();
        assertThat(claimed.status()).isEqualTo(ConversationStatus.ASSIGNED);

        Conversation assigned = requeued();
        assigned.handTo(SUPERVISOR, OTHER.userId(), null, T0);
        assertThat(assigned.requeuedAt()).isNull();

        Conversation followed = requeued();
        followed.followOwner(OTHER.userId(), "owner", null, T0);
        assertThat(followed.requeuedAt()).isNull();
        assertThat(followed.status()).isEqualTo(ConversationStatus.ASSIGNED);
    }

    @ParameterizedTest(name = "BR-S06 / 16 §5: a {0} conversation follows the new owner and keeps its status")
    @EnumSource(value = ConversationStatus.class, names = {"ASSIGNED", "ACTIVE", "WAITING_ON_CUSTOMER",
            "WAITING_ON_INTERNAL", "ESCALATED", "REOPENED"})
    void followsTheOwnerKeepingItsStatus(ConversationStatus state) {
        Conversation c = in(state);
        c.pullLog();

        assertThat(c.followOwner(OTHER.userId(), "Followed", "{\"reason\":\"WORKLOAD\"}", T0)).isTrue();

        assertThat(c.status()).isEqualTo(state);
        assertThat(c.assignedAgentId()).isEqualTo(OTHER.userId());
        assertThat(c.pullLog()).singleElement().satisfies(line -> {
            assertThat(line.type()).isEqualTo(ConversationEventType.TRANSFERRED);
            assertThat(line.actorType()).isEqualTo(SenderType.SYSTEM);
            assertThat(line.targetUserId()).isEqualTo(OTHER.userId());
            assertThat(line.note()).isEqualTo("Followed");
        });
        assertThat(c.followOwner(OTHER.userId(), "Followed", null, T0)).as("the owner's already").isFalse();
    }

    @Test
    @DisplayName("BR-S06: a queued conversation is ASSIGNED to the new owner; an ended one is left where it is")
    void queuedIsAssignedEndedIsLeft() {
        Conversation queued = opened();
        queued.route(null, T0);
        queued.pullLog();
        assertThat(queued.followOwner(OTHER.userId(), null, null, T0)).isTrue();
        assertThat(queued.status()).isEqualTo(ConversationStatus.ASSIGNED);
        assertThat(queued.pullLog()).extracting(LogEntry::type)
                .containsExactly(ConversationEventType.STATUS_CHANGED, ConversationEventType.ASSIGNED);

        Conversation resolved = in(ConversationStatus.ACTIVE);
        resolved.changeStatus(AGENT, ConversationStatus.RESOLVED, null, T0, WINDOW);
        assertThat(resolved.followOwner(OTHER.userId(), null, null, T0)).isFalse();
        assertThat(resolved.assignedAgentId()).isEqualTo(AGENT.userId());
    }

    @Test
    @DisplayName("BR-S05: claiming one's own conversation again changes nothing; someone else's is still refused")
    void claimIsIdempotentForTheClaimer() {
        Conversation c = opened();
        c.route(null, T0);
        assertThat(c.claim(AGENT, T0)).isTrue();
        c.pullLog();
        assertThat(c.claim(AGENT, T0.plusSeconds(5))).isFalse();
        assertThat(c.pullLog()).isEmpty();
        assertThatThrownBy(() -> c.claim(OTHER, T0)).extracting("errorCode")
                .isEqualTo(ErrorCode.CHAT_ALREADY_ASSIGNED);
    }

    @Test
    @DisplayName("16 §5: going back to the queue is never asked for by hand, by anyone")
    void theQueueIsNotAskedFor() {
        Conversation c = in(ConversationStatus.ACTIVE);
        assertThat(c.movesOpenTo(SUPERVISOR)).doesNotContain(ConversationStatus.QUEUED);
        assertThatThrownBy(() -> c.changeStatus(SUPERVISOR, ConversationStatus.QUEUED, "x", T0, WINDOW))
                .extracting("errorCode").isEqualTo(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED);
    }

    private static Conversation requeued() {
        Conversation c = in(ConversationStatus.ACTIVE);
        c.requeueFrom(AGENT.userId(), T0);
        assertThat(c.requeuedAt()).isNotNull();
        return c;
    }

    /** A conversation of AGENT's in {@code state}, reached through the aggregate's own moves. */
    private static Conversation in(ConversationStatus state) {
        Conversation c = opened();
        c.route(AGENT.userId(), T0);
        if (state == ConversationStatus.ASSIGNED) {
            return c;
        }
        c.acceptAgentMessage(AGENT, T0);
        switch (state) {
            case ACTIVE -> { }
            case WAITING_ON_CUSTOMER, WAITING_ON_INTERNAL ->
                    c.changeStatus(AGENT, state, "x", T0, WINDOW);
            case ESCALATED -> c.changeStatus(AGENT, state, "Ngoai tham quyen", T0, WINDOW);
            case REOPENED -> {
                c.changeStatus(AGENT, ConversationStatus.RESOLVED, null, T0, WINDOW);
                c.acceptCustomerMessage(PORTAL, T0.plusSeconds(1));
            }
            default -> throw new IllegalArgumentException(state.name());
        }
        assertThat(c.status()).isEqualTo(state);
        return c;
    }

    private static Conversation opened() {
        return Conversation.open(Identifiers.newId(), UUID.randomUUID(), "Hoi hang", null, PORTAL, null, T0,
                Duration.ofMinutes(15));
    }
}
