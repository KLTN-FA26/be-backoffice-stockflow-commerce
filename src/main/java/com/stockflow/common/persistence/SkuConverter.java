package com.stockflow.common.persistence;

import com.stockflow.common.domain.Sku;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a {@link Sku} as its code, so entities can hold the value object rather than a bare
 * {@code String} that nothing validates.
 *
 * <p><b>{@code autoApply = false} is deliberate.</b> An auto-applying converter attaches itself to
 * every {@code Sku}-typed attribute in the persistence unit, including ones in modules that have
 * not opted in, and it does so invisibly — nothing in the entity says a conversion is happening.
 * Naming it explicitly costs one annotation and makes the mapping readable:</p>
 *
 * <pre>
 * &#64;Convert(converter = SkuConverter.class)
 * &#64;Column(name = "sku", nullable = false, length = 64)
 * private Sku sku;
 * </pre>
 *
 * <p>Note what this buys beyond tidiness: {@code Sku}'s compact constructor normalises and
 * validates, so a malformed code cannot survive a read from the database either. A row corrupted by
 * a manual {@code UPDATE} fails loudly on load instead of flowing into a picking list.</p>
 */
@Converter(autoApply = false)
public class SkuConverter implements AttributeConverter<Sku, String> {

    @Override
    public String convertToDatabaseColumn(Sku attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public Sku convertToEntityAttribute(String dbData) {
        return dbData == null ? null : new Sku(dbData);
    }
}
