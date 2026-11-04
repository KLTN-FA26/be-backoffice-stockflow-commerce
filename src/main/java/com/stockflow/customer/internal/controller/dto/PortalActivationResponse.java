package com.stockflow.customer.internal.controller.dto;

/** The e-mail the contact now signs in with. No token: signing in is the login endpoint's job. */
public record PortalActivationResponse(String email) {
}
