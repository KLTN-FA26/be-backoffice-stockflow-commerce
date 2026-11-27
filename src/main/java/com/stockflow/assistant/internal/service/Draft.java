package com.stockflow.assistant.internal.service;

import java.time.Instant;
import java.util.List;

/**
 * A text the model drafted for a person to edit and send through the screen's own action (ADR-0012: the assistant never
 * sends), with what it was drafted from and what in it nothing it was given holds
 * ({@code docs/assistant-work-features-fe-contract.md} §0, "Draft"). The one record of every model-backed draft - the
 * reply and the summary in the staff chat, the payment reminder, the message to a producer - all written by
 * {@link DraftWriter}, so a user meets one behaviour whichever draft they ask for.
 *
 * @param sources         the records the draft was written from, each linking to its screen
 * @param unverifiedCodes codes and figures the text states that no source carries ({@link AnswerCodes},
 *                        {@link AnswerNumbers}): shown flagged, never removed
 * @param model           the model that wrote it, as its provider names it
 */
public record Draft(String text, List<Source> sources, List<String> unverifiedCodes, String model,
                    Instant generatedAt) {

    public Draft {
        sources = List.copyOf(sources);
        unverifiedCodes = List.copyOf(unverifiedCodes);
    }

    /** What kind of record a source is, as the contract names it. */
    public enum Kind { ORDER, CONTRACT, INSTALLMENT, BANK_TRANSACTION, QUOTE, CARTON, PRODUCTION_ORDER, CHAT, RULE }

    /** @param href a back-office path to the record's screen; null when it has none */
    public record Source(Kind kind, String label, String href) {
    }
}
