package com.stockflow.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Evicts cache entries <b>after</b> the transaction commits, not during it.
 *
 * <h2>The bug after-commit eviction prevents</h2>
 *
 * <p>The obvious way to invalidate a cache is to remove the entry as soon as the row changes. Under
 * a transaction that does the wrong thing, in a way that looks right in every test that runs a
 * single request at a time:</p>
 *
 * <pre>
 * 1. transaction begins
 * 2. price row updated to 120         (not visible to anyone else yet)
 * 3. "prices:SKU-1" removed from the cache
 * 4. ANOTHER REQUEST reads the price, misses the cache, reads the OLD committed row (100),
 *    and caches 100
 * 5. this transaction commits
 * ...the cache now holds 100 forever, while the database says 120.
 * </pre>
 *
 * <p>The window between steps 3 and 5 is small, which is exactly what makes this so unpleasant: it
 * happens under load, in production, and never on a developer's machine. Worse, the cache is now
 * <i>more</i> wrong than if nothing had been evicted at all, and it stays wrong until the TTL
 * expires. Moving the evict earlier ({@code @CacheEvict(beforeInvocation = true)}) does not help;
 * it widens the window.</p>
 *
 * <p>The fix is to run the eviction as an after-commit callback. The entry is removed once the new
 * value is visible to everyone, so the next reader caches the new value. On a rollback the callback
 * never fires, which is also correct: nothing changed, so nothing needed evicting.</p>
 *
 * <h2>What this class adds over {@code @CacheEvict}</h2>
 *
 * <p>{@link CacheConfig} calls {@code transactionAware()}, and Spring's
 * {@code TransactionAwareCacheDecorator} defers {@code put}, {@code evict} <i>and</i> {@code clear}
 * to after the commit. So a plain {@code @CacheEvict} is <b>already</b> safe here — the scenario
 * above is not an argument for this class, it is an argument for that flag, and the flag is on.</p>
 *
 * <p>What the annotation cannot do is evict a key it cannot name at compile time. Use this class
 * when:</p>
 *
 * <ul>
 *   <li>the key is only known once the method has run — the identifier of a row that was just
 *       inserted, or a SKU read out of the aggregate rather than passed in;</li>
 *   <li>one change invalidates several entries, or entries in a different cache than the one the
 *       method is annotated with;</li>
 *   <li>the eviction is conditional on something the SpEL {@code condition} attribute would have to
 *       reach into the domain model to express.</li>
 * </ul>
 *
 * <p>It also fails loudly on a cache name that is not in {@link CacheNames}, where the annotation
 * would silently address a cache nobody configured.</p>
 *
 * <pre>
 * &#64;Transactional
 * public void changePrice(Sku sku, Money newPrice) {
 *     repository.save(...);
 *     evictor.evictAfterCommit(CacheNames.PRICES, sku.code());
 * }
 * </pre>
 *
 * <p>Called outside a transaction it evicts immediately, so a service that is used both ways
 * behaves correctly in both.</p>
 */
@Component
public class TransactionalCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(TransactionalCacheEvictor.class);

    private final CacheManager cacheManager;

    public TransactionalCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Remove one key once the current transaction commits.
     *
     * <p>Called outside a transaction, it evicts immediately — so a caller does not have to know
     * whether it is inside one, and a service used both ways behaves correctly in both.</p>
     */
    public void evictAfterCommit(String cacheName, Object key) {
        run(() -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                // A name that is not in CacheNames. Loud, because the alternative is an eviction
                // that silently never happens and stale data nobody can explain.
                log.error("Cannot evict from unknown cache '{}' - is it declared in CacheNames?",
                        cacheName);
                return;
            }
            // evictIfPresent, NOT evict. CacheConfig calls transactionAware(), so every cache
            // here is wrapped in Spring's TransactionAwareCacheDecorator - and that decorator
            // defers evict() to an afterCommit callback of its own. We are ALREADY inside an
            // afterCommit callback: synchronization is still active (it is torn down later, in
            // afterCompletion), so the decorator would happily register a second synchronization
            // that the commit loop - which iterates a snapshot taken before the callbacks ran -
            // never invokes. The eviction would vanish, silently, and the log line below would
            // still claim it happened. evictIfPresent and invalidate are the two methods the
            // decorator passes straight through, precisely because they return a result that
            // cannot be deferred.
            cache.evictIfPresent(key);
            log.debug("Evicted {}[{}] after commit", cacheName, key);
        });
    }

    /**
     * Clear a whole cache once the current transaction commits.
     *
     * <p>For a change that invalidates entries whose keys you cannot enumerate — a new price list,
     * a re-imported catalogue. Blunt: it costs every other entry in that cache a re-read. Prefer
     * {@link #evictAfterCommit} when the keys are known.</p>
     */
    public void clearAfterCommit(String cacheName) {
        run(() -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                log.error("Cannot clear unknown cache '{}' - is it declared in CacheNames?", cacheName);
                return;
            }
            // invalidate(), not clear() - same reason as evictIfPresent above.
            cache.invalidate();
            log.info("Cleared cache '{}' after commit", cacheName);
        });
    }

    private void run(Runnable eviction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eviction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Deliberately afterCommit, not afterCompletion: on a rollback nothing changed, so
                // evicting would only throw away a still-correct entry.
                eviction.run();
            }
        });
    }
}
