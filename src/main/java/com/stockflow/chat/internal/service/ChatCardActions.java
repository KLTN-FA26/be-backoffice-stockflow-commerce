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
import com.stockflow.order.api.QuoteCards;
import com.stockflow.order.api.QuotesFromRequests;
import com.stockflow.order.api.SampleRoundCards;
import com.stockflow.order.api.ShortageCards;
import com.stockflow.order.api.ShortageOption;
import com.stockflow.payment.api.CustomerInstallments;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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
            CardActions.APPROVE, CardActions.REQUEST_REPRINT, CardActions.RATE, CardActions.CHOOSE);

    /**
     * What a card's action carries.
     *
     * @param version  ACCEPT, REQUEST_CHANGES: the quote's version the customer saw (the card's own when null)
     * @param cartons  ACCEPT: cartons per quote line id; a line not named is accepted as proposed
     * @param note     REQUEST_CHANGES (feedback) and REQUEST_REPRINT (required) - what to change
     * @param score    RATE: 1 to 5
     * @param comment  RATE: optional
     * @param option   CHOOSE: the way on the customer picks ({@link ShortageOption} name); {@code note} is their
     *                 optional message, {@code version} the card's version they saw
     */
    public record Action(String action, Long version, Map<UUID, Integer> cartons, String note, Integer score,
                         String comment, String option) {

        /** An action that names no option (every card but the shortage card's). */
        public Action(String action, Long version, Map<UUID, Integer> cartons, String note, Integer score,
                      String comment) {
            this(action, version, cartons, note, score, comment, null);
        }
    }

    private final ChatCallers callers;
    private final ConversationWriter writer;
    private final ChatMessageRepository messages;
    private final ChatCards cards;
    private final DocumentCards documents;
    private final ChatViews views;
    private final QuoteCards quotes;
    private final QuotesFromRequests fromRequests;
    private final QuoteRequestAnswers answers;
    private final SampleRoundCards samples;
    private final CustomerInstallments installments;
    private final ShortageCards shortage;
    private final ShortageCardMessages shortageMessages;
    private final ContextResolver contexts;
    private final Clock clock;

    public ChatCardActions(ChatCallers callers, ConversationWriter writer, ChatMessageRepository messages,
                           ChatCards cards, DocumentCards documents, ChatViews views, QuoteCards quotes,
                           QuotesFromRequests fromRequests, QuoteRequestAnswers answers, SampleRoundCards samples,
                           CustomerInstallments installments, ShortageCards shortage,
                           ShortageCardMessages shortageMessages, ContextResolver contexts, Clock clock) {
        this.callers = callers;
        this.writer = writer;
        this.messages = messages;
        this.cards = cards;
        this.documents = documents;
        this.views = views;
        this.quotes = quotes;
        this.fromRequests = fromRequests;
        this.answers = answers;
        this.samples = samples;
        this.installments = installments;
        this.shortage = shortage;
        this.shortageMessages = shortageMessages;
        this.contexts = contexts;
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
                        required(request.note(), "Say what to change in the quote", "refusal.chat.changesNote"));
                cards.update(card.id(), CardState.ACTED, documents.quotePayload(quote), actor, action,
                        "{\"quoteId\":\"" + quote.id() + "\",\"quoteStatus\":\"" + quote.status() + "\"}");
            }
            case CardActions.APPROVE, CardActions.REQUEST_REPRINT -> {
                boolean approved = CardActions.APPROVE.equals(action);
                String note = approved ? null : required(request.note(), "Say why the sample is to be printed again",
                        "refusal.chat.reprintNote");
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
            case CardActions.CHOOSE -> {
                // 17 §4.14 step 57: the customer's press is the evidence of consent - the order module records it, once,
                // and refuses a stale card (SHORTAGE_CARD_STATE_CONFLICT) or an option the card did not offer.
                long version = request.version() != null ? request.version() : payload.path("version").asLong();
                ShortageCards.Card chosen = shortage.choose(caller.customerId(), user.userId(),
                        uuid(payload, "cardId"), option(request.option()), request.note(), version);
                cards.update(card.id(), ShortageCardMessages.stateOf(chosen.status()), shortageMessages.payload(chosen),
                        actor, action, "{\"cardId\":\"" + chosen.cardId() + "\",\"option\":\""
                                + chosen.chosen().option().name() + "\"}");
            }
            default -> throw new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED);
        }
        return views.message(messages.findById(card.id(), Audience.CUSTOMER).orElseThrow());
    }

    /**
     * Sales act on a card of a conversation they are in charge of: draw up a DRAFT quote from a quote request (16 §4.2,
     * BR-11; 14 BR-22) - its SKUs, the cartons asked as a suggestion, the delivery date asked - once every design on
     * it is approved. Drawing it up and closing the request are the order module's {@link QuotesFromRequests}, the
     * operation the back office's request inbox calls too, so the two cannot drift; this adds the chat's own checks
     * before it (the conversation, who is in charge, the card offering the action) and its own error codes, which the
     * chat's screen matches on, and then {@link QuoteRequestAnswers} shows the quote on the card and attaches it to
     * the conversation, so the quote's card lands here when Sales send it. A request answered already from the inbox
     * answers that quote here too.
     *
     * @throws BusinessException {@code CHAT_CARD_ACTION_NOT_ALLOWED} for the customer's actions (BR-10, BR-11), a
     *         card no longer open or a request closed without a quote; {@code CHAT_DESIGN_NOT_APPROVED} while a design
     *         waits for review; {@code CHAT_NOT_ASSIGNEE}
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
        QuotesFromRequests.Scope scope = "PROJECT".equals(payload.path("scope").asText())
                ? QuotesFromRequests.Scope.PROJECT : QuotesFromRequests.Scope.DESIGN;
        QuotesFromRequests.Drawn quote;
        try {
            quote = fromRequests.draw(user, scope, uuid(payload, "requestId"));
        } catch (BusinessException refused) {
            throw onTheCard(refused);
        }
        if (!quote.customerId().equals(c.customerId())) {
            // BR-02: the quote is the request's customer's; a card about another customer's request draws up nothing.
            throw new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED);
        }
        answers.answer(c, card, quote.quoteId(), quote.quoteNumber(), staff);
        return views.message(messages.findById(card.id(), Audience.STAFF).orElseThrow());
    }

    /**
     * The card's own words for the request's refusals: the chat's screen matches on {@code CHAT_DESIGN_NOT_APPROVED},
     * and a request gone or closed is an action the card no longer offers. The quote's own refusals pass through.
     */
    private static BusinessException onTheCard(BusinessException refused) {
        return switch (refused.errorCode()) {
            case DESIGN_NOT_APPROVED -> new BusinessException(ErrorCode.CHAT_DESIGN_NOT_APPROVED);
            case DESIGN_QUOTE_REQUEST_CLOSED -> new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED,
                    "The quote request is closed").localized("refusal.chat.requestClosed");
            case NOT_FOUND -> new BusinessException(ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED);
            default -> refused;
        };
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
                    .formatted(transfer.transferReference()))
                    .localized("refusal.chat.nothingOwed", transfer.transferReference());
        }
        UUID contextId = cards.attach(c, new ContextRef(ContextKind.CONTRACT, transfer.contractId()), staff);
        UUID posted = cards.postInto(c, contextId, reminder ? CardKind.PAYMENT_REMINDER : CardKind.PAYMENT_INFO,
                documents.transferPayload(transfer, reminder), (reminder ? "Nhắc thanh toán " : "Thông tin chuyển khoản ")
                        + transfer.transferReference(), staff).messageId();
        return views.message(messages.findById(posted, Audience.STAFF).orElseThrow());
    }

    /**
     * Sales send the shortage choice card of one of the customer's orders into the conversation they are in charge of
     * (17 §4.14 step 55, 16 BR-15): the system draws it from the order - the batch, the cartons short from the
     * warehouse, the options with their figures - so Sales type only a note. The card is the order module's document
     * ({@link ShortageCards#send}); this posts it here, attaches the order to the conversation, and keeps its message
     * for later updates. {@code replaceLive} replaces the card still waiting for the same batch.
     *
     * @throws BusinessException {@code CHAT_CONTEXT_NOT_FOUND} for an order that is not the conversation's customer's
     *         (BR-02); {@code CHAT_NOT_ASSIGNEE}; the order's own refusals ({@code SHORTAGE_CARD_NOT_SENDABLE})
     */
    public ChatMessageView sendShortageCard(CurrentUser user, UUID conversationId, UUID orderId, String note,
                                            boolean replaceLive) {
        Actor staff = callers.staff(user);
        Conversation c = writer.lock(conversationId);
        c.requireInCharge(staff, false);
        ContextResolver.Resolved about = contexts.requireOwnedBy(new ContextRef(ContextKind.ORDER, orderId),
                c.customerId(), false);
        ShortageCards.Card card = shortage.send(c.customerId(), orderId, note, replaceLive, user.userId());
        UUID contextId = cards.attach(c, about.ref(), staff);
        UUID posted = cards.postInto(c, contextId, CardKind.SHORTAGE_CHOICE, shortageMessages.payload(card),
                ShortageCardMessages.fallbackText(card), staff).messageId();
        shortage.linkMessage(card.cardId(), posted);
        return views.message(messages.findById(posted, Audience.STAFF).orElseThrow());
    }

    /** The customer's rating (CSAT), on the survey card or straight on the conversation; once. */
    void rate(Conversation c, Actor customer, int score, String comment) {
        UUID agent = c.assignedAgentId();
        c.rate(customer, score, comment, clock.instant());
        writer.save(c, agent);
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
                    ? "This is the staff's to do" : "This is the customer's to do on the card (16 BR-10, BR-11)")
                    .localized(customer ? "refusal.chat.staffAction" : "refusal.chat.customerAction");
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

    private static ShortageOption option(String name) {
        try {
            return ShortageOption.valueOf(name == null ? "" : name.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name one of the options on the card");
        }
    }

    private static String required(String text, String why, String key) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, why).localized(key);
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
