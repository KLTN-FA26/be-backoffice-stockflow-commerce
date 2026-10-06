package com.stockflow.identity.internal.domain;

/** Why a session was ended before its token expired. Stored as text, so never reorder or rename. */
public enum SessionEndReason {
    /** The user signed this device out. */
    LOGOUT,
    /** The user asked to sign out every other device. */
    LOGOUT_OTHERS,
    /** The password changed and the user chose to end the other sessions. */
    PASSWORD_CHANGED,
    /** The user's roles changed, so tokens carrying the old permissions must not outlive it. */
    ROLE_CHANGED
}
