package com.stockflow.product.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.domain.ProductChangeType;
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
import com.stockflow.product.internal.repository.ProductCommerceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The units of a product's SKU: the cartons of its catalogue variant (docs 01 §3, BR-03, BR-09, BR-10) and the
 * variant's sales units (§3, BR-11). Both are edited through the product, under the same guard as the logistics: not
 * while it is under review, never once discontinued. A customer's own SKU reads its variant's cartons and is refused
 * on every write ({@code UOM_CARTON_OF_CUSTOMER_SKU}, owner decision Q1).
 *
 * <p>Sales unit: a SKU is sold by the carton of its carton specification, which the system assigns (kltn-docs 01 §3
 * "Đơn vị bán", BR-11): quotes, orders and contracts count cartons, priced per piece. So there is nothing to list or
 * choose - the sales unit <em>is</em> the variant's one active carton (01 BR-03,
 * {@code uk_uom_conversions_one_carton}), read when asked for; a SKU with no carton yet has none. The table that once
 * listed units a variant sells in ({@code product.variant_sales_units}, dropped by V20261226000100) recorded that same
 * fact a second time, was never written by the system, and kept a carton from being retired (BR-10) while it was
 * listed.</p>
 */
@Service
@Transactional
public class SkuUnitService {

    private final ProductPublication products;
    private final ProductCommerceRepository variants;
    private final UomConversionService units;
    private final ProductChangeLogRepository history;

    public SkuUnitService(ProductPublication products, ProductCommerceRepository variants, UomConversionService units,
                          ProductChangeLogRepository history) {
        this.products = products;
        this.variants = variants;
        this.units = units;
        this.history = history;
    }

    /** The unit a variant is sold in: its carton, holding {@code factor} pieces. */
    public record SalesUnitView(UUID variantId, UUID uomConversionId, String unitOfMeasure, int factor) {
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
        units.remove(sku, conversionId, version);
        history.record(productId, ProductChangeType.UOM_CONVERSION, variantId, null, null, null,
                List.of("unit:" + current.unitOfMeasure(), "deleted"));
    }

    // ---- sales unit ---------------------------------------------------------------------------------------

    /** The unit the variant is sold in: its catalogue variant's one active carton; an empty page while it has none. */
    @Transactional(readOnly = true)
    public PageResponse<SalesUnitView> salesUnits(UUID productId, UUID variantId, int page, int size) {
        Sku sku = readSku(productId, variantId);
        Pages.of(page, size);   // the same bounds as every list
        var carton = units.conversionsOf(List.of(sku)).getOrDefault(sku.code(), List.of()).stream()
                .filter(UomConversion::active)
                .findFirst();
        if (carton.isEmpty()) {
            return PageResponse.of(List.of(), page, size, 0);
        }
        var view = new SalesUnitView(variantId, carton.get().id(), carton.get().unitOfMeasure(), carton.get().factor());
        return PageResponse.of(page == 0 ? List.of(view) : List.of(), page, size, 1);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private Sku readSku(UUID productId, UUID variantId) {
        products.read(productId);
        return new Sku(variants.variantSku(productId, variantId).sku());
    }

    private Sku editableSku(UUID productId, UUID variantId) {
        var product = products.lock(productId);
        if (product.status() == ProductStatus.DISCONTINUED || product.status() == ProductStatus.PENDING_APPROVAL)
            throw new BusinessException(ErrorCode.CONFLICT);
        var variant = variants.variantSku(productId, variantId);
        if (variant.customerSku()) {
            throw new BusinessException(ErrorCode.UOM_CARTON_OF_CUSTOMER_SKU,
                    "SKU %s is a customer's own SKU: it is packed in its variant's carton".formatted(variant.sku()));
        }
        if (variant.obsolete()) {
            throw new BusinessException(ErrorCode.VARIANT_OBSOLETE,
                    "SKU %s is obsolete: its units are kept as they are".formatted(variant.sku()));
        }
        return new Sku(variant.sku());
    }

    private static UomConversion withoutVersion(UomConversion c) {
        return new UomConversion(c.id(), c.sku(), c.unitOfMeasure(), c.factor(), c.weightKg(), c.lengthCm(),
                c.widthCm(), c.heightCm(), c.active(), 0);
    }
}
