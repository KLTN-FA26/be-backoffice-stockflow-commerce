package com.stockflow.common.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.UUID;

/**
 * The Spring Data interface a module's repositories extend.
 *
 * <p>It exists so that every repository in the system starts from the same two capabilities —
 * CRUD and {@code Specification} queries — rather than each author picking a different combination.
 * {@code JpaSpecificationExecutor} in particular is what {@link com.stockflow.common.persistence.Specs}
 * and the data-scope filters are built on; a repository without it cannot apply a scope.</p>
 *
 * <p>{@code @NoRepositoryBean} keeps Spring Data from trying to instantiate this interface itself.
 * Without it, startup fails with a message about an unresolvable entity type that gives no hint
 * about the real cause.</p>
 *
 * <p>Repositories stay package-private inside {@code internal.repository}. Other modules reach
 * data through the owning module's service, never through its repository.</p>
 */
@NoRepositoryBean
public interface BaseJpaRepository<E extends BaseEntity>
        extends JpaRepository<E, UUID>, JpaSpecificationExecutor<E> {
}
