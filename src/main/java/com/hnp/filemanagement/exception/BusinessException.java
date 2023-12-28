package com.hnp.filemanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.EXPECTATION_FAILED)
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
