package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {


    private final GlobalGeneralLogging globalGeneralLogging;

    public HomeController(GlobalGeneralLogging globalGeneralLogging) {
        this.globalGeneralLogging = globalGeneralLogging;
    }


    //ACCESS_HOME
//    @PreAuthorize("hasAuthority('ACCESS_HOME') || hasAuthority('ADMIN')")
    @GetMapping
    public String home(@AuthenticationPrincipal UserDetailsImpl userDetails, HttpServletRequest request) {
        int principalId = 0;
        String principalUsername = "None";
        if(userDetails != null) {
            principalId = userDetails.getId();
            principalUsername = userDetails.getUsername();
        }
        String logMessage = "request to get home page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "HomeController.class", logMessage);

        return "redirect:/files/public-files";
    }
}
