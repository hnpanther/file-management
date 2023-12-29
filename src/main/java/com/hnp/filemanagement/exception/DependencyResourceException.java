package com.hnp.filemanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.CONFLICT)
public class DependencyResourceException extends RuntimeException {

    public DependencyResourceException(String message) {
        super(message);
    }
}
