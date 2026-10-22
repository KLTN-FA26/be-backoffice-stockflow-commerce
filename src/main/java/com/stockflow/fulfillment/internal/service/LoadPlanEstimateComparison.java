package com.stockflow.fulfillment.internal.service;

import java.util.UUID;

/**
 * The extension point for comparing a load plan with the advisory estimate made before packing (design v2 §5
 * {@code reconciliation.vsEstimate}: vehicles by type, cartons by line, volume, weight, flags). The estimate is PR EST;
 * until it is merged, {@link None} answers that there is nothing to compare. The estimate PR replaces that bean with
 * the real comparison - nothing else in load planning changes.
 */
public interface LoadPlanEstimateComparison {

    /** The comparison for this plan, or null when there is no estimate to compare with. */
    Object compare(UUID loadPlanId);

    /** No estimate yet: no comparison. */
    @org.springframework.stereotype.Component
    class None implements LoadPlanEstimateComparison {
        @Override
        public Object compare(UUID loadPlanId) {
            return null;
        }
    }
}
