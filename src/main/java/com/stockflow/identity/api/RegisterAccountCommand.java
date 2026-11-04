package com.stockflow.identity.api;

public record RegisterAccountCommand(String email, String password, String fullName) {
}
