package com.stockflow.inventory.api;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The units of measure of a SKU's inventory item (docs 01 §3, BR-09, BR-10). Every write joins the
 * caller's transaction and takes the SKU's lock, so the one default purchase unit is switched safely.
 * Each unit has its own version (optimistic locking, {@code OPTIMISTIC_LOCK}).
 *
 * <p>Whether a variant still sells in a unit is the product module's to know: it checks before it
 * asks to delete or retire one.</p>
 */
public interface UomConversionService {

    /** The base unit first, then the packing units by code. {@code INVENTORY_ITEM_NOT_FOUND} without an item. */
    PageResponse<UomConversion> conversions(Sku sku, int page, int size);

    /** {@code UOM_CONVERSION_NOT_FOUND} unless {@code id} is a unit of this SKU's item. */
    UomConversion conversion(Sku sku, UUID id);

    /** Those of {@code ids} that are units of this SKU's item. */
    Map<UUID, UomConversion> conversions(Sku sku, Collection<UUID> ids);

    /**
     * Every unit of each SKU's item, the base unit first, then by code, active and retired alike, in one query: what
     * a document listing several items offers to count in, and how it finds the units its lines name by code (a
     * goods receipt's lines, SCRUM-473). A SKU without an item is absent.
     */
    Map<String, List<UomConversion>> conversionsOf(Collection<Sku> skus);

    /** The base unit: the unit stock of the SKU is counted in. */
    UomConversion baseUnit(Sku sku);

    /**
     * Declare a packing unit. {@code UOM_CONVERSION_DUPLICATE} when the code is taken (the base unit's
     * included); marking it the default purchase unit, or the default shipping unit, unmarks the previous one.
     */
    UomConversion add(Sku sku, UomConversionDraft draft);

    /**
     * Replace a unit. Unmarking the default purchase unit hands the mark back to the base unit; a retired
     * unit cannot be the default purchase unit or the default shipping unit. Marking the default shipping unit
     * unmarks the item's previous one in the same transaction (never a conflict); unmarking it leaves the item
     * without one. On the base unit only the two marks change. {@code UOM_CONVERSION_IN_USE} when the factor
     * or code of a unit a document counts in would change (BR-10): retire it and declare a new one instead.
     */
    UomConversion change(Sku sku, UUID id, long expectedVersion, UomConversionDraft draft);

    /** Delete a packing unit; {@code UOM_CONVERSION_IN_USE} for the base unit and for a unit a document counts in. */
    void remove(Sku sku, UUID id, long expectedVersion);
}
