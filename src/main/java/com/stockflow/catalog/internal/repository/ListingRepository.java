package com.stockflow.catalog.internal.repository;

import com.stockflow.catalog.internal.domain.Listing;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ListingRepository {
    private final JdbcTemplate jdbc;

    public ListingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Listing map(ResultSet r, int n) throws SQLException {
        return new Listing(
                r.getObject("product_id", UUID.class),
                r.getString("slug"),
                r.getString("seo_title"),
                r.getString("seo_description"),
                r.getLong("revision"),
                r.getLong("projected_revision"),
                r.getBoolean("enabled"),
                r.getBoolean("ever_published"),
                r.getString("published_title"),
                r.getString("published_description"),
                r.getString("published_seo_title"),
                r.getString("published_seo_description"));
    }

    public Optional<Listing> find(UUID id) {
        return jdbc
                .query(
                        "select * from catalog.product_listing where product_id=?",
                        ListingRepository::map,
                        id)
                .stream()
                .findFirst();
    }

    public Optional<UUID> publishedProductForSku(String sku) {
        return jdbc
                .query(
                        "select product_id from catalog.catalog_entry where sku=? and published and"
                                + " product_id is not null",
                        (r, n) -> r.getObject(1, UUID.class),
                        sku)
                .stream()
                .findFirst();
    }

    /** A published SKU's listing as the storefront shows it: the product it belongs to, its slug and title. */
    public record ListedSku(String sku, UUID productId, String slug, String title) {}

    /**
     * The storefront listing of each of these SKUs that is on sale now: a published catalog entry of an enabled,
     * published listing. A SKU that is not on sale has no entry (a reorder skips it).
     */
    public Map<String, ListedSku> listedBySku(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        var rows = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
                """
select e.sku, e.product_id, l.slug, l.published_title
from catalog.catalog_entry e join catalog.product_listing l on l.product_id = e.product_id
where e.sku in (:skus) and e.published and l.enabled and l.published_title is not null
""",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("skus", skus),
                (r, n) -> new ListedSku(r.getString("sku"), r.getObject("product_id", UUID.class),
                        r.getString("slug"), r.getString("published_title")));
        Map<String, ListedSku> bySku = new LinkedHashMap<>();
        rows.forEach(row -> bySku.putIfAbsent(row.sku(), row));
        return Map.copyOf(bySku);
    }

    public Optional<Listing> bySlug(String slug) {
        return jdbc
                .query(
                        "select * from catalog.product_listing where slug=? and enabled and"
                                + " published_title is not null",
                        ListingRepository::map,
                        slug)
                .stream()
                .findFirst();
    }

    public void save(Listing l) {
        // Pre-check improves feedback; the unique constraint still arbitrates concurrent products.
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from catalog.product_listing where slug=? and"
                                + " product_id<>?)",
                        Boolean.class,
                        l.slug(),
                        l.productId()))) throw new BusinessException(ErrorCode.CATALOG_SLUG_EXISTS);
        try {
            jdbc.update(
                    """
insert into catalog.product_listing(product_id, slug, seo_title, seo_description, revision)
values (?,?,?,?,?) on conflict(product_id) do update set slug=excluded.slug,
seo_title=excluded.seo_title, seo_description=excluded.seo_description, revision=excluded.revision
""",
                    l.productId(),
                    l.slug(),
                    l.seoTitle(),
                    l.seoDescription(),
                    l.revision());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CATALOG_SLUG_EXISTS);
        }
    }

    public void enable(UUID id, boolean enabled) {
        jdbc.update(
                """
                update catalog.product_listing set enabled=?, ever_published=ever_published or ?
                where product_id=?
                """,
                enabled,
                enabled,
                id);
        if (!enabled)
            jdbc.update("update catalog.catalog_entry set published=false where product_id=?", id);
    }

    public void sourceRevision(UUID id, long version) {
        jdbc.update(
                "update catalog.product_listing set revision=? where product_id=?", version, id);
    }

    public void hideEntries(UUID id) {
        jdbc.update("update catalog.catalog_entry set published=false where product_id=?", id);
    }

    public Set<String> projectedSkus(UUID id, long revision) {
        return Set.copyOf(jdbc.query(
                "select sku from catalog.catalog_entry where product_id=? and published and source_revision=?",
                (r, n) -> r.getString(1), id, revision));
    }

    /** One published SKU's storefront entry. No price: the storefront shows none (kltn-docs 13 BR-04). */
    public void projectSku(
            UUID productId,
            UUID skuId,
            String sku,
            String title,
            String description,
            String seoTitle,
            String seoDescription,
            long revision,
            Instant now) {
        int changed = jdbc.update(
                """
insert into catalog.catalog_entry(id,sku,title,slug,description,published,
    seo_title,seo_description,version,created_at,product_id,source_revision)
values (?,?,?,?,?,true,?,?,0,?,?,?)
on conflict(sku) do update set title=excluded.title,description=excluded.description,
    published=true,
    seo_title=excluded.seo_title,seo_description=excluded.seo_description,
    product_id=excluded.product_id,source_revision=excluded.source_revision,
    version=catalog.catalog_entry.version+1,last_modified_at=excluded.created_at
where (catalog.catalog_entry.product_id is null or catalog.catalog_entry.product_id=excluded.product_id)
    and (catalog.catalog_entry.source_revision is null or catalog.catalog_entry.source_revision<=excluded.source_revision)
""",
                Identifiers.newId(),
                sku,
                title,
                "sku-" + skuId,
                description,
                seoTitle,
                seoDescription,
                Timestamp.from(now),
                productId,
                revision);
        if (changed != 1) throw new BusinessException(ErrorCode.CONFLICT);
    }

    public void projected(UUID id, String title, String description) {
        jdbc.update(
                """
update catalog.product_listing set projected_revision=revision,published_title=?,
published_description=?,published_seo_title=seo_title,published_seo_description=seo_description
where product_id=?
""",
                title,
                description,
                id);
    }

    public void acknowledgeHidden(UUID id) {
        jdbc.update(
                "update catalog.product_listing set projected_revision=revision where product_id=?",
                id);
    }

    public List<UUID> dirtyIds() {
        return jdbc.query(
                """
select product_id from catalog.product_listing where revision<>projected_revision
    or (enabled and published_title is null)
order by last_attempt_at nulls first,product_id limit 50
""",
                (r, n) -> r.getObject(1, UUID.class));
    }

    public void attempted(UUID id, Instant now) {
        jdbc.update(
                "update catalog.product_listing set last_attempt_at=? where product_id=?",
                Timestamp.from(now),
                id);
    }
}
