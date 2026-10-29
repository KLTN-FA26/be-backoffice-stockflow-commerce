package com.stockflow.procurement.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Objects;
import java.util.UUID;

/**
 * The packing unit a purchase-order line was ordered in (SCRUM-473): "5 CARTON" rather than 5,000 units.
 *
 * <p>The count is kept because it is what the buyer and the supplier said; the line's quantity is still in base
 * units ({@link #quantity()}), and so are its price, the supplier's MOQ and pack multiple, and the approval limits -
 * a carton price would need a rounding rule and a schema change for nothing the business asked for.
 * {@code tg_purchase_order_lines_unit} states {@code ordered_qty = unit_qty × factor} a second time.</p>
 *
 * @param factor what one unit holds, as the unit's row says; at least 1, as {@code ck_uom_conversions_factor} allows
 * @param count  how many units were ordered
 */
public record PackingUnit(UUID uomConversionId, String unitOfMeasure, int factor, int count) {

    public PackingUnit {
        Objects.requireNonNull(uomConversionId, "uomConversionId");
        Objects.requireNonNull(unitOfMeasure, "unitOfMeasure");
        if (factor < 1) {
            throw new IllegalArgumentException("A packing unit holds at least one base unit");
        }
        if (count < 1) {
            throw new IllegalArgumentException("A line orders at least one packing unit");
        }
    }

    /** The base units this many packing units hold. */
    public int quantity() {
        return Math.multiplyExact(count, factor);
    }

    /**
     * The same unit, counted again for {@code quantity} base units.
     *
     * @throws BusinessException {@code PO_LINE_UNIT_NOT_MULTIPLE} when {@code quantity} is not a whole number of units
     */
    public PackingUnit withQuantity(String poNumber, int quantity) {
        return new PackingUnit(uomConversionId, unitOfMeasure, factor, wholeUnits(poNumber, quantity, factor));
    }

    /**
     * How many units of {@code factor} base units {@code quantity} is. BR-09: the factor is a whole number and there
     * is no part of a carton, so anything else is {@code PO_LINE_UNIT_NOT_MULTIPLE} rather than the generic 409
     * {@code tg_purchase_order_lines_unit} would turn it into.
     */
    public static int wholeUnits(String poNumber, int quantity, int factor) {
        if (quantity % factor != 0) {
            throw new BusinessException(ErrorCode.PO_LINE_UNIT_NOT_MULTIPLE,
                    "%s is ordered in units of %d; %d is not a whole number of them".formatted(poNumber, factor, quantity));
        }
        return quantity / factor;
    }
}
