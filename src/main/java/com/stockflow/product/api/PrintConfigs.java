package com.stockflow.product.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Customization templates as other modules read them: the storefront's design studio (catalog), and a customer's
 * design, which is drawn on one template and checked against it (design). Read-only; templates are written through the
 * product module's own back-office API.
 */
public interface PrintConfigs {

    /**
     * The active template of every ACTIVE variant of a product, by variant position - what a customer may start a
     * design on (kltn-docs 13 BR-14). Empty for a product that is not CUSTOMIZABLE.
     */
    List<PrintConfig> activeOfProduct(UUID productId);

    /** The template {@code code} of the variant {@code sku}, active or not (a draft keeps its template - B39). */
    Optional<PrintConfig> ofVariant(String sku, String code);

    /** A template by id, active or not. */
    Optional<PrintConfig> byId(UUID templateId);

    /**
     * Whether a design drawn on this template can still be submitted (kltn-docs 12 BR-02, BR-05): its variant is ACTIVE
     * and an ordinary one, not a customer's own, of an approved or published CUSTOMIZABLE product. Whether the template
     * itself is still active does not matter: a draft keeps its template (B39).
     */
    boolean designable(UUID templateId);
}
