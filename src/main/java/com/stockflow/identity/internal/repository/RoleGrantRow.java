package com.stockflow.identity.internal.repository;

/**
 * Projection of {@link RoleJpaRepository#findGrantsByCode(String)}: the role's version repeated on
 * every row, plus one granted permission — or null resource and action for a role with none.
 */
public interface RoleGrantRow {

    Long getVersion();

    String getResource();

    String getAction();

    /** The role's {@code data_scope}, repeated on every row. */
    String getDataScope();
}
