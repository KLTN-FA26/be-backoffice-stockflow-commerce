package com.stockflow.common.security;

import com.stockflow.common.persistence.Specs;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds the row filter that {@link DataScope} describes.
 *
 * <p>One place, one implementation. The alternative — each query service writing its own
 * {@code if (scope == OWN) ...} — guarantees that the tenth one written differs from the first
 * nine, and the difference will be discovered by an auditor rather than a test.</p>
 *
 * <h2>The refusal rule</h2>
 *
 * <p>When a scope cannot be applied — the user has WAREHOUSE scope but the entity has no warehouse
 * attribute, or their warehouse list is empty — this returns {@link Specs#none()}, matching nothing,
 * and in the clearly-broken cases throws {@link ScopeViolationException}.</p>
 *
 * <p>It never falls back to "return everything". That direction of failure is the only one that is
 * safe: a user seeing an empty list files a support ticket, and somebody fixes their warehouse
 * assignment that afternoon. A user seeing every row files nothing, because the screen looks
 * exactly as they expect it to — and nobody finds out until an audit, if ever. The asymmetry
 * between those two outcomes is why the default here is deny.</p>
 */
public final class DataScopeSpecifications {

    private DataScopeSpecifications() {
    }

    /**
     * The filter for the scope currently in force.
     *
     * @param prototype an instance of the entity, used only to read its attribute names
     * @throws ScopeViolationException when no scope has been established for this thread, which
     *                                 means a scoped repository was called outside a guarded
     *                                 request without saying so explicitly
     */
    public static <E extends ScopedEntity> Specification<E> forCurrentUser(ScopedEntity prototype) {
        DataScopeContext.Scope scope = DataScopeContext.current().orElseThrow(() ->
                new ScopeViolationException(
                        "No data scope in force. A scoped query ran outside a guarded request - "
                        + "either annotate the endpoint with @RequiresPermission(scope = ...), or, "
                        + "for a background job that legitimately reads everything, call the "
                        + "repository's *Unscoped method."));
        return forScope(prototype, scope.effective(), scope.user());
    }

    /** The filter for an explicitly supplied scope. Exposed for tests and for background jobs. */
    public static <E extends ScopedEntity> Specification<E> forScope(
            ScopedEntity prototype, DataScope scope, CurrentUser user) {

        return switch (scope) {
            case ALL -> Specs.all();

            case WAREHOUSE -> {
                String attribute = require(prototype.warehouseAttribute(), prototype, scope);
                // An empty warehouse list is not "all warehouses" - it is a user whose assignment
                // was never made. Specs.in() returns none() for an empty collection, which is the
                // behaviour we want, but say it here so the intent survives a refactor of Specs.
                if (user.warehouseCodes().isEmpty()) {
                    yield Specs.none();
                }
                yield Specs.in(attribute, user.warehouseCodes());
            }

            case TEAM -> {
                String attribute = require(prototype.teamAttribute(), prototype, scope);
                // Team membership is not on CurrentUser yet (OQ-11). Denying is the honest
                // placeholder: it fails visibly the first time somebody grants TEAM scope, instead
                // of behaving like ALL and being discovered much later.
                yield Specs.none();
            }

            case OWN -> {
                String attribute = require(prototype.ownerAttribute(), prototype, scope);
                // Specs.eq treats a null value as "no filter" and returns all() - the right
                // behaviour for an optional search parameter, and catastrophic here. A CurrentUser
                // with a null userId (a system actor assembled by a background job, say) would get
                // a specification that matches every row while the calling code reads as though
                // OWN scope had been applied. Deny-by-default has to be enforced at this call site,
                // because the helper cannot know which of its two callers it is serving.
                if (user.userId() == null) {
                    throw new ScopeViolationException(
                            "OWN scope requires a user id, and the current principal has none. A "
                            + "background job that must read across owners should call the "
                            + "repository's *Unscoped method deliberately.");
                }
                yield Specs.eq(attribute, user.userId());
            }
        };
    }

    /**
     * @throws ScopeViolationException if the entity has no attribute for this scope level
     *
     * <p>Throwing rather than returning {@link Specs#none()} because this is a <b>programming</b>
     * error, not an authorisation outcome: somebody granted WAREHOUSE scope on an endpoint whose
     * entity has no warehouse column. Silently returning an empty list would send the developer
     * hunting through data for a bug that is in the annotation.</p>
     */
    private static String require(String attribute, ScopedEntity prototype, DataScope scope) {
        if (attribute == null) {
            // Parentheses matter: method invocation binds tighter than +, so without them
            // .formatted would apply to the last literal alone - one %s, three arguments, the
            // extras silently dropped and the first two placeholders reaching the operator raw.
            throw new ScopeViolationException(
                    ("%s declares no attribute for %s scope. Either map one in ScopedEntity, or do "
                            + "not grant %s scope on endpoints returning this type.")
                            .formatted(prototype.getClass().getSimpleName(), scope, scope));
        }
        return attribute;
    }
}
