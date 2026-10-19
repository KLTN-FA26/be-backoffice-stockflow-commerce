package com.stockflow.procurement.internal.repository;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.procurement.internal.domain.CatalogSupplier;
import com.stockflow.procurement.internal.domain.PriceChange;
import com.stockflow.procurement.internal.domain.SupplierItem;
import com.stockflow.procurement.internal.domain.SupplierItemRepository;
import com.stockflow.procurement.internal.domain.SupplierItemTerms;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.entity.SupplierItemJpaEntity;
import com.stockflow.procurement.internal.entity.SupplierItemPriceJpaEntity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The supplier catalog over JPA, plus native reads of the supplier row that the supplier entity
 * deliberately does not map ({@code currency}).
 *
 * <p>The price history lives here and not in the aggregate: a reprice closes the open period and
 * opens the next one, and that has to commit with the row it prices.</p>
 */
@Repository
class SupplierItemRepositoryAdapter implements SupplierItemRepository {

    /** Namespace of the catalog advisory lock; the inventory item lock uses 701. */
    private static final int CATALOG_LOCK_SEED = 769;

    private static final String PAIR_CONSTRAINT = "uk_supplier_items_pair";

    private static final SortWhitelist SORT =
            SortWhitelist.of("createdAt", "lastModifiedAt", "currentPrice")
                    .withDefault("createdAt", Sort.Direction.DESC);

    private static final String SUPPLIER = """
            SELECT id, code, name, status, currency, lead_time_days
              FROM procurement.suppliers
             WHERE id = ?""";

    private static final String SUPPLIER_FOR_SHARE = SUPPLIER + "\n   FOR SHARE";

    /** One query for the SKU's whole catalog: the item row and the slice of its supplier. */
    private static final String SUPPLIERS_OF = """
            SELECT i.id, i.supplier_id, i.inventory_item_id, i.supplier_sku_code, i.current_price,
                   i.currency AS item_currency, i.moq, i.pack_size, i.lead_time_days AS item_lead_time_days,
                   i.is_preferred, i.version, i.created_at, i.last_modified_at,
                   s.code, s.name, s.status, s.currency AS supplier_currency,
                   s.lead_time_days AS supplier_lead_time_days
              FROM procurement.supplier_items i
              JOIN procurement.suppliers s ON s.id = i.supplier_id
             WHERE i.inventory_item_id = ?
             ORDER BY i.is_preferred DESC, s.name, s.code, i.id""";

    private final SupplierItemJpaRepository jpa;
    private final SupplierItemPriceJpaRepository prices;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    SupplierItemRepositoryAdapter(SupplierItemJpaRepository jpa, SupplierItemPriceJpaRepository prices,
                                  EntityManager entityManager, JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.prices = prices;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CatalogSupplier> supplierForShare(UUID supplierId) {
        return catalogSupplier(SUPPLIER_FOR_SHARE, supplierId);
    }

    @Override
    public Optional<CatalogSupplier> findSupplier(UUID supplierId) {
        return catalogSupplier(SUPPLIER, supplierId);
    }

    private Optional<CatalogSupplier> catalogSupplier(String sql, UUID supplierId) {
        return jdbc.query(sql,
                (rs, row) -> new CatalogSupplier(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), SupplierStatus.valueOf(rs.getString("status")),
                        Currency.getInstance(rs.getString("currency")), rs.getObject("lead_time_days", Integer.class)),
                supplierId).stream().findFirst();
    }

    @Override
    public Optional<SupplierItem> findById(UUID supplierItemId) {
        return jpa.findById(supplierItemId).map(SupplierItemRepositoryAdapter::domain);
    }

    @Override
    public Optional<SupplierItem> findByIdForUpdate(UUID supplierItemId) {
        return jpa.findByIdForUpdate(supplierItemId).map(entity -> {
            // The lock query returns the persistence-context copy when the row is already loaded;
            // refreshing under the lock is what makes the numbers read afterwards current.
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return domain(entity);
        });
    }

    @Override
    public void lockCatalogOf(UUID inventoryItemId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, " + CATALOG_LOCK_SEED + "))", rs -> {},
                inventoryItemId.toString());
    }

    @Override
    public Optional<SupplierItem> findPreferredForUpdate(UUID inventoryItemId) {
        return jpa.findPreferredForUpdate(inventoryItemId).map(entity -> {
            // As in findByIdForUpdate: the persistence-context copy may predate the lock.
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return domain(entity);
        });
    }

    @Override
    public SupplierItem save(SupplierItem item, Optional<PriceChange> priceChange, Instant now) {
        // A new item's id cannot be in the table yet, so it is not looked up. A stored one was read under
        // its lock in this transaction, so the lookup is a persistence-context hit, not a SELECT.
        boolean inserting = item.isNew();
        SupplierItemJpaEntity entity = inserting
                ? new SupplierItemJpaEntity(item.id(), item.supplierId(), item.inventoryItemId())
                : jpa.findById(item.id()).orElseThrow(() -> new IllegalStateException(
                        "Supplier item " + item.id() + " vanished before it was saved"));
        var terms = item.terms();
        entity.update(terms.supplierSkuCode(), terms.price().amount(), terms.price().currency().getCurrencyCode(),
                terms.moq(), terms.packSize(), terms.leadTimeDays(), item.preferred());
        try {
            jpa.saveAndFlush(entity);
        } catch (DataIntegrityViolationException race) {
            String detail = String.valueOf(race.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
            if (!detail.contains(PAIR_CONSTRAINT)) throw race;
            var error = ErrorCode.SUPPLIER_ITEM_DUPLICATE;
            throw new BusinessException(error, error.defaultMessage(), race);
        }
        if (inserting) {
            prices.saveAndFlush(newPeriod(entity.getId(), terms.price(), now));
        } else {
            priceChange.ifPresent(change -> record(entity.getId(), change.to(), now));
        }
        return domain(entity);
    }

    /**
     * Closes the open period at {@code now} and opens the next from it. Closing is flushed before the
     * insert, because Hibernate would otherwise run the insert first and the two open periods would
     * overlap. A second reprice at the same instant (or before it, if the clock stepped back) rewrites
     * the open period rather than making a zero-length one, which {@code ck_supplier_item_prices_period}
     * refuses. An item that has no open period (a row carried over without history) gets one.
     */
    private void record(UUID itemId, Money price, Instant now) {
        var open = prices.findBySupplierItemIdAndEffectiveToIsNull(itemId);
        if (open.isPresent() && !now.isAfter(open.get().getEffectiveFrom())) {
            open.get().reprice(price.amount(), price.currency().getCurrencyCode());
            prices.saveAndFlush(open.get());
            return;
        }
        if (open.isPresent()) {
            open.get().closeAt(now);
            prices.saveAndFlush(open.get());
        }
        prices.saveAndFlush(newPeriod(itemId, price, now));
    }

    private static SupplierItemPriceJpaEntity newPeriod(UUID itemId, Money price, Instant from) {
        return new SupplierItemPriceJpaEntity(Identifiers.newId(), itemId, price.amount(),
                price.currency().getCurrencyCode(), from);
    }

    /** The price periods go with the row: {@code fk_supplier_item_prices_item} is ON DELETE CASCADE. */
    @Override
    public void delete(SupplierItem item) {
        jpa.deleteById(item.id());
        jpa.flush();
    }

    @Override
    public boolean usedByOpenPurchaseOrder(UUID supplierId, UUID inventoryItemId) {
        return jpa.usedByOpenPurchaseOrder(supplierId, inventoryItemId);
    }

    @Override
    public PageResponse<SupplierItem> listBySupplier(UUID supplierId, int page, int size, String sort) {
        // The id breaks ties, or rows with the same price repeat and vanish across pages.
        var pageable = Pages.of(page, size, SORT.parse(sort).and(Sort.by("id")));
        return Pages.toResponse(jpa.findBySupplierId(supplierId, pageable).map(SupplierItemRepositoryAdapter::domain));
    }

    @Override
    public List<SkuSupplierRow> suppliersOf(UUID inventoryItemId) {
        return jdbc.query(SUPPLIERS_OF, skuSupplierRow(), inventoryItemId);
    }

    private static RowMapper<SkuSupplierRow> skuSupplierRow() {
        return (rs, row) -> {
            var item = supplierItem(rs.getObject("id", UUID.class), rs.getObject("supplier_id", UUID.class),
                    rs.getObject("inventory_item_id", UUID.class), rs.getString("supplier_sku_code"),
                    rs.getBigDecimal("current_price"), rs.getString("item_currency"), rs.getInt("moq"),
                    rs.getInt("pack_size"), rs.getObject("item_lead_time_days", Integer.class),
                    rs.getBoolean("is_preferred"), rs.getLong("version"), instant(rs, "created_at"),
                    instant(rs, "last_modified_at"));
            var supplier = new CatalogSupplier(item.supplierId(), rs.getString("code"), rs.getString("name"),
                    SupplierStatus.valueOf(rs.getString("status")),
                    Currency.getInstance(rs.getString("supplier_currency")),
                    rs.getObject("supplier_lead_time_days", Integer.class));
            return new SkuSupplierRow(item, supplier);
        };
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static SupplierItem domain(SupplierItemJpaEntity e) {
        return supplierItem(e.getId(), e.getSupplierId(), e.getInventoryItemId(), e.getSupplierSkuCode(),
                e.getCurrentPrice(), e.getCurrency(), e.getMoq(), e.getPackSize(), e.getLeadTimeDays(),
                e.isPreferred(), e.getVersion(), e.getCreatedAt(), e.getLastModifiedAt());
    }

    /**
     * The one row-to-aggregate mapping, for the entity and for the native SKU query alike. It goes
     * through {@link SupplierItemTerms#stored}, which checks nothing: the table admits rows the
     * catalog form would refuse, and one of them must not fail a whole page, the SKU's supplier list,
     * or the edit that would correct it.
     */
    private static SupplierItem supplierItem(UUID id, UUID supplierId, UUID inventoryItemId, String supplierSkuCode,
                                             BigDecimal price, String currency, int moq, int packSize,
                                             Integer leadTimeDays, boolean preferred, long version,
                                             Instant createdAt, Instant lastModifiedAt) {
        var terms = SupplierItemTerms.stored(supplierSkuCode, new Money(price, Currency.getInstance(currency)), moq,
                packSize, leadTimeDays);
        return new SupplierItem(id, supplierId, inventoryItemId, terms, preferred, version, createdAt,
                lastModifiedAt);
    }
}
