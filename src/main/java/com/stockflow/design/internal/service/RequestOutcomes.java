package com.stockflow.design.internal.service;

import com.stockflow.design.internal.domain.QuoteRequestStatus;
import com.stockflow.design.spi.RequestQuotes;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * How a closed quote request ended (R5-5), so the customer who reads "closed" also reads the quote that answered it:
 * {@code QUOTED} when Sales drew a quote from it, {@code CLOSED_BY_STAFF} when a salesperson closed it without one.
 * A request closed by the system (its design deleted or archived) has no outcome, and neither has a request whose quote
 * the chat card drew before quotes named their request (PR #390, 2026-11-20): it is not found and reads
 * {@code CLOSED_BY_STAFF}, a database rebuilt since holds none.
 *
 * <p>The customer is given the quote only once it was sent: before that it is Sales' working copy, which they cannot
 * open; the outcome still reads {@code QUOTED}.</p>
 */
@Component
public class RequestOutcomes {

    public static final String QUOTED = "QUOTED";
    public static final String CLOSED_BY_STAFF = "CLOSED_BY_STAFF";

    /** @param closedOutcome null while the request is open, and for one the system closed */
    public record Outcome(String closedOutcome, UUID quoteId, String quoteNumber) {

        public static final Outcome NONE = new Outcome(null, null, null);
    }

    /** A request as far as its outcome goes. */
    public record Closed(UUID requestId, QuoteRequestStatus status, UUID closedBy) {
    }

    private final ObjectProvider<RequestQuotes> quotes;

    RequestOutcomes(ObjectProvider<RequestQuotes> quotes) {
        this.quotes = quotes;
    }

    /** By request id, for requests on one design; one statement for all of them. */
    public Map<UUID, Outcome> ofDesignRequests(Collection<Closed> requests, boolean forCustomer) {
        return outcomes(requests, forCustomer, false);
    }

    /** By request id, for project quote requests. */
    public Map<UUID, Outcome> ofProjectRequests(Collection<Closed> requests, boolean forCustomer) {
        return outcomes(requests, forCustomer, true);
    }

    private Map<UUID, Outcome> outcomes(Collection<Closed> requests, boolean forCustomer, boolean project) {
        List<UUID> closed = requests.stream().filter(r -> r.status() == QuoteRequestStatus.CLOSED)
                .map(Closed::requestId).distinct().toList();
        if (closed.isEmpty()) {
            return Map.of();
        }
        RequestQuotes source = quotes.getIfAvailable();
        Map<UUID, RequestQuotes.Answer> answers = source == null ? Map.of()
                : project ? source.ofProjectRequests(closed) : source.ofDesignRequests(closed);
        Map<UUID, Outcome> outcomes = new HashMap<>();
        for (Closed request : requests) {
            if (request.status() != QuoteRequestStatus.CLOSED) {
                continue;
            }
            RequestQuotes.Answer answer = answers.get(request.requestId());
            if (answer != null) {
                boolean shown = !forCustomer || answer.sent();
                outcomes.put(request.requestId(), new Outcome(QUOTED, shown ? answer.quoteId() : null,
                        shown ? answer.quoteNumber() : null));
            } else if (request.closedBy() != null) {
                outcomes.put(request.requestId(), new Outcome(CLOSED_BY_STAFF, null, null));
            }
        }
        return outcomes;
    }
}
