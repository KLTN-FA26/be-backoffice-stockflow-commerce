package com.stockflow.identity.api;

/** What an administrator can do to an account's status (SCRUM-454). */
public enum UserStatusChange {
    /** ACTIVE → LOCKED: signs the user out and keeps them out until unlocked. */
    LOCK,
    /** LOCKED → ACTIVE; also ends a lock after too many wrong passwords. */
    UNLOCK,
    /** Retire the account but keep it, so its history keeps a name. */
    DISABLE,
    /** DISABLED → ACTIVE. */
    ENABLE
}
