package com.stockflow.invoicing.internal.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where an e-invoice stands (WF-05 E2, plan §1.4; kltn-docs 21 §5.1).
 *
 * <pre>
 * DRAFT ──issue──▶ SUBMITTING ──provider/tax authority answer──▶ ISSUED ──replaced (E4)──▶ REPLACED
 *   │                   │ refused (a wrong tax code ...)
 *   │                   ▼
 *   │                REJECTED ──corrected──▶ DRAFT
 *   └─ voided before it was ever sent ─▶ VOIDED_DRAFT
 * </pre>
 *
 * <p><b>There is no way out of ISSUED but REPLACED, and no cancellation.</b> TT 91/2026/TT-BTC Điều 10 has no
 * procedure to cancel an invoice that was drawn up: a wrong one is adjusted or replaced by another invoice naming it,
 * or - a wrong name or address with the right tax code - notified to the tax authority without a new invoice. An
 * adjusted invoice stays ISSUED and carries a flag. The same edges are {@code tg_einvoice_guard}.</p>
 *
 * <p>SUBMITTING has no way back to DRAFT either: once the provider may have the invoice, only its answer for this
 * transaction decides, which is what keeps a lost reply from becoming a second invoice.</p>
 */
public enum EInvoiceStatus {

    /** Drawn up, not sent: the accountant reads it, corrects the buyer, issues or voids it. */
    DRAFT,
    /** Sent to the provider, or about to be: waiting for its answer under the invoice's transaction id. */
    SUBMITTING,
    /** Numbered, coded by the tax authority and signed. Never changes. */
    ISSUED,
    /** The provider or the tax authority refused it; no invoice exists at the provider. Goes back to a draft. */
    REJECTED,
    /** A draft that was never sent, given up with a reason. Final. */
    VOIDED_DRAFT,
    /** An issued invoice another invoice replaced (E4). Final, no longer in force. */
    REPLACED;

    public Set<EInvoiceStatus> next() {
        return switch (this) {
            case DRAFT -> EnumSet.of(SUBMITTING, VOIDED_DRAFT);
            case SUBMITTING -> EnumSet.of(ISSUED, REJECTED);
            case REJECTED -> EnumSet.of(DRAFT);
            case ISSUED -> EnumSet.of(REPLACED);
            case VOIDED_DRAFT, REPLACED -> EnumSet.noneOf(EInvoiceStatus.class);
        };
    }

    public boolean canTransitionTo(EInvoiceStatus target) {
        return next().contains(target);
    }

    /** Whether the invoice was issued: it has a number and its files, whether or not it is still in force. */
    public boolean wasIssued() {
        return this == ISSUED || this == REPLACED;
    }
}
