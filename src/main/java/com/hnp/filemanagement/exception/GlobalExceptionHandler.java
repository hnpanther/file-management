package com.hnp.filemanagement.exception;


import com.hnp.filemanagement.controller.FileController;
import com.hnp.filemanagement.controller.UserController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import java.io.PrintWriter;
import java.io.StringWriter;

@ControllerAdvice(assignableTypes = {UserController.class, FileController.class})
public class GlobalExceptionHandler {

    Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);


//    @ResponseStatus(value= HttpStatus.NOT_FOUND, reason="Resource Not Found")
    @ExceptionHandler({ResourceNotFoundException.class, DuplicateResourceException.class, DependencyResourceException.class, BusinessException.class})
    public ModelAndView ResourceNotFoundHandler(RuntimeException e) {

        logger.error(e.getMessage());

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
