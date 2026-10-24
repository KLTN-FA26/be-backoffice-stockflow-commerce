package com.stockflow.catalog.internal.repository;

import com.stockflow.catalog.internal.domain.SearchQuery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The SQL of catalog search (docs 13 §4.2; ADR-0009).
 *
 * <p>Every method returns at most {@code window} candidates and applies visibility <b>in SQL, before
 * anything is cut</b>: a listing is visible only when it is enabled, has a published title and has at
 * least one published catalog entry. Filtering after paging was the defect of the old listing (a short
 * page, a wrong total), and this class must never reintroduce it.</p>
 *
 * <p>No user text is concatenated into SQL. Words are letters and digits only (see
 * {@code SearchQueryNormalizer}); they and the LIKE patterns travel as bind parameters.</p>
 *
 * <p>Methods that use {@code pg_trgm}'s threshold ({@link #similar}) must run inside a transaction:
 * the threshold is set with {@code set_config(..., is_local = true)}, which lasts to the end of the
 * transaction on the same connection.</p>
 */
@Repository
public class CatalogSearchRepository {

    /**
     * One candidate. {@code skus} are the product's published SKU codes; {@code matchedSku} is the
     * one the query matched (exactly or by prefix), else null; {@code textScore} has no stock term.
     */
    public record Candidate(
            UUID productId,
            String slug,
            String title,
            String seoTitle,
            double textScore,
            List<String> skus,
            String matchedSku,
            Instant listedAt) {}

    /** Weights from {@code stockflow.catalog.search}, passed in so this class holds no policy. */
    public record Weights(double textRank, double similarity, double exactSku) {}

    /** Order of an unranked listing. */
    public enum Order { NEWEST, TITLE }

    private static final String FOLDED_TITLE = "catalog.immutable_unaccent(lower(l.published_title))";
    private static final String VISIBLE =
            "l.enabled and l.published_title is not null and e.skus is not null";

    private final JdbcTemplate jdbc;

    public CatalogSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Full-text match (last word as a prefix) or a SKU starting with the input; best text score first. */
    public List<Candidate> match(SearchQuery query, Weights w, int window) {
        return match(query, w, window, null);
    }

    /** {@link #match}, among {@code only} when it is not null (a category being browsed, SCRUM-171). */
    public List<Candidate> match(SearchQuery query, Weights w, int window, Set<UUID> only) {
        var sql = new Sql();
        sql.add("select c.*, (? * c.ts_rank + ? * c.sim + case when c.exact_sku then ? else 0 end) as score from (",
                w.textRank(), w.similarity(), w.exactSku());
        sql.add("select l.product_id, l.slug, l.published_title, l.published_seo_title, e.skus, e.listed_at,"
                + " e.exact_sku, e.matched_sku, ts_rank_cd(l.search_document, tsq.q) as ts_rank,"
                + " public.similarity(" + FOLDED_TITLE + ", ?) as sim from catalog.product_listing l", query.text());
        sql.add("cross join (select ");
        tsquery(sql, query);
        sql.add(" as q) tsq");
        entries(sql, query);
        visible(sql, only);
        sql.add(" and (l.search_document @@ tsq.q");
        skuPrefixSubquery(sql, query);
        sql.add(")) c order by score desc, c.published_title, c.product_id limit ?", window);
        return jdbc.query(sql.text(), CatalogSearchRepository::map, sql.args());
    }

    /**
     * Typo fallback: titles whose accent-folded form is similar to the input (trigram), used when
     * {@link #match} found nothing. Needs a surrounding transaction (see class javadoc).
     */
    public List<Candidate> similar(SearchQuery query, Weights w, double threshold, int window) {
        return similar(query, w, threshold, window, null);
    }

    /** {@link #similar}, among {@code only} when it is not null. */
    public List<Candidate> similar(SearchQuery query, Weights w, double threshold, int window, Set<UUID> only) {
        jdbc.queryForObject("select set_config('pg_trgm.similarity_threshold', ?, true)", String.class,
                Double.toString(threshold));
        var sql = new Sql();
        sql.add("select c.*, (? * c.sim + case when c.exact_sku then ? else 0 end) as score from (",
                w.similarity(), w.exactSku());
        sql.add("select l.product_id, l.slug, l.published_title, l.published_seo_title, e.skus, e.listed_at,"
                + " e.exact_sku, e.matched_sku, 0::float8 as ts_rank,"
                + " public.similarity(" + FOLDED_TITLE + ", ?) as sim from catalog.product_listing l", query.text());
        entries(sql, query);
        visible(sql, only);
        sql.add(" and " + FOLDED_TITLE + " operator(public.%) ?", query.text());
        sql.add(") c order by score desc, c.published_title, c.product_id limit ?", window);
        return jdbc.query(sql.text(), CatalogSearchRepository::map, sql.args());
    }

    /** No search text: every visible listing in the requested order. */
    public List<Candidate> listAll(Order order, int window) {
        return listAll(order, window, null);
    }

    /** {@link #listAll}, among {@code only} when it is not null. */
    public List<Candidate> listAll(Order order, int window, Set<UUID> only) {
        var sql = new Sql();
        sql.add("select l.product_id, l.slug, l.published_title, l.published_seo_title, e.skus, e.listed_at,"
                + " false as exact_sku, null as matched_sku, 0::float8 as ts_rank, 0::float8 as sim,"
                + " 0::float8 as score from catalog.product_listing l");
        entries(sql, SearchQuery.NONE);
        visible(sql, only);
        sql.add(" order by "
                + (order == Order.NEWEST
                        ? "e.listed_at desc nulls last, l.product_id desc"
                        : FOLDED_TITLE + ", l.product_id")
                + " limit ?", window);
        return jdbc.query(sql.text(), CatalogSearchRepository::map, sql.args());
    }

    /** Suggestions: the same match as {@link #match}, cut at {@code limit}. */
    public List<Candidate> suggest(SearchQuery query, Weights w, int limit) {
        return match(query, w, limit);
    }

    // ---- fragments ------------------------------------------------------------------------

    /**
     * {@code where} the listing is visible and, when {@code only} is given, one of those products. One array
     * parameter rather than an IN list, so a large category does not run into the driver's parameter limit; the
     * literal is built from UUIDs only.
     */
    private static void visible(Sql sql, Set<UUID> only) {
        if (only == null) {
            sql.add("where " + VISIBLE);
            return;
        }
        sql.add("where " + VISIBLE + " and l.product_id = any(?::uuid[])",
                only.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}")));
    }

    /** Prefix-matching tsquery: all words, the last one as a prefix ("ly nh" finds "ly nhua"). */
    private static void tsquery(Sql sql, SearchQuery query) {
        if (query.isEmpty()) {
            sql.add("''::tsquery");
        } else if (query.terms().size() == 1) {
            sql.add("to_tsquery('simple', ?)", query.lastTerm() + ":*");
        } else {
            sql.add("(websearch_to_tsquery('simple', ?) && to_tsquery('simple', ?))",
                    query.head(), query.lastTerm() + ":*");
        }
    }

    /** Published entries of a listing: its SKUs, when first listed, and which one the input names. */
    private static void entries(Sql sql, SearchQuery query) {
        var keys = query.skuKeys();
        String in = keys.isEmpty() ? "false" : "lower(x.sku) in (" + "?,".repeat(keys.size() - 1) + "?)";
        String like = keys.isEmpty() ? "false"
                : "(" + String.join(" or ", java.util.Collections.nCopies(keys.size(), "lower(x.sku) like ? escape '\\'")) + ")";
        sql.add("cross join lateral (select array_agg(x.sku order by x.sku) as skus, min(x.created_at) as listed_at,"
                + " coalesce(bool_or(" + in + "), false) as exact_sku,"
                + " min(x.sku) filter (where " + in + " or " + like + ") as matched_sku"
                + " from catalog.catalog_entry x where x.product_id = l.product_id and x.published) e",
                concat(keys, keys, prefixes(keys)));
    }

    /** {@code or product in (...)}: lets the trigram index on lower(sku) serve "starts with". */
    private static void skuPrefixSubquery(Sql sql, SearchQuery query) {
        var keys = query.skuKeys();
        if (keys.isEmpty()) return;
        sql.add(" or l.product_id in (select y.product_id from catalog.catalog_entry y where y.published"
                + " and y.product_id is not null and ("
                + String.join(" or ", java.util.Collections.nCopies(keys.size(), "lower(y.sku) like ? escape '\\'"))
                + "))", prefixes(keys).toArray());
    }

    private static List<String> prefixes(List<String> keys) {
        return keys.stream().map(k -> k.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%").toList();
    }

    private static Object[] concat(List<?>... lists) {
        var all = new ArrayList<Object>();
        for (var l : lists) all.addAll(l);
        return all.toArray();
    }

    private static Candidate map(ResultSet r, int n) throws SQLException {
        var skus = r.getArray("skus");
        var ts = r.getTimestamp("listed_at");
        return new Candidate(
                r.getObject("product_id", UUID.class),
                r.getString("slug"),
                r.getString("published_title"),
                r.getString("published_seo_title"),
                r.getDouble("score"),
                List.of((String[]) skus.getArray()),
                r.getString("matched_sku"),
                ts == null ? null : ts.toInstant());
    }

    /** Statement text and its bind values, appended together so the two cannot drift apart. */
    private static final class Sql {
        private final StringBuilder text = new StringBuilder();
        private final List<Object> args = new ArrayList<>();

        void add(String fragment, Object... values) {
            if (!text.isEmpty()) text.append(' ');
            text.append(fragment);
            args.addAll(List.of(values));
        }

        String text() {
            return text.toString();
        }

        Object[] args() {
            return args.toArray();
        }
    }
}
