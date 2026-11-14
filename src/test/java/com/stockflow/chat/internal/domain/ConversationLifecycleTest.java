package com.stockflow.chat.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The conversation's lifecycle, kltn-docs 16 §5, without a container: the move table (every one of the 121 pairs),
 * each allowed move reached through the aggregate's own methods by the side that makes it, and the refusals - wrong
 * move, wrong person, the reopen window, internal notes. {@code tools/db/qa/97_chat.sql} sweeps the same 110 moves
 * against the database's trigger.
 */
class ConversationLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-11-20T02:00:00Z");
    private static final Duration FIRST_RESPONSE = Duration.ofMinutes(30);
    private static final Duration WINDOW = Duration.ofDays(7);
    private static final UUID CUSTOMER = Identifiers.newId();
    private static final Actor PORTAL = Actor.customer(Identifiers.newId());
    private static final Actor AGENT = Actor.agent(Identifiers.newId(), false);
    private static final Actor OTHER_AGENT = Actor.agent(Identifiers.newId(), false);
    private static final Actor SUPERVISOR = Actor.agent(Identifiers.newId(), true);

    /** 16 §5, written out once more by hand: the test must not read the table it checks. */
    private static final Map<ConversationStatus, Set<ConversationStatus>> ALLOWED = Map.ofEntries(
            Map.entry(ConversationStatus.NEW, EnumSet.of(ConversationStatus.QUEUED, ConversationStatus.ASSIGNED)),
            Map.entry(ConversationStatus.QUEUED, EnumSet.of(ConversationStatus.ASSIGNED)),
            Map.entry(ConversationStatus.ASSIGNED, EnumSet.of(ConversationStatus.ACTIVE)),
            Map.entry(ConversationStatus.ACTIVE, EnumSet.of(ConversationStatus.WAITING_ON_CUSTOMER,
                    ConversationStatus.WAITING_ON_INTERNAL, ConversationStatus.ESCALATED, ConversationStatus.RESOLVED)),
            Map.entry(ConversationStatus.WAITING_ON_CUSTOMER, EnumSet.of(ConversationStatus.ACTIVE,
                    ConversationStatus.AUTO_CLOSED)),
            Map.entry(ConversationStatus.WAITING_ON_INTERNAL, EnumSet.of(ConversationStatus.ACTIVE)),
            Map.entry(ConversationStatus.ESCALATED, EnumSet.of(ConversationStatus.ACTIVE, ConversationStatus.RESOLVED)),
            Map.entry(ConversationStatus.RESOLVED, EnumSet.of(ConversationStatus.REOPENED, ConversationStatus.CLOSED)),
            Map.entry(ConversationStatus.REOPENED, EnumSet.of(ConversationStatus.ACTIVE)),
            Map.entry(ConversationStatus.AUTO_CLOSED, EnumSet.of(ConversationStatus.REOPENED,
                    ConversationStatus.CLOSED)),
            Map.entry(ConversationStatus.CLOSED, EnumSet.noneOf(ConversationStatus.class)));

    @Test
    @DisplayName("the move table is 16 §5: 18 moves allowed, the other 103 pairs (the 11 stays included) refused")
    void theMoveTableIsTheSpecification() {
        int allowed = 0;
        List<String> wrong = new ArrayList<>();
        for (ConversationStatus from : ConversationStatus.values()) {
            for (ConversationStatus to : ConversationStatus.values()) {
                boolean expected = ALLOWED.get(from).contains(to);
                if (from.canMoveTo(to) != expected) {
                    wrong.add(from + " -> " + to);
                }
                allowed += expected ? 1 : 0;
            }
            assertThat(from.next()).as(from + " next()").isEqualTo(ALLOWED.get(from));
        }
        assertThat(wrong).isEmpty();
        assertThat(allowed).isEqualTo(18);
        assertThat(ConversationStatus.values()).hasSize(11);
    }

    @Test
    @DisplayName("every allowed move, reached by the side that makes it")
    void everyAllowedMoveThroughTheAggregate() {
        // NEW -> QUEUED -> ASSIGNED (claim) -> ACTIVE (first answer)
        Conversation c = opened();
        assertThat(c.status()).isEqualTo(ConversationStatus.NEW);
        c.route(null, T0);
        assertThat(c.status()).isEqualTo(ConversationStatus.QUEUED);
        c.claim(AGENT, T0);
        assertThat(c.status()).isEqualTo(ConversationStatus.ASSIGNED);
        c.acceptAgentMessage(AGENT, T0.plusSeconds(60));
        assertThat(c.status()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(c.firstRespondedAt()).isEqualTo(T0.plusSeconds(60));
        // ACTIVE -> WAITING_ON_CUSTOMER -> ACTIVE (the customer answers)
        c.changeStatus(AGENT, ConversationStatus.WAITING_ON_CUSTOMER, null, T0, WINDOW);
        c.acceptCustomerMessage(PORTAL, T0);
        assertThat(c.status()).isEqualTo(ConversationStatus.ACTIVE);
        // ACTIVE -> WAITING_ON_INTERNAL -> ACTIVE
        c.changeStatus(AGENT, ConversationStatus.WAITING_ON_INTERNAL, "Hoi ke toan", T0, WINDOW);
        c.changeStatus(AGENT, ConversationStatus.ACTIVE, null, T0, WINDOW);
        // ACTIVE -> ESCALATED -> ACTIVE (supervisor) -> ESCALATED -> RESOLVED (supervisor)
        c.changeStatus(AGENT, ConversationStatus.ESCALATED, "Giam gia vuot han muc", T0, WINDOW);
        c.changeStatus(SUPERVISOR, ConversationStatus.ACTIVE, null, T0, WINDOW);
        c.changeStatus(AGENT, ConversationStatus.ESCALATED, "Boi thuong", T0, WINDOW);
        c.changeStatus(SUPERVISOR, ConversationStatus.RESOLVED, null, T0, WINDOW);
        assertThat(c.status()).isEqualTo(ConversationStatus.RESOLVED);
        assertThat(c.reopenUntil()).isEqualTo(T0.plus(WINDOW));
        // RESOLVED -> REOPENED (the customer writes) -> ACTIVE (the agent answers) -> RESOLVED -> CLOSED (window over)
        c.acceptCustomerMessage(PORTAL, T0.plusSeconds(10));
        assertThat(c.status()).isEqualTo(ConversationStatus.REOPENED);
        c.acceptAgentMessage(AGENT, T0.plusSeconds(20));
        assertThat(c.status()).isEqualTo(ConversationStatus.ACTIVE);
        c.changeStatus(AGENT, ConversationStatus.RESOLVED, null, T0, WINDOW);
        c.close(T0.plus(WINDOW).plusSeconds(1));
        assertThat(c.status()).isEqualTo(ConversationStatus.CLOSED);

        // NEW -> ASSIGNED (routed); ACTIVE -> RESOLVED; WAITING_ON_CUSTOMER -> AUTO_CLOSED -> REOPENED; AUTO_CLOSED -> CLOSED
        Conversation d = opened();
        d.route(AGENT.userId(), T0);
        assertThat(d.status()).isEqualTo(ConversationStatus.ASSIGNED);
        d.acceptAgentMessage(AGENT, T0);
        d.changeStatus(AGENT, ConversationStatus.WAITING_ON_CUSTOMER, null, T0, WINDOW);
        d.autoClose(T0, WINDOW);
        assertThat(d.status()).isEqualTo(ConversationStatus.AUTO_CLOSED);
        d.reopen(PORTAL, T0.plusSeconds(5));
        assertThat(d.status()).isEqualTo(ConversationStatus.REOPENED);
        d.changeStatus(AGENT, ConversationStatus.ACTIVE, null, T0, WINDOW);
        d.changeStatus(AGENT, ConversationStatus.WAITING_ON_CUSTOMER, null, T0, WINDOW);
        d.autoClose(T0, WINDOW);
        d.close(T0.plus(WINDOW));
        assertThat(d.status()).isEqualTo(ConversationStatus.CLOSED);

        assertThat(d.pullLog()).filteredOn(e -> e.type() == ConversationEventType.STATUS_CHANGED)
                .allSatisfy(e -> assertThat(e.from().canMoveTo(e.to())).isTrue());
    }

    @ParameterizedTest(name = "nobody asks for {0} by hand")
    @EnumSource(value = ConversationStatus.class, names = {"NEW", "QUEUED", "ASSIGNED", "REOPENED", "AUTO_CLOSED",
            "CLOSED"})
    void theStatesThatHappenAreNeverAskedFor(ConversationStatus target) {
        Conversation c = active();
        assertThatThrownBy(() -> c.changeStatus(SUPERVISOR, target, "x", T0, WINDOW))
                .isInstanceOf(BusinessException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("a move 16 §5 does not have from where the conversation is, is refused")
    void movesOutOfTheTableAreRefused() {
        Conversation queued = opened();
        queued.route(null, T0);
        assertThatThrownBy(() -> queued.changeStatus(SUPERVISOR, ConversationStatus.RESOLVED, null, T0, WINDOW))
                .extracting("errorCode").isEqualTo(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED);
        Conversation waiting = active();
        waiting.changeStatus(AGENT, ConversationStatus.WAITING_ON_INTERNAL, null, T0, WINDOW);
        assertThatThrownBy(() -> waiting.changeStatus(AGENT, ConversationStatus.RESOLVED, null, T0, WINDOW))
                .extracting("errorCode").isEqualTo(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED);
        assertThatThrownBy(() -> waiting.autoClose(T0, WINDOW))
                .extracting("errorCode").isEqualTo(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED);
        Conversation resolved = active();
        resolved.changeStatus(AGENT, ConversationStatus.RESOLVED, null, T0, WINDOW);
        assertThatThrownBy(() -> resolved.close(T0.plusSeconds(1)))
                .as("closed only once the reopen window passed")
                .extracting("errorCode").isEqualTo(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("only the agent in charge (or a supervisor) answers and moves it; only a supervisor takes an escalation on")
    void whoMayAct() {
        Conversation c = active();
        assertThatThrownBy(() -> c.acceptAgentMessage(OTHER_AGENT, T0)).extracting("errorCode")
                .isEqualTo(ErrorCode.CHAT_NOT_ASSIGNEE);
        assertThatThrownBy(() -> c.changeStatus(OTHER_AGENT, ConversationStatus.RESOLVED, null, T0, WINDOW))
                .extracting("errorCode").isEqualTo(ErrorCode.CHAT_NOT_ASSIGNEE);
        assertThat(c.acceptAgentMessage(SUPERVISOR, T0)).isPositive();
        assertThat(c.acceptInternalNote(OTHER_AGENT, T0)).as("any staff member writes a note").isPositive();
        assertThatThrownBy(() -> c.changeStatus(AGENT, ConversationStatus.ESCALATED, " ", T0, WINDOW))
                .as("an escalation says why").extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        c.changeStatus(AGENT, ConversationStatus.ESCALATED, "Ngoai tham quyen", T0, WINDOW);
        assertThatThrownBy(() -> c.changeStatus(AGENT, ConversationStatus.ACTIVE, null, T0, WINDOW))
                .extracting("errorCode").isEqualTo(ErrorCode.CHAT_NOT_ASSIGNEE);
        assertThatThrownBy(() -> c.claim(OTHER_AGENT, T0)).extracting("errorCode")
                .isEqualTo(ErrorCode.CHAT_ALREADY_ASSIGNED);
        assertThatThrownBy(() -> c.handTo(OTHER_AGENT, OTHER_AGENT.userId(), null, T0)).extracting("errorCode")
                .isEqualTo(ErrorCode.CHAT_NOT_ASSIGNEE);
        c.handTo(AGENT, OTHER_AGENT.userId(), "Nghi phep", T0);
        assertThat(c.assignedAgentId()).isEqualTo(OTHER_AGENT.userId());
        Conversation queued = opened();
        queued.route(null, T0);
        assertThatThrownBy(() -> queued.handTo(AGENT, OTHER_AGENT.userId(), null, T0))
                .as("only a supervisor assigns from the queue").extracting("errorCode")
                .isEqualTo(ErrorCode.CHAT_NOT_ASSIGNEE);
        queued.handTo(SUPERVISOR, OTHER_AGENT.userId(), null, T0);
        assertThat(queued.status()).isEqualTo(ConversationStatus.ASSIGNED);
        assertThat(queued.pullLog()).extracting(LogEntry::type).contains(ConversationEventType.ASSIGNED);
    }

    @Test
    @DisplayName("reopen inside the window; past it, or closed, the customer continues in a new conversation")
    void reopenOrContinueAsNew() {
        Conversation c = active();
        c.changeStatus(AGENT, ConversationStatus.RESOLVED, null, T0, WINDOW);
        assertThat(c.reopensAt(T0.plus(WINDOW).minusSeconds(1))).isTrue();
        assertThat(c.continuesAsNewAt(T0.plus(WINDOW).minusSeconds(1))).isFalse();
        assertThat(c.continuesAsNewAt(T0.plus(WINDOW))).isTrue();
        assertThatThrownBy(() -> c.acceptCustomerMessage(PORTAL, T0.plus(WINDOW)))
                .extracting("errorCode").isEqualTo(ErrorCode.CHAT_CONVERSATION_ENDED);
        assertThatThrownBy(() -> c.reopen(PORTAL, T0.plus(WINDOW)))
                .extracting("errorCode").isEqualTo(ErrorCode.CHAT_CONVERSATION_ENDED);
        c.close(T0.plus(WINDOW));
        assertThat(c.continuesAsNewAt(T0)).isTrue();
        assertThatThrownBy(() -> c.acceptSystemMessage(T0)).extracting("errorCode")
                .isEqualTo(ErrorCode.CHAT_CONVERSATION_ENDED);

        Conversation sequel = Conversation.open(Identifiers.newId(), CUSTOMER, null, null, PORTAL, c.id(), T0,
                FIRST_RESPONSE);
        assertThat(sequel.continuedFromId()).isEqualTo(c.id());
        assertThat(sequel.pullLog()).extracting(LogEntry::type).containsExactly(ConversationEventType.CONTINUED);
    }

    @Test
    @DisplayName("messages are numbered from 1 without gaps, notes included; the first answer is due 30 minutes on")
    void numbering() {
        Conversation c = opened();
        assertThat(c.firstResponseDueAt()).isEqualTo(T0.plus(FIRST_RESPONSE));
        c.route(AGENT.userId(), T0);
        assertThat(c.firstResponseOverdueAt(T0.plus(FIRST_RESPONSE).plusSeconds(1))).isTrue();
        assertThat(c.acceptCustomerMessage(PORTAL, T0)).isEqualTo(1);
        assertThat(c.acceptInternalNote(AGENT, T0)).isEqualTo(2);
        assertThat(c.acceptAgentMessage(AGENT, T0)).isEqualTo(3);
        assertThat(c.lastMessageSeq()).isEqualTo(3);
        assertThat(c.firstResponseOverdueAt(T0.plus(FIRST_RESPONSE).plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("16 BR-07: an internal note is the staff's, for the staff; the customer's audience never sees it")
    void internalNotesAreTheStaffs() {
        UUID conversation = Identifiers.newId();
        ChatMessage note = ChatMessage.internalNote(Identifiers.newId(), conversation, 1, AGENT, "Khach VIP",
                Set.of(OTHER_AGENT.userId()), null, null, T0);
        assertThat(note.visibility()).isEqualTo(Visibility.STAFF);
        assertThat(note.visibleTo(Audience.CUSTOMER)).isFalse();
        assertThat(note.visibleTo(Audience.STAFF)).isTrue();
        assertThat(Audience.CUSTOMER.sees()).containsExactly(Visibility.CUSTOMER);
        assertThatThrownBy(() -> ChatMessage.internalNote(Identifiers.newId(), conversation, 2, PORTAL, "x", Set.of(),
                null, null, T0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ChatMessage(Identifiers.newId(), conversation, 3, MessageKind.INTERNAL_NOTE,
                Visibility.CUSTOMER, SenderType.AGENT, AGENT.userId(), "lo", null, null, null, T0, null, null, null,
                Set.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatMessage.card(Identifiers.newId(), conversation, 4, PORTAL, CardKind.QUOTE, "{}",
                null, null, T0)).isInstanceOf(BusinessException.class);
        ChatMessage text = ChatMessage.text(Identifiers.newId(), conversation, 5, PORTAL, "  Xin chao  ", "web-1",
                null, T0);
        assertThat(text.body()).isEqualTo("Xin chao");
        assertThat(text.sameText("Xin chao ")).isTrue();
        assertThat(text.visibleTo(Audience.CUSTOMER)).isTrue();
    }

    private static Conversation opened() {
        return Conversation.open(Identifiers.newId(), CUSTOMER, "Hoi hang", null, PORTAL, null, T0, FIRST_RESPONSE);
    }

    private static Conversation active() {
        Conversation c = opened();
        c.route(AGENT.userId(), T0);
        c.acceptAgentMessage(AGENT, T0);
        return c;
    }
}
