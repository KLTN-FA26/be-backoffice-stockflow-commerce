package com.stockflow.payment.internal.repository;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.payment.api.BankTransferReview;
import com.stockflow.payment.internal.domain.BankTransaction;
import com.stockflow.payment.internal.domain.BankTransactionLog;
import com.stockflow.payment.internal.domain.TransferReference;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The payment module's answer to {@link BankTransferReview}: the transfer as kept, and the payable installments in one
 * statement, ordered so that those the transfer most likely pays survive the limit.
 *
 * <p><b>"Named" is the bank feed's own reading.</b> The installments the content names are found exactly as
 * {@code BankTransactionService} finds the one it records a receipt for - {@link TransferReference#read} of the content,
 * else of SePay's payment code, then {@link BankTransactionLog#installmentsNamed} - so a suggestion never reads a
 * reference the feed would read differently. Every installment it names is marked, not only a single one: the feed
 * stops at "several installments", the Accountant splits the transfer among them.</p>
 */
@Component
class BankTransferReviewAdapter implements BankTransferReview {

    private static final int MAX_LIMIT = 500;
    /** Stands for "none" in an {@code IN} list or a comparison, so the statement needs no variant without them. */
    private static final UUID NONE = new UUID(0L, 0L);

    /**
     * The payable installments, likeliest first: named by the content, the feed's hint, owing exactly the amount, of an
     * order whose number's digits the content holds (dashes, spaces and the {@code SO} dropped, as banks rewrite it),
     * then by due day. Only the order: what is likely is the caller's to judge.
     */
    private static final String CANDIDATES = """
            SELECT %s
              FROM payment.installment i
             WHERE %s
             ORDER BY (i.id IN (:named)) DESC, (i.id = :hint) DESC, (%s = :amount) DESC,
                      (strpos(:digits, regexp_replace(i.order_number, '[^0-9]', '', 'g')) > 0) DESC,
                      i.due_date ASC NULLS LAST, i.contract_number, i.seq
             LIMIT :limit""".formatted(PayableInstallmentRows.COLUMNS, PayableInstallmentRows.PAYABLE,
            PayableInstallmentRows.OUTSTANDING);

    private final BankTransactionLog transactions;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    BankTransferReviewAdapter(BankTransactionLog transactions, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Transfer> transfer(UUID transactionId) {
        if (transactionId == null) {
            return Optional.empty();
        }
        return transactions.find(transactionId).map(BankTransferReviewAdapter::toTransfer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Candidate> candidates(UUID transactionId, int limit) {
        if (transactionId == null) {
            return List.of();
        }
        Optional<BankTransaction> found = transactions.find(transactionId);
        if (found.isEmpty()) {
            return List.of();
        }
        BankTransaction t = found.get();
        Set<UUID> named = named(t);
        UUID hint = t.installmentId();
        String content = t.transfer().content() == null ? "" : t.transfer().content();
        LocalDate today = BusinessCalendar.date(clock.instant());
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("today", today)
                .addValue("named", named.isEmpty() ? List.of(NONE) : List.copyOf(named))
                .addValue("hint", hint == null ? NONE : hint)
                .addValue("amount", t.amount())
                .addValue("digits", content.replaceAll("\\D", ""))
                .addValue("limit", Math.max(1, Math.min(limit, MAX_LIMIT)));
        return jdbc.query(CANDIDATES, parameters, (rs, n) -> {
            var installment = PayableInstallmentRows.read(rs, today);
            return new Candidate(installment, named.contains(installment.id()));
        });
    }

    /** The installments the content names as the bank feed reads it (class javadoc); empty when it names none. */
    private Set<UUID> named(BankTransaction t) {
        List<TransferReference> references = TransferReference.read(t.transfer().content());
        if (references.isEmpty()) {
            references = TransferReference.read(t.transfer().paymentCode());
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (TransferReference reference : references) {
            ids.addAll(transactions.installmentsNamed(reference.contractNumbers(), reference.seq()));
        }
        return ids;
    }

    private static Transfer toTransfer(BankTransaction t) {
        return new Transfer(t.id(), t.status().name(), t.amount().setScale(0, RoundingMode.HALF_UP),
                t.transfer().transactionAt(),
                t.transfer().content(), t.reviewReason() == null ? null : t.reviewReason().name(), t.installmentId());
    }
}
