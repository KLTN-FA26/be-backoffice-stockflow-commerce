package com.stockflow.product.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.ProductChangeType;
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
import com.stockflow.product.internal.repository.VariantSalesUnitRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The units of a product's SKU: the units of measure of its inventory item (docs 01 §3 "Đơn vị quy đổi",
 * BR-09, BR-10) and the variant's sales units (§3 "Đơn vị bán", BR-11). Both are edited through the
 * product, under the same guard as the logistics: not while it is under review, never once discontinued.
 *
 * <p>The product module owns the sales units, so it is the one that refuses to delete or retire a unit
 * the variant still sells in ({@code UOM_CONVERSION_IN_USE}) before asking inventory to.</p>
 *
 * <p>Sales units: a variant with none sells in its base unit only, at the catalog base price (docs:
 * "mặc định: đơn vị cơ sở"); the list then shows that default as one entry with no id. Listing a unit
 * replaces the default, so a variant can be sold by the carton only. The catalog base price is already
 * the price of one base unit, so the base unit carries no price here; every other unit carries its
 * own, in whole VND like the catalog base price.</p>
 */
@Service
@Transactional
public class SkuUnitService {

    private final ProductPublication products;
    private final UomConversionService units;
    private final VariantSalesUnitRepository salesUnits;
    private final ProductChangeLogRepository history;
    private final CurrentUserProvider users;

    public SkuUnitService(ProductPublication products, UomConversionService units,
                          VariantSalesUnitRepository salesUnits, ProductChangeLogRepository history,
                          CurrentUserProvider users) {
        this.products = products;
        this.units = units;
        this.salesUnits = salesUnits;
        this.history = history;
        this.users = users;
    }

    /** One sales unit as shown: {@code id} and {@code version} are null for the default (nothing listed). */
    public record SalesUnitView(UUID id, UUID variantId, UUID uomConversionId, String unitOfMeasure, int factor,
                                boolean base, boolean active, BigDecimal price, boolean implicitDefault, Long version) {
    }

    // ---- units of measure ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<UomConversion> conversions(UUID productId, UUID variantId, int page, int size) {
        return units.conversions(readSku(productId, variantId), page, size);
    }

    @Transactional(readOnly = true)
    public UomConversion conversion(UUID productId, UUID variantId, UUID conversionId) {
        return units.conversion(readSku(productId, variantId), conversionId);
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "uom-conversion", resourceId = "#result?.id()")
    public UomConversion addConversion(UUID productId, UUID variantId, UomConversionDraft draft) {
        Sku sku = editableSku(productId, variantId);
        var added = units.add(sku, draft);
        history.record(productId, ProductChangeType.UOM_CONVERSION, variantId, null, null, null,
                List.of("unit:" + added.unitOfMeasure()));
        return added;
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "uom-conversion", resourceId = "#conversionId")
    public UomConversion changeConversion(UUID productId, UUID variantId, UUID conversionId, long version,
                                          UomConversionDraft draft) {
        Sku sku = editableSku(productId, variantId);
        var before = units.conversion(sku, conversionId);
        if (before.active() && !draft.active() && salesUnits.sells(variantId, conversionId)) {
            throw inUse(before);
        }
        var after = units.change(sku, conversionId, version, draft);
        var changed = RecordDiff.changedFields(withoutVersion(before), withoutVersion(after));
        if (!changed.isEmpty()) {
            history.record(productId, ProductChangeType.UOM_CONVERSION, variantId, null, null, null, changed);
        }
        return after;
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "uom-conversion", resourceId = "#conversionId")
    public void removeConversion(UUID productId, UUID variantId, UUID conversionId, long version) {
        Sku sku = editableSku(productId, variantId);
        var current = units.conversion(sku, conversionId);
        if (salesUnits.sells(variantId, conversionId)) throw inUse(current);
        units.remove(sku, conversionId, version);
        history.record(productId, ProductChangeType.UOM_CONVERSION, variantId, null, null, null,
                List.of("unit:" + current.unitOfMeasure(), "deleted"));
    }

    // ---- sales units --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<SalesUnitView> salesUnits(UUID productId, UUID variantId, int page, int size) {
        Sku sku = readSku(productId, variantId);
        var pageable = Pages.of(page, size);
        long total = salesUnits.count(variantId);
        if (total == 0) {
            UomConversion base = units.baseUnit(sku);
            var implicit = new SalesUnitView(null, variantId, base.id(), base.unitOfMeasure(), 1, true, true, null,
                    true, null);
            return PageResponse.of(page == 0 ? List.of(implicit) : List.of(), page, size, 1);
        }
        var rows = salesUnits.page(variantId, pageable.getPageSize(), pageable.getOffset());
        var conversions = units.conversions(sku, rows.stream().map(VariantSalesUnitRepository.Row::uomConversionId)
                .toList());
        return PageResponse.of(rows.stream().map(r -> view(r, conversions.get(r.uomConversionId()))).toList(),
                page, size, total);
    }

    @Transactional(readOnly = true)
    public SalesUnitView salesUnit(UUID productId, UUID variantId, UUID salesUnitId) {
        Sku sku = readSku(productId, variantId);
        var row = salesUnits.find(variantId, salesUnitId).orElseThrow(() -> salesUnitNotFound(salesUnitId));
        return view(row, units.conversion(sku, row.uomConversionId()));
    }

    /** BR-11: only a unit of the variant's own inventory item, and only one that is not retired. */
    @Auditable(action = AuditAction.CREATE, resourceType = "sales-unit", resourceId = "#result?.id()")
    public SalesUnitView addSalesUnit(UUID productId, UUID variantId, UUID uomConversionId, BigDecimal price) {
        Sku sku = editableSku(productId, variantId);
        var unit = units.conversion(sku, uomConversionId);
        if (!unit.active()) {
            throw invalid("uomConversionId", "Unit %s is retired and cannot be sold in".formatted(unit.unitOfMeasure()));
        }
        checkPrice(unit, price);
        if (salesUnits.sells(variantId, uomConversionId)) throw duplicate(unit);
        UUID id = Identifiers.newId();
        try {
            salesUnits.insert(id, variantId, uomConversionId, price, actor());
        } catch (DuplicateKeyException race) {
            throw duplicate(unit);
        }
        history.record(productId, ProductChangeType.SALES_UNIT, variantId, null, null, null,
                List.of("unit:" + unit.unitOfMeasure()));
        return salesUnit(productId, variantId, id);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "sales-unit", resourceId = "#salesUnitId")
    public SalesUnitView changeSalesUnit(UUID productId, UUID variantId, UUID salesUnitId, long version,
                                         BigDecimal price) {
        Sku sku = editableSku(productId, variantId);
        var row = salesUnits.find(variantId, salesUnitId).orElseThrow(() -> salesUnitNotFound(salesUnitId));
        var unit = units.conversion(sku, row.uomConversionId());
        checkPrice(unit, price);
        if (row.version() != version || !salesUnits.updatePrice(salesUnitId, version, price, actor())) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        }
        if (row.price() == null ? price != null : price == null || row.price().compareTo(price) != 0) {
            history.record(productId, ProductChangeType.SALES_UNIT, variantId, null, null, null,
                    List.of("unit:" + unit.unitOfMeasure(), "price"));
        }
        return salesUnit(productId, variantId, salesUnitId);
    }

    /** Removing the last one brings back the default: the base unit at the catalog base price. */
    @Auditable(action = AuditAction.DELETE, resourceType = "sales-unit", resourceId = "#salesUnitId")
    public void removeSalesUnit(UUID productId, UUID variantId, UUID salesUnitId, long version) {
        Sku sku = editableSku(productId, variantId);
        var row = salesUnits.find(variantId, salesUnitId).orElseThrow(() -> salesUnitNotFound(salesUnitId));
        if (row.version() != version || !salesUnits.delete(salesUnitId, version)) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        }
        history.record(productId, ProductChangeType.SALES_UNIT, variantId, null, null, null,
                List.of("unit:" + units.conversion(sku, row.uomConversionId()).unitOfMeasure(), "deleted"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private Sku readSku(UUID productId, UUID variantId) {
        products.read(productId);
        return new Sku(products.inventorySku(productId, variantId).sku());
    }

    private Sku editableSku(UUID productId, UUID variantId) {
        var product = products.lock(productId);
        if (product.status() == ProductStatus.DISCONTINUED || product.status() == ProductStatus.PENDING_APPROVAL)
            throw new BusinessException(ErrorCode.CONFLICT);
        return new Sku(products.inventorySku(productId, variantId).sku());
    }

    private static void checkPrice(UomConversion unit, BigDecimal price) {
        if (unit.base()) {
            if (price != null) {
                throw invalid("price", "The base unit is sold at the catalog base price; leave price empty");
            }
            return;
        }
        if (price == null) {
            throw invalid("price", "A price is required for unit " + unit.unitOfMeasure());
        }
        if (price.signum() <= 0 || price.stripTrailingZeros().scale() > 0 || price.precision() - price.scale() > 16) {
            throw invalid("price", "price is a positive whole number of VND");
        }
    }

    private static SalesUnitView view(VariantSalesUnitRepository.Row row, UomConversion unit) {
        return new SalesUnitView(row.id(), row.variantId(), row.uomConversionId(), unit.unitOfMeasure(),
                unit.factor(), unit.base(), unit.active(), row.price(), false, row.version());
    }

    private static UomConversion withoutVersion(UomConversion c) {
        return new UomConversion(c.id(), c.sku(), c.unitOfMeasure(), c.factor(), c.base(), c.weightKg(), c.lengthCm(),
                c.widthCm(), c.heightCm(), c.defaultPurchase(), c.active(), 0);
    }

    private String actor() {
        return users.current().map(u -> u.userId().toString()).orElse("system");
    }

    private static BusinessException inUse(UomConversion unit) {
        return new BusinessException(ErrorCode.UOM_CONVERSION_IN_USE,
                "The variant still sells in unit %s; remove that sales unit first".formatted(unit.unitOfMeasure()));
    }

    private static BusinessException duplicate(UomConversion unit) {
        return new BusinessException(ErrorCode.SALES_UNIT_DUPLICATE,
                "The variant already sells in unit " + unit.unitOfMeasure());
    }

    private static BusinessException salesUnitNotFound(UUID id) {
        return new BusinessException(ErrorCode.SALES_UNIT_NOT_FOUND, "No sales unit " + id);
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldError(field, message, ErrorCode.VALIDATION_FAILED.name())));
    }
}
