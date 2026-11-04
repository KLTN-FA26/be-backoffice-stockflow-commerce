package com.stockflow.product.internal.domain;

import com.stockflow.common.storage.StoredFile;

/** Original objects are private; each display size has its own immutable object. */
public record ImageRendition(int edge, int width, int height, StoredFile file) { }
