package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.Size;

/** A new empty package. @param packageType CARTON (default), BAG, PALLET, CRATE or OTHER */
public record OpenPackageRequest(@Size(max = 16) String packageType) {
}
