package com.stockflow.catalog.internal.repository;

import com.stockflow.catalog.internal.domain.StandardPrice;
import com.stockflow.catalog.internal.domain.StandardPriceRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link StandardPriceRepository} in SQL, as {@link CustomerPriceRepositoryAdapter}: a price and its breaks are replaced
 * as a whole, and the header's version moves with every save through a compare-and-set ({@code where version = ?}), so
 * two editors cannot both win.
 */
@Repository
class StandardPriceRepositoryAdapter implements StandardPriceRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final AuditorAware<String> auditor;
    private final Clock clock;

    StandardPriceRepositoryAdapter(JdbcTemplate jdbc, AuditorAware<String> auditor, Clock clock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.auditor = auditor;
        this.clock = clock;
    }

    private record Header(UUID id, String sku, String note, long version) {
    }

    @Override
    public Optional<StandardPrice> find(String sku) {
        return header("where sku = ?", sku).map(this::withTiers);
    }

    @Override
    public Optional<StandardPrice> findForUpdate(String sku) {
        return header("where sku = ? for update", sku).map(this::withTiers);
    }

    @Override
    public List<StandardPrice> of(Collection<String> skus) {
        var params = new MapSqlParameterSource();
        String filter = "";
        if (!skus.isEmpty()) {
            params.addValue("skus", skus);
            filter = " where sku in (:skus)";
        }
        List<Header> headers = named.query("select id, sku, note, version from catalog.standard_price" + filter
                + " order by sku", params, (r, n) -> header(r));
        if (headers.isEmpty()) return List.of();
        Map<UUID, List<StandardPrice.Tier>> tiers = new LinkedHashMap<>();
        named.query("select standard_price_id, min_quantity, unit_price from catalog.standard_price_tier"
                        + " where standard_price_id in (:ids) order by min_quantity",
                new MapSqlParameterSource("ids", headers.stream().map(Header::id).toList()),
                row -> {
                    tiers.computeIfAbsent(row.getObject(1, UUID.class), id -> new ArrayList<>())
                            .add(new StandardPrice.Tier(row.getInt(2), row.getBigDecimal(3)));
                });
        return headers.stream()
                .map(h -> new StandardPrice(h.id(), h.sku(), tiers.get(h.id()), h.note(), h.version()))
                .toList();
    }

    @Override
    public StandardPrice save(StandardPrice price) {
        Timestamp now = Timestamp.from(clock.instant());
        String actor = auditor.getCurrentAuditor().orElse(null);
        int updated = jdbc.update("""
                update catalog.standard_price set note = ?, version = version + 1, last_modified_at = ?,
                       last_modified_by = ?
                where id = ? and version = ?""", price.note(), now, actor, price.id(), price.version());
        if (updated == 0) {
            boolean exists = !jdbc.queryForList("select 1 from catalog.standard_price where id = ?", price.id())
                    .isEmpty();
            if (exists) {
                throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                        "The standard price of %s changed meanwhile".formatted(price.sku()));
            }
            jdbc.update("""
                    insert into catalog.standard_price(id, sku, currency, note, version, created_at, created_by)
                    values (?, ?, 'VND', ?, 0, ?, ?)""",
                    price.id(), price.sku(), price.note(), now, actor);
        }
        jdbc.update("delete from catalog.standard_price_tier where standard_price_id = ?", price.id());
        for (StandardPrice.Tier tier : price.tiers()) {
            jdbc.update("""
                    insert into catalog.standard_price_tier(id, standard_price_id, min_quantity, unit_price, version,
                                                            created_at, created_by)
                    values (?, ?, ?, ?, 0, ?, ?)""",
                    Identifiers.newId(), price.id(), tier.minQuantity(), tier.unitPrice(), now, actor);
        }
        return header("where id = ?", price.id()).map(this::withTiers).orElseThrow();
    }

    @Override
    public void delete(UUID priceId) {
        jdbc.update("delete from catalog.standard_price where id = ?", priceId);
    }

    private Optional<Header> header(String where, Object... args) {
        return jdbc.query("select id, sku, note, version from catalog.standard_price " + where,
                (r, n) -> header(r), args).stream().findFirst();
    }

    private static Header header(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Header(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getLong(4));
    }

    private StandardPrice withTiers(Header h) {
        List<StandardPrice.Tier> tiers = jdbc.query(
                "select min_quantity, unit_price from catalog.standard_price_tier where standard_price_id = ?"
                        + " order by min_quantity",
                (r, n) -> new StandardPrice.Tier(r.getInt(1), r.getBigDecimal(2)), h.id());
        return new StandardPrice(h.id(), h.sku(), tiers, h.note(), h.version());
    }
}
