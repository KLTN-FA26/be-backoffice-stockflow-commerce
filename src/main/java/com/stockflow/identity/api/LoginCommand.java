package com.stockflow.identity.api;

/** Input to {@link IdentityService#login}. */
public record LoginCommand(String username, String password) {
}
