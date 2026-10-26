package com.stockflow.inventory.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Hands out LPN numbers of a day from {@code platform.document_sequence} (type {@code LPN}), for the cartons
 * labelled while receiving (SCRUM-473). The pattern of {@code OrderNumberSequence}.
 *
 * <p><b>{@code REQUIRES_NEW} is load-bearing.</b> Cartons are labelled under the SKU's advisory lock, and one count
 * of a goods receipt labels the cartons of several lines - often several SKUs - in one transaction. Had the upsert
 * joined that transaction, the day's sequence row would stay locked until commit, taken under one SKU's lock while
 * the next line waits for another's: receipt A holds SKU 1 and the row, receipt B holds SKU 2 and waits for the row,
 * A moves on to SKU 2 - a deadlock. Committed in its own short transaction, the number holds no lock past this call.</p>
 *
 * <p>The price of {@code REQUIRES_NEW} is a second pooled connection while the caller holds its own, so the numbers
 * a line needs are drawn as one block, in one call: a call per carton made a count of many cartons borrow that second
 * connection once per carton, and concurrent counts could hold every connection of the pool while each waited for
 * one more.</p>
 *
 * <p>Gaps follow too: a count that rolls back has used its numbers, and the next carton gets a later one. Gaps
 * are acceptable for a label; duplicates are not, and {@code uk_stock_package_lpn} still forbids them.</p>
 */
@Component
class LpnSequence {

    private final EntityManager entityManager;

    LpnSequence(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Reserves {@code count} consecutive numbers of {@code day} and returns the last of them: the block is
     * {@code last - count + 1 .. last}.
     *
     * <p>Public, and called from another bean ({@code StockPackageRepositoryAdapter}): Spring's proxy applies
     * {@code @Transactional} to public methods reached through the proxy only, and a package-private or
     * self-invoked one would silently join the caller's transaction - the deadlock this class exists to avoid.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long reserve(LocalDate day, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Reserve at least one LPN, not " + count);
        }
        Object value = entityManager.createNativeQuery("""
                        insert into platform.document_sequence (document_type, sequence_date, last_value)
                        values ('LPN', :day, :count)
                        on conflict (document_type, sequence_date)
                        do update set last_value = platform.document_sequence.last_value + :count
                        returning last_value""")
                .setParameter("day", day)
                .setParameter("count", count)
                .getSingleResult();
        return ((Number) value).longValue();
    }
}
