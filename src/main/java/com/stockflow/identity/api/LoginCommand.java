package com.stockflow.identity.api;

/**
 * Input to {@link IdentityService#login}.
 *
 * @param clientAddress where the request came from, recorded on the session so the user can
 *                      recognise the device later; may be null
 * @param userAgent the client's own description of itself, recorded for the same reason; may be null
 */
public record LoginCommand(String username, String password, String clientAddress, String userAgent) {

    public LoginCommand(String username, String password) {
        this(username, password, null, null);
    }
}
