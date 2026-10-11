package com.stockflow.identity.api;

/**
 * @param temporaryPassword the generated password, shown once; null when the administrator chose it
 */
public record PasswordReset(String temporaryPassword, int sessionsEnded) {
}
