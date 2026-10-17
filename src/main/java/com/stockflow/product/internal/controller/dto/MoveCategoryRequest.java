package com.stockflow.product.internal.controller.dto;

import java.util.UUID;

/** A null parent moves the category to the root. */
public record MoveCategoryRequest(UUID parentId) { }
