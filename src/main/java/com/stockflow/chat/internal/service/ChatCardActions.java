package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.MessageKind;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.design.api.QuoteRequests;
import com.stockflow.order.api.QuoteCards;
import com.stockflow.order.api.SampleRoundCards;
import com.stockflow.payment.api.CustomerInstallments;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The actions taken on cards (kltn-docs 16 BR-03): each is the owning module's own operation, called through its
 * {@code api} with its own checks, on behalf of whoever pressed the card - the chat changes no document itself. The card
 * then shows the result, and the action is logged on the conversation with who took it (BR-01).
 *
 * <p><b>Who acts.</b> The customer accepts or sends back a quote and answers a print sample (BR-10: "agent không duyệt
 * thay khách") - Sales are refused those, whatever the customer wrote. Sales draw up a quote from a quote request, only
 * once its design is approved (BR-11), and send transfer details, only as the system's card (BR-06). Each action is
 * offered only while the card is OPEN ({@link CardActions}); a document that moved meanwhile refuses it in its module,
 * which rolls the whole action back.</p>
 *
 * <p>Lock order: the conversation, then the document (inside its module's operation). The card listeners take only the
 * conversation, after the document's transaction committed, so the two never wait on each other in reverse.</p>
 */
@Service
@Transactional
public class ChatCardActions {

    private static final Set<String> CUSTOMER_ACTIONS = Set.of(CardActions.ACCEPT, CardActions.REQUEST_CHANGES,
            CardActions.APPROVE, CardActions.REQUEST_REPRINT, CardActions.RATE);

    /**
     * What a card's action carries.
     *
     * @param version  ACCEPT, REQUEST_CHANGES: the quote's version the customer saw (the card's own when null)
     * @param cartons  ACCEPT: cartons per quote line id; a line not named is accepted as proposed
     * @param note     REQUEST_CHANGES (feedback) and REQUEST_REPRINT (required) - what to change
     * @param score    RATE: 1 to 5
     * @param comment  RATE: optional
     */
    public record Action(String action, Long version, Map<UUID, Integer> cartons, String note, Integer score,
                         String comment) {
    }

    private final ChatCallers callers;
    private final ConversationWriter writer;
    private final ChatMessageRepository messages;
    private final ChatCards cards;
    private final DocumentCards documents;
    private final ChatViews views;
    private final QuoteCards quotes;
    private final QuoteRequests quoteRequests;
    private final SampleRoundCards samples;
    private final CustomerInstallments installments;
    private final ChatProperties properties;
    private final Clock clock;

    public ChatCardActions(ChatCallers callers, ConversationWriter writer, ChatMessageRepository messages,
                           ChatCards cards, DocumentCards documents, ChatViews views, QuoteCards quotes,
                           QuoteRequests quoteRequests, SampleRoundCards samples, CustomerInstallments installments,
                           ChatProperties properties, Clock clock) {
        this.callers = callers;
        this.writer = writer;
        this.messages = messages;
        this.cards = cards;
        this.documents = documents;
        this.views = views;
        this.quotes = quotes;
        this.quoteRequests = quoteRequests;
        this.samples = samples;
        this.installments = installments;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The customer acts on a card of their own conversation.
     *
     * @throws BusinessException {@code CHAT_CONVERSATION_NOT_FOUND} / {@code CHAT_MESSAGE_NOT_FOUND} for another
     *         customer's; {@code CHAT_CARD_ACTION_NOT_ALLOWED} for an action the card does not offer them now; the
     *         document's own refusals
     */
    public ChatMessageView customerAct(CurrentUser user, UUID conversationId, UUID messageId, Action request) {
        ChatCallers.CustomerCaller caller = callers.customer(user);
        Conversation c = writer.lockOwn(conversationId, caller.customerId());
        ChatMessage card = cardOf(c, messageId, Audience.CUSTOMER);
        String action = requireOffered(card, request, true);
        JsonNode payload = cards.readPayload(card.card().payloadJson());
        Actor actor = caller.actor();
        switch (action) {
            case CardActions.ACCEPT -> {
                long version = request.version() != null ? request.version() : payload.path("version").asLong();
                QuoteCards.Card quote = quotes.accept(user, uuid(payload, "quoteId"), version, request.cartons());
                cards.update(card.id(), CardState.ACTED, documents.quotePayload(quote), actor, action,
                        "{\"quoteId\":\"" + quote.id() + "\",\"quoteStatus\":\"" + quote.status() + "\"}");
            }
            case CardActions.REQUEST_CHANGES -> {
                long version = request.version() != null ? request.version() : payload.path("version").asLong();
                QuoteCards.Card quote = quotes.requestChanges(user, uuid(payload, "quoteId"), version,
                        required(request.note(), "Say what to change in the quote"));
                cards.update(card.id(), CardState.ACTED, documents.quotePayload(quote), actor, action,
                        "{\"quoteId\":\"" + quote.id() + "\",\"quoteStatus\":\"" + quote.status() + "\"}");
            }
            case CardActions.APPROVE, CardActions.REQUEST_REPRINT -> {
                boolean approved = CardActions.APPROVE.equals(action);
                String note = approved ? null : required(request.note(), "Say why the sample is to be printed again");
                SampleRoundCards.Card round = samples.answer(caller.customerId(), user.userId(),
                        uuid(payload, "roundId"), approved, note);
                cards.update(card.id(), CardState.ACTED, samplePayload(payload, round), actor, action,
                        "{\"roundId\":\"" + round.roundId() + "\",\"result\":\"" + round.status() + "\"}");
            }
            case CardActions.RATE -> {
                int score = request.score() == null ? 0 : request.score();
                rate(c, actor, score, request.comment());
                ObjectNode rated = ((ObjectNode) payload.deepCopy()).put("score", score);
                cards.update(card.id(), CardState.ACTED, rated, actor, action, "{\"score\":" + score + "}");
            }
            default -> throw new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED);
        }
        return views.message(messages.findById(card.id(), Audience.CUSTOMER).orElseThrow());
    }

    /**
     * Sales act on a card of a conversation they are in charge of: draw up a DRAFT quote from a quote request (16 §4.2,
     * BR-11; 14 BR-22) - its SKUs, the cartons asked as a suggestion, the delivery date asked - once every design on
     * it is approved. The request is then closed in the design module, and the quote attached to the conversation, so
     * the quote's card lands here when Sales send it.
     *
     * @throws BusinessException {@code CHAT_CARD_ACTION_NOT_ALLOWED} for the customer's actions (BR-10, BR-11) or a
     *         card no longer open; {@code CHAT_DESIGN_NOT_APPROVED} while a design waits for review;
     *         {@code CHAT_NOT_ASSIGNEE}
     */
    public ChatMessageView staffAct(CurrentUser user, UUID conversationId, UUID messageId, Action request) {
        Actor staff = callers.staff(user);
        Conversation c = writer.lock(conversationId);
        ChatMessage card = cardOf(c, messageId, Audience.STAFF);
        String action = requireOffered(card, request, false);
        c.requireInCharge(staff, false);
        if (!CardActions.CREATE_QUOTE.equals(action) || card.card().kind() != CardKind.QUOTE_REQUEST) {
            throw new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED);
        }
        JsonNode payload = cards.readPayload(card.card().payloadJson());
        boolean project = "PROJECT".equals(payload.path("scope").asText());
        UUID requestId = uuid(payload, "requestId");
        List<QuoteCards.DraftLine> lines;
        LocalDate wanted;
        ObjectNode next;
        if (project) {
            QuoteRequests.ProjectRequest asked = quoteRequests.projectRequest(requestId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED));
            if (asked.lines().stream().anyMatch(l -> !"APPROVED".equals(l.designStatus()) || l.customerSku() == null)) {
                throw new BusinessException(ErrorCode.CHAT_DESIGN_NOT_APPROVED);
            }
            lines = asked.lines().stream().map(l -> new QuoteCards.DraftLine(l.customerSku(), l.cartons())).toList();
            wanted = asked.desiredDeliveryDate();
            next = (ObjectNode) payload.deepCopy();
        } else {
            QuoteRequests.DesignRequest asked = quoteRequests.designRequest(requestId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED));
            if (!"APPROVED".equals(asked.designStatus()) || asked.customerSku() == null) {
                throw new BusinessException(ErrorCode.CHAT_DESIGN_NOT_APPROVED);
            }
            if (!"OPEN".equals(asked.status())) {
                throw new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED, "The quote request is closed");
            }
            lines = List.of(new QuoteCards.DraftLine(asked.customerSku(), asked.cartons()));
            wanted = asked.desiredDeliveryDate();
            next = documents.designRequestPayload(asked, null);
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, properties.businessHours().zone());
        QuoteCards.Card quote = quotes.draft(user, new QuoteCards.Draft(draftRequestId(card.id()), c.customerId(),
                lines, wanted == null || wanted.isBefore(today) ? null : wanted,
                now.plus(properties.draftQuoteValidity())));
        if (!project) {
            quoteRequests.close(requestId, staff.userId());
            next.put("requestStatus", "CLOSED");
        }
        next.put("quoteId", quote.id().toString());
        next.put("quoteNumber", quote.number());
        cards.attach(c, new ContextRef(ContextKind.QUOTE, quote.id()), staff);
        cards.update(card.id(), CardState.ACTED, next, staff, action, "{\"quoteId\":\"" + quote.id() + "\"}");
        return views.message(messages.findById(card.id(), Audience.STAFF).orElseThrow());
    }

    /**
     * Sales send the transfer details of one of the customer's installments (16 §4.4 steps 9-10, BR-06): the system
     * draws the card - the company's account from configuration, the amount and content from the installment -
     * {@code reminder} marking it as a reminder of an overdue one. There is no other way to send an account number.
     *
     * @throws BusinessException {@code CHAT_CONTEXT_NOT_FOUND} for an installment that is not the conversation's
     *         customer's (BR-02); {@code CHAT_CARD_ACTION_NOT_ALLOWED} for one paid or cancelled
     */
    public ChatMessageView sendTransferDetails(CurrentUser user, UUID conversationId, UUID installmentId,
                                               boolean reminder) {
        Actor staff = callers.staff(user);
        Conversation c = writer.lock(conversationId);
        c.requireInCharge(staff, false);
        CustomerInstallments.Transfer transfer = installments.transfer(installmentId)
                .filter(t -> t.customerId().equals(c.customerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CONTEXT_NOT_FOUND));
        if (transfer.outstanding().signum() <= 0 || "CANCELLED".equals(transfer.status())
                || "PAID".equals(transfer.status())) {
            throw new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED, "Installment %s owes nothing"
                    .formatted(transfer.transferReference()));
        }
        UUID contextId = cards.attach(c, new ContextRef(ContextKind.CONTRACT, transfer.contractId()), staff);
        UUID posted = cards.postInto(c, contextId, reminder ? CardKind.PAYMENT_REMINDER : CardKind.PAYMENT_INFO,
                documents.transferPayload(transfer, reminder), (reminder ? "Nhắc thanh toán " : "Thông tin chuyển khoản ")
                        + transfer.transferReference(), staff).messageId();
        return views.message(messages.findById(posted, Audience.STAFF).orElseThrow());
    }

    /** The customer's rating (CSAT), on the survey card or straight on the conversation; once. */
    void rate(Conversation c, Actor customer, int score, String comment) {
        UUID agent = c.assignedAgentId();
        c.rate(customer, score, comment, clock.instant());
        writer.save(c, agent);
    }

    /** The same quote request card asks the same draft: a retried click answers the quote the first one drew up. */
    static UUID draftRequestId(UUID cardId) {
        return UUID.nameUUIDFromBytes(("chat-quote-request-card:" + cardId).getBytes(StandardCharsets.UTF_8));
    }

    private ChatMessage cardOf(Conversation c, UUID messageId, Audience audience) {
        return messages.findById(messageId, audience)
                .filter(m -> m.kind() == MessageKind.CARD && m.conversationId().equals(c.id()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
    }

    /** The action named, when the card offers it to this side now. */
    private String requireOffered(ChatMessage card, Action request, boolean customer) {
        String action = request == null || request.action() == null ? "" : request.action().strip()
                .toUpperCase(java.util.Locale.ROOT);
        if (customer != CUSTOMER_ACTIONS.contains(action)) {
            throw new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED, customer
                    ? "This is the staff's to do" : "This is the customer's to do on the card (16 BR-10, BR-11)");
        }
        CardActions.Available offered = CardActions.of(card.card().kind(), card.card().state(),
                cards.readPayload(card.card().payloadJson()));
        if (!(customer ? offered.customer() : offered.staff()).contains(action)) {
            if (card.card().kind() == CardKind.QUOTE_REQUEST && card.card().state() == CardState.OPEN
                    && CardActions.CREATE_QUOTE.equals(action)) {
                throw new BusinessException(ErrorCode.CHAT_DESIGN_NOT_APPROVED);
            }
            throw new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED);
        }
        return action;
    }

    private static ObjectNode samplePayload(JsonNode old, SampleRoundCards.Card round) {
        ObjectNode next = (ObjectNode) old.deepCopy();
        next.put("status", round.status());
        next.put("productionOrderStatus", round.productionOrderStatus());
        next.put("answeredAt", round.answeredAt() == null ? null : round.answeredAt().toString());
        return next;
    }

    private static String required(String text, String why) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, why);
        }
        return text.strip();
    }

    private static UUID uuid(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED);
        }
        return UUID.fromString(value);
    }
}
