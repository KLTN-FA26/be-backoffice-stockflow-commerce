package com.stockflow.design.api;

import java.util.UUID;

/** No storage key or bearer URL crosses the module boundary. */
public record ConfirmedDesign(UUID snapshotId, UUID productId, String checksum) { }
