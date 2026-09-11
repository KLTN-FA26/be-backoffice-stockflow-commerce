package com.stockflow.common.security;

import java.util.UUID;

/**
 * Marks a JPA entity whose rows belong to somebody — and therefore must be filtered by
 * {@link DataScope} before they are returned.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The permission matrix answers "may this user list orders?". It cannot answer "<i>which</i>
 * orders?", because that is a property of the rows, not of the endpoint. Without a second
 * mechanism, a customer who is legitimately allowed to call {@code GET /api/v1/orders} sees
 * everyone's orders, and a warehouse clerk in Ho Chi Minh City sees Hanoi's stock. Both go through
 * endpoints they are entitled to call, so no permission check fires and nothing looks wrong.</p>
 *
 * <p>Implementing this interface is how an entity declares "I am scoped". Two things follow from
 * it, and together they are what make the scope hard to forget:</p>
 * <ul>
 *   <li>{@link DataScopeSpecifications} can build the row filter for the current user;</li>
 *   <li>{@code ArchitectureTest} requires the entity's repository to extend
 *       {@link ScopedJpaRepository}, so an unscoped {@code findAll()} is a build failure rather
 *       than a data leak.</li>
 * </ul>
 *
 * <h2>What to return</h2>
 *
 * <p>The two methods name the <b>JPA attribute paths</b> the filter should compare against, not
 * the values. They are static per entity and normally return constants:</p>
 *
 * <pre>
 * &#64;Entity
 * class OrderJpaEntity extends BaseEntity implements ScopedEntity {
 *     &#64;Override public String ownerAttribute()     { return "customerId"; }
 *     &#64;Override public String warehouseAttribute() { return null; }  // orders are not per-warehouse
 * }
 * </pre>
 *
 * <p>Returning {@code null} from either method means the entity has no such dimension. That is a
 * real answer, not a gap: an order has an owner but no warehouse, a stock item has a warehouse but
 * no owner. {@link DataScopeSpecifications} refuses the query rather than returning everything
 * when the scope it is asked to apply has no attribute to apply it to — see its javadoc for why
 * that direction is the safe one.</p>
 */
public interface ScopedEntity {

    /**
     * JPA attribute holding the id of the user this row belongs to, or {@code null} if rows of this
     * type have no individual owner.
     */
    String ownerAttribute();

    /**
     * JPA attribute holding the warehouse code this row belongs to, or {@code null} if rows of this
     * type are not warehouse-specific.
     */
    String warehouseAttribute();

    /**
     * JPA attribute holding the team the row belongs to, or {@code null}.
     *
     * <p>A default of {@code null} because {@link DataScope#TEAM} is the least-used level and most
     * entities have nothing to map it to — but it must be answerable, otherwise granting TEAM scope
     * would silently behave like ALL.</p>
     */
    default String teamAttribute() {
        return null;
    }

    /** Convenience for adapters that build the filter from a plain instance. */
    static boolean hasOwner(ScopedEntity entity) {
        return entity.ownerAttribute() != null;
    }

    /** Never used as data - present so the interface documents the value type of the owner column. */
    interface OwnerId {
        UUID value();
    }
}
