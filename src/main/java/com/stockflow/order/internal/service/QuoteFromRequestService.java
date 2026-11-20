package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.contracts.QuoteDrawnFromRequest;
import com.stockflow.design.api.QuoteRequests;
import com.stockflow.order.api.QuoteCards;
import com.stockflow.order.api.QuotesFromRequests;
import com.stockflow.order.internal.domain.Quote;
import com.stockflow.order.internal.domain.QuoteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link QuotesFromRequests}: the one place a quote request becomes a DRAFT quote (kltn-docs 14 BR-22, 16 BR-11), for
 * the chat's card and the back office's request inbox alike. It lives here, not in the chat, because the quote is this
 * module's and the request is read through {@code design :: api}, which this module already depends on; the chat calls
 * it and then does what only the chat can (its card, its conversation), the inbox's controller answers with the quote.
 *
 * <p><b>Once per request.</b> The quote's request id is derived from the quote request's id, and the quote request's
 * advisory lock ({@link QuoteRepository#lockRequest}) is taken before anything is read, so two presses - from the card
 * and from the inbox, or two salespeople - queue: the second finds the first's quote and answers it, even though the
 * request is CLOSED by then. A request closed without a quote (Sales settled it otherwise) is refused.</p>
 *
 * <p>The lines are the request's own: one per design, the design's customer SKU at the cartons asked - a suggestion
 * Sales change on the draft. The delivery date asked is kept unless it has passed. The quote is valid
 * {@code stockflow.order.quote.draft-from-request-validity} until Sales change it.</p>
 */
@Service
@Transactional
public class QuoteFromRequestService implements QuotesFromRequests {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final QuoteRequests requests;
    private final QuoteCards quotes;
    private final QuoteRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration validity;

    public QuoteFromRequestService(QuoteRequests requests, QuoteCards quotes, QuoteRepository repository,
                                   ApplicationEventPublisher events, Clock clock,
                                   @Value("${stockflow.order.quote.draft-from-request-validity:P14D}") Duration validity) {
        if (validity == null || validity.isNegative() || validity.isZero()) {
            throw new IllegalArgumentException("stockflow.order.quote.draft-from-request-validity must be positive");
        }
        this.requests = requests;
        this.quotes = quotes;
        this.repository = repository;
        this.events = events;
        this.clock = clock;
        this.validity = validity;
    }

    @Override
    public Drawn draw(CurrentUser staff, Scope scope, UUID requestId) {
        UUID quoteRequestId = quoteRequestId(scope, requestId);
        repository.lockRequest(quoteRequestId);
        Optional<Quote> earlier = repository.findByRequestId(quoteRequestId);
        if (earlier.isPresent()) {
            return new Drawn(earlier.get().id(), earlier.get().quoteNumber(), earlier.get().customerId(), false);
        }
        Asked asked = scope == Scope.PROJECT ? project(requestId) : design(requestId);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, BUSINESS_ZONE);
        QuoteCards.Card quote = quotes.draft(staff, new QuoteCards.Draft(quoteRequestId, asked.customerId(),
                asked.lines(), asked.wanted() == null || asked.wanted().isBefore(today) ? null : asked.wanted(),
                now.plus(validity)));
        // Answered by this quote, the request closes - a project's as a design's (14 BR-22: one quote of every design
        // on it) - so it no longer reads as waiting in the request lists, and the next order asks again. Closing
        // publishes the request's own event, which the chat's card and the notifications follow.
        if (scope == Scope.PROJECT) {
            requests.closeProject(requestId, staff.userId());
        } else {
            requests.close(requestId, staff.userId());
        }
        events.publishEvent(new QuoteDrawnFromRequest(quote.id(), quote.number(), asked.customerId(), scope.name(),
                requestId, staff.userId(), now));
        return new Drawn(quote.id(), quote.number(), asked.customerId(), true);
    }

    /**
     * The quote's request id for a quote request: the same request always names the same quote, from whichever
     * screen. The scope is in it because a design's and a project's requests are rows of different tables.
     */
    static UUID quoteRequestId(Scope scope, UUID requestId) {
        return UUID.nameUUIDFromBytes(("quote-from-request:" + scope.name() + ":" + requestId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private record Asked(UUID customerId, List<QuoteCards.DraftLine> lines, LocalDate wanted) {
    }

    private Asked design(UUID requestId) {
        QuoteRequests.DesignRequest asked = requests.designRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!"APPROVED".equals(asked.designStatus()) || asked.customerSku() == null) {
            throw notApproved();
        }
        requireOpen(asked.status());
        return new Asked(asked.customerId(), List.of(new QuoteCards.DraftLine(asked.customerSku(), asked.cartons())),
                asked.desiredDeliveryDate());
    }

    private Asked project(UUID requestId) {
        QuoteRequests.ProjectRequest asked = requests.projectRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (asked.lines().stream().anyMatch(l -> !"APPROVED".equals(l.designStatus()) || l.customerSku() == null)) {
            throw notApproved();
        }
        requireOpen(asked.status());
        return new Asked(asked.customerId(), asked.lines().stream()
                .map(l -> new QuoteCards.DraftLine(l.customerSku(), l.cartons())).toList(), asked.desiredDeliveryDate());
    }

    private static BusinessException notApproved() {
        return new BusinessException(ErrorCode.DESIGN_NOT_APPROVED,
                "A design of the request is not approved yet; a quote follows its approval")
                .localized("refusal.quote.requestDesignNotApproved");
    }

    private static void requireOpen(String status) {
        if (!"OPEN".equals(status)) {
            throw new BusinessException(ErrorCode.DESIGN_QUOTE_REQUEST_CLOSED);
        }
    }
}
