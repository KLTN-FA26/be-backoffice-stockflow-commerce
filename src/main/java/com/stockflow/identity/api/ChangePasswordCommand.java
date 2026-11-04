package com.stockflow.identity.api;

import java.util.UUID;

/**
 * Input to {@link IdentityService#changePassword}.
 *
 * @param currentSessionId the session making the change, kept alive when other devices are signed
 *                         out; null for a token issued before sessions existed
 * @param logoutOtherDevices end every other live session of the user together with the change
 */
public record ChangePasswordCommand(
        UUID userId,
        UUID currentSessionId,
        String currentPassword,
        String newPassword,
        boolean logoutOtherDevices
) {
}
