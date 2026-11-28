package com.stockflow.identity.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code POST /staff-covers} (sales-owner plan BR-S07): {@code coverUserId} stands in for {@code userId} from
 * {@code from} to {@code to}, both days included, at most 60 days (D7).
 */
public record ArrangeCoverRequest(@NotNull UUID userId, @NotNull UUID coverUserId, @NotNull LocalDate from,
                                  @NotNull LocalDate to, @Size(max = 500) String reason) {
}
