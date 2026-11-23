package com.stockflow.invoicing.internal.service;

import java.time.Instant;

/**
 * When an invoice stuck at the provider may be taken back (WF-05 BE-07, BE-59; decision Q-5): the one rule
 * {@link EInvoiceIssuance#withdraw} applies to the provider's answer asked at that moment, and the screen applies to the
 * provider's last answer on record, to show "Rút" only where the withdrawal would be accepted (audit r2, contract H-2;
 * demo HĐ-1 step 2: "đang chờ" has "Kiểm tra lại" and no "Rút"). One rule, so the button and the refusal never
 * disagree on anything but what the provider says in between.
 *
 * <ul>
 *   <li><b>Held</b> - the provider once said it holds the transaction (a duplicate, a look-up that found it issued), or
 *       says now it is issued, issued without its number yet or still processing it (PENDING): never taken back, however
 *       long it waits (Q-5: "Đã phát hành, chưa có số = đang chờ, không bao giờ rút"; NĐ 254/2026 Phụ lục điểm 2b:
 *       one number, one use). Its way out is the provider's answer or the record from its portal.</li>
 *   <li><b>Too recent</b> - sent less than {@code withdraw-after} ago: the provider may still be working on it.</li>
 *   <li><b>No record</b> at the provider: taken back.</li>
 *   <li><b>Unconfirmed</b> - the provider cannot confirm what became of it: taken back only {@code withdraw-unknown-after}
 *       after it was last sent, and only with its support's written answer.</li>
 *   <li>Anything else the provider says - refused (the reconciliation records that itself), an answer that says
 *       nothing, no answer yet: not taken back.</li>
 * </ul>
 */
public final class WithdrawalRule {

    /** Why an invoice is not taken back now; what the screen shows instead of the button. */
    public enum Blocked {
        /** It is not SUBMITTING at a provider. */
        NOT_AT_PROVIDER,
        /** The provider holds it, or held it once (Q-5): never. */
        PROVIDER_HOLDS,
        /** Sent too recently; possible from {@link Decision#from()}. */
        TOO_RECENT,
        /** The provider does not say it has no record of it, nor that it cannot confirm it. */
        PROVIDER_STATE_UNCLEAR
    }

    /** What the provider says, asked now or last on record. */
    public enum ProviderState { NOT_FOUND, UNCONFIRMED, PENDING, ISSUED, REJECTED, UNKNOWN, NOT_ASKED }

    /**
     * @param blocked          null when it may be taken back now
     * @param from             when it may be taken back, for {@link Blocked#TOO_RECENT}; null otherwise
     * @param evidenceRequired whether the provider's support's written answer must come with it (UNCONFIRMED)
     */
    public record Decision(Blocked blocked, Instant from, boolean evidenceRequired) {

        public boolean allowed() {
            return blocked == null;
        }
    }

    private WithdrawalRule() {
    }

    /** When an invoice last sent at {@code lastSent} stops being too recent to take back; null when never sent. */
    static Instant earliest(Instant lastSent, InvoicingProperties properties) {
        return lastSent == null ? null : lastSent.plus(properties.withdrawAfter());
    }

    /**
     * @param held     whether the call log shows the provider once held the transaction
     * @param state    what the provider says
     * @param lastSent when the transaction was last sent; null when that is not on record
     */
    static Decision decide(boolean held, ProviderState state, Instant lastSent, Instant now,
                           InvoicingProperties properties) {
        if (held || state == ProviderState.PENDING || state == ProviderState.ISSUED) {
            return new Decision(Blocked.PROVIDER_HOLDS, null, false);
        }
        Instant earliest = earliest(lastSent, properties);
        if (earliest != null && now.isBefore(earliest)) {
            return new Decision(Blocked.TOO_RECENT, earliest, state == ProviderState.UNCONFIRMED);
        }
        if (state == ProviderState.NOT_FOUND) {
            return new Decision(null, null, false);
        }
        if (state == ProviderState.UNCONFIRMED) {
            Instant from = lastSent == null ? now : lastSent.plus(properties.withdrawUnknownAfter());
            return now.isBefore(from) ? new Decision(Blocked.TOO_RECENT, from, true) : new Decision(null, null, true);
        }
        return new Decision(Blocked.PROVIDER_STATE_UNCLEAR, null, false);
    }
}
