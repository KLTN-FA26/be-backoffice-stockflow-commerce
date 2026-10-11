package com.stockflow.identity.api;

/** The contact person Sales is inviting: they sign in with this e-mail address. */
public record InviteCustomerAccountCommand(String email, String fullName) {
}
