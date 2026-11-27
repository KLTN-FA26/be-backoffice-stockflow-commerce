package com.stockflow.product.internal.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.product.internal.domain.ImageRendition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The product's own image: the {@code image_*} columns of {@code product.products} (V20270106000100, ADR-0012).
 *
 * <p>Plain SQL on purpose, like the SEO columns of {@link ProductCommerceRepository}: the columns are not mapped on
 * {@code ProductJpaEntity}, so an edit of the product's details can never write a stale image back, and changing the
 * image does not move the product's {@code version} - an image is not part of the details an edit quotes the version
 * of, any more than a variant's images are. Every writer holds the product row lock
 * ({@code ProductVariantService.requireChangeable}) first.</p>
 */
@Repository
public class ProductImageRepository {

    private static final TypeReference<List<ImageRendition>> RENDITIONS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ProductImageRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A product's image: the private original, its SHA-256, its display renditions and its alt text. */
    public record ProductImage(StoredFile original, String checksum, List<ImageRendition> renditions, String altText) {
        public ProductImage {
            renditions = List.copyOf(renditions);
        }
    }

    /** The product's image, or empty when it has none (an unknown product has none either). */
    public Optional<ProductImage> find(UUID productId) {
        return jdbc.query("""
                        select image_key, image_original_name, image_content_type, image_size_bytes, image_stored_at,
                               image_checksum, image_renditions::text, image_alt_text
                        from product.products where id = ? and image_key is not null""",
                (r, n) -> new ProductImage(
                        new StoredFile(r.getString(1), r.getString(2), r.getString(3), r.getLong(4),
                                r.getTimestamp(5).toInstant()),
                        r.getString(6), renditions(r.getString(7)), r.getString(8)),
                productId).stream().findFirst();
    }

    /** Puts {@code image} on the product in place of any it had. */
    public void set(UUID productId, ProductImage image) {
        var original = image.original();
        jdbc.update("""
                        update product.products set image_key = ?, image_original_name = ?, image_content_type = ?,
                               image_size_bytes = ?, image_stored_at = ?, image_checksum = ?,
                               image_renditions = ?::jsonb, image_alt_text = ?
                        where id = ?""",
                original.key(), original.originalName(), original.contentType(), original.sizeBytes(),
                Timestamp.from(original.storedAt()), image.checksum(), write(image.renditions()), image.altText(),
                productId);
    }

    /** The product has no image any more. */
    public void clear(UUID productId) {
        jdbc.update("""
                        update product.products set image_key = null, image_original_name = null,
                               image_content_type = null, image_size_bytes = null, image_stored_at = null,
                               image_checksum = null, image_renditions = '[]'::jsonb, image_alt_text = null
                        where id = ?""",
                productId);
    }

    /**
     * The largest display rendition's key of each product's image, for the storefront cards, in one query whatever
     * the number of products. A product with no image is not a key.
     */
    public Map<UUID, String> leadRenditionKeys(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        var keys = new HashMap<UUID, String>();
        jdbc.query("""
                        select p.id,
                               (select rendition->'file'->>'key' from jsonb_array_elements(p.image_renditions) rendition
                                order by (rendition->>'edge')::integer desc limit 1)
                        from product.products p
                        where p.id in (%s) and p.image_key is not null""".formatted(placeholders),
                r -> {
                    keys.put(r.getObject(1, UUID.class), r.getString(2));
                },
                productIds.toArray());
        return keys;
    }

    private List<ImageRendition> renditions(String text) {
        try {
            return json.readValue(text, RENDITIONS);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unreadable product.products.image_renditions", ex);
        }
    }

    private String write(List<ImageRendition> renditions) {
        try {
            return json.writeValueAsString(renditions);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not write the image renditions", ex);
        }
    }
}
