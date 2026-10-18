package com.hnp.filemanagement.identity.signin;

import java.time.Instant;

/**
 * One sign-in event, as it is queued (2.17.1) - see {@code V3.19} for what each kind means. Holds what a person
 * typed as a name and never what they typed as a password; a session by its handle, never by its id.
 *
 * @param username       as typed, or the signed-in account's name
 * @param reason         why a sign-in was refused; null otherwise
 * @param sessionHandle  {@code ActiveUserSessions.handleOf} the session's id, or null
 */
public record SignInEvent(Instant occurredAt, Kind event, Channel channel, String username, String address,
                          String userAgent, Reason reason, String sessionHandle) {

    public enum Kind {
        SIGNED_IN, SIGN_IN_FAILED, SIGNED_OUT, SESSION_ENDED, SESSION_EXPIRED
    }

    /** The sign-in form, or the v1 API's password (HTTP Basic). */
    public enum Channel {
        WEB, API
    }

    public enum Reason {
        BAD_CREDENTIALS, LOCKED, LOCKED_NOW, DISABLED, UNAVAILABLE, OTHER
    }
}
