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
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * See {@link UomConversionService}. Writes take the SKU's advisory lock (the one logistics and policy
 * edits take), so two people marking different default purchase units are serialised and the
 * {@code uk_uom_conversions_default_purchase} index never surfaces as a 500.
 *
 * <p>BR-10 ("hệ số quy đổi đã phát sinh giao dịch không được sửa"): stock packages, purchase order lines
 * (and their revisions) and goods receipt lines record the unit they were made or counted in, and none of
 * them copies the factor (V20261020000400), so a unit one of them uses keeps its factor and its code. The
 * database refuses such a change (V20261020000600); asking first turns that into
 * {@code UOM_CONVERSION_IN_USE} rather than a generic 409. Its size, weight and default purchase mark stay
 * editable, and retiring it is the way out BR-10 names: declare a new unit, retire this one.</p>
 */
@Service
@Transactional
class UomConversionServiceImpl implements UomConversionService {

    private final UomConversionRepository conversions;
    private final InventoryPolicyRepository items;
    private final CurrentUserProvider users;

    UomConversionServiceImpl(UomConversionRepository conversions, InventoryPolicyRepository items,
                             CurrentUserProvider users) {
        this.conversions = conversions;
        this.items = items;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UomConversion> conversions(Sku sku, int page, int size) {
        itemId(sku);
        var pageable = Pages.of(page, size);
        return PageResponse.of(conversions.page(sku.code(), pageable.getPageSize(), pageable.getOffset()),
                page, size, conversions.count(sku.code()));
    }

    @Override
    @Transactional(readOnly = true)
    public UomConversion conversion(Sku sku, UUID id) {
        itemId(sku);
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
    public UomConversion baseUnit(Sku sku) {
        itemId(sku);
        return conversions.base(sku.code()).orElseThrow(() -> new IllegalStateException(sku + " has no base unit"));
    }

    @Override
    public UomConversion add(Sku sku, UomConversionDraft draft) {
        UomConversionRules.validatePacking(draft);
        items.lock(sku.code());
        UUID itemId = itemId(sku);
        if (conversions.unitTaken(itemId, draft.unitOfMeasure(), null)) throw duplicate(sku, draft);
        String actor = actor();
        UUID id = Identifiers.newId();
        if (draft.defaultPurchase()) conversions.clearDefaultPurchase(itemId, id, actor);
        conversions.insert(id, itemId, draft, actor);
        return conversion(sku, id);
    }

    @Override
    public UomConversion change(Sku sku, UUID id, long expectedVersion, UomConversionDraft draft) {
        items.lock(sku.code());
        UUID itemId = itemId(sku);
        UomConversion current = conversions.find(sku.code(), id).orElseThrow(() -> notFound(sku, id));
        if (current.base()) {
            UomConversionRules.validateBase(current, draft);
        } else {
            UomConversionRules.validatePacking(draft);
            if (conversions.unitTaken(itemId, draft.unitOfMeasure(), id)) throw duplicate(sku, draft);
        }
        if (current.version() != expectedVersion) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        // The base unit's code follows the item's and its factor is 1 (V20261019000300): nothing to lock here.
        boolean converts = current.factor() != draft.factor() || !current.unitOfMeasure().equals(draft.unitOfMeasure());
        if (!current.base() && converts && conversions.inUse(id)) throw inUse(sku, current);
        String actor = actor();
        if (draft.defaultPurchase() && !current.defaultPurchase()) conversions.clearDefaultPurchase(itemId, id, actor);
        if (!conversions.update(id, expectedVersion, draft, current.base(), actor)) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        }
        conversions.defaultToBaseIfNone(itemId, actor);
        return conversion(sku, id);
    }

    @Override
    public void remove(Sku sku, UUID id, long expectedVersion) {
        items.lock(sku.code());
        UUID itemId = itemId(sku);
        UomConversion current = conversions.find(sku.code(), id).orElseThrow(() -> notFound(sku, id));
        if (current.base()) {
            throw new BusinessException(ErrorCode.UOM_CONVERSION_IN_USE, "The base unit of " + sku + " cannot be deleted");
        }
        if (current.version() != expectedVersion) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        if (conversions.inUse(id)) throw inUse(sku, current);
        if (!conversions.delete(id, expectedVersion)) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
        }
        conversions.defaultToBaseIfNone(itemId, actor());
    }

    private UUID itemId(Sku sku) {
        return conversions.itemId(sku.code()).orElseThrow(
                () -> new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + sku));
    }

    private String actor() {
        return users.current().map(u -> u.userId().toString()).orElse("system");
    }

    private static BusinessException notFound(Sku sku, UUID id) {
        return new BusinessException(ErrorCode.UOM_CONVERSION_NOT_FOUND, "No unit " + id + " on " + sku);
    }

    private static BusinessException inUse(Sku sku, UomConversion unit) {
        return new BusinessException(ErrorCode.UOM_CONVERSION_IN_USE,
                "%s %s (factor %d) is used by a document; declare a new unit and retire this one (BR-10)"
                        .formatted(sku, unit.unitOfMeasure(), unit.factor()));
    }

    private static BusinessException duplicate(Sku sku, UomConversionDraft draft) {
        return new BusinessException(ErrorCode.UOM_CONVERSION_DUPLICATE,
                "%s already has a unit %s".formatted(sku, draft.unitOfMeasure()));
    }
}
