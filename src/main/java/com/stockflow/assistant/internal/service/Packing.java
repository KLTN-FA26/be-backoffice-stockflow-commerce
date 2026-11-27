package com.stockflow.assistant.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A SKU's units as a stock answer gives them (design §4.2): the base unit stock is counted in, and the packing people
 * handle it in. "Còn 1.276" leaves a planner guessing between cups and cartons; "1.276 cái, quy đổi 25 thùng 50 cái và
 * 26 cái lẻ" does not, and the division is done here, never by the model.
 *
 * <p><b>Which packing.</b> The variant's one carton (kltn-docs 01 BR-03, {@link UomConversion#cartonOf}): every SKU is
 * received, sold and shipped in it, so there is no packing to choose. None when the SKU has no carton specification.
 * The base unit has had no row of its own since V20261226000200, so {@code unit} is null here.</p>
 *
 * <p><b>Whose data.</b> The units are the item's ({@code UomConversionService}), whose own screen is guarded by
 * {@code product-products:READ}, not by the stock permission of the tools that add them. Pack sizes are how a figure
 * the user may already see is counted, not data of their own, so they go with the figure: an exception to "mirror the
 * screen" written down in design §4.2.</p>
 *
 * <p><b>A conversion, not a count.</b> {@link Packed} is the quantity divided by the packing's size. How many sealed
 * cartons stand on a shelf is the carton lookup's to say ({@code lookupScan}): some of the stock may be loose or in
 * opened cartons.</p>
 *
 * @param unit            the base unit, as the item names it ({@code PCS}, {@code CUP}); null when the SKU has none
 * @param packingUnit     the packing's unit ({@code CARTON}); null when the SKU has no packing bigger than its base
 * @param unitsPerPacking base units in one packing; null with no packing
 */
record Packing(String unit, String packingUnit, Integer unitsPerPacking) {

    private static final Logger log = LoggerFactory.getLogger(Packing.class);

    static final Packing NONE = new Packing(null, null, null);

    /** What a stock tool's description says of the fields, so all of them say it the same way. */
    static final String DESCRIPTION = " The figures count pieces; packingUnit and unitsPerPacking are the variant's "
            + "one carton, the unit it is received, sold and shipped in: name it as packingUnit does. Each ...Packed "
            + "field gives the figure beside it "
            + "as whole packings (packingCount) and loose units, worked out here: quote those, never divide yourself. "
            + "It converts a quantity; it is not a count of the packings physically there, as stock may be loose or "
            + "in other packings (lookupScan counts real cartons).";

    /**
     * The units of each SKU, in one read; a SKU without an inventory item, or every SKU when the units cannot be read,
     * is {@link #NONE}: the figures still answer the question without them.
     */
    static Map<String, Packing> of(UomConversionService units, Collection<String> skus) {
        if (units == null || skus.isEmpty()) {
            return Map.of();
        }
        Map<String, List<UomConversion>> found;
        try {
            found = units.conversionsOf(skus.stream().distinct().map(Sku::new).toList());
        } catch (RuntimeException unreadable) {
            log.warn("Assistant stock answer without units: they could not be read", unreadable);
            return Map.of();
        }
        Map<String, Packing> packings = new HashMap<>();
        found.forEach((sku, conversions) -> packings.put(sku, of(conversions)));
        return packings;
    }

    /** The packing of one SKU's units (class javadoc). */
    static Packing of(List<UomConversion> conversions) {
        return UomConversion.cartonOf(conversions).map(carton -> new Packing(null, carton.unitOfMeasure(),
                carton.factor())).orElse(NONE);
    }

    /** {@code quantity} as whole packings and loose units; null without a packing, and for a negative quantity. */
    Packed packed(long quantity) {
        if (unitsPerPacking == null || quantity < 0) {
            return null;
        }
        return new Packed(quantity / unitsPerPacking, quantity % unitsPerPacking);
    }

    /**
     * A quantity in packings: {@code packingCount} whole ones and {@code looseUnits} left over, in base units. Worked
     * out here; the model quotes it, never divides.
     */
    record Packed(long packingCount, long looseUnits) {
    }
}
