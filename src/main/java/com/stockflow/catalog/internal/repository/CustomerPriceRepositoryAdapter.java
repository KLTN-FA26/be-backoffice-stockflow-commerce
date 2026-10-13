package com.stockflow.catalog.internal.repository;

import com.stockflow.catalog.internal.domain.CustomerPrice;
import com.stockflow.catalog.internal.domain.CustomerPriceRepository;
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
 * {@link CustomerPriceRepository} in SQL, like {@link ListingRepository}: a price and its tiers are
 * replaced as a whole, and the header's version moves with every save through a compare-and-set
 * ({@code where version = ?}), so two editors cannot both win.
 */
@Repository
class CustomerPriceRepositoryAdapter implements CustomerPriceRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final AuditorAware<String> auditor;
    private final Clock clock;

    CustomerPriceRepositoryAdapter(JdbcTemplate jdbc, AuditorAware<String> auditor, Clock clock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.auditor = auditor;
        this.clock = clock;
    }

    private record Header(UUID id, UUID customerId, String sku, String note, long version) {
    }

    @Override
    public Optional<CustomerPrice> find(UUID customerId, String sku) {
        return header("where customer_id = ? and sku = ?", customerId, sku).map(this::withTiers);
    }

    @Override
    public Optional<CustomerPrice> findForUpdate(UUID customerId, String sku) {
        return header("where customer_id = ? and sku = ? for update", customerId, sku).map(this::withTiers);
    }

    @Override
    public List<CustomerPrice> ofCustomer(UUID customerId, Collection<String> skus) {
        var params = new MapSqlParameterSource("customerId", customerId);
        String filter = "";
        if (!skus.isEmpty()) {
            params.addValue("skus", skus);
            filter = " and sku in (:skus)";
        }
        List<Header> headers = named.query("select id, customer_id, sku, note, version from catalog.customer_price"
                + " where customer_id = :customerId" + filter + " order by sku", params, (r, n) -> header(r));
        if (headers.isEmpty()) return List.of();
        Map<UUID, List<CustomerPrice.Tier>> tiers = new LinkedHashMap<>();
        named.query("select customer_price_id, min_quantity, unit_price from catalog.customer_price_tier"
                        + " where customer_price_id in (:ids) order by min_quantity",
                new MapSqlParameterSource("ids", headers.stream().map(Header::id).toList()),
                row -> {
                    tiers.computeIfAbsent(row.getObject(1, UUID.class), id -> new ArrayList<>())
                            .add(new CustomerPrice.Tier(row.getInt(2), row.getBigDecimal(3)));
                });
        return headers.stream()
                .map(h -> new CustomerPrice(h.id(), h.customerId(), h.sku(), tiers.get(h.id()), h.note(), h.version()))
                .toList();
    }

    @Override
    public CustomerPrice save(CustomerPrice price) {
        Timestamp now = Timestamp.from(clock.instant());
        String actor = auditor.getCurrentAuditor().orElse(null);
        int updated = jdbc.update("""
                update catalog.customer_price set note = ?, version = version + 1, last_modified_at = ?,
                       last_modified_by = ?
                where id = ? and version = ?""", price.note(), now, actor, price.id(), price.version());
        if (updated == 0) {
            boolean exists = !jdbc.queryForList("select 1 from catalog.customer_price where id = ?", price.id()).isEmpty();
            if (exists) {
                throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                        "The price of %s for this customer changed meanwhile".formatted(price.sku()));
            }
            jdbc.update("""
                    insert into catalog.customer_price(id, customer_id, sku, currency, note, version, created_at, created_by)
                    values (?, ?, ?, 'VND', ?, 0, ?, ?)""",
                    price.id(), price.customerId(), price.sku(), price.note(), now, actor);
        }
        jdbc.update("delete from catalog.customer_price_tier where customer_price_id = ?", price.id());
        for (CustomerPrice.Tier tier : price.tiers()) {
            jdbc.update("""
                    insert into catalog.customer_price_tier(id, customer_price_id, min_quantity, unit_price, version,
                                                            created_at, created_by)
                    values (?, ?, ?, ?, 0, ?, ?)""",
                    Identifiers.newId(), price.id(), tier.minQuantity(), tier.unitPrice(), now, actor);
        }
        return header("where id = ?", price.id()).map(this::withTiers).orElseThrow();
    }

    @Override
    public void delete(UUID priceId) {
        jdbc.update("delete from catalog.customer_price where id = ?", priceId);
    }

    private Optional<Header> header(String where, Object... args) {
        return jdbc.query("select id, customer_id, sku, note, version from catalog.customer_price " + where,
                (r, n) -> header(r), args).stream().findFirst();
    }

    private static Header header(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Header(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4),
                r.getLong(5));
    }

    private CustomerPrice withTiers(Header h) {
        List<CustomerPrice.Tier> tiers = jdbc.query(
                "select min_quantity, unit_price from catalog.customer_price_tier where customer_price_id = ?"
                        + " order by min_quantity",
                (r, n) -> new CustomerPrice.Tier(r.getInt(1), r.getBigDecimal(2)), h.id());
        return new CustomerPrice(h.id(), h.customerId(), h.sku(), tiers, h.note(), h.version());
    }
}
