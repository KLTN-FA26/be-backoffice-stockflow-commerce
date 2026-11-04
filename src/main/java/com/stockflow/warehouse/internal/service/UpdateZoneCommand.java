package com.stockflow.warehouse.internal.service;

import java.util.UUID;

/** @param expectedVersion the version the edit was based on; a different current version is a 409 */
public record UpdateZoneCommand(UUID zoneId, String name, String color, long expectedVersion) {
}
