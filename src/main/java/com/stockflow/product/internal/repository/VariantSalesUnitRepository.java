package com.stockflow.product.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code product.variant_sales_units} (V20261016001300): the units a variant is sold in, each with its
 * price. Native SQL on one small table, like {@link ProductChangeLogRepository}.
 */
@Repository
public class VariantSalesUnitRepository {

    /** One stored sales unit; the unit itself (code, factor) is the inventory item's. */
    public record Row(UUID id, UUID variantId, UUID uomConversionId, BigDecimal price, long version) {
    }

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getObject("id", UUID.class),
            rs.getObject("variant_id", UUID.class), rs.getObject("uom_conversion_id", UUID.class),
            rs.getBigDecimal("price"), rs.getLong("version"));

    private final JdbcTemplate jdbc;

    public VariantSalesUnitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(UUID variantId) {
        Long count = jdbc.queryForObject("select count(*) from product.variant_sales_units where variant_id = ?",
                Long.class, variantId);
        return count == null ? 0 : count;
    }

    /** In the order they were added. */
    public List<Row> page(UUID variantId, int limit, long offset) {
        return jdbc.query("""
                select * from product.variant_sales_units where variant_id = ?
                order by created_at, id limit ? offset ?""", ROW, variantId, limit, offset);
    }

    public Optional<Row> find(UUID variantId, UUID id) {
        return jdbc.query("select * from product.variant_sales_units where variant_id = ? and id = ?", ROW,
                variantId, id).stream().findFirst();
    }

    public boolean sells(UUID variantId, UUID uomConversionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from product.variant_sales_units where variant_id = ? and uom_conversion_id = ?)""",
                Boolean.class, variantId, uomConversionId));
    }

    public void insert(UUID id, UUID variantId, UUID uomConversionId, BigDecimal price, String actor) {
        jdbc.update("""
                insert into product.variant_sales_units (id, variant_id, uom_conversion_id, price, created_by)
                values (?, ?, ?, ?, ?)""", id, variantId, uomConversionId, price, actor);
    }

    /** False when the version moved on. */
    public boolean updatePrice(UUID id, long version, BigDecimal price, String actor) {
        return jdbc.update("""
                update product.variant_sales_units set price = ?, version = version + 1, last_modified_at = now(),
                    last_modified_by = ?
                where id = ? and version = ?""", price, actor, id, version) == 1;
    }

    public boolean delete(UUID id, long version) {
        return jdbc.update("delete from product.variant_sales_units where id = ? and version = ?", id, version) == 1;
    }
}
