package com.hnp.filemanagement.identity.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Which API key, if any, the current request is acting with.
 *
 * <p>A request authenticated by a key ({@code ApiKeyAuthenticationFilter}) runs as the key's
 * creator - that user id is what {@code created_by} and {@code action_history.user_id} hold - and
 * carries the key's id beside it. Two things read it: folder access, which is the key's own rather
 * than its creator's, and the record of who did what, which says the key did it (2.3.0) so that a
 * file uploaded by an integration is not taken for one its creator uploaded by hand.
 *
 * <p>Read from the security context rather than passed in, for the reason
 * {@code FolderAccessService} gives: it is a property of the request, and threading it through
 * every service method that takes a {@code principalId} would make each of them a place to forget
 * it. Null outside a request, for a person, and for the shared v1 account (HTTP Basic), which is a
 * user and not a key.
 */
public final class ActingApiKey {

    private ActingApiKey() {
    }

    /** The id of the key the current request authenticated with, or null. */
    public static Integer currentId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetailsImpl principal)) {
            return null;
        }
        return principal.getApiKeyId();
    }
}
