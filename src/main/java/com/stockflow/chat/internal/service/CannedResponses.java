package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.CannedResponse;
import com.stockflow.chat.internal.domain.CannedResponseRepository;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationContext;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.customer.api.CustomerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sales' saved replies (kltn-docs 16, package 3): each agent's own and the team's shared ones, kept by the
 * {@code chat-canned-responses} permission; filled in on the server when sent, from the conversation they are sent in
 * ({@link CannedResponse#VARIABLES}). Every method starts with {@link ChatCallers#staff}: a portal account has no
 * saved replies.
 */
@Service
@Transactional
public class CannedResponses {

    /** A saved reply as Sales see it. */
    public record View(UUID id, String title, String body, String shortcut, String category, boolean shared,
                       UUID ownerId, long version) {

        static View of(CannedResponse r) {
            return new View(r.id(), r.title(), r.body(), r.shortcut(), r.category(), r.shared(), r.ownerId(),
                    r.version());
        }
    }

    /** A saved reply filled in for one conversation: what would be sent. */
    public record Rendered(UUID cannedResponseId, UUID conversationId, String text) {
    }

    /** What a saved reply says. {@code shared}: for every agent - a supervisor's to write. */
    public record Draft(String title, String body, String shortcut, String category, Boolean shared, Long version) {
    }

    private final ChatCallers callers;
    private final CannedResponseRepository responses;
    private final ConversationRepository conversations;
    private final ContextResolver contexts;
    private final AgentDirectory agents;
    private final CustomerService customers;
    private final Clock clock;

    public CannedResponses(ChatCallers callers, CannedResponseRepository responses,
                           ConversationRepository conversations, ContextResolver contexts, AgentDirectory agents,
                           CustomerService customers, Clock clock) {
        this.callers = callers;
        this.responses = responses;
        this.conversations = conversations;
        this.contexts = contexts;
        this.agents = agents;
        this.customers = customers;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<View> list(CurrentUser user, String search, String category) {
        Actor staff = callers.staff(user);
        return responses.visibleTo(staff.userId(), search, category).stream().map(View::of).toList();
    }

    /** A new saved reply: the caller's own, or - for a chat supervisor asking for it - shared with every agent. */
    public View create(CurrentUser user, Draft draft) {
        Actor staff = callers.staff(user);
        boolean shared = Boolean.TRUE.equals(draft.shared());
        if (shared && !staff.supervisor()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "A shared reply is the chat supervisor's to write");
        }
        return View.of(responses.save(new CannedResponse(Identifiers.newId(), shared ? null : staff.userId(),
                draft.title(), draft.body(), draft.shortcut(), draft.category(), null, 0)));
    }

    /** Rewrites one: the caller's own, or a shared one as a supervisor; against the version read, when given. */
    public View update(CurrentUser user, UUID id, Draft draft) {
        Actor staff = callers.staff(user);
        CannedResponse found = find(id);
        found.requireChangeableBy(staff);
        if (draft.version() != null && draft.version() != found.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "The saved reply changed since version "
                    + draft.version());
        }
        return View.of(responses.save(found.revise(draft.title(), draft.body(), draft.shortcut(), draft.category())));
    }

    /** Deletes one: archived, its shortcut free again. */
    public void delete(CurrentUser user, UUID id) {
        Actor staff = callers.staff(user);
        CannedResponse found = find(id);
        found.requireChangeableBy(staff);
        responses.save(found.archive(clock.instant()));
    }

    /** What the saved reply would say in that conversation, for the agent to read before sending. */
    @Transactional(readOnly = true)
    public Rendered preview(CurrentUser user, UUID id, UUID conversationId) {
        Actor staff = callers.staff(user);
        Conversation c = conversations.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND));
        return new Rendered(id, c.id(), render(staff, id, c));
    }

    /** The saved reply's text for {@code c}, its variables filled from the conversation and the agent sending it. */
    public String render(Actor staff, UUID id, Conversation c) {
        CannedResponse found = find(id);
        if (!found.visibleTo(staff.userId())) {
            throw new BusinessException(ErrorCode.CHAT_CANNED_RESPONSE_NOT_FOUND);
        }
        return found.render(values(c, staff));
    }

    /**
     * The variables' values: the customer's and the agent's names, and the number (or name) of the latest document of
     * each kind the conversation is about, as its module names it now.
     */
    private Map<String, String> values(Conversation c, Actor staff) {
        Map<String, String> values = new HashMap<>();
        customers.findById(c.customerId()).ifPresent(customer -> values.put("customerName", customer.fullName()));
        String agent = agents.names(List.of(staff.userId())).get(staff.userId());
        if (agent != null) {
            values.put("agentName", agent);
        }
        List<ConversationContext> about = new ArrayList<>(conversations.contextsOf(c.id()));
        about.sort((a, b) -> a.attachedAt().compareTo(b.attachedAt()));
        for (ConversationContext context : about) {
            String variable = switch (context.ref().kind()) {
                case QUOTE -> "quoteCode";
                case ORDER -> "orderCode";
                case CONTRACT -> "contractCode";
                case PRODUCTION_ORDER -> "productionOrderCode";
                case EINVOICE -> "invoiceCode";
                case DESIGN, DESIGN_PROJECT -> "designName";
                default -> null;
            };
            if (variable != null) {
                contexts.find(context.ref()).map(ContextResolver.Resolved::label)
                        .ifPresent(label -> values.put(variable, label));
            }
        }
        return values;
    }

    private CannedResponse find(UUID id) {
        return responses.findById(id).filter(r -> r.archivedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CANNED_RESPONSE_NOT_FOUND));
    }
}
