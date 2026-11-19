package com.stockflow.design.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.PrintConfigs;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a design's template and variant say about submitting it now (kltn-docs 12 BR-02, BR-05; B25), as one rule for
 * the two places that ask: the submission's preflight ({@link DesignSubmissionService#plan}, which turns a {@code false}
 * into {@code VARIANT_UNAVAILABLE} or {@code CARTON_SPEC_MISSING}) and {@code GET /designs/{id}/template}, which tells the
 * studio beforehand (FE request 2026-11-18 BE-11). The studio used to infer both from the catalogue, which lists only
 * published products and active templates, and so blocked designs the server would take; reading them from here
 * means the two can no longer disagree.
 */
@Component
class TemplateAvailability {

    /**
     * @param designable    the variant is ACTIVE and an ordinary one of an approved or published CUSTOMIZABLE product;
     *                      whether the template itself is still active does not matter (a draft keeps it, B39)
     * @param hasCartonSpec the variant has an active packing unit of more than one base unit (kltn-docs 01 BR-03): what
     *                      a customer orders in, without which nothing printed could be quoted or packed
     */
    record Availability(boolean designable, boolean hasCartonSpec) {
    }

    private final PrintConfigs printConfigs;
    private final UomConversionService units;

    TemplateAvailability(PrintConfigs printConfigs, UomConversionService units) {
        this.printConfigs = printConfigs;
        this.units = units;
    }

    /** For a design drawn on {@code templateId}, of the variant {@code sku}. */
    Availability of(UUID templateId, String sku) {
        boolean cartonSpec = units.conversionsOf(List.of(new Sku(sku))).getOrDefault(sku, List.of()).stream()
                .anyMatch(UomConversion::cartonCandidate);
        return new Availability(printConfigs.designable(templateId), cartonSpec);
    }
}
