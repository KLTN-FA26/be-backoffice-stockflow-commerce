package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record ApproveGalleryRequest(@PositiveOrZero long revision) {
}
