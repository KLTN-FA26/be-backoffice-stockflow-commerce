package com.stockflow.identity.api;

import java.util.UUID;

/**
 * The contact person Sales is inviting: they sign in with this e-mail address.
 *
 * @param issuedBy the staff account sending the invitation, recorded on it
 */
public record InviteCustomerAccountCommand(String email, String fullName, UUID issuedBy) {
}
