package com.stockflow.common.persistence;

import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;

/**
 * Null-tolerant {@link Specification} building blocks for filter endpoints.
 *
 * <h2>The problem they solve</h2>
 *
 * <p>A search screen sends five optional filters and usually fills in one. Written by hand that
 * becomes a chain of {@code if (status != null) spec = spec.and(...)} statements repeated in every
 * query service, and each repetition is a chance to write {@code &&} where {@code ||} belongs, or
 * to leave a filter out of the {@code and} chain entirely — which silently returns <i>more</i> rows
 * than it should. That failure is invisible in a demo with ten rows.</p>
 *
 * <p>Here, every helper returns a specification that matches everything when its argument is
 * absent, so the composition is unconditional:</p>
 *
 * <pre>
 * Specification&lt;OrderJpaEntity&gt; spec = Specification
 *         .where(Specs.&lt;OrderJpaEntity&gt;eq("status", criteria.status()))
 *         .and(Specs.eq("customerId", criteria.customerId()))
 *         .and(Specs.contains("orderNumber", criteria.search()))
 *         .and(Specs.in("warehouseCode", criteria.warehouses()));
 * </pre>
 *
 * <p>No conditionals, so no missing branch.</p>
 */
public final class Specs {

    private Specs() {
    }

    /** Matches every row. The identity element that makes unconditional composition work. */
    public static <E> Specification<E> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** Matches nothing. Useful as the "deny" outcome of a data-scope decision. */
    public static <E> Specification<E> none() {
        return (root, query, cb) -> cb.disjunction();
    }

    /** {@code attribute = value}, or everything when {@code value} is null. */
    public static <E> Specification<E> eq(String attribute, Object value) {
        return value == null ? all() : (root, query, cb) -> cb.equal(root.get(attribute), value);
    }

    public static <E> Specification<E> notEq(String attribute, Object value) {
        return value == null ? all() : (root, query, cb) -> cb.notEqual(root.get(attribute), value);
    }

    public static <E> Specification<E> isNull(String attribute) {
        return (root, query, cb) -> cb.isNull(root.get(attribute));
    }

    public static <E> Specification<E> isNotNull(String attribute) {
        return (root, query, cb) -> cb.isNotNull(root.get(attribute));
    }

    /**
     * Case-insensitive "contains", for a free-text search box.
     *
     * <p>The term is escaped before being wrapped in {@code %...%}: a user typing {@code 50%} into
     * a search field would otherwise turn the {@code %} into a wildcard and match everything, and
     * an {@code _} would match any single character. Neither is a security hole — the value is
     * still a bound parameter, not concatenated SQL — but both are wrong answers.</p>
     *
     * <p>Note the cost: a leading wildcard cannot use a normal B-tree index. On a large table this
     * needs a trigram index ({@code pg_trgm}) or a search projection, not just this helper.</p>
     */
    public static <E> Specification<E> contains(String attribute, String term) {
        if (term == null || term.isBlank()) {
            return all();
        }
        String pattern = "%" + escapeLike(term.trim().toLowerCase()) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get(attribute)), pattern, '\\');
    }

    /** Case-insensitive "starts with" - index-friendly, unlike {@link #contains}. */
    public static <E> Specification<E> startsWith(String attribute, String term) {
        if (term == null || term.isBlank()) {
            return all();
        }
        String pattern = escapeLike(term.trim().toLowerCase()) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get(attribute)), pattern, '\\');
    }

    /**
     * {@code attribute IN (values)}.
     *
     * <p>An empty collection returns {@link #none()}, not {@link #all()}. This is the one place
     * where the "absent means match everything" rule must not apply: "show me orders in these
     * warehouses" with an empty warehouse list means the user has access to none, and answering it
     * with every row would be a data leak. A null collection means the filter was not supplied at
     * all and does match everything.</p>
     */
    public static <E> Specification<E> in(String attribute, Collection<?> values) {
        if (values == null) {
            return all();
        }
        if (values.isEmpty()) {
            return none();
        }
        return (root, query, cb) -> {
            jakarta.persistence.criteria.CriteriaBuilder.In<Object> clause = cb.in(root.get(attribute));
            values.forEach(clause::value);
            return clause;
        };
    }

    /**
     * Escapes the LIKE metacharacters so a user's literal {@code %} or {@code _} stays literal.
     * Paired with the {@code '\'} escape character passed to {@code cb.like}.
     */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
