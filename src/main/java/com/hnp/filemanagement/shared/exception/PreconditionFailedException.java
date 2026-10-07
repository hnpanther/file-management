package com.hnp.filemanagement.shared.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A write refused because what it was conditioned on no longer holds - {@code If-Match} naming a
 * version that is not the current one, {@code If-None-Match: *} where there already is one. 412:
 * nothing was changed, and the client should read again before it decides.
 */
@ResponseStatus(code = HttpStatus.PRECONDITION_FAILED)
public class PreconditionFailedException extends RuntimeException {

    public PreconditionFailedException(String message) {
        super(message);
    }
}
