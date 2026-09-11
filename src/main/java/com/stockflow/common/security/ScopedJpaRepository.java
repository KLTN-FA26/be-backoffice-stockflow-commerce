package com.stockflow.common.security;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;

/**
 * The repository interface an entity implementing {@link ScopedEntity} must be queried through.
 *
 * <h2>How this actually prevents the leak</h2>
 *
 * <p>It would be nice to intercept {@code findAll()} and inject the scope automatically. That is
 * not possible without an ORM-level filter, and a Hibernate filter has its own trap: it has to be
 * enabled per session, and a code path that forgets is back to returning everything. So the
 * approach here is different and blunter:</p>
 *
 * <ol>
 *   <li>the scoped methods below are the ones that apply the filter;</li>
 *   <li>the inherited unscoped ones ({@code findAll}, {@code findAll(Specification)}) still exist,
 *       because background jobs genuinely need them;</li>
 *   <li>{@code ArchitectureTest} fails the build when a repository for a {@code ScopedEntity} is
 *       called from a {@code *QueryService} or controller via an unscoped method.</li>
 * </ol>
 *
 * <p>The safety therefore comes from a fitness function, not from a runtime trick — which is
 * slower to write once and much harder to defeat by accident. The naming helps too: a reviewer
 * seeing {@code findAllInScope} in one query service and {@code findAll} in the next has an
 * obvious question to ask.</p>
 *
 * <p>Every scoped method requires a scope to be in force and throws {@link ScopeViolationException}
 * if none is — so the failure mode of forgetting the annotation on the endpoint is a loud 403 on
 * the first call, not a quiet leak.</p>
 *
 * @param <E> a {@link BaseEntity} that is also a {@link ScopedEntity}
 */
@NoRepositoryBean
public interface ScopedJpaRepository<E extends BaseEntity & ScopedEntity> extends BaseJpaRepository<E> {

    /**
     * An instance used only to read the entity's scope attribute names.
     *
     * <p>The attribute names are static per entity, but Java has no way to ask an interface for a
     * static value through a type parameter. Implementations return a throwaway instance:</p>
     *
     * <pre>
     * &#64;Override
     * default OrderJpaEntity scopePrototype() { return OrderJpaEntity.SCOPE_PROTOTYPE; }
     * </pre>
     *
     * <p>Declared on the repository rather than derived by reflection so that a missing
     * implementation is a compile error.</p>
     */
    E scopePrototype();

    /** Every row the current user may see, filtered and paged. */
    default Page<E> findAllInScope(Specification<E> spec, Pageable pageable) {
        return findAll(scoped(spec), pageable);
    }

    default List<E> findAllInScope(Specification<E> spec) {
        return findAll(scoped(spec));
    }

    default long countInScope(Specification<E> spec) {
        return count(scoped(spec));
    }

    /**
     * Find one row by id, but only if the current user may see it.
     *
     * <p>Returning empty rather than throwing when the row exists outside the scope is intentional:
     * the caller then produces a 404, which does not confirm to an outsider that the id exists. A
     * 403 here would answer the question "is there an order with this id?" for anybody who wanted
     * to ask it repeatedly.</p>
     */
    default Optional<E> findByIdInScope(java.util.UUID id) {
        return findOne(scoped(com.stockflow.common.persistence.Specs.eq("id", id)));
    }

    private Specification<E> scoped(Specification<E> spec) {
        Specification<E> scope = DataScopeSpecifications.forCurrentUser(scopePrototype());
        return spec == null ? scope : Specification.where(spec).and(scope);
    }
}
