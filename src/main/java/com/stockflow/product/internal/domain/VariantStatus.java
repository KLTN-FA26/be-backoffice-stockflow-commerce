package com.stockflow.product.internal.domain;

/**
 * Lifecycle of a variant ({@code ck_variants_status}).
 *
 * <pre>
 *   DRAFT ──activate──▶ ACTIVE ◀──activate── BLOCKED
 *     │                   │  └────block────▶   │
 *     └──────────────obsolete──────────────────┴──▶ OBSOLETE
 * </pre>
 *
 * <p>Only an {@code ACTIVE} variant is sold and shown: the catalog lists active variants as the
 * product's SKUs, and only an active variant's published images reach the storefront. Approving a
 * product activates its draft variants. {@code OBSOLETE} is terminal — the SKU stays for the order,
 * stock and purchase history that references it.</p>
 */
public enum VariantStatus {
    DRAFT,
    ACTIVE,
    BLOCKED,
    OBSOLETE;

    public boolean canTransitionTo(VariantStatus target) {
        return switch (this) {
            case DRAFT -> target == ACTIVE || target == OBSOLETE;
            case ACTIVE -> target == BLOCKED || target == OBSOLETE;
            case BLOCKED -> target == ACTIVE || target == OBSOLETE;
            case OBSOLETE -> false;
        };
    }
}
