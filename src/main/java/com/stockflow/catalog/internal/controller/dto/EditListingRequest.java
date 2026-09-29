package com.stockflow.catalog.internal.controller.dto;
import jakarta.validation.constraints.*;
public record EditListingRequest(@NotNull @Min(0) Long revision,@NotBlank @Size(max=300) String slug,
                                 @Size(max=300) String seoTitle,@Size(max=500) String seoDescription) {}
