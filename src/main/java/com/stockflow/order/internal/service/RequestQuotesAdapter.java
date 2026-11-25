package com.stockflow.order.internal.service;

import com.stockflow.design.spi.RequestQuotes;
import com.stockflow.order.api.QuotesFromRequests.Scope;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.repository.QuoteSearch;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order module's answer to {@link RequestQuotes}: each request's quote found by the request id
 * {@link QuoteFromRequestService#quoteRequestId} derives from it, in one statement for all. A quote the chat card drew
 * before that id existed (PR #390, 2026-11-20) named its own card instead, so it is not found here.
 */
@Component
class RequestQuotesAdapter implements RequestQuotes {

    private final QuoteSearch quotes;

    RequestQuotesAdapter(QuoteSearch quotes) {
        this.quotes = quotes;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Answer> ofDesignRequests(Collection<UUID> requestIds) {
        return answers(Scope.DESIGN, requestIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Answer> ofProjectRequests(Collection<UUID> requestIds) {
        return answers(Scope.PROJECT, requestIds);
    }

    private Map<UUID, Answer> answers(Scope scope, Collection<UUID> requestIds) {
        if (requestIds == null || requestIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> askedBy = new HashMap<>();
        requestIds.stream().filter(Objects::nonNull).distinct()
                .forEach(id -> askedBy.put(QuoteFromRequestService.quoteRequestId(scope, id), id));
        Map<UUID, Answer> answers = new HashMap<>();
        quotes.byRequestIds(askedBy.keySet()).forEach(found -> answers.put(askedBy.get(found.requestId()),
                new Answer(found.quoteId(), found.quoteNumber(), found.sentAt() != null,
                        found.sentAt() == null && QuoteStatus.CANCELLED.name().equals(found.status()))));
        return answers;
    }
}
