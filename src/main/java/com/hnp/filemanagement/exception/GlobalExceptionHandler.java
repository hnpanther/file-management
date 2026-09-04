package com.hnp.filemanagement.exception;


import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.controller.FileController;
import com.hnp.filemanagement.controller.UserController;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private String message(String code) {
        return messageSource.getMessage(code, null, LocaleContextHolder.getLocale());
    }


//    @ResponseStatus(value= HttpStatus.NOT_FOUND, reason="Resource Not Found")
    @ExceptionHandler({ResourceNotFoundException.class, DuplicateResourceException.class, DependencyResourceException.class,
            BusinessException.class, InvalidDataException.class})
    public Object CustomException(RuntimeException e, @AuthenticationPrincipal UserDetailsImpl userDetails,
                                  HttpServletRequest request) {

        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if(userDetails != null) {
            logger.error("user=[" + userDetails.getId() + ", " + userDetails.getUsername() + "], path=" + request.getMethod() + " " + path + " -> error=RuntimeException," + e.getMessage(),
                    e.getMessage());
        } else {
            logger.error("user=[none user]," + " path=" + request.getMethod() + " " +  path + " -> error=RuntimeException," + e.getMessage(), e.getMessage());
        }

        HttpStatus status = statusOf(e);
        if(!wantsHtml(request)) {
            return ResponseEntity.status(status).body(e.getMessage());
        }

        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("message", e.getMessage());
        modelAndView.setViewName("error.html");
        modelAndView.setStatus(status);

        return modelAndView;
    }

    /**
     * The domain exceptions each carry a {@code @ResponseStatus}. Read it rather than repeating
     * the mapping here, so the annotation on the exception stays the single source of truth.
     */
    private static HttpStatus statusOf(RuntimeException e) {
        org.springframework.web.bind.annotation.ResponseStatus annotation =
                e.getClass().getAnnotation(org.springframework.web.bind.annotation.ResponseStatus.class);
        return annotation == null ? HttpStatus.BAD_REQUEST : HttpStatus.valueOf(annotation.code().value());
    }

    /**
     * A @PreAuthorize denial is raised inside the controller invocation, so it never reaches
     * Spring Security's ExceptionTranslationFilter and its accessDeniedPage. Handle it here, and
     * answer with a real 403 instead of the 200 the generic handler used to return.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ModelAndView accessDeniedHandler(org.springframework.security.access.AccessDeniedException e,
                                            @AuthenticationPrincipal UserDetailsImpl userDetails,
                                            HttpServletRequest request) {

        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if(userDetails != null) {
            logger.warn("user=[" + userDetails.getId() + ", " + userDetails.getUsername() + "], path=" + request.getMethod() + " " + path + " -> error=AccessDeniedException," + e.getMessage());
        } else {
            logger.warn("user=[none user]," + " path=" + request.getMethod() + " " + path + " -> error=AccessDeniedException," + e.getMessage());
        }

        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("message", message("error.accessDenied"));
        modelAndView.setViewName("error.html");
        modelAndView.setStatus(org.springframework.http.HttpStatus.FORBIDDEN);

        return modelAndView;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ModelAndView HttpRequestMethodNotSupportedHandler(HttpRequestMethodNotSupportedException e, @AuthenticationPrincipal UserDetailsImpl userDetails,
                                                             HttpServletRequest request) {

        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if(userDetails != null) {
            logger.error("user=[" + userDetails.getId() + ", " + userDetails.getUsername() + "], path=" + request.getMethod() + " "  + path + " -> error=HttpRequestMethodNotSupportedException," + e.getMessage() ,
                    e.getMessage());
        } else {
            logger.error("user=[none user]," + " path=" + request.getMethod() + " " + path + " -> error=HttpRequestMethodNotSupportedException," + e.getMessage(), e.getMessage());
        }



        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("message", e.getMessage());
        modelAndView.setViewName("error.html");

        return modelAndView;
    }

    /**
     * A missing resource must answer 404. Returning the HTML error page with a 200, as this used
     * to, makes a failed script tag arrive as a valid HTML document: the browser then reports
     * "Uncaught SyntaxError: Unexpected token '<'" and the real cause - the 404 - is invisible.
     * Only a request that actually wants HTML gets the error page; everything else gets a bare 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object NoResourceFoundHandler(NoResourceFoundException e, @AuthenticationPrincipal UserDetailsImpl userDetails,
                                         HttpServletRequest request) {
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        String who = userDetails != null
                ? "user=[" + userDetails.getId() + ", " + userDetails.getUsername() + "]"
                : "user=[none user]";
        logger.warn(who + ", path=" + request.getMethod() + " " + path + " -> error=NoResourceFoundException," + e.getMessage());

        if(!wantsHtml(request)) {
            return ResponseEntity.notFound().build();
        }

        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("message", message("error.notFound"));
        modelAndView.setViewName("error.html");
        modelAndView.setStatus(HttpStatus.NOT_FOUND);

        return modelAndView;
    }

    private static boolean wantsHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    /**
     * Anything unforeseen is a 500. This used to return 200 with the HTML error page, which meant
     * a failing JSON endpoint looked successful to its caller and a broken script tag arrived as
     * an HTML document.
     */
    @ExceptionHandler(Exception.class)
    public Object uncaughtException(Exception e, HttpServletRequest request) {

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        logger.error("uncaughtException:" + sw);

        if(!wantsHtml(request)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(message("error.unexpected"));
        }

        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("message", message("error.unexpected"));
        modelAndView.setViewName("error.html");
        modelAndView.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);

        return modelAndView;
    }

}
