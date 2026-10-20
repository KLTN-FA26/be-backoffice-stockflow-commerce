package com.stockflow.catalog.internal.repository;

import com.stockflow.catalog.internal.domain.SegmentPrice;
import com.stockflow.catalog.internal.domain.SegmentPriceRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SegmentPriceRepository} on {@code catalog.pricing_rule}, in SQL like {@link CustomerPriceRepositoryAdapter}:
 * the rows {@link ListingRepository#segmentPrice} reads, one per group and SKU, the version moving through a
 * compare-and-set so two editors cannot both win.
 */
@Repository
class SegmentPriceRepositoryAdapter implements SegmentPriceRepository {

    private static final String COLUMNS = "select id, segment_id, sku, price, version from catalog.pricing_rule ";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final AuditorAware<String> auditor;
    private final Clock clock;

    SegmentPriceRepositoryAdapter(JdbcTemplate jdbc, AuditorAware<String> auditor, Clock clock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.auditor = auditor;
        this.clock = clock;
    }

    @Override
    public Optional<SegmentPrice> find(UUID segmentId, String sku) {
        return jdbc.query(COLUMNS + "where segment_id = ? and sku = ?", (r, n) -> row(r), segmentId, sku)
                .stream().findFirst();
    }

    @Override
    public Optional<SegmentPrice> findForUpdate(UUID segmentId, String sku) {
        return jdbc.query(COLUMNS + "where segment_id = ? and sku = ? for update", (r, n) -> row(r), segmentId, sku)
                .stream().findFirst();
    }

    @Override
    public List<SegmentPrice> ofSegment(UUID segmentId, Collection<String> skus) {
        var params = new MapSqlParameterSource("segmentId", segmentId);
        String filter = "";
        if (!skus.isEmpty()) {
            params.addValue("skus", skus);
            filter = " and sku in (:skus)";
        }
        return named.query(COLUMNS + "where segment_id = :segmentId and sku is not null" + filter + " order by sku",
                params, (r, n) -> row(r));
    }

    @Override
    public SegmentPrice save(SegmentPrice price) {
        Timestamp now = Timestamp.from(clock.instant());
        String actor = auditor.getCurrentAuditor().orElse(null);
        int updated = jdbc.update("""
                update catalog.pricing_rule set price = ?, version = version + 1, last_modified_at = ?, last_modified_by = ?
                where id = ? and version = ?""", price.price(), now, actor, price.id(), price.version());
        if (updated == 0) {
            if (!jdbc.queryForList("select 1 from catalog.pricing_rule where id = ?", price.id()).isEmpty()) {
                throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                        "The group price of %s changed meanwhile".formatted(price.sku()));
            }
            jdbc.update("""
                    insert into catalog.pricing_rule(id, name, sku, segment_id, price, currency, priority, active, version,
                                                     created_at, created_by)
                    values (?, ?, ?, ?, ?, 'VND', 0, true, 0, ?, ?)""",
                    price.id(), "SEGMENT:" + price.segmentId() + ":" + price.sku(), price.sku(), price.segmentId(),
                    price.price(), now, actor);
        }
        return jdbc.query(COLUMNS + "where id = ?", (r, n) -> row(r), price.id()).getFirst();
    }

    @Override
    public void delete(UUID priceId) {
        jdbc.update("delete from catalog.pricing_rule where id = ?", priceId);
    }

    private static SegmentPrice row(ResultSet r) throws SQLException {
        return new SegmentPrice(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3),
                r.getBigDecimal(4), r.getLong(5));
    }
}
