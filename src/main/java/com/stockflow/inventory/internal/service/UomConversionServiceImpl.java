package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.inventory.internal.domain.UomConversionRules;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * See {@link UomConversionService}. A carton is its catalogue variant's and every SKU of the variant - its customers'
 * SKUs included - is packed in it (owner decision Q1, V20270104000100). Writes therefore take the advisory lock of every
 * SKU of the variant, sorted (the lock logistics edits and every carton move of a SKU take): two people declaring or
 * re-activating a carton are serialised and asked about in turn (below) rather than racing to the unique index, and a
 * new size is judged against the bins while no carton of any of those SKUs moves (06 BR-23).
 *
 * <p>BR-10 ("hệ số quy đổi đã phát sinh giao dịch không được sửa"): stock packages and goods receipt lines
 * record the unit they were made or counted in, and none of
 * them copies the factor (V20261020000400), so a unit one of them uses keeps its factor and its code. The
 * database refuses such a change (V20261020000600); asking first turns that into
 * {@code UOM_CONVERSION_IN_USE} rather than a generic 409. Its size and weight stay editable, and retiring it is the way out BR-10 names: retire this one, then declare the new one.</p>
 *
 * <p>01 BR-03: a SKU has one active packing unit, its carton ({@code uk_uom_conversions_one_carton},
 * V20261214000400). Declaring or re-activating a second is asked about first, under the SKU lock, so the index never
 * surfaces as a bare 409 and the user is told to retire the other first; retired units stay beside it as history.</p>
 */
@Service
@Transactional
class UomConversionServiceImpl implements UomConversionService {

    private final UomConversionRepository conversions;
    private final InventoryPolicyRepository items;
    private final CurrentUserProvider users;

    private final BinStranding stranding;

    UomConversionServiceImpl(UomConversionRepository conversions, InventoryPolicyRepository items,
                             CurrentUserProvider users, BinStranding stranding) {
        this.stranding = stranding;
        this.conversions = conversions;
        this.items = items;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UomConversion> conversions(Sku sku, int page, int size) {
        variantOf(sku);
        var pageable = Pages.of(page, size);
        return PageResponse.of(conversions.page(sku.code(), pageable.getPageSize(), pageable.getOffset()),
                page, size, conversions.count(sku.code()));
    }

    @Override
    @Transactional(readOnly = true)
    public UomConversion conversion(Sku sku, UUID id) {
        variantOf(sku);
        return conversions.find(sku.code(), id).orElseThrow(() -> notFound(sku, id));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UomConversion> conversions(Sku sku, Collection<UUID> ids) {
        return conversions.find(sku.code(), ids).stream()
                .collect(Collectors.toMap(UomConversion::id, Function.identity()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<UomConversion>> conversionsOf(Collection<Sku> skus) {
        return conversions.bySkus(skus.stream().map(Sku::code).distinct().toList());
    }

    @Override
    public UomConversion add(Sku sku, UomConversionDraft draft) {
        UomConversionRules.validatePacking(draft);
        UUID variantId = lockVariant(sku);
        if (conversions.unitTaken(variantId, draft.unitOfMeasure(), null)) throw duplicate(sku, draft);
        if (draft.active()) requireNoOtherActiveCarton(sku, variantId, null);
        UUID id = Identifiers.newId();
        conversions.insert(id, variantId, draft, actor());
        return conversion(sku, id);
    }

    @Override
    public UomConversion change(Sku sku, UUID id, long expectedVersion, UomConversionDraft draft) {
        UUID variantId = lockVariant(sku);
        UomConversion current = conversions.find(sku.code(), id).orElseThrow(() -> notFound(sku, id));
        UomConversionRules.validatePacking(draft);
        if (conversions.unitTaken(variantId, draft.unitOfMeasure(), id)) throw duplicate(sku, draft);
        if (draft.active()) requireNoOtherActiveCarton(sku, variantId, id);
        if (current.version() != expectedVersion) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        boolean converts = current.factor() != draft.factor() || !current.unitOfMeasure().equals(draft.unitOfMeasure());
        if (converts && conversions.inUse(id)) throw inUse(sku, current);
        // 06 BR-23: new measures must not leave a carton of this unit standing in a bin it no longer fits. Under the
        // locks of every SKU of the variant, which every carton move of those SKUs takes too.
        if (resized(current, draft)) {
            stranding.requireUnitResizable(id, draft.lengthCm(), draft.widthCm(), draft.heightCm());
        }
        if (!conversions.update(id, expectedVersion, draft, actor())) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        }
        return conversion(sku, id);
    }

    @Override
    public void remove(Sku sku, UUID id, long expectedVersion) {
        lockVariant(sku);
        UomConversion current = conversions.find(sku.code(), id).orElseThrow(() -> notFound(sku, id));
        if (current.version() != expectedVersion) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        if (conversions.inUse(id)) throw inUse(sku, current);
        if (!conversions.delete(id, expectedVersion)) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        }
    }

    /**
     * The variant whose cartons {@code sku} names, with the lock of {@code sku} and of every SKU of the variant that has
     * an item taken in sorted order (the order every multi-SKU lock in inventory takes): the asked code serialises two
     * writes of the variant's cartons even while no SKU of it has an item yet, the items' codes hold their cartons
     * still. A write is named by the variant's own code: a customer's SKU resolves to the same variant, so the
     * database cannot refuse it, and it would change the carton of every customer of the variant -
     * {@code UOM_CARTON_OF_CUSTOMER_SKU} (owner decision Q1).
     */
    private UUID lockVariant(Sku sku) {
        UUID variantId = variantOf(sku);
        if (conversions.customerSku(sku.code())) {
            throw new BusinessException(ErrorCode.UOM_CARTON_OF_CUSTOMER_SKU,
                    "%s is a customer's SKU; declare its cartons on the variant it is drawn on".formatted(sku));
        }
        java.util.SortedSet<String> codes = new java.util.TreeSet<>(conversions.skusOfVariant(variantId));
        codes.add(sku.code());
        codes.forEach(items::lock);
        return variantId;
    }

    private void requireNoOtherActiveCarton(Sku sku, UUID variantId, UUID except) {
        conversions.activeCartonOf(variantId, except).ifPresent(code -> {
            throw new BusinessException(ErrorCode.UOM_CARTON_ALREADY_DECLARED,
                    "%s already has the carton unit %s; retire it before declaring another (01 BR-03)"
                            .formatted(sku, code));
        });
    }

    /**
     * The catalogue variant {@code sku} is packed as, whether or not the SKU has an item (a catalogue variant never
     * does, V20270105000100). {@code INVENTORY_ITEM_NOT_FOUND} for a code nobody knows, the answer this gave before.
     */
    private UUID variantOf(Sku sku) {
        return conversions.variantId(sku.code()).orElseThrow(
                () -> new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No SKU " + sku));
    }

    private String actor() {
        return users.current().map(u -> u.userId().toString()).orElse("system");
    }

    private static BusinessException notFound(Sku sku, UUID id) {
        return new BusinessException(ErrorCode.UOM_CONVERSION_NOT_FOUND, "No unit " + id + " on " + sku);
    }

    private static boolean resized(UomConversion current, UomConversionDraft draft) {
        return !sameMeasure(current.lengthCm(), draft.lengthCm()) || !sameMeasure(current.widthCm(), draft.widthCm())
                || !sameMeasure(current.heightCm(), draft.heightCm());
    }

    private static boolean sameMeasure(java.math.BigDecimal a, java.math.BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static BusinessException inUse(Sku sku, UomConversion unit) {
        return new BusinessException(ErrorCode.UOM_CONVERSION_IN_USE,
                "%s %s (factor %d) is used by a document; retire it, then declare the new one (BR-10)"
                        .formatted(sku, unit.unitOfMeasure(), unit.factor()));
    }

    private static BusinessException duplicate(Sku sku, UomConversionDraft draft) {
        return new BusinessException(ErrorCode.UOM_CONVERSION_DUPLICATE,
                "%s already has a unit %s".formatted(sku, draft.unitOfMeasure()));
    }
}
