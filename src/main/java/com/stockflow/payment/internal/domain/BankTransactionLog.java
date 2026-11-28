package com.stockflow.payment.internal.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The transfers the bank feed reported ({@code payment.bank_transaction}): the port the bank-feed services work
 * through.
 */
public interface BankTransactionLog {

    /** A page of transfers, newest first by when the bank booked them. */
    record Page(List<BankTransaction> rows, long total) {
    }

    /**
     * Keeps a transfer just reported, unless the same transfer is kept already - by SePay's id or by its
     * {@linkplain BankTransfer#dedupeKey() dedupe key}. Never throws on a repeat, so two copies of one call arriving
     * together keep one row.
     *
     * @return true when this call kept it; false when it was a repeat
     */
    boolean keep(BankTransaction transaction);

    /** The kept transfer this one repeats: by SePay's id first, else by its dedupe key. */
    Optional<BankTransaction> findSame(BankTransaction.Provider provider, String providerTransactionId,
                                       String dedupeKey);

    Optional<BankTransaction> find(UUID id);

    /** {@code SELECT ... FOR UPDATE}: always the first lock a bank-feed write takes, before the orders'. */
    Optional<BankTransaction> lock(UUID id);

    /** The transfer a receipt was recorded from, locked; empty for a receipt the Accountant recorded by hand. */
    Optional<BankTransaction> lockByReceipt(UUID receiptId);

    BankTransaction save(BankTransaction transaction);

    /** What SePay sent for it, as kept. */
    Optional<String> raw(UUID id);

    /** RECEIVED transfers kept before {@code before}: left by a failure, for the catch-up to handle again. */
    List<UUID> stranded(Instant before);

    /**
     * Transfers in {@code statuses} (all when empty), newest first; {@code amount}, when given, keeps the transfers of
     * exactly that much (audit bo-25: the Accountant finds a transfer by the sum the customer says they sent).
     */
    Page search(Collection<BankTransactionStatus> statuses, Instant from, Instant to, java.math.BigDecimal amount,
                int page, int size);

    /**
     * The installments whose contract is one of {@code contractNumbers} and whose seq is {@code seq}: what a transfer's
     * {@link TransferReference} may mean.
     */
    List<UUID> installmentsNamed(Collection<String> contractNumbers, int seq);
}
