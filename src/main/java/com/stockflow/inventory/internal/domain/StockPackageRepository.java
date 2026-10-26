package com.stockflow.inventory.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The persistence port of {@link StockPackage}. */
public interface StockPackageRepository {

    Optional<StockPackage> findById(StockPackageId id);

    Optional<StockPackage> findByLpn(String lpn);

    /** The cartons with these LPNs, in one read; an LPN that is not a carton is absent. */
    List<StockPackage> findByLpns(Collection<String> lpns);

    /** The cartons labelled on these goods receipt lines, read without locking. */
    List<StockPackage> findByReceiptLines(Collection<java.util.UUID> goodsReceiptLineIds);

    /**
     * The stock packages with these ids, locked {@code FOR UPDATE} in id order and read after the lock.
     *
     * <p>The lock order every writer of a stock package keeps (V20261020000100): the SKU advisory lock, then
     * the stock packages, then their stock rows by id. The commit-time checks of V20261020000200 read without
     * locking, so they are safe only because two transactions changing one carton are serialised here.</p>
     */
    List<StockPackage> findAllByIdForUpdate(Collection<StockPackageId> ids);

    StockPackage save(StockPackage stockPackage);

    /**
     * What is left in each of these stock packages: the sum of its rows' {@code on_hand}, in one read. A package
     * with no row is absent.
     */
    java.util.Map<StockPackageId, Integer> onHandOf(Collection<StockPackageId> ids);

    /**
     * The next LPN of {@code day} ({@code LPN-yyyyMMdd-nnnnnn}), from {@code platform.document_sequence} type
     * {@code LPN}; {@code day} is the business's calendar day, as every document number's is. Drawn and committed on
     * its own, outside the caller's transaction, so it holds no lock under the caller's SKU lock: a number is never
     * handed out twice, but one the caller then rolls back is lost (a gap).
     */
    String nextLpn(java.time.LocalDate day);
}
