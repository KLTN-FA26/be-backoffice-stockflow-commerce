package com.stockflow.identity.internal.repository;

/** Projection of {@link RoleJpaRepository#findAllVersions()}. */
public interface RoleVersionRow {

    String getCode();

    Long getVersion();
}
