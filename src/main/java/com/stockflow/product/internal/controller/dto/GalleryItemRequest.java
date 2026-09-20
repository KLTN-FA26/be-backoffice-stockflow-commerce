package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record GalleryItemRequest(@NotNull UUID imageId, @NotNull @Size(max = 500) String caption) {
}
