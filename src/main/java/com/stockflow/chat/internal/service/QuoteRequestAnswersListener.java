package com.stockflow.chat.internal.service;

import com.stockflow.contracts.QuoteDrawnFromRequest;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A quote drawn up from a quote request, wherever Sales pressed the button: the request's cards show it
 * ({@link QuoteRequestAnswers}). After the quote's transaction committed, in its own, redelivered until it completes -
 * the quote stands whether or not its card could be updated.
 */
@Component
class QuoteRequestAnswersListener {

    private final QuoteRequestAnswers answers;

    QuoteRequestAnswersListener(QuoteRequestAnswers answers) {
        this.answers = answers;
    }

    @ApplicationModuleListener
    public void on(QuoteDrawnFromRequest event) {
        for (QuoteRequestAnswers.RequestCard card : answers.cardsOf(event)) {
            answers.answerCard(event, card);
        }
    }
}
