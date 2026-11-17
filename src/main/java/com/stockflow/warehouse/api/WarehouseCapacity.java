package com.stockflow.warehouse.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a warehouse's map says about its capacity (kltn-docs 06 §4.6, BR-19, BR-20; C40, C43): the computed volume of its
 * usable bins and the factor the board applies to it, with the rows below the warehouse. Only the map's half of the
 * board - what stands in the bins is inventory's, what is coming is the order module's, which puts the three together.
 *
 * <p>A bin counts when it is usable now - its own status, its shelf's and its warehouse's all ACTIVE, the rule of
 * {@link StorageLocationView#usable()} - whether or not putaway may target it: a bin kept out of putaway still holds
 * cartons. A bin whose level has no clear height (legacy data, C40) has no volume and does not count; it is counted in
 * {@code binsWithoutVolume} instead, as long as it is on the layout, so the manager can complete it. Areas never count
 * (06 BR-19, SCRUM-666).</p>
 *
 * @param prefix            the warehouse's prefix: every location code of it starts {@code <prefix>-}
 * @param active            the warehouse is ACTIVE; an inactive one has no usable bin, so no capacity
 * @param utilizationFactor (0, 1]; capacity is {@code binVolumeM3 x utilizationFactor} at every level (C43)
 * @param binVolumeM3       Σ the computed volume of its usable bins, before the factor, six decimals
 * @param groups            zones, then the shelves of no zone, then shelves, then storage areas - each by name or
 *                          code; nothing of a shelf or area taken off the layout (INACTIVE)
 * @param groupsOfLocation  each location code on a listed row to the {@link CapacityGroup#key() keys} of its rows: a
 *                          bin to its zone (or no zone) and its shelf, an area to itself - what lets the caller add up
 *                          what stands at each location row by row
 */
public record WarehouseCapacity(UUID warehouseId, String prefix, String name, boolean active,
                                BigDecimal utilizationFactor, BigDecimal binVolumeM3, int binsWithoutVolume,
                                List<CapacityGroup> groups, Map<String, List<String>> groupsOfLocation) {
}
