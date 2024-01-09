package com.hnp.filemanagement.exception;


import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.controller.FileController;
import com.hnp.filemanagement.controller.UserController;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.AccessDeniedException;

//@ControllerAdvice(assignableTypes = {UserController.class, FileController.class})
@ControllerAdvice
public class GlobalExceptionHandler {

    Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);


//    @ResponseStatus(value= HttpStatus.NOT_FOUND, reason="Resource Not Found")
    @ExceptionHandler({ResourceNotFoundException.class, DuplicateResourceException.class, DependencyResourceException.class,
            BusinessException.class, org.springframework.security.access.AccessDeniedException.class})
    public ModelAndView CustomException(RuntimeException e, @AuthenticationPrincipal UserDetailsImpl userDetails,
                                        HttpServletRequest request) {

        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if(userDetails != null) {
            logger.error("user=[" + userDetails.getId() + ", " + userDetails.getUsername() + "], path=" + path + " -> error=RuntimeException," + e.getMessage(),
                    e.getMessage());
        } else {
            logger.error("user=[none user]," + " path=" + path + " -> error=RuntimeException," + e.getMessage(), e.getMessage());
        }

        logger.error(e.getMessage());

        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("message", e.getMessage());
        modelAndView.setViewName("error.html");

        return modelAndView;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ModelAndView HttpRequestMethodNotSupportedHandler(HttpRequestMethodNotSupportedException e, @AuthenticationPrincipal UserDetailsImpl userDetails,
                                                             HttpServletRequest request) {

        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if(userDetails != null) {
            logger.error("user=[" + userDetails.getId() + ", " + userDetails.getUsername() + "], path=" + path + " -> error=HttpRequestMethodNotSupportedException," + e.getMessage() ,
                    e.getMessage());
        } else {
            logger.error("user=[none user]," + " path=" + path + " -> error=HttpRequestMethodNotSupportedException," + e.getMessage(), e.getMessage());
        }



        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("message", e.getMessage());
        modelAndView.setViewName("error.html");

        return modelAndView;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ModelAndView NoResourceFoundHandler(NoResourceFoundException e,@AuthenticationPrincipal UserDetailsImpl userDetails,
                                               HttpServletRequest request) {
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if(userDetails != null) {
            logger.error("user=[" + userDetails.getId() + ", " + userDetails.getUsername() + "], path=" + path + " -> error=NoResourceFoundException," + e.getMessage(),
                    e.getMessage());
        } else {
            logger.error("user=[none user]," + " path=" + path + " -> error=NoResourceFoundException," + e.getMessage(), e.getMessage());
        }

        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("message", e.getMessage());
        modelAndView.setViewName("error.html");

        return modelAndView;
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView uncaughtException(Exception e) {


        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        logger.error("uncaughtException:" + sw);

        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("message", "please contact with your administrator[" + e.getMessage() + "]");
        modelAndView.setViewName("error.html");

        return modelAndView;
    }

}
