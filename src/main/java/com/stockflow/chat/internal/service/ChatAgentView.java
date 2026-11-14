package com.stockflow.chat.internal.service;

import java.util.UUID;

/** A staff member who answers customers, for the assign / transfer dialog: who, and whether they are online now. */
public record ChatAgentView(UUID userId, String name, boolean online) {
}
