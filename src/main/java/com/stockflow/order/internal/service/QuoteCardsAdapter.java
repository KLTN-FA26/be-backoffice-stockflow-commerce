package com.stockflow.order.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.order.api.QuoteCards;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link QuoteCards} over the quote's own use cases ({@link Quotes}): a card's action is exactly the operation the
 * quote's page calls, so every check of the quote - its version, its validity, an active customer and SKUs - applies
 * on the card too (kltn-docs 16 BR-03).
 */
@Component
class QuoteCardsAdapter implements QuoteCards {

    private final Quotes quotes;
    private final com.stockflow.order.internal.domain.QuoteRepository repository;
    private final com.stockflow.order.internal.repository.QuoteSearch search;

    QuoteCardsAdapter(Quotes quotes, com.stockflow.order.internal.domain.QuoteRepository repository,
                      com.stockflow.order.internal.repository.QuoteSearch search) {
        this.quotes = quotes;
        this.repository = repository;
        this.search = search;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Card> quote(UUID quoteId) {
        if (quoteId == null || repository.findById(quoteId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(card(quotes.get(SYSTEM, quoteId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Card> quoteOfOrder(UUID orderId) {
        return orderId == null ? Optional.empty() : search.quoteConvertedInto(orderId).flatMap(this::quote);
    }

    @Override
    public Card accept(CurrentUser customer, UUID quoteId, long version, Map<UUID, Integer> cartons) {
        return card(quotes.accept(customer, quoteId, new Quotes.Acceptance(version,
                cartons == null ? Map.of() : cartons)));
    }

    @Override
    public Card requestChanges(CurrentUser customer, UUID quoteId, long version, String feedback) {
        return card(quotes.requestChanges(customer, quoteId, version, feedback));
    }

    @Override
    public Card draft(CurrentUser staff, Draft draft) {
        List<Quotes.Line> lines = draft.lines().stream()
                .map(line -> new Quotes.Line(line.sku(), line.cartons(), List.of(), true)).toList();
        return card(quotes.create(staff, new Quotes.Create(draft.requestId(), draft.customerId(),
                new Quotes.Draft(draft.validUntil(), null, lines, draft.requestedDeliveryDate()))));
    }

    /**
     * The card shows the converted order only once the customer can open it - the rule of the quote's customer view
     * (17 §5.2): a card sits in the customer's conversation, and the staff view it is read from shows the DRAFT order.
     */
    private static Card card(Quotes.QuoteView view) {
        List<Line> lines = view.lines().stream().map(line -> new Line(line.id(), line.lineNo(), line.sku(),
                line.cartons(), line.unitsPerCarton(), line.units(), line.proposedUnitPrice(),
                line.proposedLineTotal(), line.acceptedCartons())).toList();
        Quotes.ConvertedOrder converted = view.convertedOrder() == null
                || view.convertedOrder().status() == com.stockflow.order.api.OrderStatus.DRAFT
                ? null : view.convertedOrder();
        return new Card(view.id(), view.quoteNumber(), view.customerId(), view.status().name(), view.revision(),
                view.validUntil(), view.requestedDeliveryDate(), lines, view.proposedTotal(), view.acceptedTotal(),
                converted == null ? null : converted.orderId(), converted == null ? null : converted.orderNumber(),
                view.sentBy(), view.sentAt(), view.version());
    }

    /**
     * Who reads a quote for a card: no portal customer, so the quote's own visibility rule (a portal user sees only
     * their own sent quotes) does not apply - the chat checks the card's conversation is the quote's customer's.
     */
    private static final CurrentUser SYSTEM = new CurrentUser(new UUID(0L, 0L), "chat-card", java.util.Set.of(),
            java.util.Set.of(), com.stockflow.common.security.DataScope.ALL, null);
}
