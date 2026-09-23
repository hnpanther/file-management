package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.util.GlobalGeneralLogging;
import org.springframework.stereotype.Controller;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.hnp.filemanagement.service.AppSettingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class SecurityController {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final MessageSource messageSource;
    private final AppSettingService appSettingService;

    public SecurityController(GlobalGeneralLogging globalGeneralLogging, MessageSource messageSource,
                              AppSettingService appSettingService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.messageSource = messageSource;
        this.appSettingService = appSettingService;
    }

    /** Whether the login page offers the public-files link: only while those pages are open to visitors. */
    @ModelAttribute("publicFilesAnonymous")
    public boolean publicFilesAnonymous() {
        return appSettingService.isPublicFilesAnonymous();
    }


    @GetMapping("/login")
    public String loginPage(@AuthenticationPrincipal UserDetailsImpl userDetails) {

        int principalId = userDetails == null ? 0 : userDetails.getId();
        globalGeneralLogging.detail("login page");

        // Someone who is already signed in has no business on the login form; send them to the
        // landing page, which knows where they belong.
        if(userDetails != null) {
            return "redirect:/";
        }

        return "security/login.html";
    }

    /**
     * Target of SecurityConfig's accessDeniedPage. Reached by a forward for filter-level denials
     * such as a missing CSRF token; @PreAuthorize denials are handled by GlobalExceptionHandler.
     */
    @RequestMapping("/access-denied")
    public ModelAndView accessDenied(@AuthenticationPrincipal UserDetailsImpl userDetails) {

        int principalId = userDetails == null ? 0 : userDetails.getId();
        globalGeneralLogging.detail("access denied");

        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("message", messageSource.getMessage("error.accessDenied", null, LocaleContextHolder.getLocale()));
        modelAndView.setViewName("error.html");
        modelAndView.setStatus(HttpStatus.FORBIDDEN);
        return modelAndView;
    }
}
