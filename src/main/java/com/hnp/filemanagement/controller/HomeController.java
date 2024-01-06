package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {


    private final GlobalGeneralLogging globalGeneralLogging;

    public HomeController(GlobalGeneralLogging globalGeneralLogging) {
        this.globalGeneralLogging = globalGeneralLogging;
    }


    @GetMapping
    public String home(HttpServletRequest request) {
        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to get home page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "HomeController.class", logMessage);

        return "redirect:/files/public-files";
    }
}
