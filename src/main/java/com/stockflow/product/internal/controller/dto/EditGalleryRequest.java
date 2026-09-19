package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.util.List;

/** The first item is the cover image. */
public record EditGalleryRequest(@PositiveOrZero long revision,
                                 @NotNull @Size(max = 20) List<@NotNull @Valid GalleryItemRequest> items) {
}
