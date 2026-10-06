package com.stockflow.identity.api;

import java.util.UUID;

public record RegisteredAccount(UUID userId, TokenResponse token) {
}
