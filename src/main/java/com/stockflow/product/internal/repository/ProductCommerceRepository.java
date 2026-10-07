package com.stockflow.product.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.ProductEcommerce;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.PublicationProduct;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Canonical PIM access; no legacy product/variant/sku tables and no cross-schema joins. */
@Repository
public class ProductCommerceRepository {
    private final JdbcTemplate jdbc;

    public ProductCommerceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PublicationProduct read(UUID id, boolean lock) {
        var rows =
                jdbc.query(
                        "select * from product.products where id=?" + (lock ? " for update" : ""),
                        (r, n) ->
                                new PublicationProduct(
                                        id,
                                        r.getString("name"),
                                        r.getString("description"),
                                        null,
                                        status(r.getString("status")),
                                        List.of()),
                        id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        var p = rows.getFirst();
        var categories =
                jdbc.query(
                        """
select pc.category_id from product.product_categories pc
join product.categories c on c.id=pc.category_id where pc.product_id=? and c.is_active
order by pc.is_primary desc, pc.id limit 1
""",
                        (r, n) -> r.getObject(1, UUID.class),
                        id);
        var skus =
                jdbc.query(
                        "select id,sku from product.variants where product_id=? and status='ACTIVE'"
                                + " order by id",
                        (r, n) ->
                                new PublicationProduct.PublicationSku(
                                        r.getObject(1, UUID.class), r.getString(2)),
                        id);
        return new PublicationProduct(
                id,
                p.name(),
                p.description(),
                categories.isEmpty() ? null : categories.getFirst(),
                p.status(),
                skus);
    }

    private static ProductStatus status(String status) {
        // The expanded schema also carries ACTIVE/INACTIVE. Neither is storefront publication.
        return switch (status) {
            case "ACTIVE" -> ProductStatus.APPROVED;
            case "INACTIVE" -> ProductStatus.DRAFT;
            default -> ProductStatus.valueOf(status);
        };
    }

    public Set<UUID> published(Set<UUID> ids) {
        if (ids.isEmpty()) return Set.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return Set.copyOf(
                jdbc.query(
                        "select id from product.products where status='PUBLISHED' and id in ("
                                + placeholders
                                + ")",
                        (r, n) -> r.getObject(1, UUID.class),
                        ids.toArray()));
    }

    public PublicationProduct.PublicationSku inventorySku(UUID productId, UUID variantId) {
        return jdbc
                .query(
                        "select id,sku from product.variants where product_id=? and id=? and"
                                + " status<>'OBSOLETE'",
                        (r, n) ->
                                new PublicationProduct.PublicationSku(
                                        r.getObject(1, UUID.class), r.getString(2)),
                        productId,
                        variantId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    public ProductEcommerce ecommerce(UUID id) {
        return jdbc
                .query(
                        "select slug,seo_title,seo_description,ever_published,version from"
                                + " product.products where id=?",
                        (r, n) ->
                                new ProductEcommerce(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getBoolean(4),
                                        r.getLong(5)),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    public void advanceCommerceRevision(UUID id, String actor) {
        jdbc.update(
                "update product.products set"
                        + " version=version+1,last_modified_at=now(),last_modified_by=? where id=?",
                actor,
                id);
    }

    public void edit(UUID id, String slug, String title, String description, String actor) {
        try {
            jdbc.update(
                    """
update product.products set slug=?,seo_title=?,seo_description=?,version=version+1,
last_modified_at=now(),last_modified_by=? where id=?
""",
                    slug,
                    title,
                    description,
                    actor,
                    id);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CATALOG_SLUG_EXISTS);
        }
    }

    public record PublishedMedia(
            UUID id,
            UUID variantId,
            String sku,
            String url,
            String renditionKey,
            String altText,
            int sortOrder,
            boolean primary,
            String createdBy,
            UUID publishedBy) {}

    public List<PublishedMedia> publishedMedia(UUID id) {
        return jdbc.query(
                """
select m.id,m.variant_id,v.sku,m.url,
                    (select rendition->'file'->>'key' from jsonb_array_elements(m.renditions) rendition
                     order by (rendition->>'edge')::integer desc limit 1),
                    m.alt_text,m.sort_order,m.is_primary,m.created_by,m.published_by
from product.media m join product.variants v on v.id=m.variant_id
where v.product_id=? and v.status='ACTIVE' and m.kind='IMAGE' and m.is_published
and m.published_by is not null and m.published_at is not null
and m.created_by is not null
                and (m.storage_key is null or jsonb_array_length(m.renditions)>0)
order by v.position,v.id,m.sort_order,m.id
""",
                (r, n) ->
                        new PublishedMedia(
                                r.getObject(1, UUID.class),
                                r.getObject(2, UUID.class),
                                r.getString(3),
                                r.getString(4),
                                r.getString(5),
                                r.getString(6),
                                r.getInt(7),
                                r.getBoolean(8),
                                r.getString(9),
                                r.getObject(10, UUID.class)),
                id);
    }

    public void publication(UUID id, boolean published, String actor) {
        jdbc.update(
                """
update product.products set status=?,published_at=case when ? then coalesce(published_at,now()) else published_at end,
ever_published=ever_published or ?,version=version+1,last_modified_at=now(),last_modified_by=? where id=?
""",
                published ? "PUBLISHED" : "APPROVED",
                published,
                published,
                actor,
                id);
    }
}
