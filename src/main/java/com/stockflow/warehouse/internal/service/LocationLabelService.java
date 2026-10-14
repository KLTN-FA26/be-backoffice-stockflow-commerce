package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.LabelSelection;

import java.util.UUID;

/**
 * Chooses the locations a label print covers (SCRUM-97). Rendering is not here: the caller draws the
 * PDF after this read-only transaction has ended.
 */
public interface LocationLabelService {

    /** About 84 A4 pages: a large warehouse in one go; the worst case renders in about 0.8 s. */
    int MAX_LABELS = 2000;

    /**
     * @return the labels in walking order: bins by shelf, level and bin, then storage areas by code.
     *         Locations off the layout ({@code EffectiveStatus#isOnLayout}) are left out of a shelf,
     *         zone or whole-warehouse selection, and refused when named in a code selection.
     */
    LocationLabelSheet sheetFor(UUID warehouseId, LabelSelection selection);
}
