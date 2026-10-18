package com.stockflow.order.internal.service;

import com.stockflow.order.api.QuoteDirectory;
import com.stockflow.order.internal.domain.QuoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class QuoteDirectoryService implements QuoteDirectory {

    private final QuoteRepository quotes;

    QuoteDirectoryService(QuoteRepository quotes) {
        this.quotes = quotes;
    }

    @Override
    public Optional<QuoteReference> findQuote(UUID quoteId) {
        return quotes.findById(quoteId).map(quote -> new QuoteReference(quote.id(), quote.quoteNumber(),
                quote.customerId(), quote.status().name(), quote.offer().lines().stream()
                .map(line -> new QuotedDesign(line.designSnapshotId(), line.blankSku().code())).toList()));
    }
}
