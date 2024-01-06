package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SecurityController {

    private final GlobalGeneralLogging globalGeneralLogging;

    public SecurityController(GlobalGeneralLogging globalGeneralLogging) {
        this.globalGeneralLogging = globalGeneralLogging;
    }


    @GetMapping("/login")
    public String loginPage(HttpServletRequest request) {

        String principalUsername = "None";
        String logMessage = "request login page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(0, principalUsername,
                request.getMethod() + " " + path, "LoginController.class", logMessage);

        return "security/login.html";
    }
}
