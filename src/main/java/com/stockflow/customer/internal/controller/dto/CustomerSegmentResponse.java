package com.stockflow.customer.internal.controller.dto;

import java.util.UUID;

/** A customer group, how many customers it holds now, and the version to send back on change. */
public record CustomerSegmentResponse(UUID id, String code, String name, String description, long customerCount,
                                      long version) {
}
