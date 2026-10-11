package com.stockflow.catalog.internal.controller.dto;
import java.util.UUID;
public record CatalogCardResponse(UUID productId,String slug,String title,String seoTitle) {}
