package com.stockflow.inventory.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The persistence port of {@link StockPackage}. */
public interface StockPackageRepository {

    Optional<StockPackage> findById(StockPackageId id);

    Optional<StockPackage> findByLpn(String lpn);

    /**
     * The stock packages with these ids, locked {@code FOR UPDATE} in id order and read after the lock.
     *
     * <p>The lock order every writer of a stock package keeps (V20261020000100): the SKU advisory lock, then
     * the stock packages, then their stock rows by id. The commit-time checks of V20261020000200 read without
     * locking, so they are safe only because two transactions changing one carton are serialised here.</p>
     */
    List<StockPackage> findAllByIdForUpdate(Collection<StockPackageId> ids);

    StockPackage save(StockPackage stockPackage);
}
