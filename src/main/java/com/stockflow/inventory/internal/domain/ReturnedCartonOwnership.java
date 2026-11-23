package com.stockflow.inventory.internal.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Whose goods a carton that came back on a return request is (WF-05 Q-4, decision 2026-11-23: "không có thoả thuận trả
 * hàng thì hàng vẫn là của khách ... không có bút toán nhập kho"). The persistence port of the {@code return_*} and
 * {@code custody_*} columns of {@code inventory.stock_package} (V20261223000300), kept beside the carton rather than in
 * {@link StockPackage} as {@code return_opened_at} is: they belong to the return, and only the return paths read them.
 *
 * <p>Every write is made by a caller holding the cartons' row locks (the SKU lock, then the cartons); the database checks
 * the states against the carton's status ({@code ck_stock_package_return_ownership_status}, {@code ..._custody_*}).</p>
 */
public interface ReturnedCartonOwnership {

    /** The states of {@code ck_stock_package_return_ownership}. */
    enum Ownership {
        /** Back on a return request whose refund is not decided: in quarantine, neither released nor written off. */
        PENDING,
        /** The return refunded units: the company's goods. */
        COMPANY,
        /** The return refunded nothing: the stock-in reversed, held for the customer in the quarantine area. */
        CUSTOMER_HELD,
        /** Handed back to the customer, as they chose (at their cost). */
        CUSTOMER_SENT_BACK,
        /** Destroyed on the customer's behalf, with their written consent. */
        CUSTOMER_DESTROYED;

        /** The customer's goods, whatever became of them since: never company stock. */
        public boolean customers() {
            return this == CUSTOMER_HELD || this == CUSTOMER_SENT_BACK || this == CUSTOMER_DESTROYED;
        }
    }

    /**
     * A carton held for a customer, or whose custody ended, as the warehouse manager's custody list shows it (TH-6).
     *
     * @param choice   what the customer chose as Sales recorded it ({@code SEND_BACK}, {@code DESTROY_ON_BEHALF}); null
     *                 until they did
     * @param endedAt  when the warehouse manager carried it out; null while it is held
     */
    record Custody(UUID stockPackageId, String lpn, String sku, String lotNumber, int quantity, String locationCode,
                   UUID returnRequestId, Ownership ownership, Instant heldAt, String choice, Instant endedAt,
                   UUID endedBy, String note) {
    }

    /**
     * The cartons came back into stock on a return receipt: of return request {@code returnRequestId} they wait for its
     * refund decision ({@link Ownership#PENDING}); of a carrier's return ({@code null}) they are the company's and carry
     * no ownership. Whatever an earlier return left on them is cleared.
     */
    void cameBack(Collection<StockPackageId> ids, UUID returnRequestId);

    /** The carton's ownership; empty for one that never came back on a return request. */
    Optional<Ownership> of(StockPackageId id);

    /** By LPN, the ownership of those of these cartons that came back on a return request. */
    Map<String, Ownership> ofLpns(Collection<String> lpns);

    /** The cartons of return {@code returnRequestId} still waiting for its refund decision. */
    List<StockPackageId> waitingFor(UUID returnRequestId);

    /** The return's refund was decided: {@link Ownership#COMPANY}, or {@link Ownership#CUSTOMER_HELD} since {@code at}. */
    void settle(Collection<StockPackageId> ids, Ownership decided, Instant at);

    /** The cartons of return {@code returnRequestId} held for the customer or whose custody ended. */
    List<Custody> custodyOf(UUID returnRequestId);

    /** Records what the customer chose on every carton of the return still held for them. */
    void instruct(UUID returnRequestId, String choice);

    /** One carton's custody; empty when it is not the customer's. */
    Optional<Custody> custody(StockPackageId id);

    /** Custody of the carton ended as chosen ({@code ended}: SENT_BACK or DESTROYED), by whom, with the record. */
    void end(StockPackageId id, Ownership ended, UUID by, Instant at, String note);

    /**
     * The cartons held for customers, oldest first, in the warehouses of {@code warehousePrefixes} (null: every one),
     * of one return when {@code returnRequestId} is named; {@code ended} lists those whose custody ended instead.
     */
    List<Custody> held(Collection<String> warehousePrefixes, UUID returnRequestId, boolean ended, int offset, int limit);

    /** How many {@link #held} lists in all. */
    long countHeld(Collection<String> warehousePrefixes, UUID returnRequestId, boolean ended);
}
