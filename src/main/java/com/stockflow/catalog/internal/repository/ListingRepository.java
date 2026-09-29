package com.stockflow.catalog.internal.repository;

import com.stockflow.catalog.internal.domain.Listing;
import com.stockflow.catalog.internal.domain.SellingPrice;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ListingRepository {
    private final JdbcTemplate jdbc;
    public ListingRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static Listing map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new Listing(r.getObject("product_id", UUID.class), r.getString("slug"), r.getString("seo_title"),
                r.getString("seo_description"), r.getLong("revision"), r.getLong("projected_revision"),
                r.getBoolean("enabled"), r.getBoolean("ever_published"), r.getString("published_title"),
                r.getString("published_description"), r.getString("published_seo_title"), r.getString("published_seo_description"));
    }
    public Optional<Listing> find(UUID id) {
        return jdbc.query("select * from catalog.product_listing where product_id=?", ListingRepository::map, id).stream().findFirst();
    }
    public Optional<UUID> publishedProductForSku(String sku) {
        return jdbc.query("select product_id from catalog.catalog_entry where sku=? and published and product_id is not null",
                (r,n) -> r.getObject(1, UUID.class), sku).stream().findFirst();
    }
    public Optional<Listing> bySlug(String slug) {
        return jdbc.query("select * from catalog.product_listing where slug=? and enabled and published_title is not null",
                ListingRepository::map, slug).stream().findFirst();
    }
    public void save(Listing l) {
        // Pre-check improves feedback; the unique constraint still arbitrates concurrent products.
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from catalog.product_listing where slug=? and product_id<>?)",
                Boolean.class, l.slug(), l.productId()))) throw new BusinessException(ErrorCode.CATALOG_SLUG_EXISTS);
        try {
            jdbc.update("""
                    insert into catalog.product_listing(product_id, slug, seo_title, seo_description, revision)
                    values (?,?,?,?,?) on conflict(product_id) do update set slug=excluded.slug,
                    seo_title=excluded.seo_title, seo_description=excluded.seo_description, revision=excluded.revision
                    """, l.productId(), l.slug(), l.seoTitle(), l.seoDescription(), l.revision());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CATALOG_SLUG_EXISTS);
        }
    }
    public void enable(UUID id, boolean enabled) {
        jdbc.update("""
                update catalog.product_listing set enabled=?, ever_published=ever_published or ?,
                revision=revision+1 where product_id=?
                """, enabled, enabled, id);
        if (!enabled) jdbc.update("update catalog.catalog_entry set published=false where product_id=?", id);
    }
    public Optional<SellingPrice> price(String sku) {
        var rows = jdbc.query("""
                select price,currency,priority from catalog.pricing_rule where active and segment_id is null
                and (sku=? or sku is null) order by priority desc, id limit 2
                """, (r,n) -> new PriceRow(r.getBigDecimal(1), r.getString(2), r.getInt(3)), sku);
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size()>1 && rows.get(0).priority()==rows.get(1).priority())
            throw new BusinessException(ErrorCode.CATALOG_PRICE_AMBIGUOUS);
        return Optional.of(new SellingPrice(rows.getFirst().amount(), rows.getFirst().currency()));
    }
    private record PriceRow(java.math.BigDecimal amount,String currency,int priority) {}
    public Optional<SellingPrice> basePrice(String sku) {
        return jdbc.query("select price,currency from catalog.pricing_rule where name=? and active",
                (r,n) -> new SellingPrice(r.getBigDecimal(1),r.getString(2)),"BASE:"+sku).stream().findFirst();
    }
    public void basePrice(String sku, SellingPrice p, String actor, Instant now) {
        jdbc.update("""
                insert into catalog.pricing_rule(id,name,sku,price,currency,priority,active,version,created_at,created_by)
                values (?,?,?,?,?,0,true,0,?,?)
                on conflict (name) where name like 'BASE:%' do update set price=excluded.price,
                    currency=excluded.currency, active=true, version=catalog.pricing_rule.version+1,
                    last_modified_at=excluded.created_at,last_modified_by=excluded.created_by
                """, Identifiers.newId(), "BASE:"+sku, sku, p.amount(), p.currency(), java.sql.Timestamp.from(now), actor);
    }
    public void dirty(UUID id) { jdbc.update("update catalog.product_listing set revision=revision+1 where product_id=?", id); }
    public void hideEntries(UUID id) { jdbc.update("update catalog.catalog_entry set published=false where product_id=?", id); }
    public void projectSku(UUID productId, UUID skuId, String sku, String title, String description,
                           String seoTitle, String seoDescription, SellingPrice price, long revision, Instant now) {
        jdbc.update("""
                insert into catalog.catalog_entry(id,sku,title,slug,description,price,currency,published,
                    seo_title,seo_description,version,created_at,product_id,source_revision)
                values (?,?,?,?,?,?,?,true,?,?,0,?,?,?)
                on conflict(sku) do update set title=excluded.title,description=excluded.description,
                    price=excluded.price,currency=excluded.currency,published=true,
                    seo_title=excluded.seo_title,seo_description=excluded.seo_description,
                    product_id=excluded.product_id,source_revision=excluded.source_revision,
                    version=catalog.catalog_entry.version+1,last_modified_at=excluded.created_at
                """, Identifiers.newId(), sku, title, "sku-"+skuId, description, price.amount(), price.currency(),
                seoTitle,seoDescription,java.sql.Timestamp.from(now),productId,revision);
    }
    public void projected(UUID id, String title, String description) {
        jdbc.update("""
                update catalog.product_listing set projected_revision=revision,published_title=?,
                published_description=?,published_seo_title=seo_title,published_seo_description=seo_description
                where product_id=?
                """,title,description,id);
    }
    public void acknowledgeHidden(UUID id) {
        jdbc.update("update catalog.product_listing set projected_revision=revision where product_id=?", id);
    }
    public List<Listing> page(long offset,int size) {
        return jdbc.query("""
                select * from catalog.product_listing where enabled and published_title is not null
                order by product_id limit ? offset ?
                """, ListingRepository::map, size, offset);
    }
    public long count() {
        return jdbc.queryForObject("select count(*) from catalog.product_listing where enabled and published_title is not null", Long.class);
    }
    public List<UUID> dirtyIds() {
        return jdbc.query("""
                select product_id from catalog.product_listing where revision<>projected_revision or enabled
                order by last_attempt_at nulls first,product_id limit 50
                """, (r,n)->r.getObject(1,UUID.class));
    }
    public void attempted(UUID id,Instant now) {
        jdbc.update("update catalog.product_listing set last_attempt_at=? where product_id=?", java.sql.Timestamp.from(now), id);
    }
}
