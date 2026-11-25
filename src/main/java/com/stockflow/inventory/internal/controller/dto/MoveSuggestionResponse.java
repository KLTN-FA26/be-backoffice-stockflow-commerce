package com.stockflow.inventory.internal.controller.dto;

import java.util.List;

/**
 * Where a group of cartons may be moved together, as advice (issue #412; kltn-docs 11 §4.1, docs#46).
 *
 * @param suggestions   up to ten bins that take every carton by size and the whole group by free room, in putaway's
 *                      order; never a place one of the cartons stands in
 * @param overflowAreas the warehouse's usable OVERFLOW areas, named only when no bin takes the group; otherwise empty
 */
public record MoveSuggestionResponse(List<PutawaySuggestionResponse> suggestions, List<String> overflowAreas) {
}
