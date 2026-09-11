package com.stockflow.common.domain;

import java.util.Optional;

/**
 * The shape a persistence <b>port</b> takes: what the domain asks of storage, in the domain's own
 * words.
 *
 * <p>Extend it in {@code <module>.internal.domain} and add the queries that module actually needs:</p>
 *
 * <pre>
 * public interface ProductRepository extends AggregateRepository&lt;Product, ProductId&gt; {
 *     Optional&lt;Product&gt; findBySku(Sku sku);
 *     List&lt;Product&gt; findActiveByCategory(CategoryId category);
 * }
 * </pre>
 *
 * <p><b>What must never appear on a port:</b> {@code Page}, {@code Pageable}, {@code Specification},
 * {@code Example}, or any JPA type. Those are Spring Data's vocabulary, and letting them through
 * means the domain can no longer be tested without Spring Data, and can no longer be moved to a
 * different store without touching every use case. The adapter in {@code internal.repository}
 * translates. {@code ArchitectureTest} enforces this.</p>
 *
 * <p>Pure Java on purpose — no annotations, nothing to import — so an in-memory implementation for
 * a unit test is a {@code HashMap} and twenty lines.</p>
 *
 * @param <A>  the aggregate root
 * @param <ID> its identifier type, which should be a wrapper such as {@code OrderId}, not a bare UUID
 */
public interface AggregateRepository<A, ID> {

    Optional<A> findById(ID id);

    /**
     * Insert or update, returning the stored state.
     *
     * <p>Always use the returned instance rather than the argument: the adapter may have refreshed
     * generated columns or the version, and the argument can be a detached copy.</p>
     */
    A save(A aggregate);

    boolean existsById(ID id);
}
