package com.hnp.filemanagement.shared.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The storage behind the request could not do what was asked - the object store does not answer
 * or answers with an error, the disk is full, the share is gone (2.7.1, issue 100). The request
 * itself was fine: {@code 503 Service Unavailable} with a {@code Retry-After}, so a client that
 * retries does, and the failure counts as the server's, not the caller's.
 *
 * <p>A {@link BusinessException}, so that whatever already caught one still does; the status is
 * this class's own. What the stores refuse on purpose - a key that would leave the root, null
 * bytes - stays a plain {@code BusinessException}.
 */
@ResponseStatus(code = HttpStatus.SERVICE_UNAVAILABLE)
public class StorageUnavailableException extends BusinessException {

    /** What {@code Retry-After} says: long enough for a store that restarts, short enough to retry soon. */
    public static final int RETRY_AFTER_SECONDS = 30;

    public StorageUnavailableException(String message) {
        super(message);
    }
}
