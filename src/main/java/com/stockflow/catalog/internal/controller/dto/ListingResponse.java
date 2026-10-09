package com.stockflow.catalog.internal.controller.dto;
import java.util.UUID;
public record ListingResponse(UUID productId,String slug,String seoTitle,String seoDescription,
                              long revision,long projectedRevision,boolean published,boolean projectionPending) {}
