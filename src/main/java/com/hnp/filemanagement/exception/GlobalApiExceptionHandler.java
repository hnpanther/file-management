package com.hnp.filemanagement.exception;


import com.hnp.filemanagement.api.FileApi;
import com.hnp.filemanagement.config.security.UserDetailsImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.io.PrintWriter;
import java.io.StringWriter;

@ControllerAdvice(assignableTypes = {FileApi.class})
public class GlobalApiExceptionHandler {

    Logger logger = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);




    @ExceptionHandler({ResourceNotFoundException.class, DuplicateResourceException.class, DependencyResourceException.class,
            BusinessException.class, org.springframework.security.access.AccessDeniedException.class})
    public ResponseEntity<Object> CustomException(RuntimeException e, @AuthenticationPrincipal UserDetailsImpl userDetails,
                                        HttpServletRequest request) {

        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if(userDetails != null) {
            logger.error("user=[" + userDetails.getId() + ", " + userDetails.getUsername() + "], path=" + request.getMethod() + " " + path + " -> error=RuntimeException," + e.getMessage(),
                    e.getMessage());
        } else {
            logger.error("user=[none user]," + " path=" + request.getMethod() + " " +  path + " -> error=RuntimeException," + e.getMessage(), e.getMessage());
        }

        logger.error(e.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(e.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> HttpRequestMethodNotSupportedHandler(HttpRequestMethodNotSupportedException e, @AuthenticationPrincipal UserDetailsImpl userDetails,
                                                             HttpServletRequest request) {

        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if(userDetails != null) {
            logger.error("user=[" + userDetails.getId() + ", " + userDetails.getUsername() + "], path=" + request.getMethod() + " "  + path + " -> error=HttpRequestMethodNotSupportedException," + e.getMessage() ,
                    e.getMessage());
        } else {
            logger.error("user=[none user]," + " path=" + request.getMethod() + " " + path + " -> error=HttpRequestMethodNotSupportedException," + e.getMessage(), e.getMessage());
        }



        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> uncaughtException(Exception e) {


        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        logger.error("uncaughtException:" + sw);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(e.getMessage());
    }

}
